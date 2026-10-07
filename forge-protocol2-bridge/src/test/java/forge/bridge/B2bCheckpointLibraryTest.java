package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnPhase;
import forge.game.event.GameEventZone;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Commander-Lab #561 batch 6 B2b (Coordinator decision 6035022676, item 2):
 * real-engine controls for the lossless checkpoint library at turn one
 * precombat main (C1, C2, C4-C8).
 *
 * <p>Every game runs the production launch path on a real Forge engine: the
 * checkpoint request travels through {@code create_commander_game} in the
 * Lab record's own shape ({@code deck_state} + {@code semantic_objects}), the
 * seam subscriber is the production one, and the engine performs its own
 * shuffles, opening hands and turn-one draw. Faults are deterministic stimuli
 * set before launch and cleared after each test.
 */
public class B2bCheckpointLibraryTest {

    static final String ROGRAKH = "Rograkh, Son of Rohgahh";
    static final String BURN = "Burn Down the House";
    static final String WARP = "Chaos Warp";
    /** The record's declared library objects (obj:replay-lib-0..6). */
    static final List<String> RECORD_ORDER = Arrays.asList(
            "Mountain", "Island", "Swamp", "Forest", "Plains", "Mountain", "Island");
    /** A distinguishable top: the turn-one draw would take a non-template card. */
    static final List<String> ISLAND_TOP = Arrays.asList(
            "Island", "Swamp", "Forest", "Plains", "Mountain", "Island", "Swamp");
    static final List<String> NAMES = Arrays.asList(
            "Mountain", "Island", "Swamp", "Forest", "Plains", BURN, WARP, ROGRAKH);

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @AfterMethod(alwaysRun = true)
    public void clearFaults() {
        BridgeSession.checkpointFaultForTests = null;
    }

    // ---- request in the record's shape ----

    static String templateDeck(String tag) {
        final List<String> mainboard = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            mainboard.add("\"Mountain\"");
        }
        return "{\"deck_id\":\"" + tag + "\",\"name\":\"" + tag + "\",\"commander_names\":[\""
                + ROGRAKH + "\"],\"mainboard\":[" + String.join(",", mainboard) + "],\"sideboard\":[]}";
    }

    static JsonObject object(String semanticId, String identity, String owner, String zone,
            Integer position) {
        final JsonObject object = new JsonObject();
        object.addProperty("semantic_id", semanticId);
        object.addProperty("card_identity", identity);
        object.addProperty("owner", owner);
        object.addProperty("controller", owner);
        object.addProperty("zone", zone);
        if (position != null) {
            object.addProperty("zone_position", position);
        }
        return object;
    }

    static JsonObject hand(int templateCount) {
        final JsonObject hand = new JsonObject();
        hand.addProperty("completeness", "COMPLETE");
        hand.addProperty("template_card_identity", "Mountain");
        hand.addProperty("template_count", templateCount);
        return hand;
    }

    static JsonObject deckState(String player, int handTemplate, JsonObject library) {
        final JsonObject deck = new JsonObject();
        deck.addProperty("player_id", player);
        final JsonObject template = new JsonObject();
        template.addProperty("card_identity", "Mountain");
        template.addProperty("count", 99);
        deck.add("library_template", template);
        deck.addProperty("opening_hand_size", 7);
        deck.add("checkpoint_hand", hand(handTemplate));
        if (library != null) {
            deck.add("checkpoint_library", library);
        }
        deck.addProperty("shuffle_channel", "library_shuffle:" + player);
        return deck;
    }

    /**
     * The replay rows' checkpoint request: {@code libraryOwner}'s complete
     * library is the declared objects on top of 91 template cards, its hand is
     * eight template cards plus the burn and warp objects; every other seat
     * holds its engine opening hand of seven.
     */
    static JsonObject checkpointRequest(String libraryOwner, List<String> order) {
        final JsonArray objects = new JsonArray();
        final JsonArray runs = new JsonArray();
        for (int i = 0; i < order.size(); i++) {
            final String id = "obj:replay-lib-" + i;
            objects.add(object(id, order.get(i), libraryOwner, "library", i));
            final JsonObject run = new JsonObject();
            run.addProperty("semantic_id", id);
            runs.add(run);
        }
        final JsonObject template = new JsonObject();
        template.addProperty("card_identity", "Mountain");
        template.addProperty("count", 91);
        runs.add(template);
        final JsonObject library = new JsonObject();
        library.addProperty("completeness", "COMPLETE_TOP_TO_BOTTOM");
        library.add("runs", runs);
        objects.add(object("obj:replay-burn", BURN, libraryOwner, "hand", null));
        objects.add(object("obj:replay-warp", WARP, libraryOwner, "hand", null));
        final JsonArray decks = new JsonArray();
        for (String seat : new String[] { "P1", "P2", "P3", "P4" }) {
            final boolean owner = seat.equals(libraryOwner);
            decks.add(deckState(seat, owner ? 8 : 7, owner ? library : null));
        }
        final JsonObject request = new JsonObject();
        request.add("deck_state", decks);
        request.add("semantic_objects", objects);
        return request;
    }

    /** P1's eight battlefield Mountains of the replay rows (G1-R1 placement). */
    static JsonObject scenarioWithMountains() {
        final JsonArray battlefield = new JsonArray();
        for (int i = 0; i < 8; i++) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("card", "Mountain");
            entry.addProperty("controller", "p1");
            entry.addProperty("owner", "p1");
            entry.addProperty("tapped", false);
            battlefield.add(entry);
        }
        final JsonObject neutral = new JsonObject();
        neutral.add("battlefield", battlefield);
        neutral.add("hands", new JsonObject());
        neutral.add("players", new JsonArray());
        final JsonObject scenario = new JsonObject();
        scenario.add("neutral_initial_state", neutral);
        return scenario;
    }

    static JsonObject createResponse(BridgeEngine engine, String gameId, long seed,
            JsonObject checkpoint) {
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = new ArrayList<>();
        for (int seat = 1; seat <= 4; seat++) {
            handles.add(BridgeTestSupport.importDeck(engine, "import-" + gameId + "-" + seat,
                    templateDeck(gameId + "-" + seat)));
        }
        final JsonObject request = new JsonObject();
        request.addProperty("game_id", gameId);
        request.addProperty("format", "commander");
        request.addProperty("seed", seed);
        final JsonArray deckHandles = new JsonArray();
        handles.forEach(deckHandles::add);
        request.add("deck_handles", deckHandles);
        request.add("scenario", scenarioWithMountains());
        request.add("checkpoint_materialization", checkpoint);
        final JsonObject payload = new JsonObject();
        payload.add("request", request);
        return BridgeTestSupport.rpc(engine, "{\"protocol_version\":\"2.0.0\",\"request_id\":\"b2b-create-"
                + gameId + "\",\"message_type\":\"create_commander_game\",\"payload\":" + payload + "}");
    }

    /** Creates (not starts) a checkpoint game; the caller may subscribe recorders first. */
    static BridgeSession created(BridgeEngine engine, String gameId, long seed, JsonObject checkpoint) {
        final JsonObject response = createResponse(engine, gameId, seed, checkpoint);
        BridgeTestSupport.assertOk(response);
        return engine.sessionsForTests().get(gameId);
    }

    static DecisionFrame awaitFrameAfter(BridgeSession session, long revision, long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.revision > revision) {
                return frame;
            }
            if (session.isTerminal()) {
                return null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    static void submit(BridgeSession session, DecisionFrame frame, DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied || session.isTerminal(),
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    static DecisionFrame.Option find(DecisionFrame frame,
            java.util.function.Predicate<DecisionFrame.Option> test, String what) {
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
        }
        throw new AssertionError(what + " not offered in " + frame.kind);
    }

    /**
     * Drives the real start (p1 starts, every seat keeps) and passes priority
     * until p1 holds priority in its turn-one precombat main phase. Returns
     * that frame, or null when the session ended first.
     */
    static DecisionFrame driveToP1Main(BridgeSession session) {
        long last = -1;
        for (int i = 0; i < 120; i++) {
            final DecisionFrame frame = awaitFrameAfter(session, last, 120000);
            if (frame == null) {
                return null;
            }
            last = frame.revision;
            if (frame.kind == DecisionFrame.Kind.STARTING_PLAYER) {
                submit(session, frame, find(frame, o -> "p1".equals(o.sourceCardName), "p1 starter"));
            } else if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                submit(session, frame, find(frame, o -> o.isKeep, "keep"));
            } else if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                final PhaseType phase = session.getGame().getPhaseHandler().getPhase();
                if ("p1".equals(frame.actorPlayerId) && phase == PhaseType.MAIN1
                        && session.getGame().getPhaseHandler().getTurn() == 1) {
                    return frame;
                }
                submit(session, frame, find(frame, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + frame.kind + " before p1's main phase");
            }
        }
        throw new AssertionError("p1 never reached its turn-one main phase");
    }

    /** Drives to the seam and waits for the session to fail closed; returns the fail reason. */
    static String driveExpectingCheckpointFailure(BridgeSession session) {
        final DecisionFrame main = driveToP1Main(session);
        Assert.assertNull(main, "a decision was offered after a failed checkpoint");
        Assert.assertTrue(session.awaitTerminal(60000), "session never terminated");
        Assert.assertEquals(session.getStatus(), BridgeSession.Status.FAILED,
                "expected FAILED, reason: " + session.getFailReason());
        return session.getFailReason();
    }

    static void assertNoCardName(String text) {
        for (String name : NAMES) {
            Assert.assertFalse(text.contains(name), "card name [" + name + "] leaked: " + text);
        }
    }

    static List<String> names(List<Card> cards) {
        final List<String> names = new ArrayList<>();
        for (Card card : cards) {
            names.add(card.getName());
        }
        return names;
    }

    // ---- C1: the seam follows the engine's own draw ----

    /**
     * Kills "library work at TurnBegan / applyPostUntap / before the draw":
     * the declared top is an Island, so a materialization before the turn-one
     * draw would have the draw take it. The engine's drawn card is a
     * template Mountain, the seam ran at turn one MAIN1 after exactly one
     * draw, the library is exactly the declared 98 top to bottom, and the hand
     * is the engine's opening seven plus its draw plus the two declared
     * objects.
     */
    @Test(timeOut = 360000)
    public void theMaterializationFollowsTheTurnOneDraw() {
        final BridgeEngine engine = new BridgeEngine();
        final BridgeSession session = created(engine, "b2b-after-draw", 56101L,
                checkpointRequest("P1", ISLAND_TOP));
        BridgeTestSupport.startGame(engine, "b2b-after-draw");
        final DecisionFrame main = driveToP1Main(session);
        Assert.assertNotNull(main, "no main-phase priority: " + session.getFailReason());
        final CheckpointMaterialization.Result result = session.checkpointResultForTests();
        Assert.assertNotNull(result, "checkpoint did not run: " + session.checkpointFailureForTests());
        Assert.assertEquals(result.turnAtSeam, 1);
        Assert.assertEquals(result.phaseAtSeam, PhaseType.MAIN1);
        Assert.assertEquals(result.drawsAtSeam.get("p1"), Integer.valueOf(1),
                "the engine's own turn-one draw precedes the seam");

        final Player p1 = session.playerById("p1");
        Assert.assertEquals(p1.getNumDrawnThisTurn(), 1, "the materialization never draws");
        final List<Card> hand = new ArrayList<>(p1.getCardsIn(ZoneType.Hand));
        final List<Card> drawn = new ArrayList<>();
        for (Card card : hand) {
            if (card.getDrawnThisTurn()) {
                drawn.add(card);
            }
        }
        Assert.assertEquals(drawn.size(), 1, "exactly the engine's one draw");
        Assert.assertEquals(drawn.get(0).getName(), "Mountain",
                "the draw took a template card, never the requested top");
        final List<String> handNames = names(hand);
        Collections.sort(handNames);
        final List<String> expectedHand = new ArrayList<>(Collections.nCopies(8, "Mountain"));
        expectedHand.add(BURN);
        expectedHand.add(WARP);
        Collections.sort(expectedHand);
        Assert.assertEquals(handNames, expectedHand);

        final List<String> library = names(new ArrayList<>(p1.getCardsIn(ZoneType.Library)));
        Assert.assertEquals(library.size(), 98, "7 declared objects on 91 engine cards");
        Assert.assertEquals(library.subList(0, 7), ISLAND_TOP);
        Assert.assertEquals(new ArrayList<>(library.subList(7, 98)), Collections.nCopies(91, "Mountain"));
        // C5: a materialized library object is never referenced to a
        // principal, its owner included (CR 401.2).
        for (Card card : new ArrayList<>(p1.getCardsIn(ZoneType.Library)).subList(0, 7)) {
            for (String observer : new String[] { "p1", "p2" }) {
                final JsonArray refs = StateProjection.objectRefs(session, card,
                        session.playerById(observer));
                Assert.assertEquals(refs.size(), 1);
                Assert.assertTrue(refs.get(0).getAsJsonObject().get("hidden").getAsBoolean(),
                        "library object referenced to " + observer + ": " + refs);
                assertNoCardName(refs.toString());
            }
        }
        Assert.assertEquals(p1.getCardsIn(ZoneType.Battlefield).size(), 8,
                "the placed Mountains do not come out of the declared library");

        // C8: every frame parked before the seam is pre-checkpoint.
        Assert.assertTrue(session.firstPostCheckpointRevision() > 0);
        Assert.assertTrue(main.revision >= session.firstPostCheckpointRevision(),
                "the first main-phase frame is post-checkpoint");
        Assert.assertTrue(session.firstPostCheckpointRevision() > 1,
                "upkeep/draw frames precede the checkpoint and stay out of every tape");
        session.shutdown(5000);
    }

    // ---- C1: draw-count precondition ----

    /**
     * Kills a missing draw-count precondition: the declared library belongs
     * to p2, who has not drawn on p1's turn one. The checkpoint fails closed
     * with DRAW_COUNT_MISMATCH and no decision is offered afterwards.
     */
    @Test(timeOut = 360000)
    public void theDrawCountPreconditionFailsClosed() {
        final BridgeEngine engine = new BridgeEngine();
        final BridgeSession session = created(engine, "b2b-draws", 56102L,
                checkpointRequest("P2", RECORD_ORDER));
        BridgeTestSupport.startGame(engine, "b2b-draws");
        final String reason = driveExpectingCheckpointFailure(session);
        Assert.assertEquals(reason, "CHECKPOINT_MATERIALIZATION_REJECTED:DRAW_COUNT_MISMATCH");
        Assert.assertEquals(session.checkpointFailureForTests(), "DRAW_COUNT_MISMATCH");
        final Player p2 = session.playerById("p2");
        Assert.assertEquals(p2.getCardsIn(ZoneType.Library).size(), 92,
                "nothing was materialized before every precondition held");
        session.shutdown(5000);
    }

    // ---- C2: only declared objects ----

    /** A declared library object the runs leave out is refused at creation (parse level). */
    @Test(timeOut = 240000)
    public void anUndeclaredLibraryObjectIsRefusedAtCreation() {
        final JsonObject request = checkpointRequest("P1", RECORD_ORDER);
        request.getAsJsonArray("semantic_objects")
                .add(object("obj:stray-lib", "Swamp", "P1", "library", 7));
        final JsonObject response = createResponse(new BridgeEngine(), "b2b-stray", 56103L, request);
        BridgeTestSupport.assertError(response, BridgeErrors.GAME_CREATION_FAILED);
        final String message = response.getAsJsonArray("errors").get(0).getAsJsonObject()
                .get("message").getAsString();
        Assert.assertTrue(message.contains("PARTIAL_LIBRARY_REQUEST obj:stray-lib"), message);
        assertNoCardName(response.toString());
    }

    /**
     * Kills a missing membership check: an extra object appears in the
     * library at materialization. The bridge's own readback refuses it as
     * LIBRARY_UNDECLARED_OBJECT.
     */
    @Test(timeOut = 360000)
    public void anUndeclaredExtraLibraryObjectIsRefused() {
        BridgeSession.checkpointFaultForTests = "extra_object";
        final BridgeEngine engine = new BridgeEngine();
        final BridgeSession session = created(engine, "b2b-extra", 56104L,
                checkpointRequest("P1", RECORD_ORDER));
        BridgeTestSupport.startGame(engine, "b2b-extra");
        final String reason = driveExpectingCheckpointFailure(session);
        Assert.assertEquals(reason, "CHECKPOINT_MATERIALIZATION_REJECTED:LIBRARY_UNDECLARED_OBJECT");
        session.shutdown(5000);
    }

    // ---- C6: position verification, coded ----

    /**
     * Kills a missing position check: two declared objects swap places. The
     * mismatch is LIBRARY_POSITION_MISMATCH, and neither the failure, the
     * audit trail nor the orchestration report names a card.
     */
    @Test(timeOut = 360000)
    public void aPositionMismatchIsACodeWithoutAName() {
        OrchestrationKey.keyForTests(new byte[32]);
        try {
            BridgeSession.checkpointFaultForTests = "swap_positions";
            final BridgeEngine engine = new BridgeEngine();
            final BridgeSession session = created(engine, "b2b-swap", 56105L,
                    checkpointRequest("P1", ISLAND_TOP));
            BridgeTestSupport.startGame(engine, "b2b-swap");
            final String reason = driveExpectingCheckpointFailure(session);
            Assert.assertEquals(reason, "CHECKPOINT_MATERIALIZATION_REJECTED:LIBRARY_POSITION_MISMATCH");
            assertNoCardName(reason);
            for (BridgeSession.AuditEvent event : session.auditSnapshot()) {
                if (event.type.startsWith("checkpoint")) {
                    assertNoCardName(event.type + event.details);
                }
            }
            final JsonObject report = session.checkpointPayload();
            Assert.assertEquals(report.get("status").getAsString(), "FAILED");
            Assert.assertEquals(report.get("failure_code").getAsString(), "LIBRARY_POSITION_MISMATCH");
            assertNoCardName(report.toString());
            session.shutdown(5000);
        } finally {
            OrchestrationKey.keyForTests(null);
        }
    }

    /** {@code Zone.add}'s silent drop (an object missing from the zone) is caught by the readback. */
    @Test(timeOut = 360000)
    public void aDroppedObjectIsDetected() {
        BridgeSession.checkpointFaultForTests = "drop_object";
        final BridgeEngine engine = new BridgeEngine();
        final BridgeSession session = created(engine, "b2b-drop", 56106L,
                checkpointRequest("P1", RECORD_ORDER));
        BridgeTestSupport.startGame(engine, "b2b-drop");
        Assert.assertEquals(driveExpectingCheckpointFailure(session),
                "CHECKPOINT_MATERIALIZATION_REJECTED:LIBRARY_OBJECT_MISSING");
        session.shutdown(5000);
    }

    // ---- C4: no shuffle, draw or zone-change event ----

    /** Records every engine event's class (and zone details) in delivery order. */
    public static final class EventRecorder {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @com.google.common.eventbus.Subscribe
        public void onEvent(GameEvent event) {
            if (event instanceof GameEventZone) {
                final GameEventZone zone = (GameEventZone) event;
                events.add("Zone:" + zone.zoneType() + ":" + zone.mode() + ":"
                        + (zone.player() == null ? "-" : zone.player().getName()));
            } else if (event instanceof GameEventTurnPhase) {
                events.add("TurnPhase:" + ((GameEventTurnPhase) event).phase());
            } else if (event instanceof GameEventPlayerPriority) {
                events.add("Priority:" + ((GameEventPlayerPriority) event).phase());
            } else {
                events.add(event.getClass().getSimpleName());
            }
        }

        List<String> snapshot() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
    }

    /**
     * Kills a materialization that fires shuffle, draw or zone-change events:
     * every event the engine delivered between turn one's MAIN1 phase event
     * (where the seam runs) and its first MAIN1 priority is exactly one
     * {@code ComplexUpdate} view refresh of p1's library and one of its hand;
     * no shuffle, no draw, no {@code GameEventCardChangeZone}, no per-card
     * {@code Added}. The engine's shuffle and draw counts are unchanged.
     */
    @Test(timeOut = 360000)
    public void theMaterializationFiresNoShuffleDrawOrZoneChangeEvent() {
        final BridgeEngine engine = new BridgeEngine();
        final BridgeSession session = created(engine, "b2b-events", 56107L,
                checkpointRequest("P1", RECORD_ORDER));
        final EventRecorder recorder = new EventRecorder();
        session.getGame().subscribeToEvents(recorder);
        BridgeTestSupport.startGame(engine, "b2b-events");
        final DecisionFrame main = driveToP1Main(session);
        Assert.assertNotNull(main, "no main-phase priority: " + session.getFailReason());
        final Player p1 = session.playerById("p1");
        final List<String> events = recorder.snapshot();
        final int seam = events.indexOf("TurnPhase:MAIN1");
        Assert.assertTrue(seam >= 0, "no MAIN1 phase event: " + events);
        final int priority = events.subList(seam, events.size()).indexOf("Priority:MAIN1");
        Assert.assertTrue(priority > 0, "no MAIN1 priority after the seam: " + events);
        final List<String> window = new ArrayList<>(events.subList(seam + 1, seam + priority));
        final String p1Name = p1.getName();
        Assert.assertEquals(window, Arrays.asList(
                "Zone:Library:ComplexUpdate:" + p1Name,
                "Zone:Hand:ComplexUpdate:" + p1Name),
                "the materialization's only events are one view refresh per touched zone: " + window);
        Assert.assertEquals(p1.getNumDrawnThisTurn(), 1);
        Assert.assertEquals(session.libraryShuffles(p1), shufflesBeforeTurnOne(events, session),
                "no shuffle after the opening");
        session.shutdown(5000);
    }

    /** The engine's shuffles happen before turn one (opening, CR 103.3); none after. */
    private static int shufflesBeforeTurnOne(List<String> events, BridgeSession session) {
        final int seam = events.indexOf("TurnPhase:MAIN1");
        Assert.assertFalse(events.subList(seam, events.size()).contains("GameEventShuffle"),
                "a shuffle followed the seam: " + events);
        return session.libraryShuffles(session.playerById("p1"));
    }

    // ---- C5/C6: published verification is coded counts only ----

    @Test(timeOut = 360000)
    public void theOrchestrationReportIsCodedCountsOnly() {
        OrchestrationKey.keyForTests(new byte[32]);
        try {
            final BridgeEngine engine = new BridgeEngine();
            final BridgeSession session = created(engine, "b2b-report", 56108L,
                    checkpointRequest("P1", RECORD_ORDER));
            BridgeTestSupport.startGame(engine, "b2b-report");
            Assert.assertNotNull(driveToP1Main(session), session.getFailReason());
            final JsonObject response = BridgeTestSupport.rpc(engine,
                    "{\"protocol_version\":\"2.0.0\",\"request_id\":\"b2b-cs\","
                            + "\"message_type\":\"get_constructed_state\",\"game_id\":\"b2b-report\"}");
            BridgeTestSupport.assertOk(response);
            final JsonObject report = response.getAsJsonObject("payload")
                    .getAsJsonObject("checkpoint_materialization");
            Assert.assertEquals(report.get("status").getAsString(), "MATERIALIZED");
            final JsonObject checks = report.getAsJsonObject("lossless_hidden_checks");
            Assert.assertEquals(checks.get("library_object").getAsInt(), 7);
            Assert.assertEquals(checks.get("library_membership").getAsInt(), 1);
            Assert.assertEquals(checks.get("library_order").getAsInt(), 1);
            Assert.assertEquals(checks.get("hand_object").getAsInt(), 2);
            Assert.assertEquals(checks.get("hand_composition").getAsInt(), 4);
            Assert.assertEquals(report.get("first_post_checkpoint_revision").getAsLong(),
                    session.firstPostCheckpointRevision());
            assertNoCardName(report.toString());
            Assert.assertFalse(report.toString().contains("obj:"), "no object reference: " + report);
            session.shutdown(5000);
        } finally {
            OrchestrationKey.keyForTests(null);
        }
    }

    /** C7: a declared hand that the scenario also scripts is refused. */
    @Test(timeOut = 240000)
    public void aScriptedHandForADeclaredHandIsRefused() {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = new ArrayList<>();
        for (int seat = 1; seat <= 4; seat++) {
            handles.add(BridgeTestSupport.importDeck(engine, "import-c7-" + seat,
                    templateDeck("c7-" + seat)));
        }
        final JsonObject scenario = scenarioWithMountains();
        final JsonArray p1Hand = new JsonArray();
        p1Hand.add("Mountain");
        scenario.getAsJsonObject("neutral_initial_state").getAsJsonObject("hands").add("p1", p1Hand);
        final JsonObject request = new JsonObject();
        request.addProperty("game_id", "b2b-c7");
        request.addProperty("format", "commander");
        request.addProperty("seed", 1L);
        final JsonArray deckHandles = new JsonArray();
        handles.forEach(deckHandles::add);
        request.add("deck_handles", deckHandles);
        request.add("scenario", scenario);
        request.add("checkpoint_materialization", checkpointRequest("P1", RECORD_ORDER));
        final JsonObject payload = new JsonObject();
        payload.add("request", request);
        final JsonObject response = BridgeTestSupport.rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"b2b-c7\",\"message_type\":\"create_commander_game\",\"payload\":"
                + payload + "}");
        BridgeTestSupport.assertError(response, BridgeErrors.GAME_CREATION_FAILED);
        Assert.assertTrue(response.toString().contains("HAND_SCRIPT_CONFLICT"), response.toString());
    }
}
