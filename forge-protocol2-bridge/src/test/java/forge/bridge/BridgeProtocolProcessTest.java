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
 * Separate-process Protocol-2 qualification: spawns the real bridge JVM over JSONL
 * stdin/stdout and drives handshake, import, creation, start, mulligans, an external
 * pass, negative controls, event export and clean shutdown. Proves protocol purity
 * (stdout parses as JSON on every line) and the real pinned engine behind the pipe.
 */
public class BridgeProtocolProcessTest {
    private static final String ENGINE_SHA = "a37a865a53280dd8ad6fad3384d69611e8c5a42f";

    private static final class Child {
        final Process process;
        final BufferedWriter stdin;
        final BlockingQueue<String> stdout = new ArrayBlockingQueue<>(1024);
        final List<String> stderr = new ArrayList<>();
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
            // Prove no display dependency: the bridge must run without any X server.
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
            }, "bridge-test-stdout");
            outPump.setDaemon(true);
            outPump.start();
            final Thread errPump = new Thread(() -> {
                try {
                    String line;
                    while ((line = err.readLine()) != null) {
                        synchronized (stderr) {
                            stderr.add(line);
                        }
                    }
                } catch (Exception e) {
                    // Pump ends with the process.
                }
            }, "bridge-test-stderr");
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
                    Assert.fail("stdout line is not valid JSON (protocol purity violated): " + line);
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

    private static void assertOk(JsonObject response) {
        Assert.assertTrue(response.get("success").getAsBoolean(), "expected ok: " + response);
    }

    private static void assertErrorCode(JsonObject response, String code) {
        Assert.assertFalse(response.get("success").getAsBoolean(), "expected error: " + response);
        Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                .get("code").getAsString(), code, "response: " + response);
    }

    @Test(timeOut = 900000)
    public void testSeparateProcessQualification() throws Exception {
        final Child child = new Child();
        try {
            final JsonObject hello = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-start\",\"message_type\":\"start_engine\"}", 240000);
            assertOk(hello);

            final JsonObject version = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-version\",\"message_type\":\"get_provider_version\"}", 60000);
            assertOk(version);
            final JsonObject identity = version.get("payload").getAsJsonObject();
            Assert.assertEquals(identity.get("provider").getAsString(), "forge");
            Assert.assertEquals(identity.get("engine_commit").getAsString(), ENGINE_SHA);
            Assert.assertEquals(identity.get("protocol_version").getAsString(), "2.0.0");

            final JsonObject caps = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-caps\",\"message_type\":\"get_capabilities\"}", 60000);
            assertOk(caps);
            Assert.assertFalse(caps.get("payload").getAsJsonObject().getAsJsonObject("capabilities")
                    .get("legal_actions_supported").getAsBoolean());

            final List<String> handles = new ArrayList<>(4);
            for (int i = 1; i <= 4; i++) {
                final String deck = BridgeTestSupport.deckResource("deck" + i + ".json");
                final JsonObject imported = child.request("{\"protocol_version\":\"2.0.0\","
                        + "\"request_id\":\"p-import-" + i + "\",\"message_type\":\"import_deck\","
                        + "\"payload\":{\"deck\":" + deck + "}}", 60000);
                assertOk(imported);
                handles.add(imported.get("payload").getAsJsonObject().getAsJsonObject("deck_handle")
                        .get("handle_id").getAsString());
            }

            final StringBuilder deckArray = new StringBuilder();
            for (int i = 0; i < handles.size(); i++) {
                if (i > 0) {
                    deckArray.append(',');
                }
                deckArray.append('"').append(handles.get(i)).append('"');
            }
            final JsonObject created = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-create\",\"message_type\":\"create_commander_game\","
                    + "\"payload\":{\"request\":{\"game_id\":\"proc-1\",\"format\":\"commander\","
                    + "\"deck_handles\":[" + deckArray + "]}}}", 60000);
            assertOk(created);
            Assert.assertEquals(created.get("payload").getAsJsonObject().get("player_count")
                    .getAsInt(), 4);

            final JsonObject started = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-start-game\",\"message_type\":\"start_game\","
                    + "\"game_id\":\"proc-1\"}", 60000);
            assertOk(started);

            // Drive keeps until the first priority frame.
            String actor = null;
            for (int i = 0; i < 30; i++) {
                final JsonObject actions = child.request("{\"protocol_version\":\"2.0.0\","
                        + "\"request_id\":\"p-poll-" + i + "\",\"message_type\":\"get_legal_actions\","
                        + "\"game_id\":\"proc-1\"}", 60000);
                assertOk(actions);
                final JsonObject decision = actions.get("payload").getAsJsonObject()
                        .getAsJsonObject("decision");
                final String status = decision.get("status").getAsString();
                if (status.equals("no_pending_decision")) {
                    Thread.sleep(1000);
                    continue;
                }
                if (!decision.has("kind")) {
                    Thread.sleep(1000);
                    continue;
                }
                if (decision.get("kind").getAsString().equals("MULLIGAN")) {
                    actor = decision.get("actor").getAsString();
                    System.err.println("[it] keep for " + actor + " rev "
                            + decision.get("revision").getAsLong());
                    final JsonObject kept = child.request("{\"protocol_version\":\"2.0.0\","
                            + "\"request_id\":\"p-keep-" + i + "\","
                            + "\"message_type\":\"resolve_mulligan\",\"game_id\":\"proc-1\","
                            + "\"payload\":{\"player_id\":\"" + actor + "\",\"keep\":true,"
                            + "\"bottom_card_ids\":[]}}", 60000);
                    assertOk(kept);
                    continue;
                }
                actor = decision.get("actor").getAsString();
                System.err.println("[it] priority actor=" + actor + " rev "
                        + decision.get("revision").getAsLong() + " status "
                        + decision.get("status").getAsString());
                final JsonObject trail = child.request("{\"protocol_version\":\"2.0.0\","
                        + "\"request_id\":\"p-trail\",\"message_type\":\"export_event_log\","
                        + "\"game_id\":\"proc-1\"}", 60000);
                final JsonArray events = trail.get("payload").getAsJsonObject()
                        .getAsJsonObject("log").getAsJsonArray("events");
                final StringBuilder seq = new StringBuilder();
                for (int k = 0; k < events.size(); k++) {
                    seq.append(events.get(k).getAsJsonObject().get("event_type").getAsString())
                            .append(';');
                }
                System.err.println("[it] audit trail: " + seq);
                break;
            }
            Assert.assertNotNull(actor, "never reached a priority decision");

            // Negative controls over the pipe: unknown option, wrong actor, stale revision.
            final JsonObject unknown = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-neg-unknown\",\"message_type\":\"submit_action\","
                    + "\"game_id\":\"proc-1\",\"payload\":{\"proposal\":{\"proposal_id\":\"n1\","
                    + "\"actor_id\":\"" + actor + "\",\"legal_action_id\":\"opt-nope\","
                    + "\"action_type\":\"pass_priority\"}}}", 60000);
            assertErrorCode(unknown, BridgeErrors.UNKNOWN_OPTION);

            final String otherActor = actor.equals("p1") ? "p2" : "p1";
            final JsonObject poll = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-poll-final\",\"message_type\":\"get_legal_actions\","
                    + "\"game_id\":\"proc-1\"}", 60000);
            assertOk(poll);
            final JsonArray offered = poll.get("payload").getAsJsonObject().getAsJsonArray("actions");
            Assert.assertTrue(offered.size() > 0, "priority frame must offer options");
            final String optionId = offered.get(0).getAsJsonObject().get("action_id").getAsString();
            final long revision = poll.get("payload").getAsJsonObject().getAsJsonObject("decision")
                    .get("revision").getAsLong();
            final String preHash = poll.get("payload").getAsJsonObject().toString();

            final JsonObject wrongActor = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-neg-actor\",\"message_type\":\"submit_action\","
                    + "\"game_id\":\"proc-1\",\"payload\":{\"proposal\":{\"proposal_id\":\"n2\","
                    + "\"actor_id\":\"" + otherActor + "\",\"legal_action_id\":\"" + optionId + "\","
                    + "\"action_type\":\"pass_priority\"}}}", 60000);
            assertErrorCode(wrongActor, BridgeErrors.WRONG_ACTOR);

            final JsonObject stale = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-neg-stale\",\"message_type\":\"submit_action\","
                    + "\"game_id\":\"proc-1\",\"payload\":{\"revision\":" + (revision - 1) + ","
                    + "\"proposal\":{\"proposal_id\":\"n3\",\"actor_id\":\"" + actor + "\","
                    + "\"legal_action_id\":\"" + optionId + "\","
                    + "\"action_type\":\"pass_priority\"}}}", 60000);
            assertErrorCode(stale, BridgeErrors.STALE_REVISION);

            // Real external pass over the pipe.
            final JsonObject pass = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-pass\",\"message_type\":\"pass_priority\","
                    + "\"game_id\":\"proc-1\",\"payload\":{\"actor_id\":\"" + actor + "\"}}", 60000);
            assertOk(pass);
            Assert.assertNotEquals(pass.toString(), preHash);

            // Lab-shaped state validation.
            final JsonObject state = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-state\",\"message_type\":\"get_game_state\","
                    + "\"game_id\":\"proc-1\"}", 60000);
            assertOk(state);
            final JsonObject gameState = state.get("payload").getAsJsonObject()
                    .getAsJsonObject("state");
            for (String key : new String[] { "game_id", "status", "turn_number", "phase",
                    "players", "stack", "legal_actions", "winner_ids", "event_sequence" }) {
                Assert.assertTrue(gameState.has(key), "missing GameState key " + key);
            }
            Assert.assertEquals(gameState.getAsJsonArray("players").size(), 4);
            final JsonObject player0 = gameState.getAsJsonArray("players").get(0).getAsJsonObject();
            for (String key : new String[] { "player_id", "seat", "life", "poison_counters",
                    "commander_damage_received", "commander_cast_count", "mana_pool", "zones",
                    "land_plays_remaining", "has_lost" }) {
                Assert.assertTrue(player0.has(key), "missing PlayerState key " + key);
            }

            final JsonObject log = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-log\",\"message_type\":\"export_event_log\","
                    + "\"game_id\":\"proc-1\"}", 60000);
            assertOk(log);
            Assert.assertTrue(log.get("payload").getAsJsonObject().getAsJsonObject("log")
                    .getAsJsonArray("events").size() > 0);

            final JsonObject down = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-down\",\"message_type\":\"shutdown_game\","
                    + "\"game_id\":\"proc-1\"}", 60000);
            assertOk(down);

            final JsonObject off = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"p-off\",\"message_type\":\"shutdown_engine\"}", 30000);
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
