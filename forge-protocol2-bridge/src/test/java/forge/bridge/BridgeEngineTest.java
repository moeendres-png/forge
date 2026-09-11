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
        final JsonObject created = BridgeTestSupport.createGame(engine, "c1", "create-ok", handles);
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

    @Test
    public void testCreationInjectionRejected() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        final String deckArray = "\"" + String.join("\",\"", handles) + "\"";
        // R5: starting-player seat injection rejected (Forge selects the chooser instead).
        final JsonObject seat = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"seat1\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"seat1\",\"format\":\"commander\",\"starting_player_seat\":2,"
                        + "\"deck_handles\":[" + deckArray + "]}}}");
        BridgeTestSupport.assertError(seat, BridgeErrors.STARTING_PLAYER_SEAT_UNSUPPORTED);
        // R5: starting-life injection rejected even for the canonical value.
        final JsonObject life = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"life1\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"life1\",\"format\":\"commander\",\"starting_life\":40,"
                        + "\"deck_handles\":[" + deckArray + "]}}}");
        BridgeTestSupport.assertError(life, BridgeErrors.STARTING_LIFE_UNSUPPORTED);
    }

    @Test(timeOut = 300000)
    public void testLifecyclePassAndShutdown() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-life", "life-ok", handles);
        BridgeTestSupport.startGame(engine, "life-ok");
        final BridgeSession session = engine.sessionsForTests().get("life-ok");
        final DecisionFrame priority = BridgeTestSupport.driveStartToPriority(session, "p2", 120000);
        Assert.assertEquals(priority.status, DecisionFrame.Status.SUPPORTED);
        Assert.assertNotNull(BridgeTestSupport.findOption(priority, "pass_priority"),
                "every priority frame must offer pass");
        // Negative controls BEFORE any submission mutates state.
        final String preHash = priority.preStateHash;
        DecisionFrame.Option first = BridgeTestSupport.findOption(priority, "pass_priority");
        Assert.assertNotNull(first);
        final BridgeSession.SubmitOutcome missingActor = session.submit(null,
                first.optionId, "pass_priority", priority.revision);
        Assert.assertFalse(missingActor.applied);
        Assert.assertEquals(missingActor.errorCode, BridgeErrors.MALFORMED_REQUEST);
        final BridgeSession.SubmitOutcome missingRevision = session.submit(priority.actorPlayerId,
                first.optionId, "pass_priority", null);
        Assert.assertFalse(missingRevision.applied);
        Assert.assertEquals(missingRevision.errorCode, BridgeErrors.MALFORMED_REQUEST);
        final BridgeSession.SubmitOutcome unknown = session.submit(priority.actorPlayerId,
                "opt-does-not-exist", "pass_priority", priority.revision);
        Assert.assertFalse(unknown.applied);
        Assert.assertEquals(unknown.errorCode, BridgeErrors.UNKNOWN_OPTION);
        final String otherActor = priority.actorPlayerId.equals("p1") ? "p2" : "p1";
        final BridgeSession.SubmitOutcome wrongActor = session.submit(otherActor,
                first.optionId, "pass_priority", priority.revision);
        Assert.assertFalse(wrongActor.applied);
        Assert.assertEquals(wrongActor.errorCode, BridgeErrors.WRONG_ACTOR);
        final BridgeSession.SubmitOutcome stale = session.submit(priority.actorPlayerId,
                first.optionId, "pass_priority", priority.revision - 1);
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
        BridgeTestSupport.createGame(engine, "c-life2", "life-ok-2", handles);
        BridgeTestSupport.startGame(engine, "life-ok-2");
        final BridgeSession second = engine.sessionsForTests().get("life-ok-2");
        final DecisionFrame secondFirst = BridgeTestSupport.driveStartToPriority(second, "p4", 120000);
        Assert.assertNotNull(secondFirst);
        Assert.assertEquals(secondFirst.status, DecisionFrame.Status.SUPPORTED);
        // Commander starting life comes from Forge's own Commander lifecycle (T9).
        final JsonObject state = StateProjection.gameState(second, null);
        for (int i = 0; i < state.getAsJsonArray("players").size(); i++) {
            Assert.assertEquals(state.getAsJsonArray("players").get(i).getAsJsonObject()
                    .get("life").getAsInt(), 40);
        }
        second.shutdown(5000);
        Assert.assertTrue(second.isTerminal());
    }

    @Test(timeOut = 300000)
    public void testShipAbortsLoudly() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-ship", "ship-ok", handles);
        BridgeTestSupport.startGame(engine, "ship-ok");
        final BridgeSession session = engine.sessionsForTests().get("ship-ok");
        final DecisionFrame starting = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(starting);
        Assert.assertEquals(starting.kind, DecisionFrame.Kind.STARTING_PLAYER);
        final BridgeSession.SubmitOutcome chosen =
                BridgeTestSupport.submitStartingPlayer(session, starting, "p4");
        Assert.assertTrue(chosen.applied);
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
                ship.optionId, "mulligan", frame.revision);
        Assert.assertTrue(outcome.applied, "ship delivery failed: " + outcome.errorCode);
        session.awaitTerminal(30000);
        Assert.assertEquals(session.getStatus(), BridgeSession.Status.FAILED);
        Assert.assertTrue(session.getFailReason().contains("tuckCardsViaMulligan"),
                "fail reason must name the blocker: " + session.getFailReason());
        session.shutdown(2000);
    }

    @Test(timeOut = 300000)
    public void testStartingPlayerExternalChoice() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-start", "start-ok", handles);
        BridgeTestSupport.startGame(engine, "start-ok");
        final BridgeSession session = engine.sessionsForTests().get("start-ok");
        // Forge's rules-selected chooser parks the decision; the test assumes nothing
        // about which controller was selected.
        final DecisionFrame starting = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(starting);
        Assert.assertEquals(starting.kind, DecisionFrame.Kind.STARTING_PLAYER);
        Assert.assertEquals(starting.status, DecisionFrame.Status.SUPPORTED);
        Assert.assertTrue(starting.actorPlayerId.matches("p[1-4]"));
        // Complete legal set: every player Forge permits.
        Assert.assertEquals(starting.options.size(), 4);
        // No default: the game cannot advance without an explicit submission.
        final long firstRevision = starting.revision;
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Assert.assertEquals(session.getCurrentFrame().revision, firstRevision);
        Assert.assertEquals(session.getStatus(), BridgeSession.Status.RUNNING);
        // Negative controls on the starting frame, before any mutation.
        DecisionFrame.Option p3option = null;
        for (DecisionFrame.Option option : starting.options) {
            if ("p3".equals(option.sourceCardName)) {
                p3option = option;
            }
        }
        Assert.assertNotNull(p3option, "p3 must be offered by semantic identity");
        final String preHash = starting.preStateHash;
        final String wrongActor = starting.actorPlayerId.equals("p1") ? "p2" : "p1";
        final BridgeSession.SubmitOutcome wrong = session.submit(wrongActor, p3option.optionId,
                "structural_decision", starting.revision);
        Assert.assertFalse(wrong.applied);
        Assert.assertEquals(wrong.errorCode, BridgeErrors.WRONG_ACTOR);
        final BridgeSession.SubmitOutcome stale = session.submit(starting.actorPlayerId,
                p3option.optionId, "structural_decision", starting.revision - 1);
        Assert.assertFalse(stale.applied);
        Assert.assertEquals(stale.errorCode, BridgeErrors.STALE_REVISION);
        final BridgeSession.SubmitOutcome missingRevision = session.submit(starting.actorPlayerId,
                p3option.optionId, "structural_decision", null);
        Assert.assertFalse(missingRevision.applied);
        Assert.assertEquals(missingRevision.errorCode, BridgeErrors.MALFORMED_REQUEST);
        Assert.assertEquals(StateHash.ofGame(session.getGame(), session), preHash);
        // Explicit selection of p3 (not the first option): Forge must start with p3.
        final BridgeSession.SubmitOutcome outcome = session.submit(starting.actorPlayerId,
                p3option.optionId, "structural_decision", starting.revision);
        Assert.assertTrue(outcome.applied, "choice failed: " + outcome.errorCode);
        final DecisionFrame priority = BridgeTestSupport.driveKeepsToPriority(session, 120000);
        Assert.assertNotNull(priority);
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
        final BridgeSession.SubmitOutcome attempt = session.submit("p1", "opt-anything",
                "pass_priority", frame.revision);
        Assert.assertFalse(attempt.applied);
        Assert.assertEquals(attempt.errorCode, BridgeErrors.UNSUPPORTED_DECISION);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testHiddenInformationAdversary() {
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
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED);
        DecisionFrame.Option cast = BridgeTestSupport.findOption(frame, "cast_spell");
        Assert.assertNotNull(cast, "Memnite cast must be offered to p1");
        // Adversary: observe as p2. Nothing of p1's private decision may leak.
        final JsonObject asP2 = StateProjection.gameState(session, "p2");
        final String flatP2 = asP2.toString();
        Assert.assertTrue(flatP2.contains("Serra Angel"), "own hand must be visible");
        Assert.assertFalse(flatP2.contains("Memnite"), "opponent hand leaked: " + flatP2);
        Assert.assertFalse(flatP2.contains(cast.label), "opponent action label leaked");
        Assert.assertFalse(flatP2.contains(cast.optionId), "opponent action id leaked");
        // No offered option ID may appear; private sources (hand-only cards) may not appear.
        // ("Plains" is exempt from the name check: it is also publicly on the battlefield.)
        for (DecisionFrame.Option option : frame.options) {
            Assert.assertFalse(flatP2.contains(option.optionId),
                    "option id leaked: " + option.optionId);
        }
        Assert.assertEquals(
                asP2.getAsJsonArray("legal_actions").size(), 0, "p2 must see no legal actions");
        final JsonObject p1State = playerState(asP2, "p1");
        Assert.assertEquals(p1State.getAsJsonObject("zones").getAsJsonArray("hand").size(), 2);
        Assert.assertEquals(p1State.getAsJsonObject("zones").getAsJsonArray("hand").get(0)
                .getAsString(), "<hidden>");
        Assert.assertEquals(p1State.getAsJsonObject("zones").getAsJsonArray("hand").get(1)
                .getAsString(), "<hidden>");
        Assert.assertEquals(p1State.getAsJsonObject("zones").getAsJsonArray("library").size(), 0);
        // Actor observes its own legal options.
        final JsonObject asP1 = StateProjection.gameState(session, "p1");
        Assert.assertTrue(asP1.toString().contains("Memnite"));
        Assert.assertTrue(asP1.getAsJsonArray("legal_actions").size() > 0);
        // Public/null observer receives no private action options.
        final JsonObject asPublic = StateProjection.gameState(session, null);
        Assert.assertEquals(asPublic.getAsJsonArray("legal_actions").size(), 0);
        Assert.assertFalse(asPublic.toString().contains("Memnite"));
        Assert.assertFalse(asPublic.toString().contains("Serra Angel"));
        // Public zones stay visible to everyone.
        Assert.assertTrue(flatP2.contains("Plains"));
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testEnumerationFailureFailsClosed() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("enumfail-ok");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        ExternalPlayerController.enumerationFaultForTests = true;
        try {
            BridgeTestSupport.launchConstructed(constructed);
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 60000);
            Assert.assertNotNull(frame);
            // No partial SUPPORTED set: explicit UNSUPPORTED with zero options.
            Assert.assertEquals(frame.status, DecisionFrame.Status.UNSUPPORTED);
            Assert.assertTrue(frame.reason.contains("NATIVE_ENUMERATION_FAILED"),
                    "reason: " + frame.reason);
            Assert.assertTrue(frame.options.isEmpty());
            final String hash = StateHash.ofGame(session.getGame(), session);
            final BridgeSession.SubmitOutcome attempt = session.submit("p1", "opt-anything",
                    "pass_priority", frame.revision);
            Assert.assertFalse(attempt.applied);
            Assert.assertEquals(attempt.errorCode, BridgeErrors.UNSUPPORTED_DECISION);
            Assert.assertEquals(StateHash.ofGame(session.getGame(), session), hash);
        } finally {
            ExternalPlayerController.enumerationFaultForTests = false;
            session.shutdown(5000);
        }
    }

    @Test(timeOut = 300000)
    public void testProjectionFailureIsExplicit() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-proj", "proj-ok", handles);
        BridgeTestSupport.startGame(engine, "proj-ok");
        final BridgeSession session = engine.sessionsForTests().get("proj-ok");
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        StateProjection.failRequiredReadsForTests = true;
        try {
            final JsonObject response = BridgeTestSupport.rpc(engine,
                    "{\"protocol_version\":\"2.0.0\",\"request_id\":\"proj1\","
                            + "\"message_type\":\"get_game_state\",\"game_id\":\"proj-ok\"}");
            Assert.assertFalse(response.get("success").getAsBoolean(),
                    "projection failure must not yield a GameState: " + response);
            Assert.assertEquals(response.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                    .get("code").getAsString(), BridgeErrors.PROJECTION_FAILED);
        } finally {
            StateProjection.failRequiredReadsForTests = false;
        }
        final JsonObject recovered = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"proj2\","
                        + "\"message_type\":\"get_game_state\",\"game_id\":\"proj-ok\"}");
        BridgeTestSupport.assertOk(recovered);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testNonzeroManaMakesUnsupported() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("mana-ok");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        // Forge natively recognizes the spell: it exists and is playable.
        boolean nativeLegal = false;
        for (Card card : session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Hand)) {
            if (!card.getName().equals("Grizzly Bears")) {
                continue;
            }
            for (forge.game.spellability.SpellAbility ability :
                    card.getAllPossibleAbilities(session.getGame().getPlayers().get(0), true)) {
                if (ability.isSpell() && ability.canPlay()) {
                    nativeLegal = true;
                }
            }
        }
        Assert.assertTrue(nativeLegal, "Grizzly Bears must be natively playable here");
        // ...but the bridge fails the whole decision closed instead of filtering or paying.
        Assert.assertEquals(frame.status, DecisionFrame.Status.UNSUPPORTED);
        Assert.assertTrue(frame.reason.contains("MANA_PAYMENT_CHOICE"), "reason: " + frame.reason);
        Assert.assertTrue(frame.options.isEmpty());
        final String hash = StateHash.ofGame(session.getGame(), session);
        final BridgeSession.SubmitOutcome attempt = session.submit("p1", "opt-anything",
                "cast_spell", frame.revision);
        Assert.assertFalse(attempt.applied);
        Assert.assertEquals(attempt.errorCode, BridgeErrors.UNSUPPORTED_DECISION);
        Assert.assertEquals(StateHash.ofGame(session.getGame(), session), hash);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testFixedOutputManaAbilitySupported() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("tap-ok");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED);
        DecisionFrame.Option tap = BridgeTestSupport.findOption(frame, "activate_ability");
        Assert.assertNotNull(tap, "fixed-output Plains tap must be offered");
        final BridgeSession.SubmitOutcome outcome =
                session.submit("p1", tap.optionId, "activate_ability", frame.revision);
        Assert.assertTrue(outcome.applied, "tap failed: " + outcome.errorCode);
        Assert.assertTrue(outcome.executionOk, "engine declined: " + session.getLastExecutionError());
        // No controller choice was involved: exactly one white mana floats.
        final JsonObject asP1 = StateProjection.gameState(session, "p1");
        final JsonObject p1 = playerState(asP1, "p1");
        Assert.assertEquals(p1.getAsJsonObject("mana_pool").get("W").getAsInt(), 1);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testDispatchFieldValidation() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-fields", "fields-ok", handles);
        BridgeTestSupport.startGame(engine, "fields-ok");
        final BridgeSession session = engine.sessionsForTests().get("fields-ok");
        final DecisionFrame priority = BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        final String hash = StateHash.ofGame(session.getGame(), session);
        // submit_action missing fields.
        final JsonObject noActor = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"f1\","
                        + "\"message_type\":\"submit_action\",\"game_id\":\"fields-ok\","
                        + "\"payload\":{\"revision\":" + priority.revision + ",\"proposal\":{"
                        + "\"proposal_id\":\"x\",\"legal_action_id\":\"opt-x\","
                        + "\"action_type\":\"pass_priority\"}}}");
        BridgeTestSupport.assertError(noActor, BridgeErrors.MALFORMED_REQUEST);
        final JsonObject noRevision = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"f2\","
                        + "\"message_type\":\"submit_action\",\"game_id\":\"fields-ok\","
                        + "\"payload\":{\"proposal\":{\"proposal_id\":\"x\",\"actor_id\":\""
                        + priority.actorPlayerId
                        + "\",\"legal_action_id\":\"opt-x\",\"action_type\":\"pass_priority\"}}}");
        BridgeTestSupport.assertError(noRevision, BridgeErrors.MALFORMED_REQUEST);
        // pass_priority missing fields.
        final JsonObject passNoActor = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"f3\","
                        + "\"message_type\":\"pass_priority\",\"game_id\":\"fields-ok\","
                        + "\"payload\":{\"revision\":" + priority.revision + "}}");
        BridgeTestSupport.assertError(passNoActor, BridgeErrors.MALFORMED_REQUEST);
        final JsonObject passNoRevision = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"f4\","
                        + "\"message_type\":\"pass_priority\",\"game_id\":\"fields-ok\","
                        + "\"payload\":{\"actor_id\":\"" + priority.actorPlayerId + "\"}}");
        BridgeTestSupport.assertError(passNoRevision, BridgeErrors.MALFORMED_REQUEST);
        // get_legal_actions principal gate.
        final JsonObject noPrincipal = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"f5\","
                        + "\"message_type\":\"get_legal_actions\",\"game_id\":\"fields-ok\","
                        + "\"payload\":{}}");
        BridgeTestSupport.assertError(noPrincipal, BridgeErrors.MALFORMED_REQUEST);
        final String other = priority.actorPlayerId.equals("p1") ? "p2" : "p1";
        final JsonObject wrongPrincipal = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"f6\","
                        + "\"message_type\":\"get_legal_actions\",\"game_id\":\"fields-ok\","
                        + "\"payload\":{\"actor_id\":\"" + other + "\"}}");
        BridgeTestSupport.assertError(wrongPrincipal, BridgeErrors.WRONG_ACTOR);
        final JsonObject legal = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"f7\","
                        + "\"message_type\":\"get_legal_actions\",\"game_id\":\"fields-ok\","
                        + "\"payload\":{\"actor_id\":\"" + priority.actorPlayerId + "\"}}");
        BridgeTestSupport.assertOk(legal);
        Assert.assertTrue(legal.get("payload").getAsJsonObject()
                .getAsJsonArray("actions").size() > 0);
        Assert.assertEquals(StateHash.ofGame(session.getGame(), session), hash,
                "rejected submissions must not mutate state");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testMulliganMissingKeepRejected() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        BridgeTestSupport.createGame(engine, "c-nokeep", "nokeep-ok", handles);
        BridgeTestSupport.startGame(engine, "nokeep-ok");
        final BridgeSession session = engine.sessionsForTests().get("nokeep-ok");
        final DecisionFrame starting = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(starting);
        Assert.assertEquals(starting.kind, DecisionFrame.Kind.STARTING_PLAYER);
        BridgeTestSupport.submitStartingPlayer(session, starting, "p1");
        final DecisionFrame mulligan = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(mulligan);
        Assert.assertEquals(mulligan.kind, DecisionFrame.Kind.MULLIGAN);
        final String hash = StateHash.ofGame(session.getGame(), session);
        final JsonObject missing = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"mk1\","
                        + "\"message_type\":\"resolve_mulligan\",\"game_id\":\"nokeep-ok\","
                        + "\"payload\":{\"player_id\":\"" + mulligan.actorPlayerId + "\","
                        + "\"revision\":" + mulligan.revision + ",\"bottom_card_ids\":[]}}");
        BridgeTestSupport.assertError(missing, BridgeErrors.MALFORMED_REQUEST);
        Assert.assertEquals(StateHash.ofGame(session.getGame(), session), hash);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testUnsupportedCallbacksThrow() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("cb-ok");
        final forge.game.player.Player player = constructed.game.getPlayers().get(0);
        final ExternalPlayerController controller =
                (ExternalPlayerController) player.getController();
        int thrown = 0;
        try {
            controller.declareAttackers(player, null);
        } catch (BridgeUnsupportedDecision e) {
            thrown++;
        }
        try {
            controller.chooseTargetsFor(null);
        } catch (BridgeUnsupportedDecision e) {
            thrown++;
        }
        try {
            controller.confirmTrigger(null);
        } catch (BridgeUnsupportedDecision e) {
            thrown++;
        }
        try {
            controller.orderSimultaneousSa(java.util.Arrays.asList(null, null));
        } catch (BridgeUnsupportedDecision e) {
            thrown++;
        }
        try {
            controller.tuckCardsViaMulligan(null, 1);
        } catch (BridgeUnsupportedDecision e) {
            thrown++;
        }
        try {
            controller.chooseNumber(null, "t", 0, 1);
        } catch (BridgeUnsupportedDecision e) {
            thrown++;
        }
        try {
            controller.chooseManaFromPool(java.util.Collections.emptyList());
        } catch (BridgeUnsupportedDecision e) {
            thrown++;
        }
        Assert.assertEquals(thrown, 7, "every unrepresented callback must throw");
        Assert.assertFalse(controller.isAI());
        constructed.session.shutdown(1000);
    }

    @Test(timeOut = 300000)
    public void testFaceDownVisibilityGate() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("vis-ok");
        final forge.game.player.Player p1 = constructed.game.getPlayers().get(0);
        final forge.game.player.Player p2 = constructed.game.getPlayers().get(1);
        final Card morph = BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears",
                ZoneType.Battlefield);
        morph.turnFaceDown(true);
        // Native gates: the battlefield zone is visible to all, but face-down identity
        // additionally requires the face gate (controller or explicit may-look grant).
        Assert.assertTrue(morph.getView().canBeShownTo(p2.getView()));
        Assert.assertFalse(morph.getView().canFaceDownBeShownTo(p2.getView()));
        Assert.assertTrue(morph.getView().canFaceDownBeShownTo(p1.getView()));
        // Bridge projection obeys it literally.
        final JsonObject asP2 = StateProjection.gameState(constructed.session, "p2");
        Assert.assertFalse(asP2.toString().contains("Grizzly Bears"));
        Assert.assertTrue(asP2.toString().contains("<face-down>"));
        final JsonObject asP1 = StateProjection.gameState(constructed.session, "p1");
        Assert.assertTrue(asP1.toString().contains("Grizzly Bears"));
        // An explicit native may-look grant is honored (permit path, not bridge rules).
        morph.addMayLookTemp(p2);
        final JsonObject asP2Look = StateProjection.gameState(constructed.session, "p2");
        Assert.assertTrue(asP2Look.toString().contains("Grizzly Bears"));
        morph.removeMayLookTemp(p2);
        constructed.session.shutdown(1000);
    }

    @Test(timeOut = 300000)
    public void testFlashbackZoneSeenNotSilent() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("flash-ok");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Think Twice", ZoneType.Graveyard);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        final forge.game.player.Player p1 = session.getGame().getPlayers().get(0);
        // The engine itself surfaces the card through the virtual Flashback zone.
        boolean inFlashbackZone = false;
        for (Card card : p1.getCardsIn(ZoneType.Flashback)) {
            if (card.getName().equals("Think Twice")) {
                inFlashbackZone = true;
            }
        }
        Assert.assertTrue(inFlashbackZone, "Think Twice must surface via ZoneType.Flashback");
        // ...and natively playable from the graveyard through its flashback ability.
        boolean nativeLegal = false;
        for (Card card : p1.getCardsIn(ZoneType.Graveyard)) {
            if (!card.getName().equals("Think Twice")) {
                continue;
            }
            for (forge.game.spellability.SpellAbility ability :
                    card.getAllPossibleAbilities(p1, true)) {
                if (ability.isSpell() && ability.canPlay()) {
                    nativeLegal = true;
                }
            }
        }
        Assert.assertTrue(nativeLegal, "Think Twice must be natively playable here");
        // Nonzero flashback cost: seen, recognized, failed closed — never filtered.
        Assert.assertEquals(frame.status, DecisionFrame.Status.UNSUPPORTED);
        Assert.assertTrue(frame.reason.contains("MANA_PAYMENT_CHOICE"), "reason: " + frame.reason);
        Assert.assertTrue(frame.options.isEmpty());
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testSubmitResponseWithholdsNextActor() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("r9-ok");
        final BridgeEngine engine = constructed.engine;
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame);
        Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED);
        final java.util.List<String> optionIds = new java.util.ArrayList<>();
        for (DecisionFrame.Option option : frame.options) {
            optionIds.add(option.optionId);
        }
        // Complete p2 engine response carries nothing of p1's private frame.
        final JsonObject asP2 = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r9-p2\","
                        + "\"message_type\":\"get_game_state\",\"game_id\":\"r9-ok\","
                        + "\"payload\":{\"observer_player_id\":\"p2\"}}");
        BridgeTestSupport.assertOk(asP2);
        final String flatP2 = asP2.toString();
        Assert.assertFalse(flatP2.contains("Memnite"));
        for (String id : optionIds) {
            Assert.assertFalse(flatP2.contains(id), "option id leaked");
        }
        Assert.assertEquals(asP2.get("payload").getAsJsonObject().getAsJsonObject("state")
                .getAsJsonArray("legal_actions").size(), 0);
        Assert.assertTrue(asP2.get("payload").getAsJsonObject().getAsJsonObject("bridge")
                .get("pending_decision").isJsonNull());
        Assert.assertEquals(asP2.get("payload").getAsJsonObject().getAsJsonObject("bridge")
                .get("revision").getAsInt(), -1);
        // Actor sees its own decision identity.
        final JsonObject asP1 = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r9-p1\","
                        + "\"message_type\":\"get_game_state\",\"game_id\":\"r9-ok\","
                        + "\"payload\":{\"observer_player_id\":\"p1\"}}");
        BridgeTestSupport.assertOk(asP1);
        Assert.assertTrue(asP1.get("payload").getAsJsonObject().getAsJsonObject("bridge")
                .get("pending_decision").getAsJsonObject().get("revision").getAsLong()
                == frame.revision);
        // Submit pass as p1: the next frame belongs to p2 and must be withheld.
        DecisionFrame.Option pass = BridgeTestSupport.findOption(frame, "pass_priority");
        Assert.assertNotNull(pass);
        final JsonObject submitted = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"r9-sub\","
                        + "\"message_type\":\"submit_action\",\"game_id\":\"r9-ok\",\"payload\":{"
                        + "\"revision\":" + frame.revision + ",\"proposal\":{\"proposal_id\":\"s1\","
                        + "\"actor_id\":\"p1\",\"legal_action_id\":\"" + pass.optionId + "\","
                        + "\"action_type\":\"pass_priority\"}}}");
        BridgeTestSupport.assertOk(submitted);
        final String flatSubmit = submitted.toString();
        Assert.assertFalse(flatSubmit.contains("opt-"), "next-actor option id leaked: " + flatSubmit);
        final JsonObject nextDecision = submitted.get("payload").getAsJsonObject()
                .getAsJsonObject("next_decision");
        Assert.assertEquals(nextDecision.get("status").getAsString(), "withheld");
        Assert.assertTrue(submitted.get("payload").getAsJsonObject().getAsJsonObject("bridge")
                .get("pending_decision").isJsonNull());
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testProjectionFaultFamilies() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("faultfam-ok");
        final BridgeEngine engine = constructed.engine;
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final String stateRequest = "{\"protocol_version\":\"2.0.0\",\"request_id\":\"ff\","
                + "\"message_type\":\"get_game_state\",\"game_id\":\"faultfam-ok\"}";
        StateProjection.failManaPoolForTests = true;
        try {
            final JsonObject manaFail = BridgeTestSupport.rpc(engine, stateRequest);
            Assert.assertFalse(manaFail.get("success").getAsBoolean());
            Assert.assertEquals(manaFail.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                    .get("code").getAsString(), BridgeErrors.PROJECTION_FAILED);
        } finally {
            StateProjection.failManaPoolForTests = false;
        }
        StateProjection.failCommanderDamageForTests = true;
        try {
            final JsonObject damageFail = BridgeTestSupport.rpc(engine, stateRequest);
            Assert.assertFalse(damageFail.get("success").getAsBoolean());
            Assert.assertEquals(damageFail.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                    .get("code").getAsString(), BridgeErrors.PROJECTION_FAILED);
        } finally {
            StateProjection.failCommanderDamageForTests = false;
        }
        StateProjection.failCommanderCastsForTests = true;
        try {
            final JsonObject castsFail = BridgeTestSupport.rpc(engine, stateRequest);
            Assert.assertFalse(castsFail.get("success").getAsBoolean());
            Assert.assertEquals(castsFail.get("errors").getAsJsonArray().get(0).getAsJsonObject()
                    .get("code").getAsString(), BridgeErrors.PROJECTION_FAILED);
        } finally {
            StateProjection.failCommanderCastsForTests = false;
        }
        final JsonObject recovered = BridgeTestSupport.rpc(engine, stateRequest);
        BridgeTestSupport.assertOk(recovered);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testRosterStableAcrossLoss() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("loss-ok");
        final BridgeSession session = constructed.session;
        for (int seat = 0; seat < 4; seat++) {
            BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Hand);
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        final forge.game.player.Player p4 =
                constructed.game.getPlayers().get(3);
        Assert.assertEquals(session.playerIdOf(p4), "p4");
        Assert.assertEquals(session.getGame().getPlayers().size(), 4);
        // Real native loss transition, driven while the engine is parked: p4 concedes,
        // the engine's own SBA processing removes p4 from ingamePlayers on next step.
        p4.concede();
        final BridgeSession.SubmitOutcome pass = BridgeTestSupport.submitPass(session, frame);
        Assert.assertTrue(pass.applied, "pass failed: " + pass.errorCode);
        Assert.assertEquals(session.getGame().getPlayers().size(), 3);
        boolean p4ingame = false;
        for (forge.game.player.Player player : session.getGame().getPlayers()) {
            if (player == p4) {
                p4ingame = true;
            }
        }
        Assert.assertFalse(p4ingame, "p4 must be removed from ingamePlayers");
        // Registry identity is immutable: no throw, no display-name fallback.
        Assert.assertEquals(session.playerIdOf(p4), "p4");
        Assert.assertEquals(session.seatOf(p4), 3);
        Assert.assertTrue(p4 == session.playerById("p4"));
        // Observation roster preserves all registered participants with stable ids.
        final JsonObject asP1 = StateProjection.gameState(session, "p1");
        Assert.assertEquals(asP1.getAsJsonArray("players").size(), 4);
        for (int i = 0; i < 4; i++) {
            final JsonObject playerState =
                    asP1.getAsJsonArray("players").get(i).getAsJsonObject();
            Assert.assertEquals(playerState.get("player_id").getAsString(), "p" + (i + 1));
            Assert.assertEquals(playerState.get("seat").getAsInt(), i);
        }
        final JsonObject p4State = playerState(asP1, "p4");
        Assert.assertTrue(p4State.get("has_lost").getAsBoolean());
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
