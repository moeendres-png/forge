package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.player.Player;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WS233 Forge variable-player provider cardinality qualification.
 *
 * <p>Real engine, real cards, real controllers. One cardinality-generic
 * provider path ({@code 2..5}); 0P/1P/6P+ fail closed with
 * {@link BridgeErrors#PLAYER_COUNT_UNSUPPORTED} and leak no session.
 */
public class WS233CardinalityTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    // ---- negative matrix ----

    @Test(timeOut = 120000)
    public void testNegativeCountsFailClosed() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> six = BridgeTestSupport.importPod(engine, 6);
        Assert.assertEquals(new HashSet<>(six).size(), 6, "handles must be distinct");

        // 0P: empty handle list.
        final JsonObject empty = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"neg0\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"ws233-neg-0\",\"format\":\"commander\","
                        + "\"deck_handles\":[]}}}");
        BridgeTestSupport.assertError(empty, BridgeErrors.PLAYER_COUNT_UNSUPPORTED);
        Assert.assertNull(engine.sessionsForTests().get("ws233-neg-0"),
                "rejected 0P creation must not leak a session");

        // 0P shape: missing deck_handles key entirely.
        final JsonObject missing = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"neg0b\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"ws233-neg-0b\",\"format\":\"commander\"}}}");
        BridgeTestSupport.assertError(missing, BridgeErrors.PLAYER_COUNT_UNSUPPORTED);
        Assert.assertNull(engine.sessionsForTests().get("ws233-neg-0b"));

        // 1P.
        final JsonObject one = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"neg1\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"ws233-neg-1\",\"format\":\"commander\","
                        + "\"deck_handles\":[\"" + six.get(0) + "\"]}}}");
        BridgeTestSupport.assertError(one, BridgeErrors.PLAYER_COUNT_UNSUPPORTED);
        Assert.assertNull(engine.sessionsForTests().get("ws233-neg-1"));

        // 6P: must fail closed, never truncate to five.
        final StringBuilder decks = new StringBuilder();
        for (int i = 0; i < six.size(); i++) {
            if (i > 0) {
                decks.append(',');
            }
            decks.append('"').append(six.get(i)).append('"');
        }
        final JsonObject sixP = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"neg6\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"ws233-neg-6\",\"format\":\"commander\","
                        + "\"deck_handles\":[" + decks + "]}}}");
        BridgeTestSupport.assertError(sixP, BridgeErrors.PLAYER_COUNT_UNSUPPORTED);
        Assert.assertNull(engine.sessionsForTests().get("ws233-neg-6"));

        // Unknown-handle semantics preserved at a legal count.
        final List<String> four = BridgeTestSupport.importPod(engine, 4);
        final JsonObject unknown = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"unk233\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"ws233-neg-unk\",\"format\":\"commander\","
                        + "\"deck_handles\":[\"" + four.get(0) + "\",\"" + four.get(1)
                        + "\",\"" + four.get(2) + "\",\"deck-nope\"]}}}");
        BridgeTestSupport.assertError(unknown, BridgeErrors.UNKNOWN_DECK_HANDLE);
        Assert.assertNull(engine.sessionsForTests().get("ws233-neg-unk"));
    }

    // ---- positive lifecycle matrix ----

    @Test(timeOut = 300000)
    public void testTwoPlayerLifecycle() {
        qualifyLifecycle(2);
    }

    @Test(timeOut = 300000)
    public void testThreePlayerLifecycle() {
        qualifyLifecycle(3);
    }

    @Test(timeOut = 300000)
    public void testFourPlayerLifecycle() {
        qualifyLifecycle(4);
    }

    @Test(timeOut = 300000)
    public void testFivePlayerLifecycle() {
        qualifyLifecycle(5);
    }

    private static void qualifyLifecycle(int playerCount) {
        final String tag = "ws233-" + playerCount + "p";
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine, playerCount);

        // 1-2. exactly N handles accepted; session constructible.
        final JsonObject created = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"c-" + tag + "\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"" + tag + "\",\"format\":\"commander\","
                        + "\"deck_handles\":[\"" + String.join("\",\"", handles) + "\"]}}}");
        BridgeTestSupport.assertOk(created);
        final JsonObject payload = created.get("payload").getAsJsonObject();
        Assert.assertEquals(payload.get("player_count").getAsInt(), playerCount);
        final JsonArray seats = payload.getAsJsonArray("seats");
        Assert.assertEquals(seats.size(), playerCount);
        for (int i = 0; i < playerCount; i++) {
            final JsonObject seat = seats.get(i).getAsJsonObject();
            Assert.assertEquals(seat.get("seat").getAsInt(), i);
            Assert.assertEquals(seat.get("player_id").getAsString(), "p" + (i + 1));
        }

        // 3. game starts.
        BridgeTestSupport.startGame(engine, tag);
        final BridgeSession session = engine.sessionsForTests().get(tag);
        Assert.assertNotNull(session);

        // 4-5. exactly N Forge players; distinct stable principal identities.
        final List<Player> roster = session.registryPlayers();
        Assert.assertEquals(roster.size(), playerCount);
        final Set<String> principals = new HashSet<>();
        for (Player player : roster) {
            principals.add(session.playerIdOf(player));
        }
        for (int i = 1; i <= playerCount; i++) {
            Assert.assertTrue(principals.contains("p" + i), "missing principal p" + i);
        }
        Assert.assertEquals(principals.size(), playerCount);

        // 6-7. all external, none AI.
        for (Player player : session.getGame().getPlayers()) {
            Assert.assertTrue(player.getController() instanceof ExternalPlayerController,
                    "unexpected controller " + player.getController().getClass());
            Assert.assertFalse(player.getController().isAI());
        }

        // 8-9. Rules-Core-owned starting-player frame over exactly the N-player pod.
        final DecisionFrame starting = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(starting, "engine must park a decision");
        Assert.assertEquals(starting.kind, DecisionFrame.Kind.STARTING_PLAYER);
        Assert.assertEquals(starting.status, DecisionFrame.Status.SUPPORTED);
        Assert.assertTrue(starting.actorPlayerId.matches("p[1-" + playerCount + "]"),
                "chooser outside pod: " + starting.actorPlayerId);
        Assert.assertEquals(starting.options.size(), playerCount,
                "starting-player domain must be exactly the pod");

        // No first-seat default: explicitly choose the LAST seat.
        final String lastSeat = "p" + playerCount;
        final BridgeSession.SubmitOutcome chosen =
                BridgeTestSupport.submitStartingPlayer(session, starting, lastSeat);
        Assert.assertTrue(chosen.applied, "choice failed: " + chosen.errorCode);

        // 10-11. mulligans progress without defaults; first priority reachable.
        final DecisionFrame priority =
                BridgeTestSupport.driveKeepsToPriority(session, 120000);
        Assert.assertNotNull(priority);
        Assert.assertEquals(priority.kind, DecisionFrame.Kind.PRIORITY);
        Assert.assertEquals(priority.status, DecisionFrame.Status.SUPPORTED);

        // 12-13. pass_priority exposed; valid external submission advances state.
        final DecisionFrame.Option pass = BridgeTestSupport.findOption(priority, "pass_priority");
        Assert.assertNotNull(pass, "every priority frame must offer pass");
        final JsonObject legal = BridgeTestSupport.legalActions(engine, tag,
                priority.actorPlayerId);
        boolean passExposed = false;
        for (int i = 0; i < legal.getAsJsonArray("actions").size(); i++) {
            if ("pass_priority".equals(legal.getAsJsonArray("actions").get(i)
                    .getAsJsonObject().get("action_type").getAsString())) {
                passExposed = true;
            }
        }
        Assert.assertTrue(passExposed, "pass_priority must be an authoritative legal action");
        final String otherActor = priority.actorPlayerId.equals("p1") ? "p2" : "p1";
        final BridgeSession.SubmitOutcome wrongActor = session.submit(otherActor,
                pass.optionId, "pass_priority", priority.revision);
        Assert.assertFalse(wrongActor.applied);
        Assert.assertEquals(wrongActor.errorCode, BridgeErrors.WRONG_ACTOR);
        final BridgeSession.SubmitOutcome advance =
                BridgeTestSupport.submitPass(session, priority);
        Assert.assertTrue(advance.applied, "pass failed: " + advance.errorCode);
        Assert.assertNotEquals(advance.postStateHash, advance.preStateHash);

        // 14-15. projection has exactly N public entries; principal scoping intact.
        final JsonObject state = StateProjection.gameState(session, null);
        Assert.assertEquals(state.getAsJsonArray("players").size(), playerCount);
        final JsonObject asFirst = StateProjection.gameState(session, "p1");
        final JsonArray scoped = asFirst.getAsJsonArray("players");
        Assert.assertEquals(scoped.size(), playerCount);
        for (int i = 0; i < scoped.size(); i++) {
            final JsonObject entry = scoped.get(i).getAsJsonObject();
            final JsonArray hand = entry.getAsJsonObject("zones").getAsJsonArray("hand");
            if ("p1".equals(entry.get("player_id").getAsString())) {
                for (int h = 0; h < hand.size(); h++) {
                    Assert.assertFalse("<hidden>".equals(hand.get(h).getAsString()),
                            "owner must see own hand");
                }
            } else if (hand.size() > 0) {
                for (int h = 0; h < hand.size(); h++) {
                    Assert.assertEquals(hand.get(h).getAsString(), "<hidden>");
                }
            }
            Assert.assertEquals(entry.getAsJsonObject("zones").getAsJsonArray("library").size(),
                    0, "library order stays hidden");
        }
        final JsonObject badObserver = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"obs-" + tag + "\","
                        + "\"message_type\":\"get_game_state\",\"game_id\":\"" + tag + "\","
                        + "\"payload\":{\"observer_player_id\":\"p" + (playerCount + 1) + "\"}}");
        BridgeTestSupport.assertError(badObserver, BridgeErrors.WRONG_ACTOR);

        // 16. Commander starting life comes from Forge's rules lifecycle.
        for (int i = 0; i < state.getAsJsonArray("players").size(); i++) {
            Assert.assertEquals(state.getAsJsonArray("players").get(i).getAsJsonObject()
                    .get("life").getAsInt(), 40);
        }

        // 17. turn order is exactly the live roster, no phantom seats.
        Assert.assertEquals(session.getGame().getPlayersInTurnOrder().size(), playerCount);

        // 19-20. clean shutdown; same handles reusable (no cross-session contamination).
        session.shutdown(5000);
        Assert.assertTrue(session.isTerminal());
        final JsonObject again = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"c2-" + tag + "\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"" + tag + "-again\",\"format\":\"commander\","
                        + "\"deck_handles\":[\"" + String.join("\",\"", handles) + "\"]}}}");
        BridgeTestSupport.assertOk(again);
        BridgeTestSupport.startGame(engine, tag + "-again");
        final BridgeSession second = engine.sessionsForTests().get(tag + "-again");
        Assert.assertNotNull(BridgeTestSupport.awaitFrame(second, 60000));
        second.shutdown(5000);
        Assert.assertTrue(second.isTerminal());
    }

    // ---- variable-player semantic replay (in-process record + independent replay) ----

    @Test(timeOut = 300000)
    public void testSemanticReplayAtTwoPlayers() {
        recordReplayAtCount(2, 4451L);
    }

    @Test(timeOut = 300000)
    public void testSemanticReplayAtFivePlayers() {
        recordReplayAtCount(5, 8873L);
    }

    private static final class TapedStep {
        DecisionFrame.Kind kind;
        String fingerprint;
    }

    private static void recordReplayAtCount(int playerCount, long seed) {
        final String lastSeat = "p" + playerCount;
        // Record: starting choice (last seat) + keeps + 4 passes.
        final List<TapedStep> tape = new ArrayList<>();
        final BridgeEngine engineA = new BridgeEngine();
        BridgeTestSupport.startEngine(engineA);
        final List<String> handlesA = BridgeTestSupport.importPod(engineA, playerCount);
        createSeeded(engineA, "ws233-rec-" + playerCount, handlesA, seed);
        BridgeTestSupport.startGame(engineA, "ws233-rec-" + playerCount);
        final BridgeSession sessionA =
                engineA.sessionsForTests().get("ws233-rec-" + playerCount);
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

        // Replay: same seed/count, fresh engine; every taped choice resolves exactly once.
        final BridgeEngine engineB = new BridgeEngine();
        BridgeTestSupport.startEngine(engineB);
        final List<String> handlesB = BridgeTestSupport.importPod(engineB, playerCount);
        createSeeded(engineB, "ws233-rep-" + playerCount, handlesB, seed);
        BridgeTestSupport.startGame(engineB, "ws233-rep-" + playerCount);
        final BridgeSession sessionB =
                engineB.sessionsForTests().get("ws233-rep-" + playerCount);
        long seenB = -1;
        for (TapedStep step : tape) {
            DecisionFrame parked = null;
            for (int i = 0; i < 60; i++) {
                final DecisionFrame next = awaitNext(sessionB, seenB, 15000);
                Assert.assertNotNull(next, "no frame while replaying at " + playerCount + "P");
                seenB = next.revision;
                if (next.kind == step.kind) {
                    parked = next;
                    break;
                }
                // Mulligan frames interleave deterministically; consume in order.
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
        // Drain to the same depth (4 passes applied) then compare public coordinates.
        Assert.assertEquals(SemanticReplay.publicStateDigest(sessionB), digestA,
                "replay public coordinates must match the recording at " + playerCount + "P");
        sessionB.shutdown(5000);
        Assert.assertTrue(sessionB.isTerminal());
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
}
