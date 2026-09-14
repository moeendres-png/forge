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
 * WS202 separate-process actual-card evidence: every family is driven through
 * a real child bridge JVM over Protocol-2.0.0 JSONL, using scenario injection
 * for deterministic actual-card boards plus fixed seeds. The parent only
 * transports pilot selections among offered options; legality, costs, combat,
 * triggers and randomness stay in the child engine throughout.
 *
 * <p>Each test boots its own child (isolation), imports the four fixture
 * decks, creates one scenario game, resolves keeps, chooses p1 to start, and
 * drives exactly one family to native execution proof read back over the pipe
 * (life totals, zone names, seed echo). Anything the pipe cannot represent
 * stays UNKNOWN in the successor matrix; nothing here weakens assertions.
 */
public class WS202SeparateProcessTest {
    private static final String ENGINE_SHA = "aa5c00aa32dfd40e213f223f8fd400c43daabb24";

    private static final String[] SEATS = { "p1", "p2", "p3", "p4" };

    // ---- child process harness ----

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
            }, "ws202pipe-stdout");
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
            }, "ws202pipe-stderr");
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

    // ---- wire helpers ----

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

    /** Polls all four seats; returns the currently parked frame, or null. */
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

    // ---- game establishment ----

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
        // Unscripted seats hold empty hands: no natural draws accumulate into
        // cleanup discards, so every parked frame belongs to the family drive.
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

    /** Resolves keeps for all seats and chooses p1 to start; ends at first priority. */
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

    // ---- generic drivers ----

    /**
     * Passes frames until the parked frame matches actor/kind (and, when
     * non-negative, a step-name fragment and minimum turn). Answers pool ties
     * with the first offered pool option (explicit pilot tie-break), passes
     * SUPPORTED priorities, declines bystander combat declarations, answers
     * green-first choice mana. Fails loudly on anything else.
     */
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

    /** Passes everything until the observer reads the expected life total. */
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

    // ---- pipe family games ----

    @Test(timeOut = 900000)
    public void testPipeCharmModesTargets() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-charm";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Mountain", "p1", "p1", false));
            battlefield.add(placement("Plains", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Boros Charm");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 1101L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "p1", "Mountain");
            tapSource(pipe, game, "p1", "Plains");
            Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Boros Charm".equals(o.source),
                    "Charm cast");
            JsonObject casted = submit(pipe, game, frame.actor, cast.id, cast.type,
                    frame.revision, null);
            Assert.assertTrue(casted.get("payload").getAsJsonObject().getAsJsonObject("decision")
                    .get("executed").getAsBoolean(), "cast must execute: " + casted);
            Frame modeFrame = null;
            for (int i = 0; i < 20; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("MODE_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    modeFrame = parked;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "modes");
            }
            Assert.assertNotNull(modeFrame, "MODE_SELECTION never parked over the pipe");
            Assert.assertTrue(modeFrame.opts.size() >= 2, "a real modal choice must be offered");
            final Opt damage = find(modeFrame, o -> o.label != null
                    && o.label.contains("4 damage"), "damage mode");
            submit(pipe, game, modeFrame.actor, damage.id, damage.type, modeFrame.revision,
                    null);
            Frame targetFrame = null;
            for (int i = 0; i < 20; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("TARGET_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    targetFrame = parked;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "charm target");
            }
            Assert.assertNotNull(targetFrame, "Charm target never parked over the pipe");
            final Opt toP2 = find(targetFrame,
                    o -> o.label != null && o.label.contains("p2"), "Charm -> p2");
            submit(pipe, game, targetFrame.actor, toP2.id, toP2.type, targetFrame.revision,
                    null);
            resolveToLife(pipe, game, "p1", "p2", 36, 60);
            Assert.assertEquals(life(state(pipe, game, "p1"), "p2"), 36);
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeFireballX() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-fireball";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            for (int i = 0; i < 7; i++) {
                battlefield.add(placement("Mountain", "p1", "p1", false));
            }
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Fireball");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 1102L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            for (int i = 0; i < 7; i++) {
                tapSource(pipe, game, "p1", "Mountain");
            }
            Assert.assertEquals(pool(state(pipe, game, "p1"), "p1", "R"), 7);
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Fireball".equals(o.source),
                    "Fireball cast");
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
            Assert.assertTrue(xFrame.freeInput, "unbounded X must park validated free input");
            Assert.assertEquals(xFrame.opts.size(), 1);
            // Free-input negative over the pipe: out-of-range values fail closed.
            final JsonObject rejected = pipe.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + pipe.nextId("neg") + "\","
                    + "\"message_type\":\"submit_action\",\"game_id\":\"" + game + "\","
                    + "\"payload\":{\"revision\":" + xFrame.revision + ",\"proposal\":{"
                    + "\"proposal_id\":\"" + pipe.nextId("p") + "\",\"actor_id\":\"p1\","
                    + "\"legal_action_id\":\"" + xFrame.opts.get(0).id + "\","
                    + "\"action_type\":\"" + xFrame.opts.get(0).type + "\","
                    + "\"value\":" + (xFrame.inputMax + 1) + "}}}", 60000);
            assertErrorCode(rejected, BridgeErrors.UNKNOWN_OPTION);
            submit(pipe, game, "p1", xFrame.opts.get(0).id, xFrame.opts.get(0).type,
                    xFrame.revision, 5L);
            Frame targetFrame = null;
            for (int i = 0; i < 20; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("TARGET_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    targetFrame = parked;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "fireball targets");
            }
            Assert.assertNotNull(targetFrame, "Fireball targets never parked over the pipe");
            final Opt both = find(targetFrame,
                    o -> o.label != null && o.label.contains("p2") && o.label.contains("p3"),
                    "Fireball -> p2 + p3");
            submit(pipe, game, targetFrame.actor, both.id, both.type, targetFrame.revision,
                    null);
            resolveToLife(pipe, game, "p1", "p2", 38, 60);
            resolveToLife(pipe, game, "p1", "p3", 38, 60);
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeCounterPitch() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-force";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Forest", "p2", "p2", false));
            battlefield.add(placement("Forest", "p2", "p2", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand1 = new JsonArray();
            hand1.add("Force of Will");
            hand1.add("Turn to Frog");
            hand1.add("Ponder");
            hands.add("p1", hand1);
            final JsonArray hand2 = new JsonArray();
            hand2.add("Llanowar Elves");
            hand2.add("Fog");
            hands.add("p2", hand2);
            createScenarioGame(pipe, game, 1103L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            // Turn 1 passes; turn 2 p2 casts Elves then Fog.
            driveTo(pipe, game, "p2", "PRIORITY", "MAIN", 2, 200);
            tapSource(pipe, game, "p2", "Forest");
            Frame elvesFrame = driveTo(pipe, game, "p2", "PRIORITY", null, -1, 40);
            final Opt elves = find(elvesFrame,
                    o -> "cast_spell".equals(o.type) && "Llanowar Elves".equals(o.source),
                    "Elves cast");
            submit(pipe, game, elvesFrame.actor, elves.id, elves.type, elvesFrame.revision,
                    null);
            tapSource(pipe, game, "p2", "Forest");
            final Frame fogFrame = driveTo(pipe, game, "p2", "PRIORITY", null, -1, 40);
            final Opt fog = find(fogFrame,
                    o -> "cast_spell".equals(o.type) && "Fog".equals(o.source), "Fog cast");
            submit(pipe, game, fogFrame.actor, fog.id, fog.type, fogFrame.revision, null);
            // P1 answers with the pitch route (cost-text labels cross the pipe).
            final Frame forceFrame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 80);
            int routes = 0;
            final List<String> routeLabels = new ArrayList<>();
            for (Opt o : forceFrame.opts) {
                if ("cast_spell".equals(o.type) && "Force of Will".equals(o.source)) {
                    routes++;
                    routeLabels.add(o.label);
                }
            }
            Assert.assertEquals(routes, 2, "hard cast and pitch must both be offered");
            Assert.assertNotEquals(routeLabels.get(0), routeLabels.get(1),
                    "cost-text labels must discriminate the two routes");
            final Opt pitch = find(forceFrame,
                    o -> "cast_spell".equals(o.type) && "Force of Will".equals(o.source)
                            && o.label != null && o.label.toLowerCase().contains("life"),
                    "Force pitch cast");
            submit(pipe, game, forceFrame.actor, pitch.id, pitch.type, forceFrame.revision,
                    null);
            Frame targetFrame = null;
            boolean exiled = false;
            for (int i = 0; i < 40; i++) {
                if (targetFrame != null && exiled) {
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("TARGET_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    targetFrame = parked;
                    Assert.assertEquals(parked.opts.size(), 2, "Elves and Fog must be offered");
                    final Opt toElves = find(parked,
                            o -> o.label != null && o.label.contains("Llanowar Elves"),
                            "Force -> Elves spell");
                    submit(pipe, game, parked.actor, toElves.id, toElves.type, parked.revision,
                            null);
                    continue;
                }
                if ("COST_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    boolean confirmOnly = !parked.opts.isEmpty();
                    for (Opt o : parked.opts) {
                        if (o.label == null || !(o.label.endsWith("[Yes]")
                                || o.label.endsWith("[No]"))) {
                            confirmOnly = false;
                        }
                    }
                    if (confirmOnly) {
                        final Opt yes = find(parked,
                                o -> o.label != null && o.label.endsWith("[Yes]"),
                                "pay life");
                        submit(pipe, game, parked.actor, yes.id, yes.type, parked.revision,
                                null);
                        continue;
                    }
                    final Opt frog = find(parked,
                            o -> o.label != null && o.label.contains("Turn to Frog"),
                            "pitch Frog");
                    JsonObject paid = submit(pipe, game, parked.actor, frog.id, frog.type,
                            parked.revision, null);
                    Assert.assertTrue(paid.get("payload").getAsJsonObject()
                            .getAsJsonObject("decision").get("executed").getAsBoolean(),
                            "pitch payment must execute: " + paid);
                    exiled = true;
                    continue;
                }
                autoAnswer(pipe, game, parked, "p1", "force flow");
            }
            Assert.assertNotNull(targetFrame, "Force never asked for its stack target");
            Assert.assertTrue(exiled, "pitch exile never executed");
            // Resolve out by condition (all lives stay 40 here): pass priority
            // until the countered Elves reaches the graveyard.
            boolean buried = false;
            for (int i = 0; i < 60 && !buried; i++) {
                if (zone(state(pipe, game, "p1"), "p2", "graveyard")
                        .contains("Llanowar Elves")) {
                    buried = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("PRIORITY".equals(parked.kind) && "SUPPORTED".equals(parked.status)) {
                    pass(pipe, game, parked);
                } else if ("MANA_PAYMENT".equals(parked.kind)) {
                    final Opt first = parked.opts.get(0);
                    submit(pipe, game, parked.actor, first.id, first.type, parked.revision,
                            null);
                } else {
                    Assert.fail("unexpected " + parked.kind + " while resolving Force");
                }
            }
            Assert.assertTrue(buried, "resolution never buried the Elves");
            final JsonObject end = state(pipe, game, "p1");
            boolean elvesBuried = false;
            for (String name : zone(end, "p2", "graveyard")) {
                if ("Llanowar Elves".equals(name)) {
                    elvesBuried = true;
                }
            }
            Assert.assertTrue(elvesBuried, "Elves spell must be countered to graveyard");
            boolean frogExiled = false;
            for (String name : zone(end, "p1", "exile")) {
                if ("Turn to Frog".equals(name)) {
                    frogExiled = true;
                }
            }
            Assert.assertTrue(frogExiled, "pitched Frog must be exiled");
            Assert.assertEquals(life(end, "p1"), 39);
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeTriggerOrder() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-b01";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Soul Warden", "p1", "p1", false));
            battlefield.add(placement("Soul Warden", "p1", "p1", false));
            battlefield.add(placement("Soul Warden", "p2", "p2", false));
            battlefield.add(placement("Soul Warden", "p3", "p3", false));
            battlefield.add(placement("Soul Warden", "p4", "p4", false));
            battlefield.add(placement("Forest", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Llanowar Elves");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 1104L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "p1", "Forest");
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Llanowar Elves".equals(o.source),
                    "Elves cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            Frame orderFrame = null;
            for (int i = 0; i < 20; i++) {
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("TRIGGER_ORDER".equals(parked.kind) && parked.actor.equals("p1")) {
                    orderFrame = parked;
                    break;
                }
                autoAnswer(pipe, game, parked, "p1", "trigger order");
            }
            Assert.assertNotNull(orderFrame, "trigger order never parked over the pipe");
            Assert.assertEquals(orderFrame.opts.size(), 2);
            final Opt ordered = find(orderFrame,
                    o -> o.label != null && o.label.indexOf("#0;") >= 0
                            && o.label.indexOf("#1;") >= 0
                            && o.label.indexOf("#0;") < o.label.indexOf("#1;"),
                    "Warden-A then Warden-B");
            submit(pipe, game, orderFrame.actor, ordered.id, ordered.type,
                    orderFrame.revision, null);
            resolveToLife(pipe, game, "p1", "p1", 42, 60);
            final JsonObject end = state(pipe, game, "p1");
            Assert.assertEquals(life(end, "p1"), 42);
            Assert.assertEquals(life(end, "p2"), 41);
            Assert.assertEquals(life(end, "p3"), 41);
            Assert.assertEquals(life(end, "p4"), 41);
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeCombatDamage() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-e02";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Carnage Tyrant", "p2", "p2", false));
            battlefield.add(placement("Runeclaw Bear", "p1", "p1", false));
            battlefield.add(placement("Llanowar Elves", "p1", "p1", false));
            createScenarioGame(pipe, game, 1105L, handles, battlefield, new JsonObject());
            keepsAndStarter(pipe, game);
            final Frame attackFrame = driveTo(pipe, game, "p2", "COMBAT_DECLARE_ATTACKERS",
                    "DECLARE_ATTACKERS", -1, 200);
            final Opt attack = find(attackFrame,
                    o -> o.label != null && o.label.contains("Carnage Tyrant")
                            && o.label.contains("-> p1"),
                    "Tyrant attacks p1");
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
            resolveToLife(pipe, game, "p1", "p1", 36, 60);
            final JsonObject end = state(pipe, game, "p1");
            Assert.assertEquals(life(end, "p1"), 36);
            Assert.assertFalse(zone(end, "p1", "battlefield").contains("Runeclaw Bear"));
            Assert.assertFalse(zone(end, "p1", "battlefield").contains("Llanowar Elves"));
            Assert.assertTrue(zone(end, "p2", "battlefield").contains("Carnage Tyrant"));
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeCommanderMoveConcede() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-g02";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Isamaru, Hound of Konda", "p1", "p1", false));
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
            createScenarioGame(pipe, game, 1106L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            // P2 Murders on its own turn; the SBA commander choice is answered
            // over the pipe (forced lone target needs no frame).
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
                    Assert.assertEquals(parked.actor, "p1");
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
            // Concession crosses the pipe as an ordinary framed option.
            final Frame concedeFrame = driveTo(pipe, game, "p3", "PRIORITY", null, -1, 120);
            final Opt concede = find(concedeFrame, o -> "concede".equals(o.type), "concede");
            JsonObject left = submit(pipe, game, concedeFrame.actor, concede.id, concede.type,
                    concedeFrame.revision, null);
            Assert.assertTrue(left.get("payload").getAsJsonObject().getAsJsonObject("decision")
                    .get("executed").getAsBoolean(), "concede must execute: " + left);
            boolean lost = false;
            for (int i = 0; i < 20 && !lost; i++) {
                if (playerState(state(pipe, game, "p3"), "p3").get("has_lost").getAsBoolean()) {
                    lost = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                autoAnswer(pipe, game, parked, "p3", "post-concede");
            }
            Assert.assertTrue(lost, "p3 must have lost by concession");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeCloneEntry() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-clone";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Runeclaw Bear", "p2", "p2", false));
            battlefield.add(placement("Memnite", "p2", "p2", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Clone");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 1107L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            for (int i = 0; i < 4; i++) {
                tapSource(pipe, game, "p1", "Island");
            }
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Clone".equals(o.source), "Clone cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            boolean copied = false;
            for (int i = 0; i < 40; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "battlefield")
                        .contains("Runeclaw Bear")) {
                    copied = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if (("REPLACEMENT_CONFIRM".equals(parked.kind)
                        || "COPY_CHOICE".equals(parked.kind)) && parked.actor.equals("p1")) {
                    Opt bear = null;
                    for (Opt o : parked.opts) {
                        if (o.label != null && o.label.contains("Runeclaw Bear")) {
                            bear = o;
                        }
                    }
                    if (bear == null) {
                        for (Opt o : parked.opts) {
                            if (o.label != null && o.label.toLowerCase().contains("yes")) {
                                bear = o;
                            }
                        }
                    }
                    Assert.assertNotNull(bear, "Bear copy must be offered: " + parked.kind);
                    submit(pipe, game, parked.actor, bear.id, bear.type, parked.revision,
                            null);
                    continue;
                }
                autoAnswer(pipe, game, parked, "p1", "clone entry");
            }
            Assert.assertTrue(copied, "Bear copy never entered over the pipe");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 1200000)
    public void testPipeHumilityLayers() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-humility";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Runeclaw Bear", "p2", "p2", false));
            battlefield.add(placement("Memnite", "p2", "p2", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Island", "p1", "p1", false));
            battlefield.add(placement("Plains", "p3", "p3", false));
            battlefield.add(placement("Plains", "p3", "p3", false));
            battlefield.add(placement("Plains", "p3", "p3", false));
            battlefield.add(placement("Plains", "p3", "p3", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand1 = new JsonArray();
            hand1.add("Clone");
            hands.add("p1", hand1);
            final JsonArray hand3 = new JsonArray();
            hand3.add("Humility");
            hands.add("p3", hand3);
            createScenarioGame(pipe, game, 1108L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            // Turn 1: Clone copies the Bear.
            for (int i = 0; i < 4; i++) {
                tapSource(pipe, game, "p1", "Island");
            }
            Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Clone".equals(o.source), "Clone cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            boolean copied = false;
            for (int i = 0; i < 40 && !copied; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "battlefield")
                        .contains("Runeclaw Bear")) {
                    copied = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if (("REPLACEMENT_CONFIRM".equals(parked.kind)
                        || "COPY_CHOICE".equals(parked.kind)) && parked.actor.equals("p1")) {
                    Opt bear = null;
                    for (Opt o : parked.opts) {
                        if (o.label != null && o.label.contains("Runeclaw Bear")) {
                            bear = o;
                        }
                    }
                    if (bear == null) {
                        for (Opt o : parked.opts) {
                            if (o.label != null && o.label.toLowerCase().contains("yes")) {
                                bear = o;
                            }
                        }
                    }
                    Assert.assertNotNull(bear, "Bear copy must be offered");
                    submit(pipe, game, parked.actor, bear.id, bear.type, parked.revision,
                            null);
                    continue;
                }
                autoAnswer(pipe, game, parked, "p1", "clone entry");
            }
            Assert.assertTrue(copied, "Bear copy never entered before Humility");
            // Turn 3: p3 casts Humility; the copy keeps its Bear identity.
            driveTo(pipe, game, "p3", "PRIORITY", "MAIN", 3, 400);
            for (int i = 0; i < 4; i++) {
                tapSource(pipe, game, "p3", "Plains");
            }
            final Frame humilityFrame = driveTo(pipe, game, "p3", "PRIORITY", null, -1, 40);
            final Opt humility = find(humilityFrame,
                    o -> "cast_spell".equals(o.type) && "Humility".equals(o.source),
                    "Humility cast");
            JsonObject humbled = submit(pipe, game, humilityFrame.actor, humility.id,
                    humility.type, humilityFrame.revision, null);
            Assert.assertTrue(humbled.get("payload").getAsJsonObject()
                    .getAsJsonObject("decision").get("executed").getAsBoolean(),
                    "Humility cast must execute: " + humbled);
            boolean humilityOut = false;
            for (int i = 0; i < 60 && !humilityOut; i++) {
                if (zone(state(pipe, game, "p3"), "p3", "battlefield").contains("Humility")
                        && zone(state(pipe, game, "p1"), "p1", "battlefield")
                                .contains("Runeclaw Bear")) {
                    humilityOut = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                autoAnswer(pipe, game, parked, "p3", "humility resolve");
            }
            Assert.assertTrue(humilityOut, "Bear copy identity lost under Humility over pipe");
            // Humility leaves via its controller's concession: the copy endures.
            final Frame concedeFrame = driveTo(pipe, game, "p3", "PRIORITY", null, -1, 120);
            final Opt concede = find(concedeFrame, o -> "concede".equals(o.type), "concede");
            submit(pipe, game, concedeFrame.actor, concede.id, concede.type,
                    concedeFrame.revision, null);
            boolean endured = false;
            for (int i = 0; i < 40 && !endured; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "battlefield")
                        .contains("Runeclaw Bear")) {
                    endured = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                autoAnswer(pipe, game, parked, "p3", "post-humility");
            }
            Assert.assertTrue(endured, "Bear copy did not endure Humility leaving");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeDiscardHidden() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-discard";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Mountain", "p1", "p1", false));
            battlefield.add(placement("Mountain", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Tormenting Voice");
            hand.add("Plains");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 1109L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
            tapSource(pipe, game, "p1", "Mountain");
            tapSource(pipe, game, "p1", "Mountain");
            final Frame frame = driveTo(pipe, game, "p1", "PRIORITY", null, -1, 40);
            final Opt cast = find(frame,
                    o -> "cast_spell".equals(o.type) && "Tormenting Voice".equals(o.source),
                    "Voice cast");
            submit(pipe, game, frame.actor, cast.id, cast.type, frame.revision, null);
            boolean discarded = false;
            for (int i = 0; i < 20; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "graveyard").contains("Plains")) {
                    discarded = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("COST_SELECTION".equals(parked.kind) && parked.actor.equals("p1")) {
                    final Opt plains = find(parked,
                            o -> o.label != null && o.label.contains("Plains"),
                            "discard Plains");
                    submit(pipe, game, parked.actor, plains.id, plains.type, parked.revision,
                            null);
                    continue;
                }
                autoAnswer(pipe, game, parked, "p1", "discard cost");
            }
            Assert.assertTrue(discarded, "Plains never reached graveyard as cost over pipe");
            // Principal scoping over the pipe: the foe's projection hides the hand.
            final JsonObject foe = state(pipe, game, "p2");
            final StringBuilder foeHand = new StringBuilder();
            for (JsonElement element : playerState(foe, "p1").getAsJsonObject("zones")
                    .getAsJsonArray("hand")) {
                final String shown = element.getAsString();
                Assert.assertEquals(shown, "<hidden>", "foe must not see p1 hand names");
                foeHand.append(shown).append(';');
            }
            Assert.assertTrue(foeHand.length() > 0, "p1 must still hold cards");
            Assert.assertFalse(foeHand.toString().contains("Plains"));
            Assert.assertFalse(foeHand.toString().contains("Tormenting Voice"));
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeRampantSearch() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-f01";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Forest", "p1", "p1", false));
            battlefield.add(placement("Mountain", "p1", "p1", false));
            final JsonObject hands = new JsonObject();
            final JsonArray hand = new JsonArray();
            hand.add("Rampant Growth");
            hands.add("p1", hand);
            createScenarioGame(pipe, game, 1110L, handles, battlefield, hands);
            keepsAndStarter(pipe, game);
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
                    Assert.assertFalse(parked.opts.isEmpty(), "search must offer options");
                    final Opt plains = find(parked,
                            o -> o.label != null && o.label.contains("Plains"), "find Plains");
                    submit(pipe, game, parked.actor, plains.id, plains.type, parked.revision,
                            null);
                    continue;
                }
                // Search offers name hidden-zone cards to the entitled searcher
                // only: while p1 owns the parked frame, an outsider sees no
                // options and no names.
                if (parked.actor.equals("p1")) {
                    final JsonObject outsider = state(pipe, game, "p2");
                    Assert.assertEquals(outsider.getAsJsonArray("legal_actions").size(), 0);
                }
                autoAnswer(pipe, game, parked, "p1", "search");
            }
            Assert.assertTrue(searched, "searched Plains never arrived over the pipe");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeSerpentReplacement() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-a04";
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
            createScenarioGame(pipe, game, 1111L, handles, battlefield, hands);
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
            Assert.assertEquals(orderFrame.opts.size(), 2, "exactly two legal orders");
            final Opt scalesFirst = find(orderFrame,
                    o -> o.label != null && o.label.contains("plus one"), "Scales first");
            submit(pipe, game, orderFrame.actor, scalesFirst.id, scalesFirst.type,
                    orderFrame.revision, null);
            boolean entered = false;
            for (int i = 0; i < 40 && !entered; i++) {
                if (zone(state(pipe, game, "p1"), "p1", "battlefield")
                        .contains("Stonecoil Serpent")) {
                    entered = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                autoAnswer(pipe, game, parked, "p1", "serpent entry");
            }
            Assert.assertTrue(entered, "Serpent never entered over the pipe");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipePropagandaTax() throws Exception {
        final Pipe pipe = boot();
        final String game = "wspipe-e01";
        try {
            final List<String> handles = importPod(pipe);
            final JsonArray battlefield = new JsonArray();
            battlefield.add(placement("Propaganda", "p1", "p1", false));
            battlefield.add(placement("Runeclaw Bear", "p2", "p2", false));
            battlefield.add(placement("Runeclaw Bear", "p2", "p2", false));
            battlefield.add(placement("Island", "p2", "p2", false));
            battlefield.add(placement("Island", "p2", "p2", false));
            createScenarioGame(pipe, game, 1112L, handles, battlefield, new JsonObject());
            keepsAndStarter(pipe, game);
            final Frame attackFrame = driveTo(pipe, game, "p2", "COMBAT_DECLARE_ATTACKERS",
                    "DECLARE_ATTACKERS", -1, 200);
            final Opt split = find(attackFrame,
                    o -> o.label != null && o.label.contains("-> p1")
                            && o.label.contains("-> p3"),
                    "Bear-A -> p1, Bear-B -> p3");
            submit(pipe, game, attackFrame.actor, split.id, split.type, attackFrame.revision,
                    null);
            boolean taxed = false;
            for (int i = 0; i < 80; i++) {
                final JsonObject st = state(pipe, game, "p2");
                if (life(st, "p1") == 38 && life(st, "p3") == 38) {
                    taxed = true;
                    break;
                }
                final Frame parked = await(pipe, game, 60000);
                Assert.assertNotNull(parked);
                if ("PRIORITY".equals(parked.kind) && "SUPPORTED".equals(parked.status)) {
                    pass(pipe, game, parked);
                } else if ("MANA_PAYMENT".equals(parked.kind)) {
                    Opt payment = null;
                    for (Opt o : parked.opts) {
                        if (o.label != null && o.label.contains("Island")) {
                            payment = o;
                            break;
                        }
                    }
                    if (payment == null) {
                        payment = parked.opts.get(0);
                    }
                    submit(pipe, game, parked.actor, payment.id, payment.type, parked.revision,
                            null);
                } else if ("COMBAT_DECLARE_BLOCKERS".equals(parked.kind)) {
                    final Opt noBlocks = find(parked,
                            o -> o.label != null && o.label.contains("No blocks"), "no blocks");
                    submit(pipe, game, parked.actor, noBlocks.id, noBlocks.type,
                            parked.revision, null);
                } else {
                    Assert.fail("unexpected " + parked.kind + " in pipe Propaganda flow");
                }
            }
            Assert.assertTrue(taxed, "Propaganda-taxed attack never resolved over the pipe");
            shutdown(pipe, game);
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    @Test(timeOut = 900000)
    public void testPipeSeedTwins() throws Exception {
        final Pipe pipe = boot();
        try {
            final List<String> handles = importPod(pipe);
            final String first = pipeTwinOpening(pipe, handles, "wspipe-twin-a", 424242L);
            final String second = pipeTwinOpening(pipe, handles, "wspipe-twin-b", 424242L);
            Assert.assertEquals(second, first, "same-seed pipe twins must deal the same opening");
            final String third = pipeTwinOpening(pipe, handles, "wspipe-twin-c", 777L);
            Assert.assertFalse(third.isEmpty());
            Assert.assertNotEquals(third, first, "distinct seeds must not deal identical openings");
        } finally {
            try {
                pipe.close();
            } catch (Exception e) {
                pipe.process.destroyForcibly();
            }
        }
    }

    private static String pipeTwinOpening(Pipe pipe, List<String> handles, String game, long seed)
            throws Exception {
        final StringBuilder deckArray = new StringBuilder();
        for (int i = 0; i < handles.size(); i++) {
            if (i > 0) {
                deckArray.append(',');
            }
            deckArray.append('"').append(handles.get(i)).append('"');
        }
        final JsonObject created = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("create") + "\","
                + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                + "\"game_id\":\"" + game + "\",\"format\":\"commander\","
                + "\"seed\":" + seed + ",\"deck_handles\":[" + deckArray + "]}}}", 60000);
        assertOk(created);
        final JsonObject started = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("start") + "\","
                + "\"message_type\":\"start_game\",\"game_id\":\"" + game + "\"}", 60000);
        assertOk(started);
        keepsAndStarter(pipe, game);
        final JsonObject st = state(pipe, game, "p1");
        Assert.assertEquals(st.get("seed").getAsLong(), seed);
        final List<String> names = new ArrayList<>();
        for (JsonElement element : playerState(st, "p1").getAsJsonObject("zones")
                .getAsJsonArray("hand")) {
            names.add(element.getAsString());
        }
        names.sort(String::compareTo);
        final StringBuilder hand = new StringBuilder();
        for (String name : names) {
            hand.append(name).append(';');
        }
        final JsonObject down = pipe.request("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"" + pipe.nextId("down") + "\","
                + "\"message_type\":\"shutdown_game\",\"game_id\":\"" + game + "\"}", 60000);
        assertOk(down);
        return hand.toString();
    }
}
