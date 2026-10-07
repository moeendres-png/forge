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
 * Commander-Lab #561 batch 6 B2b, condition C3: the keyed Rules-RNG tape is
 * reproduced by the same seed in two separate bridge processes and changed by
 * seed + 1, at the replay rows' checkpoint (P1's turn-one precombat main,
 * after the lossless library materialization).
 *
 * <p>Three child JVMs (the WS202 separate-process pattern) each carry the same
 * orchestration key and the same game id, because the result HMAC binds the
 * game id: the Lab's twin must name both sides' games identically. Each child
 * is driven over Protocol-2.0.0 JSONL only; the parent never touches engine
 * state.
 */
public class B2bRngTapeSeparateProcessTest {
    private static final String ENGINE_SHA = "c4d67145a6f9902e031a11dde5c33c60f51ed08d";
    private static final String KEY_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final String GAME = "b2b-replay-twin";
    private static final String[] SEATS = { "p1", "p2", "p3", "p4" };

    private static final class Pipe {
        final Process process;
        final BufferedWriter stdin;
        final BlockingQueue<String> stdout = new ArrayBlockingQueue<>(4096);
        final AtomicLong ids = new AtomicLong();

        Pipe() throws Exception {
            final String javaBin = System.getProperty("java.home") + "/bin/java";
            final String classpath = System.getProperty("java.class.path");
            final Path repoRoot = Paths.get("").toAbsolutePath().getParent();
            final ProcessBuilder builder = new ProcessBuilder(javaBin,
                    "-Djava.awt.headless=true", "-cp", classpath, "forge.bridge.BridgeMain");
            builder.environment().put("FORGE_ENGINE_SHA", ENGINE_SHA);
            builder.environment().put("FORGE_ASSETS_DIR", repoRoot.resolve("forge-gui").toString());
            builder.environment().put(OrchestrationKey.KEY_VARIABLE, KEY_HEX);
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
            }, "b2b-pipe-stdout");
            pump.setDaemon(true);
            pump.start();
            final BufferedReader err = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8));
            final Thread errPump = new Thread(() -> {
                try {
                    while (err.readLine() != null) {
                        // Diagnostics stay on stderr.
                    }
                } catch (Exception e) {
                    // Pump ends with the process.
                }
            }, "b2b-pipe-stderr");
            errPump.setDaemon(true);
            errPump.start();
        }

        String nextId(String stem) {
            return stem + "-" + ids.incrementAndGet();
        }

        JsonObject request(String json, long timeoutMillis) throws Exception {
            final String requestId = JsonParser.parseString(json).getAsJsonObject()
                    .get("request_id").getAsString();
            stdin.write(json);
            stdin.write("\n");
            stdin.flush();
            final long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                final String line = stdout.poll(Math.max(100, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                final JsonObject response = JsonParser.parseString(line).getAsJsonObject();
                if (requestId.equals(response.get("request_id").getAsString())) {
                    return response;
                }
            }
            Assert.fail("no response for " + requestId);
            return null;
        }

        JsonObject call(String type, String gameId, JsonObject payload) throws Exception {
            final JsonObject envelope = new JsonObject();
            envelope.addProperty("protocol_version", "2.0.0");
            envelope.addProperty("request_id", nextId(type));
            envelope.addProperty("message_type", type);
            if (gameId != null) {
                envelope.addProperty("game_id", gameId);
            }
            if (payload != null) {
                envelope.add("payload", payload);
            }
            return request(envelope.toString(), 240000);
        }

        void close() {
            try {
                stdin.close();
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Already exiting.
            } finally {
                process.destroyForcibly();
            }
        }
    }

    private static JsonObject ok(JsonObject response) {
        Assert.assertTrue(response.get("success").getAsBoolean(), "expected ok: " + response);
        return response.getAsJsonObject("payload");
    }

    /** The parked frame: kind, actor, revision and the offered actions, or null. */
    private static JsonObject poll(Pipe pipe) throws Exception {
        for (String seat : SEATS) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("actor_id", seat);
            final JsonObject response = pipe.call("get_legal_actions", GAME, payload);
            if (!response.get("success").getAsBoolean()) {
                continue;
            }
            final JsonObject body = response.getAsJsonObject("payload");
            if (body.getAsJsonObject("decision").has("kind")) {
                return body;
            }
        }
        return null;
    }

    private static JsonObject awaitAfter(Pipe pipe, long revision) throws Exception {
        final long deadline = System.currentTimeMillis() + 240000;
        while (System.currentTimeMillis() < deadline) {
            final JsonObject frame = poll(pipe);
            if (frame != null && frame.getAsJsonObject("decision").get("revision").getAsLong() > revision) {
                return frame;
            }
            Thread.sleep(200);
        }
        Assert.fail("no new frame parked after revision " + revision);
        return null;
    }

    private static String step(Pipe pipe) throws Exception {
        final JsonObject payload = new JsonObject();
        payload.addProperty("observer_player_id", "p1");
        final JsonObject state = ok(pipe.call("get_game_state", GAME, payload)).getAsJsonObject("state");
        return state.get("turn_number").getAsInt() + ":"
                + (state.get("step").isJsonNull() ? "-" : state.get("step").getAsString());
    }

    /** One clean process: boot, create the checkpoint game, drive to P1's turn-one main, read the tape. */
    private static JsonObject runTwin(long seed) throws Exception {
        final Pipe pipe = new Pipe();
        try {
            ok(pipe.call("start_engine", null, null));
            final JsonArray handles = new JsonArray();
            for (int seat = 1; seat <= 4; seat++) {
                final JsonObject deck = new JsonObject();
                deck.add("deck", JsonParser.parseString(
                        B2bCheckpointLibraryTest.templateDeck("twin-" + seat)));
                handles.add(ok(pipe.call("import_deck", null, deck)).getAsJsonObject("deck_handle")
                        .get("handle_id").getAsString());
            }
            final JsonObject request = new JsonObject();
            request.addProperty("game_id", GAME);
            request.addProperty("format", "commander");
            request.addProperty("seed", seed);
            request.add("deck_handles", handles);
            request.add("scenario", B2bCheckpointLibraryTest.scenarioWithMountains());
            request.add("checkpoint_materialization", B2bCheckpointLibraryTest.checkpointRequest(
                    "P1", B2bCheckpointLibraryTest.RECORD_ORDER));
            final JsonObject create = new JsonObject();
            create.add("request", request);
            final JsonObject created = ok(pipe.call("create_commander_game", null, create));
            Assert.assertEquals(created.getAsJsonObject("rng").get("rules_seed").getAsLong(), seed);
            ok(pipe.call("start_game", GAME, null));
            long last = -1;
            for (int i = 0; i < 120; i++) {
                final JsonObject frame = awaitAfter(pipe, last);
                final JsonObject decision = frame.getAsJsonObject("decision");
                final String kind = decision.get("kind").getAsString();
                final String actor = decision.get("actor").getAsString();
                last = decision.get("revision").getAsLong();
                if ("PRIORITY".equals(kind) && "p1".equals(actor) && "1:MAIN1".equals(step(pipe))) {
                    final JsonObject tape = ok(pipe.call("get_rules_rng_tape", GAME, null));
                    final JsonObject constructed = ok(pipe.call("get_constructed_state", GAME, null));
                    tape.add("checkpoint", constructed.getAsJsonObject("checkpoint_materialization"));
                    tape.addProperty("main_revision", last);
                    return tape;
                }
                if ("MULLIGAN".equals(kind)) {
                    final JsonObject keep = new JsonObject();
                    keep.addProperty("player_id", actor);
                    keep.addProperty("revision", last);
                    keep.addProperty("keep", true);
                    keep.add("bottom_card_ids", new JsonArray());
                    ok(pipe.call("resolve_mulligan", GAME, keep));
                    continue;
                }
                if ("STARTING_PLAYER".equals(kind) || "PRIORITY".equals(kind)) {
                    String chosen = null;
                    String type = null;
                    for (JsonElement element : frame.getAsJsonArray("actions")) {
                        final JsonObject action = element.getAsJsonObject();
                        final boolean match = "STARTING_PLAYER".equals(kind)
                                ? "p1".equals(action.has("source_object_id")
                                        && !action.get("source_object_id").isJsonNull()
                                        ? action.get("source_object_id").getAsString() : null)
                                : "pass_priority".equals(action.get("action_type").getAsString());
                        if (match) {
                            chosen = action.get("action_id").getAsString();
                            type = action.get("action_type").getAsString();
                        }
                    }
                    Assert.assertNotNull(chosen, "no expected option in " + frame);
                    final JsonObject proposal = new JsonObject();
                    proposal.addProperty("proposal_id", pipe.nextId("p"));
                    proposal.addProperty("actor_id", actor);
                    proposal.addProperty("legal_action_id", chosen);
                    proposal.addProperty("action_type", type);
                    final JsonObject submit = new JsonObject();
                    submit.addProperty("revision", last);
                    submit.add("proposal", proposal);
                    ok(pipe.call("submit_action", GAME, submit));
                    continue;
                }
                Assert.fail("unexpected " + kind + " before P1's main phase");
            }
            Assert.fail("P1 never reached its turn-one main phase");
            return null;
        } finally {
            pipe.close();
        }
    }

    private static List<String> digests(JsonObject tape) {
        final List<String> digests = new ArrayList<>();
        for (JsonElement element : tape.getAsJsonArray("rules_rng_results")) {
            digests.add(element.getAsJsonObject().get("result_digest").getAsString());
        }
        return digests;
    }

    /**
     * Kills a tape that does not reproduce the engine's shuffles: the same
     * seed gives an equal, non-empty tape (results, coordinates, call total
     * and privileged digest) in two processes; seed + 1 changes the results.
     */
    @Test(timeOut = 1500000)
    public void theSameSeedGivesAnEqualTapeInTwoProcessesAndSeedPlusOneDiffers() throws Exception {
        final long seed = 561006L;
        final JsonObject record = runTwin(seed);
        final JsonObject replay = runTwin(seed);
        final JsonObject control = runTwin(seed + 1);
        for (JsonObject tape : new JsonObject[] { record, replay, control }) {
            Assert.assertEquals(tape.get("engine_state").getAsString(), "PARKED", tape.toString());
            Assert.assertEquals(tape.getAsJsonObject("checkpoint").get("status").getAsString(),
                    "MATERIALIZED", tape.toString());
            Assert.assertTrue(tape.getAsJsonArray("rules_rng_results").size() >= 4,
                    "non-empty keyed result coordinates (C3): " + tape);
            Assert.assertTrue(tape.get("rules_seed_explicit").getAsBoolean());
        }
        Assert.assertEquals(replay.getAsJsonArray("rules_rng_results"),
                record.getAsJsonArray("rules_rng_results"), "same seed, same results and coordinates");
        Assert.assertEquals(replay.get("rules_random_calls").getAsLong(),
                record.get("rules_random_calls").getAsLong());
        Assert.assertEquals(replay.get("privileged_state_digest").getAsString(),
                record.get("privileged_state_digest").getAsString());
        Assert.assertEquals(replay.getAsJsonObject("checkpoint"), record.getAsJsonObject("checkpoint"));
        Assert.assertNotEquals(digests(control), digests(record),
                "seed + 1 must change the engine's shuffle results");
    }
}
