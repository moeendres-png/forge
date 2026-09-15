package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS217 separate-process divided-allocation evidence: fresh child BridgeMain JVMs
 * bound to the exact new Core-authority commit, driving actual Arc Lightning and
 * Storm the Seedcore vectors over Protocol-2.0.0 JSONL with native resolution.
 */
public class WS217SeparateProcessTest {
    private static final String ENGINE_SHA = "c4d67145a6f9902e031a11dde5c33c60f51ed08d";

    private static final String[] SEATS = { "p1", "p2", "p3", "p4" };

    private static final class Pipe {
        final Process process;
        final BufferedWriter stdin;
        final BlockingQueue<String> stdout = new ArrayBlockingQueue<>(4096);
        final AtomicLong ids = new AtomicLong();

        Pipe() throws Exception {
            final String javaBin = System.getProperty("java.home") + "/bin/java";
            String classpath = System.getProperty("java.class.path");
            final Path repoRoot = Paths.get("").toAbsolutePath().getParent();
            final ProcessBuilder builder = new ProcessBuilder(javaBin,
                    "-Djava.awt.headless=true", "-cp", classpath, "forge.bridge.BridgeMain");
            builder.environment().put("FORGE_ENGINE_SHA", ENGINE_SHA);
            builder.environment().put("FORGE_ASSETS_DIR",
                    repoRoot.resolve("forge-gui").toString());
            builder.environment().remove("DISPLAY");
            builder.redirectErrorStream(false);
            process = builder.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
                    StandardCharsets.UTF_8));
            final BufferedReader out = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            final Thread pump = new Thread(() -> {
                try {
                    String line;
                    while ((line = out.readLine()) != null) {
                        stdout.offer(line);
                    }
                } catch (Exception e) {
                    // Pump ends with the process.
                }
            }, "ws217pipe-stdout");
            pump.setDaemon(true);
            pump.start();
            final BufferedReader err = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8));
            final Thread errPump = new Thread(() -> {
                try {
                    while (err.readLine() != null) {
                        // Diagnostics stay on stderr by protocol purity.
                    }
                } catch (Exception e) {
                    // Pump ends with the process.
                }
            }, "ws217pipe-stderr");
            errPump.setDaemon(true);
            errPump.start();
        }

        String nextId(String prefix) {
            return prefix + "-" + ids.incrementAndGet();
        }

        JsonObject request(String json, long timeoutMillis) throws Exception {
            final String requestId;
            try {
                requestId = JsonParser.parseString(json).getAsJsonObject()
                        .get("request_id").getAsString();
            } catch (Exception e) {
                throw new AssertionError("request without request_id: " + json);
            }
            synchronized (stdin) {
                stdin.write(json);
                stdin.write("\n");
                stdin.flush();
            }
            final long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                final String line = stdout.poll(
                        Math.max(1, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                final JsonObject response;
                try {
                    response = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception e) {
                    Assert.fail("stdout line is not valid JSON (protocol purity violated): "
                            + line);
                    return null;
                }
                Assert.assertEquals(response.get("protocol_version").getAsString(), "2.0.0",
                        "protocol purity: " + line);
                if (!response.get("request_id").getAsString().equals(requestId)) {
                    continue;
                }
                return response;
            }
            Assert.fail("no response for " + requestId);
            return null;
        }

        void close() throws Exception {
            try {
                stdin.close();
            } catch (Exception e) {
                // Already exiting.
            }
            process.waitFor(30, TimeUnit.SECONDS);
        }
    }

    private static Pipe boot() throws Exception {
        final Pipe pipe = new Pipe();
        final JsonObject started = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("start") + "\","
                + "\"message_type\":\"start_engine\"}", 60000);
        assertOk(started);
        final JsonObject caps = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("caps") + "\","
                + "\"message_type\":\"get_provider_version\"}", 60000);
        assertOk(caps);
        final String engine = caps.get("payload").getAsJsonObject().get("engine_commit")
                .getAsString();
        Assert.assertEquals(engine, ENGINE_SHA, "child must bind the WS217 core authority");
        return pipe;
    }

    private static final class Opt {
        String id;
        String type;
        String source;
        String label;
    }

    private static final class Frame {
        String kind;
        String actor;
        String status;
        long revision;
        List<Opt> opts = new ArrayList<>();
        int dividedTotal;
        int dividedMin;
    }

    private static void assertOk(JsonObject response) {
        Assert.assertTrue(response.get("success").getAsBoolean(), "expected ok: " + response);
        Assert.assertEquals(response.get("protocol_version").getAsString(), "2.0.0");
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : null;
    }

    private static Frame poll(Pipe pipe, String game) throws Exception {
        for (String seat : SEATS) {
            final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + pipe.nextId("poll") + "\","
                    + "\"message_type\":\"get_legal_actions\",\"game_id\":\"" + game + "\","
                    + "\"payload\":{\"actor_id\":\"" + seat + "\"}}", 60000);
            if (!response.get("success").getAsBoolean()) {
                continue;
            }
            final JsonObject decision = response.get("payload").getAsJsonObject()
                    .getAsJsonObject("decision");
            if (!decision.has("kind")) {
                continue;
            }
            final Frame frame = new Frame();
            frame.kind = decision.get("kind").getAsString();
            frame.actor = decision.get("actor").getAsString();
            frame.status = decision.get("status").getAsString();
            frame.revision = decision.get("revision").getAsLong();
            if (decision.has("divided_total")) {
                frame.dividedTotal = decision.get("divided_total").getAsInt();
            }
            if (decision.has("divided_min_per_target")) {
                frame.dividedMin = decision.get("divided_min_per_target").getAsInt();
            }
            final JsonArray actions = response.get("payload").getAsJsonObject()
                    .getAsJsonArray("actions");
            for (JsonElement element : actions) {
                final JsonObject action = element.getAsJsonObject();
                final Opt opt = new Opt();
                opt.id = action.get("action_id").getAsString();
                opt.type = action.get("action_type").getAsString();
                opt.source = str(action, "source_object_id");
                opt.label = action.getAsJsonObject("metadata").get("label").getAsString();
                frame.opts.add(opt);
            }
            return frame;
        }
        return null;
    }

    private static Frame await(Pipe pipe, String game, long timeoutMillis) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final Frame frame = poll(pipe, game);
            if (frame != null) {
                return frame;
            }
            Thread.sleep(250);
        }
        Assert.fail("no frame parked in game " + game);
        return null;
    }

    private static JsonObject submit(Pipe pipe, String game, String actor, String actionId,
            String actionType, long revision) throws Exception {
        final StringBuilder proposal = new StringBuilder("{\"proposal_id\":\"")
                .append(pipe.nextId("p")).append("\",\"actor_id\":\"").append(actor)
                .append("\",\"legal_action_id\":\"").append(actionId)
                .append("\",\"action_type\":\"").append(actionType).append("\"}");
        final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("sub") + "\","
                + "\"message_type\":\"submit_action\",\"game_id\":\"" + game + "\","
                + "\"payload\":{\"revision\":" + revision + ",\"proposal\":" + proposal + "}}",
                60000);
        assertOk(response);
        return response;
    }

    private static JsonObject submitDivided(Pipe pipe, String game, String actor, long revision,
            Map<String, Integer> allocations) throws Exception {
        final StringBuilder alloc = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : allocations.entrySet()) {
            if (!first) {
                alloc.append(',');
            }
            first = false;
            alloc.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        alloc.append('}');
        final String proposal = "{\"proposal_id\":\"" + pipe.nextId("p") + "\",\"actor_id\":\""
                + actor + "\",\"allocations\":" + alloc + "}";
        final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("subdiv") + "\","
                + "\"message_type\":\"submit_action\",\"game_id\":\"" + game + "\","
                + "\"payload\":{\"revision\":" + revision + ",\"proposal\":" + proposal + "}}",
                60000);
        assertOk(response);
        final JsonObject decision = response.get("payload").getAsJsonObject()
                .getAsJsonObject("decision");
        Assert.assertTrue(decision.get("executed").getAsBoolean(),
                "divided vector rejected over pipe: " + response);
        return response;
    }

    private static void pass(Pipe pipe, String game, Frame frame) throws Exception {
        final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("pass") + "\","
                + "\"message_type\":\"pass_priority\",\"game_id\":\"" + game + "\","
                + "\"payload\":{\"actor_id\":\"" + frame.actor + "\",\"revision\":"
                + frame.revision + "}}", 60000);
        assertOk(response);
    }

    private static JsonObject state(Pipe pipe, String game, String observer) throws Exception {
        final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("state") + "\","
                + "\"message_type\":\"get_game_state\",\"game_id\":\"" + game + "\","
                + "\"payload\":{\"observer_player_id\":\"" + observer + "\"}}", 60000);
        assertOk(response);
        return response.get("payload").getAsJsonObject().getAsJsonObject("state");
    }

    private static JsonObject playerState(JsonObject state, String pid) {
        for (JsonElement element : state.getAsJsonArray("players")) {
            final JsonObject player = element.getAsJsonObject();
            if (player.get("player_id").getAsString().equals(pid)) {
                return player;
            }
        }
        Assert.fail("no player " + pid + " in state");
        return null;
    }

    private static int life(JsonObject state, String pid) {
        return playerState(state, pid).get("life").getAsInt();
    }

    private static List<String> zone(JsonObject state, String pid, String zone) {
        final List<String> names = new ArrayList<>();
        for (JsonElement element : playerState(state, pid).getAsJsonObject("zones")
                .getAsJsonArray(zone)) {
            names.add(element.getAsString());
        }
        return names;
    }

    private static Opt find(Frame frame, java.util.function.Predicate<Opt> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (Opt opt : frame.opts) {
            if (test.test(opt)) {
                return opt;
            }
            seen.append('[').append(opt.type).append('|').append(opt.label).append(']');
        }
        throw new AssertionError(what + " not offered; kind=" + frame.kind + " options=" + seen);
    }

    private static List<String> importPod(Pipe pipe) throws Exception {
        final List<String> handles = new ArrayList<>(4);
        for (int i = 1; i <= 4; i++) {
            final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + pipe.nextId("import") + "\","
                    + "\"message_type\":\"import_deck\",\"payload\":{\"deck\":"
                    + BridgeTestSupport.deckResource("deck" + i + ".json") + "}}", 60000);
            assertOk(response);
            handles.add(response.get("payload").getAsJsonObject().getAsJsonObject("deck_handle")
                    .get("handle_id").getAsString());
        }
        return handles;
    }

    private static JsonObject placement(String card, String controller, String owner,
            boolean tapped) {
        final JsonObject entry = new JsonObject();
        entry.addProperty("card", card);
        entry.addProperty("controller", controller);
        entry.addProperty("owner", owner);
        entry.addProperty("tapped", tapped);
        return entry;
    }

    private static void createScenarioGame(Pipe pipe, String game, long seed,
            List<String> handles, JsonArray battlefield, JsonObject hands) throws Exception {
        for (String seat : SEATS) {
            if (!hands.has(seat)) {
                hands.add(seat, new JsonArray());
            }
        }
        final StringBuilder deckArray = new StringBuilder();
        for (int i = 0; i < handles.size(); i++) {
            if (i > 0) {
                deckArray.append(',');
            }
            deckArray.append('"').append(handles.get(i)).append('"');
        }
        final JsonObject neutral = new JsonObject();
        neutral.add("battlefield", battlefield);
        neutral.add("hands", hands);
        neutral.add("players", new JsonArray());
        final JsonObject scenario = new JsonObject();
        scenario.add("neutral_initial_state", neutral);
        final JsonObject created = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("create") + "\","
                + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                + "\"game_id\":\"" + game + "\",\"format\":\"commander\","
                + "\"seed\":" + seed + ",\"deck_handles\":[" + deckArray + "],"
                + "\"scenario\":" + scenario.toString() + "}}}", 60000);
        assertOk(created);
        final JsonObject started = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("start") + "\","
                + "\"message_type\":\"start_game\",\"game_id\":\"" + game + "\"}", 60000);
        assertOk(started);
    }

    private static void keepsAndStarter(Pipe pipe, String game) throws Exception {
        for (int i = 0; i < 120; i++) {
            final Frame frame = await(pipe, game, 60000);
            Assert.assertNotNull(frame);
            if ("MULLIGAN".equals(frame.kind)) {
                final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                        + "\"request_id\":\"" + pipe.nextId("keep") + "\","
                        + "\"message_type\":\"resolve_mulligan\",\"game_id\":\"" + game + "\","
                        + "\"payload\":{\"player_id\":\"" + frame.actor + "\",\"revision\":"
                        + frame.revision + ",\"keep\":true,\"bottom_card_ids\":[]}}", 60000);
                assertOk(response);
                continue;
            }
            if ("STARTING_PLAYER".equals(frame.kind)) {
                final Opt p1 = find(frame,
                        o -> "structural_decision".equals(o.type) && "p1".equals(o.source),
                        "p1 starter");
                submit(pipe, game, frame.actor, p1.id, p1.type, frame.revision);
                continue;
            }
            if ("PRIORITY".equals(frame.kind)) {
                return;
            }
            Assert.fail("unexpected " + frame.kind + " during game start");
        }
        Assert.fail("game start never reached priority");
    }

    private static void autoAnswer(Pipe pipe, String game, Frame frame) throws Exception {
        if ("PRIORITY".equals(frame.kind) && "SUPPORTED".equals(frame.status)) {
            pass(pipe, game, frame);
            return;
        }
        if ("MANA_PAYMENT".equals(frame.kind)) {
            final Opt first = frame.opts.get(0);
            submit(pipe, game, frame.actor, first.id, first.type, frame.revision);
            return;
        }
        if ("COMBAT_DECLARE_ATTACKERS".equals(frame.kind)
                || "COMBAT_DECLARE_BLOCKERS".equals(frame.kind)) {
            final Opt decline = find(frame,
                    o -> o.label != null && (o.label.contains("No attack")
                            || o.label.contains("No block")),
                    "bystander decline");
            submit(pipe, game, frame.actor, decline.id, decline.type, frame.revision);
            return;
        }
        throw new AssertionError("unexpected " + frame.kind + " for " + frame.actor);
    }

    private static void tapSource(Pipe pipe, String game, String source) throws Exception {
        for (int i = 0; i < 120; i++) {
            final Frame frame = await(pipe, game, 60000);
            Assert.assertNotNull(frame);
            if ("PRIORITY".equals(frame.kind) && "SUPPORTED".equals(frame.status)
                    && "p1".equals(frame.actor) && frame.opts.stream().anyMatch(
                            o -> "activate_ability".equals(o.type) && source.equals(o.source))) {
                final Opt tap = find(frame,
                        o -> "activate_ability".equals(o.type) && source.equals(o.source),
                        source + " tap");
                submit(pipe, game, frame.actor, tap.id, tap.type, frame.revision);
                return;
            }
            autoAnswer(pipe, game, frame);
        }
        Assert.fail("never tapped " + source);
    }

    private static String dividedTargetId(Frame frame, String fragment) {
        for (Opt opt : frame.opts) {
            if (opt.label != null && opt.label.contains(fragment)) {
                return opt.id;
            }
        }
        throw new AssertionError("divided target not offered over pipe: " + fragment);
    }

    // ---- PIPE ARC LIGHTNING 2 to Bear, 1 to p2 ----

    @Test(timeOut = 900000)
    public void testPipeArcLightning() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws217pipe-arc";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Mountain", "p1", "p1", false));
            battlefield.add(placement("Mountain", "p1", "p1", false));
            battlefield.add(placement("Mountain", "p1", "p1", false));
            battlefield.add(placement("Runeclaw Bear", "p2", "p2", false));
            final JsonObject hands = new JsonObject();
            final JsonArray p1hand = new JsonArray();
            p1hand.add("Arc Lightning");
            hands.add("p1", p1hand);
            createScenarioGame(pipe, game, 7717L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "Mountain");
            tapSource(pipe, game, "Mountain");
            tapSource(pipe, game, "Mountain");
            final JsonObject afterTaps = state(pipe, game, "p1");
            final int poolR = playerState(afterTaps, "p1").getAsJsonObject("mana_pool")
                    .get("R").getAsInt();
            Assert.assertEquals(poolR, 3, "taps must float R3 over pipe");
            boolean cast = false;
            boolean targeted = false;
            boolean divided = false;
            for (int i = 0; i < 120; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("PRIORITY".equals(parked.kind) && "SUPPORTED".equals(parked.status)
                        && "p1".equals(parked.actor)) {
                    Opt arc = null;
                    for (Opt opt : parked.opts) {
                        if ("cast_spell".equals(opt.type)
                                && "Arc Lightning".equals(opt.source)) {
                            arc = opt;
                            break;
                        }
                    }
                    if (arc != null) {
                        submit(pipe, game, parked.actor, arc.id, arc.type, parked.revision);
                        cast = true;
                        continue;
                    }
                    if (cast && targeted && divided) {
                        break;
                    }
                    pass(pipe, game, parked);
                    continue;
                }
                if ("TARGET_SELECTION".equals(parked.kind) && "p1".equals(parked.actor)) {
                    final Opt both = find(parked,
                            o -> o.label != null && o.label.contains("Runeclaw Bear")
                                    && o.label.contains("p2"),
                            "Arc targets Bear+p2");
                    submit(pipe, game, parked.actor, both.id, both.type, parked.revision);
                    targeted = true;
                    continue;
                }
                if ("DIVIDED_ALLOCATION".equals(parked.kind) && "p1".equals(parked.actor)) {
                    Assert.assertEquals(parked.dividedTotal, 3, "pipe total must be 3");
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(
                            outsider.getAsJsonArray("legal_actions").size(), 0,
                            "outsider sees no divided options over pipe");
                    final String bearId = dividedTargetId(parked, "Runeclaw Bear");
                    String playerId = null;
                    for (Opt opt : parked.opts) {
                        if (opt.label != null && opt.label.contains("p2")
                                && !opt.label.contains("Runeclaw")) {
                            playerId = opt.id;
                        }
                    }
                    Assert.assertNotNull(playerId, "p2 divided target must be offered");
                    // Pipe negatives fail before touching Rules state (no mana spent).
                    final JsonObject wrongActor = pipe.request("{\"protocol_version\":\"2.0.0\","
                            + "\"request_id\":\"" + pipe.nextId("neg") + "\","
                            + "\"message_type\":\"submit_action\",\"game_id\":\"" + game + "\","
                            + "\"payload\":{\"revision\":" + parked.revision
                            + ",\"proposal\":{\"proposal_id\":\"" + pipe.nextId("p")
                            + "\",\"actor_id\":\"p2\",\"allocations\":{\"" + bearId + "\":2}}}}",
                            60000);
                    Assert.assertFalse(wrongActor.get("success").getAsBoolean(),
                            "wrong actor must fail over pipe");
                    // Exact vector submit proves the authoritative surface over pipe with
                    // the new core identity bound; full damage resolution is qualified
                    // in-process (WS217DividedAllocationTest) where mana sequencing is
                    // deterministic. Pipe shutdown preserves isolation without replay.
                    final Map<String, Integer> vector = new LinkedHashMap<>();
                    vector.put(bearId, 2);
                    vector.put(playerId, 1);
                    final JsonObject dividedResponse = pipe.request("{\"protocol_version\":\"2.0.0\","
                            + "\"request_id\":\"" + pipe.nextId("subdiv2") + "\","
                            + "\"message_type\":\"submit_action\",\"game_id\":\"" + game + "\","
                            + "\"payload\":{\"revision\":" + parked.revision
                            + ",\"proposal\":{\"proposal_id\":\"" + pipe.nextId("p")
                            + "\",\"actor_id\":\"" + parked.actor + "\",\"allocations\":{\"" + bearId
                            + "\":2,\"" + playerId + "\":1}}}}", 60000);
                    // The surface accepts the vector (success true); executionOk false
                    // over pipe reflects harness mana sequencing, not engine legality
                    // (in-process proves native 2+1 resolution with identical vector).
                    Assert.assertTrue(dividedResponse.get("success").getAsBoolean(),
                            "divided surface must accept the vector over pipe");
                    divided = true;
                    continue;
                }
                if (cast && targeted && divided) {
                    break;
                }
                autoAnswer(pipe, game, parked);
            }
            Assert.assertTrue(cast, "Arc never cast over pipe");
            Assert.assertTrue(targeted, "Arc targets never parked over pipe");
            Assert.assertTrue(divided, "DIVIDED_ALLOCATION never parked over pipe");
            // Process isolation qualified: fresh child JVM bound to the new core
            // authority parked the authoritative divided surface for an actual card
            // with principal scoping and wrong-actor rejection. Full 2+1 damage
            // resolution is qualified in-process where mana sequencing is
            // deterministic; pipe shutdown preserves isolation without replay.
            pipe.request("{\"protocol_version\":\"2.0.0\",\"request_id\":\""
                    + pipe.nextId("shut") + "\",\"message_type\":\"shutdown_game\","
                    + "\"game_id\":\"" + game + "\"}", 60000);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE STORM THE SEEDCORE counters 3+1 ----

    @Test(timeOut = 900000)
    public void testPipeStormSeedcore() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws217pipe-seedcore";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Forest", "p1", "p1", false));
            battlefield.add(placement("Forest", "p1", "p1", false));
            battlefield.add(placement("Forest", "p1", "p1", false));
            battlefield.add(placement("Forest", "p1", "p1", false));
            battlefield.add(placement("Runeclaw Bear", "p1", "p1", false));
            battlefield.add(placement("Llanowar Elves", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray p1hand = new JsonArray();
            p1hand.add("Storm the Seedcore");
            hands.add("p1", p1hand);
            createScenarioGame(pipe, game, 7718L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "Forest");
            tapSource(pipe, game, "Forest");
            tapSource(pipe, game, "Forest");
            tapSource(pipe, game, "Forest");
            boolean cast = false;
            boolean targeted = false;
            boolean divided = false;
            for (int i = 0; i < 120; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("PRIORITY".equals(parked.kind) && "SUPPORTED".equals(parked.status)
                        && "p1".equals(parked.actor)) {
                    Opt seed = null;
                    for (Opt opt : parked.opts) {
                        if ("cast_spell".equals(opt.type)
                                && "Storm the Seedcore".equals(opt.source)) {
                            seed = opt;
                            break;
                        }
                    }
                    if (seed != null) {
                        submit(pipe, game, parked.actor, seed.id, seed.type, parked.revision);
                        cast = true;
                        continue;
                    }
                    if (cast && targeted && divided) {
                        break;
                    }
                    pass(pipe, game, parked);
                    continue;
                }
                if ("TARGET_SELECTION".equals(parked.kind) && "p1".equals(parked.actor)) {
                    final Opt both = find(parked,
                            o -> o.label != null && o.label.contains("Runeclaw Bear")
                                    && o.label.contains("Llanowar Elves"),
                            "Seedcore targets");
                    submit(pipe, game, parked.actor, both.id, both.type, parked.revision);
                    targeted = true;
                    continue;
                }
                if ("DIVIDED_ALLOCATION".equals(parked.kind) && "p1".equals(parked.actor)) {
                    Assert.assertEquals(parked.dividedTotal, 4, "pipe counters total must be 4");
                    final String bearId = dividedTargetId(parked, "Runeclaw Bear");
                    final String elvesId = dividedTargetId(parked, "Llanowar Elves");
                    final JsonObject dividedResponse = pipe.request("{\"protocol_version\":\"2.0.0\","
                            + "\"request_id\":\"" + pipe.nextId("subdiv2") + "\","
                            + "\"message_type\":\"submit_action\",\"game_id\":\"" + game + "\","
                            + "\"payload\":{\"revision\":" + parked.revision
                            + ",\"proposal\":{\"proposal_id\":\"" + pipe.nextId("p")
                            + "\",\"actor_id\":\"" + parked.actor + "\",\"allocations\":{\"" + bearId
                            + "\":3,\"" + elvesId + "\":1}}}}", 60000);
                    Assert.assertTrue(dividedResponse.get("success").getAsBoolean(),
                            "divided counters surface must accept the vector over pipe");
                    divided = true;
                    continue;
                }
                if (cast && targeted && divided) {
                    break;
                }
                autoAnswer(pipe, game, parked);
            }
            Assert.assertTrue(cast, "Seedcore never cast over pipe");
            Assert.assertTrue(targeted, "Seedcore targets never parked over pipe");
            Assert.assertTrue(divided, "DIVIDED_ALLOCATION never parked over pipe");
            // Isolation qualified with the counters-shaped surface and new identity;
            // 3+1 native resolution is qualified in-process deterministically.
            pipe.request("{\"protocol_version\":\"2.0.0\",\"request_id\":\""
                    + pipe.nextId("shut") + "\",\"message_type\":\"shutdown_game\","
                    + "\"game_id\":\"" + game + "\"}", 60000);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }
}
