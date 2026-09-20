package forge.bridge;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * R15 determinism twins: same-seed record/replay at 3P/4P plus a
 * distinct-seed divergence control (2P/5P twins retained via WS233).
 *
 * <p>Mirrors the WS233 record/replay shape exactly: starting choice
 * (last seat) + keeps + 4 passes taped with fingerprints on engine A,
 * fresh engine B with the same seed replays every choice exactly once,
 * public-state digests match. A different seed must diverge. Tests use
 * only engine-recorded decisions; no harness randomness.</p>
 */
public class WsR15DeterminismTwinTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static final class TapedStep {
        DecisionFrame.Kind kind;
        String fingerprint;
    }

    private static void createSeeded(BridgeEngine engine, String gameId, List<String> handles,
            long seed) {
        final JsonObject created = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"seed-" + gameId + "\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"" + gameId + "\",\"format\":\"commander\",\"seed\":" + seed
                        + ",\"deck_handles\":[\"" + String.join("\",\"", handles) + "\"]}}}");
        BridgeTestSupport.assertOk(created);
    }

    private static TapedStep taped(DecisionFrame frame, DecisionFrame.Option option,
            BridgeSession session) {
        final TapedStep step = new TapedStep();
        step.kind = frame.kind;
        step.fingerprint = SemanticReplay.optionFingerprint(frame, option, session);
        return step;
    }

    private static DecisionFrame awaitNext(BridgeSession session, long seenRevision,
            long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.revision != seenRevision) {
                return frame;
            }
            if (session.isTerminal()) {
                return frame != null && frame.revision != seenRevision ? frame : null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        final DecisionFrame frame = session.getCurrentFrame();
        return frame != null && frame.revision != seenRevision ? frame : null;
    }

    private static DecisionFrame.Option keepOption(DecisionFrame frame) {
        for (DecisionFrame.Option option : frame.options) {
            if (option.isKeep) {
                return option;
            }
        }
        throw new AssertionError("no keep option parked");
    }

    private static DecisionFrame.Option findStartingOption(DecisionFrame frame,
            String playerId) {
        for (DecisionFrame.Option option : frame.options) {
            if (playerId.equals(option.sourceCardName)) {
                return option;
            }
        }
        throw new AssertionError("no starting-player option for " + playerId);
    }

    private static String recordTape(int playerCount, long seed, String tag) {
        final String lastSeat = "p" + playerCount;
        final List<TapedStep> tape = new ArrayList<>();
        final BridgeEngine engineA = new BridgeEngine();
        BridgeTestSupport.startEngine(engineA);
        final List<String> handlesA = BridgeTestSupport.importPod(engineA, playerCount);
        createSeeded(engineA, tag + "-rec-" + playerCount, handlesA, seed);
        BridgeTestSupport.startGame(engineA, tag + "-rec-" + playerCount);
        final BridgeSession sessionA =
                engineA.sessionsForTests().get(tag + "-rec-" + playerCount);
        long seen = -1;
        int passes = 0;
        for (int i = 0; i < 80 && passes < 4; i++) {
            final DecisionFrame parked = awaitNext(sessionA, seen, 15000);
            Assert.assertNotNull(parked, "no frame while recording at " + playerCount + "P");
            seen = parked.revision;
            if (parked.kind == DecisionFrame.Kind.STARTING_PLAYER) {
                final DecisionFrame.Option opt = findStartingOption(parked, lastSeat);
                tape.add(taped(parked, opt, sessionA));
                Assert.assertTrue(sessionA.submit(parked.actorPlayerId, opt.optionId,
                        opt.actionType, parked.revision).applied);
            } else if (parked.kind == DecisionFrame.Kind.MULLIGAN) {
                final DecisionFrame.Option keep = keepOption(parked);
                tape.add(taped(parked, keep, sessionA));
                Assert.assertTrue(sessionA.submit(parked.actorPlayerId, keep.optionId,
                        keep.actionType, parked.revision).applied);
            } else if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                final DecisionFrame.Option pass = BridgeTestSupport.findOption(parked,
                        "pass_priority");
                Assert.assertNotNull(pass);
                tape.add(taped(parked, pass, sessionA));
                Assert.assertTrue(BridgeTestSupport.submitPass(sessionA, parked).applied);
                passes++;
            } else {
                Assert.fail("unexpected frame while recording: " + parked.kind);
            }
        }
        Assert.assertEquals(passes, 4, "must record four passes");
        final String digestA = SemanticReplay.publicStateDigest(sessionA);
        sessionA.shutdown(5000);
        LastTape.steps = tape;
        LastTape.digest = digestA;
        return digestA;
    }

    private static final class LastTape {
        static List<TapedStep> steps = new ArrayList<>();
        static String digest = "";
    }

    private static String replayTape(int playerCount, long seed, String tag) {
        final BridgeEngine engineB = new BridgeEngine();
        BridgeTestSupport.startEngine(engineB);
        final List<String> handlesB = BridgeTestSupport.importPod(engineB, playerCount);
        createSeeded(engineB, tag + "-rep-" + playerCount, handlesB, seed);
        BridgeTestSupport.startGame(engineB, tag + "-rep-" + playerCount);
        final BridgeSession sessionB =
                engineB.sessionsForTests().get(tag + "-rep-" + playerCount);
        long seenB = -1;
        for (TapedStep step : LastTape.steps) {
            DecisionFrame parked = null;
            for (int i = 0; i < 60; i++) {
                final DecisionFrame next = awaitNext(sessionB, seenB, 15000);
                Assert.assertNotNull(next, "no frame while replaying at " + playerCount + "P");
                seenB = next.revision;
                if (next.kind == step.kind) {
                    parked = next;
                    break;
                }
                Assert.fail("frame kind drift while replaying: expected " + step.kind
                        + " got " + next.kind);
            }
            Assert.assertNotNull(parked);
            final DecisionFrame.Option resolved =
                    SemanticReplay.resolveExactlyOnce(parked, sessionB, step.fingerprint);
            final BridgeSession.SubmitOutcome outcome = sessionB.submit(parked.actorPlayerId,
                    resolved.optionId, resolved.actionType, parked.revision);
            Assert.assertTrue(outcome.applied, "replay submit failed: " + outcome.errorCode);
        }
        final String digestB = SemanticReplay.publicStateDigest(sessionB);
        sessionB.shutdown(5000);
        Assert.assertTrue(sessionB.isTerminal());
        return digestB;
    }

    @Test(timeOut = 300000)
    public void testTwinThreePlayers() {
        final String digestA = recordTape(3, 5511L, "wsr15");
        final String digestB = replayTape(3, 5511L, "wsr15");
        Assert.assertEquals(digestB, digestA, "3P same-seed replay must match");
    }

    @Test(timeOut = 300000)
    public void testTwinFourPlayers() {
        final String digestA = recordTape(4, 6622L, "wsr15");
        final String digestB = replayTape(4, 6622L, "wsr15");
        Assert.assertEquals(digestB, digestA, "4P same-seed replay must match");
    }

    @Test(timeOut = 300000)
    public void testDivergenceControlThreePlayers() {
        final String digestA = recordTape(3, 5511L, "wsr15-div");
        final BridgeEngine engineB = new BridgeEngine();
        BridgeTestSupport.startEngine(engineB);
        final List<String> handlesB = BridgeTestSupport.importPod(engineB, 3);
        createSeeded(engineB, "wsr15-div-rep-3", handlesB, 9999L);
        BridgeTestSupport.startGame(engineB, "wsr15-div-rep-3");
        final BridgeSession sessionB = engineB.sessionsForTests().get("wsr15-div-rep-3");
        long seenB = -1;
        int passes = 0;
        for (int i = 0; i < 80 && passes < 4; i++) {
            final DecisionFrame parked = awaitNext(sessionB, seenB, 15000);
            Assert.assertNotNull(parked, "no frame while diverging at 3P");
            seenB = parked.revision;
            if (parked.kind == DecisionFrame.Kind.STARTING_PLAYER) {
                final DecisionFrame.Option opt = findStartingOption(parked, "p1");
                Assert.assertTrue(sessionB.submit(parked.actorPlayerId, opt.optionId,
                        opt.actionType, parked.revision).applied);
            } else if (parked.kind == DecisionFrame.Kind.MULLIGAN) {
                final DecisionFrame.Option keep = keepOption(parked);
                Assert.assertTrue(sessionB.submit(parked.actorPlayerId, keep.optionId,
                        keep.actionType, parked.revision).applied);
            } else if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                final DecisionFrame.Option pass = BridgeTestSupport.findOption(parked,
                        "pass_priority");
                Assert.assertNotNull(pass);
                Assert.assertTrue(BridgeTestSupport.submitPass(sessionB, parked).applied);
                passes++;
            } else {
                Assert.fail("unexpected frame while diverging: " + parked.kind);
            }
        }
        Assert.assertEquals(passes, 4, "must drive four passes");
        final String digestB = SemanticReplay.publicStateDigest(sessionB);
        sessionB.shutdown(5000);
        Assert.assertNotEquals(digestB, digestA, "distinct seeds must diverge at 3P");
    }
}
