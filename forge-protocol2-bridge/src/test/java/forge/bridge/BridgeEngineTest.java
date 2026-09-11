package forge.bridge;

import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Real-runtime qualification: lifecycle, external decisions, hidden information,
 * negative controls and fail-closed unsupported paths. Real engine, real cards,
 * no mocks, no AI pilot.
 */
public class BridgeEngineTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @Test
    public void testImportValidation() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        // Unknown card fails closed.
        final JsonObject badCard = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"bad1\","
                        + "\"message_type\":\"import_deck\",\"payload\":{\"deck\":{\"deck_id\":\"x\","
                        + "\"name\":\"x\",\"commander_names\":[\"Isamaru, Hound of Konda\"],"
                        + "\"mainboard\":[\"Not A Real Card\"]}}}");
        BridgeTestSupport.assertError(badCard, BridgeErrors.DECK_IMPORT_FAILED);
        // Wrong size fails closed.
        final JsonObject badSize = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"bad2\","
                        + "\"message_type\":\"import_deck\",\"payload\":{\"deck\":{\"deck_id\":\"x\","
                        + "\"name\":\"x\",\"commander_names\":[\"Isamaru, Hound of Konda\"],"
                        + "\"mainboard\":[\"Plains\"]}}}");
        BridgeTestSupport.assertError(badSize, BridgeErrors.DECK_IMPORT_FAILED);
        // Good import returns a forge handle with the full 100 accepted.
        final String handle = BridgeTestSupport.importDeck(engine, "good1",
                BridgeTestSupport.deckResource("deck1.json"));
        Assert.assertTrue(handle.startsWith("deck-"));
        final BridgeEngine.ImportedDeck deck = engine.decksForTests().get(handle);
        Assert.assertEquals(deck.commanderNames.size(), 1);
    }

    @Test
    public void testCreateValidation() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        // Seed rejected: RNG truth.
        final JsonObject seeded = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"seed1\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"seeded\",\"format\":\"commander\",\"seed\":42,"
                        + "\"deck_handles\":[\"" + String.join("\",\"", handles) + "\"]}}}");
        BridgeTestSupport.assertError(seeded, BridgeErrors.SEED_UNSUPPORTED);
        // Wrong pod size rejected.
        final JsonObject pod3 = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"pod3\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"pod3\",\"format\":\"commander\",\"deck_handles\":[\""
                        + handles.get(0) + "\",\"" + handles.get(1) + "\"]}}}");
        BridgeTestSupport.assertError(pod3, BridgeErrors.PLAYER_COUNT_UNSUPPORTED);
        // Unknown handle rejected.
        final JsonObject unknown = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"unk1\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"unk1\",\"format\":\"commander\",\"deck_handles\":[\""
                        + handles.get(0) + "\",\"" + handles.get(1) + "\",\""
                        + handles.get(2) + "\",\"deck-nope\"]}}}");
        BridgeTestSupport.assertError(unknown, BridgeErrors.UNKNOWN_DECK_HANDLE);
        // Real creation.
        final JsonObject created = BridgeTestSupport.createGame(engine, "c1", "create-ok", handles, 0);
        Assert.assertEquals(created.get("payload").getAsJsonObject().get("player_count").getAsInt(), 4);
        BridgeTestSupport.startGame(engine, "create-ok");
        final BridgeSession session = engine.sessionsForTests().get("create-ok");
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(frame, "engine must park a decision");
        // No AI anywhere on the control path.
        for (Player player : session.getGame().getPlayers()) {
            Assert.assertTrue(player.getController() instanceof ExternalPlayerController,
                    "unexpected controller " + player.getController().getClass());
            Assert.assertFalse(player.getController().isAI());
        }
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testLifecyclePassAndShutdown() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-life", "life-ok", handles, 0);
        BridgeTestSupport.startGame(engine, "life-ok");
        final BridgeSession session = engine.sessionsForTests().get("life-ok");
        final DecisionFrame priority = BridgeTestSupport.driveKeepsToPriority(session, 120000);
        Assert.assertEquals(priority.status, DecisionFrame.Status.SUPPORTED);
        Assert.assertNotNull(BridgeTestSupport.findOption(priority, "pass_priority"),
                "every priority frame must offer pass");
        // Negative controls BEFORE any submission mutates state.
        final String preHash = priority.preStateHash;
        DecisionFrame.Option first = BridgeTestSupport.findOption(priority, "pass_priority");
        Assert.assertNotNull(first);
        final BridgeSession.SubmitOutcome unknown = session.submit(priority.actorPlayerId,
                "opt-does-not-exist", null, null);
        Assert.assertFalse(unknown.applied);
        Assert.assertEquals(unknown.errorCode, BridgeErrors.UNKNOWN_OPTION);
        final String otherActor = priority.actorPlayerId.equals("p1") ? "p2" : "p1";
        final BridgeSession.SubmitOutcome wrongActor = session.submit(otherActor,
                first.optionId, null, null);
        Assert.assertFalse(wrongActor.applied);
        Assert.assertEquals(wrongActor.errorCode, BridgeErrors.WRONG_ACTOR);
        final BridgeSession.SubmitOutcome stale = session.submit(priority.actorPlayerId,
                first.optionId, null, priority.revision - 1);
        Assert.assertFalse(stale.applied);
        Assert.assertEquals(stale.errorCode, BridgeErrors.STALE_REVISION);
        Assert.assertEquals(StateHash.ofGame(session.getGame(), session), preHash,
                "rejected submissions must not mutate state");
        // Real external pass advances the game.
        final BridgeSession.SubmitOutcome pass = BridgeTestSupport.submitPass(session, priority);
        Assert.assertTrue(pass.applied, "pass failed: " + pass.errorCode + " " + pass.errorMessage);
        Assert.assertNotEquals(pass.postStateHash, pass.preStateHash);
        final DecisionFrame next = session.getCurrentFrame();
        Assert.assertNotNull(next);
        Assert.assertNotEquals(next.revision, priority.revision);
        // Consumed option IDs die with their frame.
        final BridgeSession.SubmitOutcome replay = session.submit(priority.actorPlayerId,
                first.optionId, null, null);
        Assert.assertFalse(replay.applied);
        // Event/audit binding exists.
        final JsonObject log = BridgeTestSupport.rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"log1\",\"message_type\":\"export_event_log\",\"game_id\":\"life-ok\"}");
        BridgeTestSupport.assertOk(log);
        final JsonObject logObj = log.get("payload").getAsJsonObject().getAsJsonObject("log");
        Assert.assertTrue(logObj.get("events").getAsJsonArray().size() > 0);
        Assert.assertTrue(logObj.get("log_sha256").getAsString().matches("[0-9a-f]{64}"));
        session.shutdown(5000);
        Assert.assertTrue(session.isTerminal());
        // Repeated lifecycle on the same engine: no cross-session corruption.
        BridgeTestSupport.createGame(engine, "c-life2", "life-ok-2", handles, 1);
        BridgeTestSupport.startGame(engine, "life-ok-2");
        final BridgeSession second = engine.sessionsForTests().get("life-ok-2");
        final DecisionFrame secondFirst = BridgeTestSupport.driveKeepsToPriority(second, 120000);
        Assert.assertNotNull(secondFirst);
        Assert.assertEquals(secondFirst.status, DecisionFrame.Status.SUPPORTED);
        second.shutdown(5000);
        Assert.assertTrue(second.isTerminal());
    }

    @Test(timeOut = 300000)
    public void testShipAbortsLoudly() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-ship", "ship-ok", handles, 0);
        BridgeTestSupport.startGame(engine, "ship-ok");
        final BridgeSession session = engine.sessionsForTests().get("ship-ok");
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(frame);
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.MULLIGAN);
        DecisionFrame.Option ship = null;
        for (DecisionFrame.Option option : frame.options) {
            if (!option.isKeep) {
                ship = option;
            }
        }
        Assert.assertNotNull(ship);
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                ship.optionId, "mulligan", null);
        Assert.assertTrue(outcome.applied, "ship delivery failed: " + outcome.errorCode);
        session.awaitTerminal(30000);
        Assert.assertEquals(session.getStatus(), BridgeSession.Status.FAILED);
        Assert.assertTrue(session.getFailReason().contains("tuckCardsViaMulligan"),
                "fail reason must name the blocker: " + session.getFailReason());
        session.shutdown(2000);
    }

    @Test(timeOut = 300000)
    public void testStartingSeatHonored() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-seat", "seat-ok", handles, 2);
        BridgeTestSupport.startGame(engine, "seat-ok");
        final BridgeSession session = engine.sessionsForTests().get("seat-ok");
        BridgeTestSupport.driveKeepsToPriority(session, 120000);
        Assert.assertEquals(session.getGame().getPhaseHandler().getPlayerTurn(),
                session.getGame().getPlayers().get(2));
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testConstructedCastExecution() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("cast-ok");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 10; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        // Upkeep/draw priorities park pass-only frames; advance to p1's main phase where
        // sorcery-speed casts are legal. This itself proves complete per-phase framing.
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.PRIORITY);
        Assert.assertEquals(frame.actorPlayerId, "p1");
        Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED);
        DecisionFrame.Option cast = BridgeTestSupport.findOption(frame, "cast_spell");
        Assert.assertNotNull(cast, "Memnite cast must be offered");
        // Real external submission through the real pipeline.
        final BridgeSession.SubmitOutcome outcome = session.submit("p1", cast.optionId, "cast_spell",
                frame.revision);
        Assert.assertTrue(outcome.applied, "cast failed: " + outcome.errorCode);
        Assert.assertTrue(outcome.executionOk, "engine declined: " + session.getLastExecutionError());
        Assert.assertEquals(session.getGame().getStack().size(), 1);
        Assert.assertEquals(session.getGame().getStack().peekAbility().getHostCard().getName(), "Memnite");
        // Drive passes until the spell resolves onto the battlefield.
        boolean resolved = false;
        long seenRevision = frame.revision;
        for (int i = 0; i < 16 && !resolved; i++) {
            for (Card card : session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Memnite")) {
                    resolved = true;
                }
            }
            if (resolved) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.revision == seenRevision) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }
            seenRevision = parked.revision;
            frame = parked;
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            final BridgeSession.SubmitOutcome pass = BridgeTestSupport.submitPass(session, frame);
            Assert.assertTrue(pass.applied);
        }
        Assert.assertTrue(resolved, "Memnite never reached the battlefield");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testConstructedUnsupportedTargeting() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("bolt-ok");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Swords to Plowshares", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 10; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(frame);
        // The targeted spell is legal but not representable: fail closed, never filtered.
        Assert.assertEquals(frame.status, DecisionFrame.Status.UNSUPPORTED);
        Assert.assertTrue(frame.reason.contains("TARGETING"), "reason: " + frame.reason);
        Assert.assertTrue(frame.options.isEmpty());
        final BridgeSession.SubmitOutcome attempt = session.submit("p1", "opt-anything", null, null);
        Assert.assertFalse(attempt.applied);
        Assert.assertEquals(attempt.errorCode, BridgeErrors.UNSUPPORTED_DECISION);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testHiddenInformationProjection() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("hidden-ok");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Serra Angel", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(frame);
        // Adversary: observe as p2. Opponent hand identity must not leak.
        final JsonObject asP2 = StateProjection.gameState(session, "p2");
        final String flatP2 = asP2.toString();
        Assert.assertTrue(flatP2.contains("Serra Angel"), "own hand must be visible");
        Assert.assertFalse(flatP2.contains("Memnite"), "opponent hand leaked: " + flatP2);
        final JsonObject p1State = playerState(asP2, "p1");
        Assert.assertEquals(p1State.getAsJsonObject("zones").getAsJsonArray("hand").size(), 1);
        Assert.assertEquals(p1State.getAsJsonObject("zones").getAsJsonArray("hand").get(0)
                .getAsString(), "<hidden>");
        Assert.assertEquals(p1State.getAsJsonObject("zones").getAsJsonArray("library").size(), 0);
        // Own observation sees own hand fully.
        final JsonObject asP1 = StateProjection.gameState(session, "p1");
        Assert.assertTrue(asP1.toString().contains("Memnite"));
        // Public zones stay visible to everyone.
        Assert.assertTrue(flatP2.contains("Plains"));
        session.shutdown(5000);
    }

    private static JsonObject playerState(JsonObject state, String playerId) {
        for (int i = 0; i < state.getAsJsonArray("players").size(); i++) {
            final JsonObject player = state.getAsJsonArray("players").get(i).getAsJsonObject();
            if (player.get("player_id").getAsString().equals(playerId)) {
                return player;
            }
        }
        throw new AssertionError("no such player " + playerId);
    }
}
