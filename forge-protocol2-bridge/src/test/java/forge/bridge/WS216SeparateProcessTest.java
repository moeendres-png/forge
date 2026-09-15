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
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS216 separate-process gap closure: every bounded WS202 pipe UNKNOWN driven
 * through a real child bridge JVM over Protocol-2.0.0 JSONL with scenario
 * injection plus fixed seeds. Each test boots its own child (isolation).
 * The parent only transports pilot selections among offered options.
 */
public class WS216SeparateProcessTest {
    private static final String ENGINE_SHA = "aa5c00aa32dfd40e213f223f8fd400c43daabb24";

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
            }, "ws216pipe-stdout");
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
            }, "ws216pipe-stderr");
            errPump.setDaemon(true);
            errPump.start();
        }

        String nextId(String stem) {
            return stem + "-" + ids.incrementAndGet();
        }

        JsonObject request(String json, long timeoutMillis) throws Exception {
            final JsonObject sent = JsonParser.parseString(json).getAsJsonObject();
            final String requestId = sent.get("request_id").getAsString();
            stdin.write(json);
            stdin.write("\n");
            stdin.flush();
            final long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                final String line = stdout.poll(
                        Math.max(100, deadline - System.currentTimeMillis()),
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
        boolean freeInput;
        long inputMin;
        long inputMax;
    }

    private static void assertOk(JsonObject response) {
        Assert.assertTrue(response.get("success").getAsBoolean(), "expected ok: " + response);
        Assert.assertEquals(response.get("protocol_version").getAsString(), "2.0.0");
    }

    private static void assertErrorCode(JsonObject response, String code) {
        Assert.assertFalse(response.get("success").getAsBoolean(), "expected error: " + response);
        Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                .get("code").getAsString(), code, "response: " + response);
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
            if (decision.has("free_input") && decision.get("free_input").getAsBoolean()) {
                frame.freeInput = true;
                frame.inputMin = decision.get("input_min").getAsLong();
                frame.inputMax = decision.get("input_max").getAsLong();
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
            String actionType, long revision, Long value) throws Exception {
        final StringBuilder proposal = new StringBuilder("{\"proposal_id\":\"")
                .append(pipe.nextId("p")).append("\",\"actor_id\":\"").append(actor)
                .append("\",\"legal_action_id\":\"").append(actionId)
                .append("\",\"action_type\":\"").append(actionType).append("\"");
        if (value != null) {
            proposal.append(",\"value\":").append(value);
        }
        proposal.append("}");
        final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("sub") + "\","
                + "\"message_type\":\"submit_action\",\"game_id\":\"" + game + "\","
                + "\"payload\":{\"revision\":" + revision + ",\"proposal\":" + proposal + "}}",
                60000);
        assertOk(response);
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

    private static int librarySize(JsonObject state, String pid) {
        return playerState(state, pid).getAsJsonObject("zones").get("library_size").getAsInt();
    }

    private static JsonArray battlefieldDetails(JsonObject state, String pid) {
        return playerState(state, pid).getAsJsonObject("zones")
                .getAsJsonArray("battlefield_details");
    }

    private static JsonObject detailFor(JsonArray details, String name) {
        for (JsonElement element : details) {
            final JsonObject entry = element.getAsJsonObject();
            if (name.equals(str(entry, "name"))) {
                return entry;
            }
        }
        return null;
    }

    private static int pool(JsonObject state, String pid, String color) {
        return playerState(state, pid).getAsJsonObject("mana_pool").get(color).getAsInt();
    }

    private static int turn(JsonObject state) {
        return state.get("turn_number").getAsInt();
    }

    private static String step(JsonObject state) {
        return str(state, "step") == null ? "" : str(state, "step");
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
                submit(pipe, game, frame.actor, p1.id, p1.type, frame.revision, null);
                continue;
            }
            if ("PRIORITY".equals(frame.kind)) {
                return;
            }
            Assert.fail("unexpected " + frame.kind + " during game start");
        }
        Assert.fail("game start never reached priority");
    }

    private static Frame driveTo(Pipe pipe, String game, String actor, String kind,
            String stepFragment, int minTurn, int budget) throws Exception {
        for (int i = 0; i < budget; i++) {
            final Frame frame = await(pipe, game, 60000);
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind);
            if (frame.kind.equals(kind) && frame.actor.equals(actor)
                    && "SUPPORTED".equals(frame.status)) {
                if (minTurn >= 0 || stepFragment != null) {
                    final JsonObject st = state(pipe, game, actor);
                    if (minTurn >= 0 && turn(st) < minTurn) {
                        autoAnswer(pipe, game, frame, actor, kind);
                        continue;
                    }
                    if (stepFragment != null && !step(st).contains(stepFragment)) {
                        autoAnswer(pipe, game, frame, actor, kind);
                        continue;
                    }
                }
                return frame;
            }
            autoAnswer(pipe, game, frame, actor, kind);
        }
        throw new AssertionError("never reached " + kind + " for " + actor);
    }

    private static void autoAnswer(Pipe pipe, String game, Frame frame, String actor,
            String targetKind) throws Exception {
        if ("PRIORITY".equals(frame.kind) && "SUPPORTED".equals(frame.status)) {
            pass(pipe, game, frame);
            return;
        }
        if ("MANA_PAYMENT".equals(frame.kind)) {
            final Opt first = frame.opts.get(0);
            submit(pipe, game, frame.actor, first.id, first.type, frame.revision, null);
            return;
        }
        if (("COMBAT_DECLARE_ATTACKERS".equals(frame.kind)
                || "COMBAT_DECLARE_BLOCKERS".equals(frame.kind))
                && !frame.actor.equals(actor)) {
            final Opt decline = find(frame,
                    o -> o.label != null && (o.label.contains("No attack")
                            || o.label.contains("No block")),
                    "bystander decline");
            submit(pipe, game, frame.actor, decline.id, decline.type, frame.revision, null);
            return;
        }
        if ("COLOR_CHOICE".equals(frame.kind)) {
            Opt green = null;
            for (Opt opt : frame.opts) {
                if (opt.label != null && opt.label.contains("green")) {
                    green = opt;
                }
            }
            final Opt pick = green == null ? frame.opts.get(0) : green;
            submit(pipe, game, frame.actor, pick.id, pick.type, frame.revision, null);
            return;
        }
        throw new AssertionError("unexpected " + frame.kind + " for " + frame.actor
                + " while driving to " + targetKind);
    }

    private static void tapSource(Pipe pipe, String game, String actor, String source)
            throws Exception {
        final Frame frame = driveTo(pipe, game, actor, "PRIORITY", "MAIN", -1, 120);
        final Opt tap = find(frame,
                o -> "activate_ability".equals(o.type) && source.equals(o.source),
                source + " tap");
        submit(pipe, game, frame.actor, tap.id, tap.type, frame.revision, null);
        for (int i = 0; i < 10; i++) {
            final Frame parked = await(pipe, game, 60000);
            Assert.assertNotNull(parked, "no frame parked after tap");
            if ("PRIORITY".equals(parked.kind) && parked.actor.equals(actor)) {
                return;
            }
            autoAnswer(pipe, game, parked, actor, "tap follow-up");
        }
        Assert.fail("priority never resumed after tap");
    }

    private static void resolveToLife(Pipe pipe, String game, String observer, String pid,
            int life, int budget) throws Exception {
        for (int i = 0; i < budget; i++) {
            if (life(state(pipe, game, observer), pid) == life) {
                return;
            }
            final Frame parked = await(pipe, game, 60000);
            Assert.assertNotNull(parked);
            if ("PRIORITY".equals(parked.kind) && "SUPPORTED".equals(parked.status)) {
                pass(pipe, game, parked);
            } else if ("MANA_PAYMENT".equals(parked.kind)) {
                final Opt first = parked.opts.get(0);
                submit(pipe, game, parked.actor, first.id, first.type, parked.revision, null);
            } else {
                Assert.fail("unexpected " + parked.kind + " while resolving to life " + life);
            }
        }
        Assert.fail("life " + life + " for " + pid + " never reached");
    }

    private static void assignDamage(Pipe pipe, String game, String source, String recipient,
            int amount) throws Exception {
        for (int i = 0; i < 20; i++) {
            final Frame parked = await(pipe, game, 60000);
            Assert.assertNotNull(parked, "damage frame never parked for " + recipient);
            if ("PRIORITY".equals(parked.kind) && "SUPPORTED".equals(parked.status)) {
                pass(pipe, game, parked);
                continue;
            }
            if ("MANA_PAYMENT".equals(parked.kind)) {
                final Opt first = parked.opts.get(0);
                submit(pipe, game, parked.actor, first.id, first.type, parked.revision, null);
                continue;
            }
            if (!"COMBAT_DAMAGE".equals(parked.kind)) {
                throw new AssertionError("expected COMBAT_DAMAGE, got " + parked.kind);
            }
            final Opt match = find(parked,
                    o -> o.label != null && o.label.contains(source)
                            && o.label.contains(recipient)
                            && o.label.contains(String.valueOf(amount)),
                    amount + " to " + recipient);
            submit(pipe, game, parked.actor, match.id, match.type, parked.revision, null);
            return;
        }
        throw new AssertionError("damage assignment never settled for " + recipient);
    }

    private static Pipe boot() throws Exception {
        final Pipe pipe = new Pipe();
        try {
            final JsonObject hello = pipe.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"ws-boot\",\"message_type\":\"start_engine\"}", 240000);
            assertOk(hello);
            final JsonObject version = pipe.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"ws-version\",\"message_type\":\"get_provider_version\"}",
                    60000);
            assertOk(version);
            Assert.assertEquals(version.get("payload").getAsJsonObject()
                    .get("engine_commit").getAsString(), ENGINE_SHA);
            return pipe;
        } catch (Exception | AssertionError e) {
            try {
                pipe.close();
            } catch (Exception inner) {
                pipe.process.destroyForcibly();
            }
            throw e;
        }
    }

    private static void shutdown(Pipe pipe, String game) throws Exception {
        final JsonObject down = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("down") + "\","
                + "\"message_type\":\"shutdown_game\",\"game_id\":\"" + game + "\"}", 60000);
        assertOk(down);
        final JsonObject off = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("off") + "\","
                + "\"message_type\":\"shutdown_engine\"}", 30000);
        assertOk(off);
        pipe.close();
        Assert.assertEquals(pipe.process.exitValue(), 0);
    }

    // ---- PIPE_ORDER_CHOICE: Bone Dancer ordered graveyard over the pipe ----

    @Test(timeOut = 900000)
    public void testPipeOrderChoice() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-order";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Carnage Tyrant", "p2", "p2", false));
            battlefield.add(placement("Bone Dancer", "p2", "p2", false));
            battlefield.add(placement("Runeclaw Bear", "p1", "p1", false));
            battlefield.add(placement("Llanowar Elves", "p1", "p1", false));
            createScenarioGame(pipe, game, 2201L, handles, battlefield, new JsonObject());
            keepsAndStarter(pipe, game);
            final Frame attackFrame = driveTo(pipe, game, "p2", "COMBAT_DECLARE_ATTACKERS",
                    "DECLARE_ATTACKERS", -1, 200);
            final Opt attack = find(attackFrame,
                    o -> o.label != null && o.label.contains("Carnage Tyrant")
                            && o.label.contains("-> p1") && !o.label.contains("Bone Dancer"),
                    "Tyrant attacks p1 alone");
            submit(pipe, game, attackFrame.actor, attack.id, attack.type, attackFrame.revision,
                    null);
            final Frame blockFrame = driveTo(pipe, game, "p1", "COMBAT_DECLARE_BLOCKERS",
                    null, -1, 80);
            final Opt block = find(blockFrame,
                    o -> o.label != null && o.label.contains("Runeclaw Bear")
                            && o.label.contains("Llanowar Elves"),
                    "double block");
            submit(pipe, game, blockFrame.actor, block.id, block.type, blockFrame.revision,
                    null);
            assignDamage(pipe, game, "Carnage Tyrant", "Runeclaw Bear", 2);
            assignDamage(pipe, game, "Carnage Tyrant", "Llanowar Elves", 1);
            assignDamage(pipe, game, "Carnage Tyrant", "player p1", 4);
            boolean ordered = false;
            for (int i = 0; i < 40; i++) {
                if (ordered && life(state(pipe, game, "p1"), "p1") == 36) {
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("ORDER_CHOICE".equals(parked.kind) && parked.actor.equals("p1")) {
                    Assert.assertEquals(parked.opts.size(), 2, "Bear/Elves permutations");
                    // Principal scoping: outsider sees no options while p1 owns it.
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                    final Opt bearFirst = find(parked,
                            o -> o.label != null && o.label.contains("Runeclaw Bear")
                                    && o.label.contains("Llanowar Elves")
                                    && o.label.indexOf("Runeclaw Bear")
                                            < o.label.indexOf("Llanowar Elves"),
                            "Bear then Elves");
                    submit(pipe, game, parked.actor, bearFirst.id, bearFirst.type,
                            parked.revision, null);
                    ordered = true;
                    continue;
                }
                autoAnswer(pipe, game, parked, "p1", "ordered graveyard");
            }
            Assert.assertTrue(ordered, "ORDER_CHOICE never parked over the pipe");
            resolveToLife(pipe, game, "p1", "p1", 36, 60);
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_COLOR_CHOICE: City of Brass parks and funds over the pipe ----
    // Lands carry no summoning sickness, so the turn-1 scenario placement taps
    // immediately (Birds would be sick until turn 2 when placed via the hook).

    @Test(timeOut = 900000)
    public void testPipeColorChoice() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-color";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("City of Brass", "p1", "p1", false));
            createScenarioGame(pipe, game, 2202L, handles, battlefield, new JsonObject());
            keepsAndStarter(pipe, game);
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", "MAIN", -1, 120);
            final Opt tap = find(frame,
                    o -> "activate_ability".equals(o.type)
                            && "City of Brass".equals(o.source),
                    "City activation");
            submit(pipe, game, frame.actor, tap.id, tap.type, frame.revision, null);
            boolean colored = false;
            for (int i = 0; i < 10; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("COLOR_CHOICE".equals(parked.kind) && parked.actor.equals("p1")) {
                    Assert.assertTrue(parked.opts.size() >= 5);
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                    final Opt green = find(parked,
                            o -> o.label != null && o.label.contains("green"), "green mana");
                    submit(pipe, game, parked.actor, green.id, green.type, parked.revision,
                            null);
                    colored = true;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "color choice");
            }
            Assert.assertTrue(colored, "COLOR_CHOICE never parked over the pipe");
            Assert.assertEquals(pool(state(pipe, game, "p1"), "p1", "G"), 1);
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_GENERIC_SELECTION: Fleshbag sacrifice over the pipe ----

    @Test(timeOut = 900000)
    public void testPipeGenericSelection() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-generic-sel";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Runeclaw Bear", "p1", "p1", false));
            battlefield.add(placement("Llanowar Elves", "p1", "p1", false));
            battlefield.add(placement("Swamp", "p1", "p1", false));
            battlefield.add(placement("Swamp", "p1", "p1", false));
            battlefield.add(placement("Swamp", "p1", "p1", false));
            battlefield.add(placement("Runeclaw Bear", "p2", "p2", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Fleshbag Marauder");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 2203L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "p1", "Swamp");
            tapSource(pipe, game, "p1", "Swamp");
            tapSource(pipe, game, "p1", "Swamp");
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Fleshbag Marauder".equals(o.source),
                    "Fleshbag cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            boolean sacrificed = false;
            for (int i = 0; i < 30; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("GENERIC_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    Assert.assertEquals(parked.opts.size(), 3);
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                    final Opt elves = find(parked,
                            o -> o.label != null && o.label.contains("Llanowar Elves"),
                            "sacrifice Elves");
                    submit(pipe, game, parked.actor, elves.id, elves.type, parked.revision,
                            null);
                    sacrificed = true;
                    continue;
                }
                if ("GENERIC_SELECTION".equals(parked.kind)) {
                    submit(pipe, game, parked.actor, parked.opts.get(0).id,
                            parked.opts.get(0).type, parked.revision, null);
                    continue;
                }
                if (sacrificed && zone(state(pipe, game, "p1"), "p1", "graveyard")
                        .contains("Llanowar Elves")) {
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "fleshbag sacrifice");
                if (zone(state(pipe, game, "p1"), "p1", "graveyard")
                        .contains("Llanowar Elves")) {
                    sacrificed = true;
                    break;
                }
            }
            Assert.assertTrue(sacrificed, "GENERIC_SELECTION never settled over the pipe");
            Assert.assertTrue(zone(state(pipe, game, "p1"), "p1", "graveyard")
                    .contains("Llanowar Elves"));
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_GENERIC_CONFIRM: Snort may-discard over the pipe ----

    @Test(timeOut = 900000)
    public void testPipeGenericConfirm() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-generic-confirm";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Mountain", "p1", "p1", false));
            battlefield.add(placement("Mountain", "p1", "p1", false));
            battlefield.add(placement("Mountain", "p1", "p1", false));
            battlefield.add(placement("Mountain", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Snort");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 2204L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "p1", "Mountain");
            tapSource(pipe, game, "p1", "Mountain");
            tapSource(pipe, game, "p1", "Mountain");
            tapSource(pipe, game, "p1", "Mountain");
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Snort".equals(o.source),
                    "Snort cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            boolean confirmed = false;
            for (int i = 0; i < 30; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("GENERIC_CONFIRM".equals(parked.kind) && parked.actor.equals("p1")) {
                    Assert.assertEquals(parked.opts.size(), 2);
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                    final Opt yes = find(parked,
                            o -> o.label != null && o.label.contains("Yes"), "Yes discard");
                    submit(pipe, game, parked.actor, yes.id, yes.type, parked.revision,
                            null);
                    confirmed = true;
                    continue;
                }
                if ("GENERIC_CONFIRM".equals(parked.kind)) {
                    final Opt no = find(parked,
                            o -> o.label != null && o.label.contains("No"), "No");
                    submit(pipe, game, parked.actor, no.id, no.type, parked.revision, null);
                    continue;
                }
                if (confirmed) {
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "snort confirm");
            }
            Assert.assertTrue(confirmed, "GENERIC_CONFIRM never parked over the pipe");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_BINARY: Extinction Event odd/even over the pipe ----

    @Test(timeOut = 900000)
    public void testPipeBinary() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-binary";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Runeclaw Bear", "p1", "p1", false));
            battlefield.add(placement("Llanowar Elves", "p1", "p1", false));
            battlefield.add(placement("Carnage Tyrant", "p2", "p2", false));
            battlefield.add(placement("Swamp", "p1", "p1", false));
            battlefield.add(placement("Swamp", "p1", "p1", false));
            battlefield.add(placement("Swamp", "p1", "p1", false));
            battlefield.add(placement("Swamp", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Extinction Event");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 2205L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "p1", "Swamp");
            tapSource(pipe, game, "p1", "Swamp");
            tapSource(pipe, game, "p1", "Swamp");
            tapSource(pipe, game, "p1", "Swamp");
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Extinction Event".equals(o.source),
                    "Extinction cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            boolean binary = false;
            for (int i = 0; i < 10; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("BINARY_CHOICE".equals(parked.kind) && parked.actor.equals("p1")) {
                    Assert.assertEquals(parked.opts.size(), 2);
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                    submit(pipe, game, parked.actor, parked.opts.get(0).id,
                            parked.opts.get(0).type, parked.revision, null);
                    binary = true;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "binary choice");
            }
            Assert.assertTrue(binary, "BINARY_CHOICE never parked over the pipe");
            boolean settled = false;
            for (int i = 0; i < 30 && !settled; i++) {
                final JsonObject st = state(pipe, game, "p1");
                final List<String> exile = zone(st, "p1", "exile");
                if (exile.contains("Llanowar Elves") != exile.contains("Runeclaw Bear")) {
                    settled = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                autoAnswer(pipe, game, parked, "p1", "binary settle");
            }
            Assert.assertTrue(settled, "binary exile never settled over the pipe");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_AMOUNT: Master divider over the pipe ----

    @Test(timeOut = 900000)
    public void testPipeAmount() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-amount";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Master of the Wild Hunt", "p1", "p1", false));
            battlefield.add(placement("Dire Wolves", "p1", "p1", false));
            battlefield.add(placement("Dire Wolves", "p1", "p1", false));
            battlefield.add(placement("Runeclaw Bear", "p2", "p2", false));
            createScenarioGame(pipe, game, 2206L, handles, battlefield, new JsonObject());
            keepsAndStarter(pipe, game);
            // Scenario creatures enter during turn 1 via the hook and are
            // summoning-sick on turn 1; Master taps, so wait for p1's next own
            // turn (turn 5 in a 4-player pod) where sickness has cleared.
            final JsonObject beforeMaster = state(pipe, game, "p1");
            final int turnNow = beforeMaster.get("turn_number").getAsInt();
            int masterTurn = turnNow + 1;
            while ((masterTurn - 1) % 4 != 0) {
                masterTurn++;
            }
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", "MAIN", masterTurn,
                    400);
            final Opt activate = find(frame,
                    o -> "activate_ability".equals(o.type)
                            && "Master of the Wild Hunt".equals(o.source),
                    "Master activation");
            submit(pipe, game, frame.actor, activate.id, activate.type, frame.revision,
                    null);
            boolean targeted = false;
            boolean amount = false;
            for (int i = 0; i < 40; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("TARGET_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    final Opt target = find(parked,
                            o -> o.label != null && o.label.contains("Runeclaw Bear"),
                            "Master -> Bear");
                    submit(pipe, game, parked.actor, target.id, target.type,
                            parked.revision, null);
                    targeted = true;
                    continue;
                }
                if (("GENERIC_CONFIRM".equals(parked.kind)
                        || "TRIGGER_PLAY".equals(parked.kind))
                        && parked.actor.equals("p2")) {
                    final Opt yes = find(parked,
                            o -> o.label != null && o.label.contains("Yes"), "divider Yes");
                    submit(pipe, game, parked.actor, yes.id, yes.type, parked.revision,
                            null);
                    continue;
                }
                if ("AMOUNT_DISTRIBUTION".equals(parked.kind) && parked.actor.equals("p2")) {
                    Assert.assertTrue(parked.opts.size() >= 2);
                    final JsonObject outsider = state(pipe, game, "p1");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                    Opt two = null;
                    for (Opt o : parked.opts) {
                        if (o.label != null && o.label.contains("2")) {
                            two = o;
                        }
                    }
                    final Opt pick = two == null ? parked.opts.get(0) : two;
                    submit(pipe, game, parked.actor, pick.id, pick.type, parked.revision,
                            null);
                    amount = true;
                    continue;
                }
                if (targeted && amount) {
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "master amount");
                if (targeted && amount) {
                    break;
                }
            }
            Assert.assertTrue(targeted, "Master target never parked over the pipe");
            Assert.assertTrue(amount, "AMOUNT_DISTRIBUTION never parked over the pipe");
            boolean bearDead = false;
            for (int i = 0; i < 30 && !bearDead; i++) {
                if (!zone(state(pipe, game, "p2"), "p2", "battlefield")
                        .contains("Runeclaw Bear")) {
                    bearDead = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("AMOUNT_DISTRIBUTION".equals(parked.kind)) {
                    submit(pipe, game, parked.actor, parked.opts.get(0).id,
                            parked.opts.get(0).type, parked.revision, null);
                    continue;
                }
                autoAnswer(pipe, game, parked, "p1", "amount settle");
            }
            Assert.assertTrue(bearDead, "Bear must die to Wolves over the pipe");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_MALFORMED_SCENARIO: stack injection rejected over the real pipe ----

    @Test(timeOut = 900000)
    public void testPipeMalformedScenario() throws Exception {
        final Pipe pipe = boot();
        try {
            final List<String> handles = importPod(pipe);
            final StringBuilder deckArray = new StringBuilder();
            for (int i = 0; i < handles.size(); i++) {
                if (i > 0) {
                    deckArray.append(',');
                }
                deckArray.append('"').append(handles.get(i)).append('"');
            }
            final JsonObject neutral = new JsonObject();
            neutral.add("battlefield", new JsonArray());
            neutral.add("hands", new JsonObject());
            neutral.add("players", new JsonArray());
            final JsonArray stack = new JsonArray();
            stack.add("x");
            neutral.add("stack", stack);
            final JsonObject scenario = new JsonObject();
            scenario.add("neutral_initial_state", neutral);
            final JsonObject response = pipe.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + pipe.nextId("badcreate") + "\","
                    + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                    + "\"game_id\":\"ws216pipe-bad\",\"format\":\"commander\","
                    + "\"seed\":9999,\"deck_handles\":[" + deckArray + "],"
                    + "\"scenario\":" + scenario.toString() + "}}}", 60000);
            assertErrorCode(response, "game_creation_failed");
            Assert.assertTrue(response.toString().contains("stack"),
                    "rejection must name the offending field: " + response);
            final JsonObject off = pipe.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + pipe.nextId("off") + "\","
                    + "\"message_type\":\"shutdown_engine\"}", 30000);
            assertOk(off);
            pipe.close();
            Assert.assertEquals(pipe.process.exitValue(), 0);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_SEARCH_OUTCOME: library shrink plus tapped arrival over the pipe ----

    @Test(timeOut = 900000)
    public void testPipeSearchOutcome() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-search";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Forest", "p1", "p1", false));
            battlefield.add(placement("Mountain", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Rampant Growth");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 2207L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            final int libraryBefore = librarySize(state(pipe, game, "p1"), "p1");
            Assert.assertTrue(libraryBefore > 0, "scenario library must be non-empty");
            tapSource(pipe, game, "p1", "Forest");
            tapSource(pipe, game, "p1", "Mountain");
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Rampant Growth".equals(o.source),
                    "Rampant Growth cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            boolean searched = false;
            for (int i = 0; i < 20; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "battlefield").contains("Plains")) {
                    searched = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("SEARCH_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    Assert.assertFalse(parked.opts.isEmpty());
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                    final Opt plains = find(parked,
                            o -> o.label != null && o.label.contains("Plains"), "find Plains");
                    submit(pipe, game, parked.actor, plains.id, plains.type, parked.revision,
                            null);
                    continue;
                }
                if (parked.actor.equals("p1")) {
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                }
                autoAnswer(pipe, game, parked, "p1", "search");
            }
            Assert.assertTrue(searched, "searched Plains never arrived over the pipe");
            final JsonObject after = state(pipe, game, "p1");
            Assert.assertTrue(librarySize(after, "p1") < libraryBefore,
                    "library must shrink by the search");
            final JsonArray details = battlefieldDetails(after, "p1");
            final JsonObject plainsDetail = detailFor(details, "Plains");
            Assert.assertNotNull(plainsDetail, "searched Plains detail must project");
            Assert.assertTrue(plainsDetail.get("tapped").getAsBoolean(),
                    "searched land must arrive tapped");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_REPLACEMENT_OUTCOME: Serpent counters plus PT over the pipe ----

    @Test(timeOut = 900000)
    public void testPipeReplacementOutcome() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-replacement";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Doubling Season", "p1", "p1", false));
            battlefield.add(placement("Hardened Scales", "p1", "p1", false));
            battlefield.add(placement("Forest", "p1", "p1", false));
            battlefield.add(placement("Forest", "p1", "p1", false));
            battlefield.add(placement("Forest", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Stonecoil Serpent");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 2208L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "p1", "Forest");
            tapSource(pipe, game, "p1", "Forest");
            tapSource(pipe, game, "p1", "Forest");
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Stonecoil Serpent".equals(o.source),
                    "Serpent cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            Frame xFrame = null;
            for (int i = 0; i < 20; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("X_ANNOUNCE".equals(parked.kind) && parked.actor.equals("p1")) {
                    xFrame = parked;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "X announce");
            }
            Assert.assertNotNull(xFrame, "X_ANNOUNCE never parked over the pipe");
            Assert.assertTrue(xFrame.freeInput);
            submit(pipe, game, "p1", xFrame.opts.get(0).id, xFrame.opts.get(0).type,
                    xFrame.revision, 3L);
            Frame orderFrame = null;
            for (int i = 0; i < 20; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("REPLACEMENT_ORDER".equals(parked.kind) && parked.actor.equals("p1")) {
                    orderFrame = parked;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "replacement order");
            }
            Assert.assertNotNull(orderFrame, "replacement order never parked over the pipe");
            Assert.assertEquals(orderFrame.opts.size(), 2);
            final Opt scalesFirst = find(orderFrame,
                    o -> o.label != null && o.label.contains("plus one"), "Scales first");
            submit(pipe, game, orderFrame.actor, scalesFirst.id, scalesFirst.type,
                    orderFrame.revision, null);
            boolean entered = false;
            JsonObject serpentDetail = null;
            for (int i = 0; i < 40 && !entered; i++) {
                final JsonObject st = state(pipe, game, "p1");
                if (zone(st, "p1", "battlefield").contains("Stonecoil Serpent")) {
                    final JsonObject detail = detailFor(battlefieldDetails(st, "p1"),
                            "Stonecoil Serpent");
                    if (detail != null && detail.has("counters")
                            && detail.getAsJsonObject("counters").has("+1/+1")
                            && detail.getAsJsonObject("counters").get("+1/+1")
                                    .getAsInt() == 8) {
                        entered = true;
                        serpentDetail = detail;
                        break;
                    }
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                autoAnswer(pipe, game, parked, "p1", "serpent entry");
            }
            Assert.assertTrue(entered, "Serpent never entered with 8 counters over the pipe");
            Assert.assertEquals(serpentDetail.get("power").getAsInt(), 8);
            Assert.assertEquals(serpentDetail.get("toughness").getAsInt(), 8);
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_COMMANDER_TAX_RECAST: taxed recast over the pipe ----
    // Isamaru starts in the command zone natively (fixture deck commander);
    // first cast from command (W), Murder to command, taxed recast ({W}+{2}).

    @Test(timeOut = 900000)
    public void testPipeCommanderTaxRecast() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-commander-tax";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Plains", "p1", "p1", false));
            battlefield.add(placement("Plains", "p1", "p1", false));
            battlefield.add(placement("Plains", "p1", "p1", false));
            battlefield.add(placement("Swamp", "p2", "p2", false));
            battlefield.add(placement("Swamp", "p2", "p2", false));
            battlefield.add(placement("Swamp", "p2", "p2", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Murder");
            hands.add("p2", hand);
            createScenarioGame(pipe, game, 2209L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            // First cast Isamaru from the command zone for {W} on p1's opening.
            driveTo(pipe, game, "p1", "PRIORITY", "MAIN", -1, 120);
            tapSource(pipe, game, "p1", "Plains");
            final Frame firstCastFrame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt firstCast = find(firstCastFrame,
                    o -> "cast_spell".equals(o.type)
                            && "Isamaru, Hound of Konda".equals(o.source),
                    "Isamaru first cast");
            submit(pipe, game, firstCastFrame.actor, firstCast.id, firstCast.type,
                    firstCastFrame.revision, null);
            boolean firstOut = false;
            for (int i = 0; i < 30 && !firstOut; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "battlefield")
                        .contains("Isamaru, Hound of Konda")) {
                    firstOut = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                autoAnswer(pipe, game, parked, "p1", "first cast settle");
            }
            Assert.assertTrue(firstOut, "Isamaru never entered from command over pipe");
            driveTo(pipe, game, "p2", "PRIORITY", "MAIN", 2, 200);
            tapSource(pipe, game, "p2", "Swamp");
            tapSource(pipe, game, "p2", "Swamp");
            tapSource(pipe, game, "p2", "Swamp");
            final Frame murderFrame = driveTo(pipe, game, "p2", "PRIORITY", null, -1, 40);
            final Opt murder = find(murderFrame,
                    o -> "cast_spell".equals(o.type) && "Murder".equals(o.source),
                    "Murder cast");
            submit(pipe, game, murderFrame.actor, murder.id, murder.type, murderFrame.revision,
                    null);
            boolean moved = false;
            for (int i = 0; i < 40; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "command").contains(
                        "Isamaru, Hound of Konda")) {
                    moved = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("TARGET_SELECTION".equals(parked.kind) && parked.actor.equals("p2")) {
                    final Opt target = find(parked,
                            o -> o.label != null && o.label.contains("Isamaru"),
                            "Murder -> Isamaru");
                    submit(pipe, game, parked.actor, target.id, target.type, parked.revision,
                            null);
                    continue;
                }
                if ("COMMANDER_MOVE".equals(parked.kind) && parked.actor.equals("p1")) {
                    Opt yes = null;
                    for (Opt o : parked.opts) {
                        if (o.label != null && o.label.contains("Yes")) {
                            yes = o;
                        }
                    }
                    Assert.assertNotNull(yes, "command-zone confirm must be offered");
                    JsonObject done = submit(pipe, game, parked.actor, yes.id, yes.type,
                            parked.revision, null);
                    Assert.assertTrue(done.get("payload").getAsJsonObject()
                            .getAsJsonObject("decision").get("executed").getAsBoolean(),
                            "commander move must execute: " + done);
                    continue;
                }
                autoAnswer(pipe, game, parked, "p2", "commander move");
            }
            Assert.assertTrue(moved, "Isamaru never reached the command zone over the pipe");
            // Taxed recast on p1's next own turn: {W} plus {2} for one prior cast.
            final JsonObject beforeRecast = state(pipe, game, "p1");
            final int turnNow = beforeRecast.get("turn_number").getAsInt();
            int recastTurn = turnNow + 1;
            while ((recastTurn - 1) % 4 != 0) {
                recastTurn++;
            }
            driveTo(pipe, game, "p1", "PRIORITY", "MAIN", recastTurn, 400);
            tapSource(pipe, game, "p1", "Plains");
            tapSource(pipe, game, "p1", "Plains");
            tapSource(pipe, game, "p1", "Plains");
            final Frame recastFrame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt recast = find(recastFrame,
                    o -> "cast_spell".equals(o.type)
                            && "Isamaru, Hound of Konda".equals(o.source),
                    "Isamaru recast with tax");
            JsonObject recastDone = submit(pipe, game, recastFrame.actor, recast.id,
                    recast.type, recastFrame.revision, null);
            Assert.assertTrue(recastDone.get("payload").getAsJsonObject()
                    .getAsJsonObject("decision").get("executed").getAsBoolean(),
                    "taxed recast must execute: " + recastDone);
            boolean recastOut = false;
            for (int i = 0; i < 30 && !recastOut; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "battlefield")
                        .contains("Isamaru, Hound of Konda")) {
                    recastOut = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                autoAnswer(pipe, game, parked, "p1", "recast settle");
            }
            Assert.assertTrue(recastOut, "taxed Isamaru never re-entered over the pipe");
            final JsonObject after = state(pipe, game, "p1");
            Assert.assertEquals(pool(after, "p1", "W"), 0, "taxed cost must spend floated W");
            Assert.assertTrue(playerState(after, "p1").getAsJsonObject("commander_cast_count")
                    .has("Isamaru, Hound of Konda"));
            Assert.assertEquals(playerState(after, "p1").getAsJsonObject("commander_cast_count")
                    .get("Isamaru, Hound of Konda").getAsInt(), 2,
                    "pipe commander cast count must record first cast plus taxed recast");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    // ---- PIPE_HIDDEN: Faithless Looting effect discard over the pipe ----

    @Test(timeOut = 900000)
    public void testPipeHiddenZoneSelection() throws Exception {
        final Pipe pipe = boot();
        final String game = "ws216pipe-hidden";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Mountain", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Faithless Looting");
            hand.add("Plains");
            hand.add("Island");
            hand.add("Swamp");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 2210L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "p1", "Mountain");
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Faithless Looting".equals(o.source),
                    "Looting cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            boolean hidden = false;
            for (int i = 0; i < 20; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("HIDDEN_ZONE_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    Assert.assertFalse(parked.opts.isEmpty());
                    // Wrong actor cannot read: p2 sees no options.
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                    for (Opt o : parked.opts) {
                        Assert.assertFalse(outsider.toString().contains(o.id),
                                "outsider must not see hidden option identity");
                    }
                    // Wrong-actor submit fails closed over the pipe.
                    final JsonObject wrong = pipe.request("{\"protocol_version\":\"2.0.0\","
                            + "\"request_id\":\"" + pipe.nextId("wrong") + "\","
                            + "\"message_type\":\"submit_action\",\"game_id\":\"" + game + "\","
                            + "\"payload\":{\"revision\":" + parked.revision
                            + ",\"proposal\":{\"proposal_id\":\"" + pipe.nextId("p")
                            + "\",\"actor_id\":\"p2\",\"legal_action_id\":\""
                            + parked.opts.get(0).id + "\",\"action_type\":\""
                            + parked.opts.get(0).type + "\"}}}", 60000);
                    assertErrorCode(wrong, "wrong_actor");
                    submit(pipe, game, parked.actor, parked.opts.get(0).id,
                            parked.opts.get(0).type, parked.revision, null);
                    hidden = true;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "hidden discard");
            }
            Assert.assertTrue(hidden, "HIDDEN_ZONE_SELECTION never parked over the pipe");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }
}
