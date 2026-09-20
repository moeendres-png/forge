package forge.bridge;

import com.google.gson.JsonArray;
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

/**
 * WS233 fresh-process cardinality matrix: one real OS-process bridge JVM per
 * supported count (2/3/4/5) over BridgeMain JSONL. Each child proves import,
 * creation with exact player_count/seats, start, Core-owned starting-player
 * choice over the full N-seat domain, keeps, first priority, an advancing
 * pass, N-player state projection and clean shutdown. No JVM is reused across
 * counts; process isolation is real.
 */
public class WS233CardinalityProcessTest {
    private static final String ENGINE_SHA = "c4d67145a6f9902e031a11dde5c33c60f51ed08d";

    private static final class Child {
        final Process process;
        final BufferedWriter stdin;
        final BlockingQueue<String> stdout = new ArrayBlockingQueue<>(1024);
        final List<String> stdoutLines = new ArrayList<>();

        Child() throws Exception {
            final String javaHome = System.getProperty("java.home");
            final String javaBin = javaHome + "/bin/java";
            final String classpath = System.getProperty("java.class.path");
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
            final BufferedReader err = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8));
            final Thread outPump = new Thread(() -> {
                try {
                    String line;
                    while ((line = out.readLine()) != null) {
                        stdout.offer(line);
                        synchronized (stdoutLines) {
                            stdoutLines.add(line);
                        }
                    }
                } catch (Exception e) {
                    // Pump ends with the process.
                }
            }, "ws233-stdout");
            outPump.setDaemon(true);
            outPump.start();
            final Thread errPump = new Thread(() -> {
                try {
                    while (err.readLine() != null) {
                        // Chatter stays on stderr by protocol design.
                    }
                } catch (Exception e) {
                    // Pump ends with the process.
                }
            }, "ws233-stderr");
            errPump.setDaemon(true);
            errPump.start();
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
                        Math.max(100, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                final JsonObject response;
                try {
                    response = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception e) {
                    Assert.fail("stdout line is not valid JSON: " + line);
                    return null;
                }
                Assert.assertEquals(response.get("protocol_version").getAsString(), "2.0.0");
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

    private static void assertOk(JsonObject response) {
        Assert.assertTrue(response.get("success").getAsBoolean(), "expected ok: " + response);
    }

    @Test(timeOut = 900000)
    public void testFreshProcessTwoPlayers() throws Exception {
        qualifyFreshProcess(2);
    }

    @Test(timeOut = 900000)
    public void testFreshProcessThreePlayers() throws Exception {
        qualifyFreshProcess(3);
    }

    @Test(timeOut = 900000)
    public void testFreshProcessFourPlayers() throws Exception {
        qualifyFreshProcess(4);
    }

    @Test(timeOut = 900000)
    public void testFreshProcessFivePlayers() throws Exception {
        qualifyFreshProcess(5);
    }

    // R16: sixth seat via the deck-targeted fixture (six distinct handles).
    @Test(timeOut = 900000)
    public void testFreshProcessSixPlayers() throws Exception {
        qualifyFreshProcess(6);
    }

    private static void qualifyFreshProcess(int playerCount) throws Exception {
        final String tag = "ws233p" + playerCount;
        final String lastSeat = "p" + playerCount;
        final Child child = new Child();
        try {
            final JsonObject hello = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-start\",\"message_type\":\"start_engine\"}",
                    240000);
            assertOk(hello);

            final JsonObject version = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-version\","
                    + "\"message_type\":\"get_provider_version\"}", 60000);
            assertOk(version);
            Assert.assertEquals(version.get("payload").getAsJsonObject()
                    .get("engine_commit").getAsString(), ENGINE_SHA);

            final JsonObject caps = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-caps\",\"message_type\":\"get_capabilities\"}",
                    60000);
            assertOk(caps);
            final JsonObject capabilities = caps.get("payload").getAsJsonObject()
                    .getAsJsonObject("capabilities");
            Assert.assertEquals(capabilities.get("max_players").getAsInt(), 6);
            Assert.assertEquals(capabilities.get("min_players").getAsInt(), 2);

            final String[] pods = { "deck1.json", "deck2.json", "deck3.json", "deck4.json",
                    "deck5.json", "deck-targeted.json" };
            final List<String> handles = new ArrayList<>(playerCount);
            for (int i = 0; i < playerCount; i++) {
                final String deck = BridgeTestSupport.deckResource(pods[i]);
                final JsonObject imported = child.request("{\"protocol_version\":\"2.0.0\","
                        + "\"request_id\":\"" + tag + "-import-" + i + "\","
                        + "\"message_type\":\"import_deck\","
                        + "\"payload\":{\"deck\":" + deck + "}}", 60000);
                assertOk(imported);
                handles.add(imported.get("payload").getAsJsonObject()
                        .getAsJsonObject("deck_handle").get("handle_id").getAsString());
            }

            final StringBuilder deckArray = new StringBuilder();
            for (int i = 0; i < handles.size(); i++) {
                if (i > 0) {
                    deckArray.append(',');
                }
                deckArray.append('"').append(handles.get(i)).append('"');
            }
            final JsonObject created = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-create\","
                    + "\"message_type\":\"create_commander_game\","
                    + "\"payload\":{\"request\":{\"game_id\":\"" + tag + "\","
                    + "\"format\":\"commander\","
                    + "\"deck_handles\":[" + deckArray + "]}}}", 60000);
            assertOk(created);
            Assert.assertEquals(created.get("payload").getAsJsonObject().get("player_count")
                    .getAsInt(), playerCount);
            Assert.assertEquals(created.get("payload").getAsJsonObject()
                    .getAsJsonArray("seats").size(), playerCount);

            final JsonObject started = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-start-game\",\"message_type\":\"start_game\","
                    + "\"game_id\":\"" + tag + "\"}", 60000);
            assertOk(started);

            // Drive the real start over the pipe: full-domain starting choice
            // (last seat, never the first), keeps, then first priority.
            final String[] seats = new String[playerCount];
            for (int i = 0; i < playerCount; i++) {
                seats[i] = "p" + (i + 1);
            }
            String actor = null;
            long revision = -1;
            String chosenStarter = null;
            for (int i = 0; i < 90 && actor == null; i++) {
                for (String seat : seats) {
                    final JsonObject actions = child.request("{\"protocol_version\":\"2.0.0\","
                            + "\"request_id\":\"" + tag + "-poll-" + i + "-" + seat + "\","
                            + "\"message_type\":\"get_legal_actions\",\"game_id\":\"" + tag + "\","
                            + "\"payload\":{\"actor_id\":\"" + seat + "\"}}", 60000);
                    if (!actions.get("success").getAsBoolean()) {
                        continue;
                    }
                    final JsonObject decision = actions.get("payload").getAsJsonObject()
                            .getAsJsonObject("decision");
                    if (!decision.has("kind")) {
                        continue;
                    }
                    final String kind = decision.get("kind").getAsString();
                    if (kind.equals("STARTING_PLAYER")) {
                        final String chooser = decision.get("actor").getAsString();
                        final long choiceRev = decision.get("revision").getAsLong();
                        final JsonArray options = actions.get("payload").getAsJsonObject()
                                .getAsJsonArray("actions");
                        Assert.assertEquals(options.size(), playerCount,
                                "starting domain must be the full pod");
                        String choiceId = null;
                        for (int k = 0; k < options.size(); k++) {
                            final JsonObject opt = options.get(k).getAsJsonObject();
                            if (opt.get("source_object_id").getAsString().equals(lastSeat)) {
                                choiceId = opt.get("action_id").getAsString();
                            }
                        }
                        Assert.assertNotNull(choiceId, lastSeat + " must be offered");
                        final JsonObject chose = child.request("{\"protocol_version\":\"2.0.0\","
                                + "\"request_id\":\"" + tag + "-choose\","
                                + "\"message_type\":\"submit_action\","
                                + "\"game_id\":\"" + tag + "\",\"payload\":{\"revision\":"
                                + choiceRev + ",\"proposal\":{\"proposal_id\":\"c1\","
                                + "\"actor_id\":\"" + chooser + "\",\"legal_action_id\":\""
                                + choiceId + "\",\"action_type\":\"structural_decision\"}}}",
                                60000);
                        assertOk(chose);
                        chosenStarter = lastSeat;
                        break;
                    }
                    if (kind.equals("MULLIGAN")) {
                        final String mulliganActor = decision.get("actor").getAsString();
                        final long mulliganRev = decision.get("revision").getAsLong();
                        final JsonObject kept = child.request("{\"protocol_version\":\"2.0.0\","
                                + "\"request_id\":\"" + tag + "-keep-" + i + "\","
                                + "\"message_type\":\"resolve_mulligan\",\"game_id\":\"" + tag
                                + "\",\"payload\":{\"player_id\":\"" + mulliganActor + "\","
                                + "\"revision\":" + mulliganRev + ",\"keep\":true,"
                                + "\"bottom_card_ids\":[]}}", 60000);
                        assertOk(kept);
                        break;
                    }
                    actor = decision.get("actor").getAsString();
                    revision = decision.get("revision").getAsLong();
                }
                if (actor == null) {
                    Thread.sleep(500);
                }
            }
            Assert.assertNotNull(actor, "never reached a priority decision at " + playerCount
                    + "P");
            Assert.assertEquals(chosenStarter, lastSeat);

            // Projection over the pipe: exactly N players, Forge-owned life totals.
            final JsonObject state = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-state\",\"message_type\":\"get_game_state\","
                    + "\"game_id\":\"" + tag + "\",\"payload\":{\"observer_player_id\":\""
                    + actor + "\"}}", 60000);
            assertOk(state);
            final JsonObject gameState = state.get("payload").getAsJsonObject()
                    .getAsJsonObject("state");
            Assert.assertEquals(gameState.getAsJsonArray("players").size(), playerCount);
            for (int i = 0; i < playerCount; i++) {
                Assert.assertEquals(gameState.getAsJsonArray("players").get(i)
                        .getAsJsonObject().get("life").getAsInt(), 40);
            }
            Assert.assertEquals(gameState.get("active_player_id").getAsString(), lastSeat);

            // A real external pass advances state over the pipe.
            final JsonObject pass = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-pass\",\"message_type\":\"pass_priority\","
                    + "\"game_id\":\"" + tag + "\",\"payload\":{\"actor_id\":\"" + actor + "\","
                    + "\"revision\":" + revision + "}}", 60000);
            assertOk(pass);
            Assert.assertNotEquals(pass.get("payload").getAsJsonObject()
                    .getAsJsonObject("decision").get("post_state_hash").getAsString(),
                    pass.get("payload").getAsJsonObject().getAsJsonObject("decision")
                            .get("pre_state_hash").getAsString());

            final JsonObject down = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-down\",\"message_type\":\"shutdown_game\","
                    + "\"game_id\":\"" + tag + "\"}", 60000);
            assertOk(down);
            final JsonObject off = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"" + tag + "-off\",\"message_type\":\"shutdown_engine\"}",
                    30000);
            assertOk(off);
            child.close();
            Assert.assertEquals(child.process.exitValue(), 0);
            synchronized (child.stdoutLines) {
                Assert.assertFalse(child.stdoutLines.isEmpty());
                for (String line : child.stdoutLines) {
                    JsonParser.parseString(line).getAsJsonObject();
                }
            }
        } finally {
            try {
                child.close();
            } catch (Exception e) {
                child.process.destroyForcibly();
            }
        }
    }
}
