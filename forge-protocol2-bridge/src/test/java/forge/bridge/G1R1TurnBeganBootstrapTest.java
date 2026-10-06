package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Commander-Lab #561 G1-R1 (Coordinator decision 6005365186): real-engine
 * controls for the TurnBegan scenario-bootstrap placement point.
 *
 * <p>Every game here runs the production bridge launch path on a real Forge
 * engine with real cards and the external controller boundary: the scenario
 * plan travels through {@code create_commander_game}, the TurnBegan subscriber
 * and the retained start-game hook are the production ones, and all legality
 * (sickness, attack declaration, mana, casting) stays in the Rules Core.
 *
 * <p>Rules basis (accepted authority): CR 302.6, CR 508.1a, CR 103.6a and the
 * XMage BEGIN_TURN precedent recorded in
 * {@code docs/af07_final_closure_20261002/PLACEMENT_POINT_ADJUDICATION.md}.
 * This placement is a qualification-bridge behavior, not unmodified
 * Forge-native game-start behavior: Forge's own {@code initVariantsZones}
 * permanents plus the {@code getTurn() > 0} guard stay summoning-sick on turn
 * one.
 */
public class G1R1TurnBeganBootstrapTest {

    private static final String[] SEATS = { "p1", "p2", "p3", "p4" };

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @AfterMethod
    public void clearFaults() {
        BridgeSession.scenarioBootstrapFaultForTests = null;
    }

    // ---- scenario game harness (production launch path, no shortcuts) ----

    private static JsonObject placement(String card, String controller, boolean tapped) {
        final JsonObject entry = new JsonObject();
        entry.addProperty("card", card);
        entry.addProperty("controller", controller);
        entry.addProperty("owner", controller);
        entry.addProperty("tapped", tapped);
        return entry;
    }

    private static JsonObject placementWithCounters(String card, String controller, boolean tapped,
            String counterName, int amount) {
        final JsonObject entry = placement(card, controller, tapped);
        final JsonObject counters = new JsonObject();
        counters.addProperty(counterName, amount);
        entry.add("counters", counters);
        return entry;
    }

    /** A neutral scenario carrying exactly the given battlefield entries. */
    private static JsonObject neutral(JsonArray battlefield) {
        final JsonObject neutral = new JsonObject();
        neutral.add("battlefield", battlefield);
        neutral.add("hands", new JsonObject());
        neutral.add("players", new JsonArray());
        return neutral;
    }

    private static ScenarioBootstrap.Plan planFor(JsonArray battlefield) {
        return ScenarioBootstrap.parse(neutral(battlefield));
    }

    /** Reads a projected battlefield-detail field from a seat's own state read. */
    private static boolean readbackFlag(BridgeSession session, String playerId, String observer,
            String cardName, String field) {
        final JsonObject state = StateProjection.gameState(session, observer);
        for (JsonElement element : state.getAsJsonArray("players")) {
            final JsonObject player = element.getAsJsonObject();
            if (!player.get("player_id").getAsString().equals(playerId)) {
                continue;
            }
            final JsonObject zones = player.getAsJsonObject("zones");
            for (JsonElement detail : zones.getAsJsonArray("battlefield_details")) {
                final JsonObject entry = detail.getAsJsonObject();
                if (cardName.equals(entry.get("name").getAsString())) {
                    Assert.assertTrue(entry.has(field), "missing readback field " + field);
                    return entry.get(field).getAsBoolean();
                }
            }
        }
        throw new AssertionError("no " + field + " readback for " + cardName + " on " + playerId);
    }

    private static String stateStep(BridgeSession session) {
        final JsonObject state = StateProjection.gameState(session, "p1");
        return state.get("step").isJsonNull() ? null : state.get("step").getAsString();
    }

    /** Content signature of the offered options; order-independent. */
    private static List<String> optionSignature(DecisionFrame frame) {
        final List<String> signature = new ArrayList<>();
        for (DecisionFrame.Option option : frame.options) {
            signature.add(option.actionType + "|" + option.label + "|" + option.sourceCardName
                    + "|pass=" + option.isPass + "|keep=" + option.isKeep);
        }
        Collections.sort(signature);
        return signature;
    }

    private static void expectIllegalState(Runnable action, String fragment) {
        try {
            action.run();
            throw new AssertionError("expected IllegalStateException containing: " + fragment);
        } catch (IllegalStateException expected) {
            final String message = String.valueOf(expected.getMessage());
            Assert.assertTrue(message.contains(fragment),
                    "expected fragment [" + fragment + "] in [" + message + "]");
        }
    }

    /**
     * Answers mulligans and the starting-player choice until the game thread
     * dies from a failed bootstrap; asserts no priority frame was ever parked
     * and returns the fail reason.
     */
    private static String driveStartExpectingFailure(BridgeSession session) {
        final long deadline = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < deadline && !session.isTerminal()) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 5000);
            if (session.isTerminal()) {
                break;
            }
            if (frame == null) {
                continue;
            }
            if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                final DecisionFrame.Option keep = find(frame, o -> o.isKeep, "keep");
                // A submission can lose the race against the game thread already
                // failing in the hook; the terminal assertions below decide.
                if (!session.submit(frame.actorPlayerId, keep.optionId, keep.actionType,
                        frame.revision).applied) {
                    break;
                }
            } else if (frame.kind == DecisionFrame.Kind.STARTING_PLAYER) {
                final DecisionFrame.Option starter = find(frame,
                        o -> "p1".equals(o.sourceCardName), "p1 starter");
                if (!session.submit(frame.actorPlayerId, starter.optionId, starter.actionType,
                        frame.revision).applied) {
                    break;
                }
            } else {
                throw new AssertionError("frame parked despite failed bootstrap: " + frame.kind
                        + " for " + frame.actorPlayerId);
            }
        }
        Assert.assertTrue(session.isTerminal(), "session never reached terminal after failure");
        Assert.assertEquals(session.getStatus(), BridgeSession.Status.FAILED,
                "expected FAILED, reason: " + session.getFailReason());
        return session.getFailReason();
    }

    /** Records the engine's own tap/untap events for the scenario permanents. */
    private static final class TapEventRecorder {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @com.google.common.eventbus.Subscribe
        public void onTapped(forge.game.event.GameEventCardTapped event) {
            if (event != null && event.card() != null) {
                events.add(event.card().getName() + ":" + event.tapped());
            }
        }

        List<String> snapshot() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
    }

    /** Builds, seeds and starts a real 4P Commander game with a scenario plan. */
    private static BridgeSession scenarioGame(String gameId, long seed, JsonArray battlefield,
            JsonObject hands) {
        return scenarioGame(gameId, seed, battlefield, hands, null);
    }

    /**
     * Builds, seeds and starts a real 4P Commander game with a scenario plan.
     * An optional recorder observes the engine's own tap events from before the
     * first turn begins.
     */
    private static BridgeSession scenarioGame(String gameId, long seed, JsonArray battlefield,
            JsonObject hands, TapEventRecorder recorder) {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        for (String seat : SEATS) {
            if (!hands.has(seat)) {
                hands.add(seat, new JsonArray());
            }
        }
        final JsonObject neutral = new JsonObject();
        neutral.add("battlefield", battlefield);
        neutral.add("hands", hands);
        neutral.add("players", new JsonArray());
        final JsonObject scenario = new JsonObject();
        scenario.add("neutral_initial_state", neutral);
        final StringBuilder deckArray = new StringBuilder();
        for (int i = 0; i < handles.size(); i++) {
            if (i > 0) {
                deckArray.append(',');
            }
            deckArray.append('"').append(handles.get(i)).append('"');
        }
        final JsonObject created = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"g1-create-" + gameId + "\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"" + gameId + "\",\"format\":\"commander\","
                        + "\"seed\":" + seed + ",\"deck_handles\":[" + deckArray + "],"
                        + "\"scenario\":" + scenario + "}}}");
        BridgeTestSupport.assertOk(created);
        final BridgeSession session = engine.sessionsForTests().get(gameId);
        if (recorder != null) {
            session.getGame().subscribeToEvents(recorder);
        }
        BridgeTestSupport.startGame(engine, gameId);
        return engine.sessionsForTests().get(gameId);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static DecisionFrame.Option find(DecisionFrame frame,
            java.util.function.Predicate<DecisionFrame.Option> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
            seen.append('[').append(option.actionType).append('|').append(option.label)
                    .append('|').append(option.sourceCardName).append(']');
        }
        throw new AssertionError(what + " not offered; kind=" + frame.kind + " actor="
                + frame.actorPlayerId + " status=" + frame.status + " reason=" + frame.reason
                + " options=" + seen);
    }

    private static boolean any(DecisionFrame frame,
            java.util.function.Predicate<DecisionFrame.Option> test) {
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return true;
            }
        }
        return false;
    }

    private static Card battlefieldCard(BridgeSession session, String playerId, String name) {
        final Player player = session.playerById(playerId);
        Assert.assertNotNull(player, "no such player " + playerId);
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(card.getName())) {
                return card;
            }
        }
        throw new AssertionError(name + " is not on " + playerId + "'s battlefield");
    }

    /** p2's first engine-offered priority frame on p1's turn one. */
    private static DecisionFrame p2PriorityOnTurnOne(BridgeSession session) {
        final DecisionFrame p1Priority = BridgeTestSupport.driveStartToPriority(session, "p1",
                120000);
        Assert.assertEquals(p1Priority.kind, DecisionFrame.Kind.PRIORITY);
        final DecisionFrame p1Main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 60);
        Assert.assertNotNull(p1Main, "p1 never reached its main phase");
        submit(session, p1Main, find(p1Main, o -> o.isPass, "pass"));
        for (int i = 0; i < 40; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 30000);
            Assert.assertNotNull(frame, "no frame while awaiting p2 priority");
            Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + frame.kind + " " + frame.reason);
            if (frame.kind == DecisionFrame.Kind.PRIORITY && "p2".equals(frame.actorPlayerId)) {
                return frame;
            }
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId
                    + " before p2 priority");
        }
        throw new AssertionError("p2 never held priority on p1's turn 1");
    }

    /**
     * Passes supported frames until the requested player is at declare
     * attackers on turn one. The engine does not park a declare-attackers frame
     * when no legal attacker exists ({@code canAttack.isEmpty()} returns before
     * parking), so reaching turn two without one is itself the defect this
     * fails on.
     */
    private static DecisionFrame driveToAttackersTurnOne(BridgeSession session, String actor) {
        for (int i = 0; i < 300; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 30000);
            Assert.assertNotNull(frame, "no frame while driving to attackers");
            Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + frame.kind + " " + frame.reason);
            if (session.getGame().getPhaseHandler().getTurn() > 1) {
                throw new AssertionError("declare-attackers was never offered on turn 1: "
                        + "the engine found no legal attacker");
            }
            if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && actor.equals(frame.actorPlayerId)) {
                return frame;
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, frame, find(frame, o -> o.isPass, "pass"));
            } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, frame, BridgeTestSupport.equivalentPayment(frame));
            } else {
                throw new AssertionError("unexpected " + frame.kind + " for "
                        + frame.actorPlayerId + " while driving to attackers");
            }
        }
        throw new AssertionError("never reached declare attackers for " + actor);
    }

    /**
     * Drives all of turn one and fails if the requested player is ever offered
     * a declare-attackers frame; returns silently once turn two is reached.
     * Used by the wrong-point controls: "no legal attacker" is expressed by the
     * engine not parking the frame at all.
     */
    private static void assertNoTurnOneAttackerFrame(BridgeSession session, String actor) {
        for (int i = 0; i < 300; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 30000);
            Assert.assertNotNull(frame, "no frame while driving through turn one");
            Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + frame.kind + " " + frame.reason);
            if (session.getGame().getPhaseHandler().getTurn() > 1) {
                return;
            }
            Assert.assertFalse(frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                            && actor.equals(frame.actorPlayerId),
                    "wrong point offered a turn-one attacker: " + frame.options);
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, frame, find(frame, o -> o.isPass, "pass"));
            } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, frame, BridgeTestSupport.equivalentPayment(frame));
            } else {
                throw new AssertionError("unexpected " + frame.kind + " for "
                        + frame.actorPlayerId + " while driving through turn one");
            }
        }
        throw new AssertionError("turn never advanced past turn one");
    }

    // ---- C4: non-vacuous required controls ----

    /**
     * C4(b). Kills the defect "the active seat's appropriately placed creature
     * is left summoning-sick on turn 1" (the pre-G1-R1 placement point): the
     * scenario-placed Runeclaw Bear must be legally declarable as an attacker
     * on p1's first turn.
     */
    @Test(timeOut = 300000)
    public void activeSeatPlacedCreatureCanBeDeclaredAsAttackerTurnOne() {
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final BridgeSession session = scenarioGame("g1-c4b", 5611L, battlefield,
                new JsonObject());
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        final DecisionFrame attackers = driveToAttackersTurnOne(session, "p1");
        final DecisionFrame.Option attack = find(attackers,
                o -> o.label != null && o.label.contains("Runeclaw Bear")
                        && o.label.contains("-> p"),
                "scenario-placed Runeclaw Bear attack");
        Assert.assertFalse(attack.label.contains("-> p1"), "cannot attack itself");
        session.shutdown(5000);
    }

    /**
     * C4(a). Kills the defect "a non-active seat's placed creature receives
     * turn-control readiness anyway". On p1's turn 1, p2's scenario-placed
     * Llanowar Elves is not continuously controlled since p2's most recent turn
     * began (it has never had one): it must not be offered as a {T} ability
     * while p2 holds priority, and native readback must say so.
     */
    @Test(timeOut = 300000)
    public void nonActiveSeatPlacedCreatureCannotTapTurnOne() {
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Llanowar Elves", "p2", false));
        final BridgeSession session = scenarioGame("g1-c4a", 5612L, battlefield,
                new JsonObject());
        // Hand priority to p2 and inspect its engine-offered option set on p1's
        // turn 1.
        final DecisionFrame p2Frame = p2PriorityOnTurnOne(session);
        Assert.assertEquals(session.getGame().getPhaseHandler().getTurn(), 1);
        Assert.assertEquals(session.getGame().getPhaseHandler().getPlayerTurn().getId(),
                session.getGame().getPlayers().get(0).getId());
        Assert.assertFalse(any(p2Frame, o -> "activate_ability".equals(o.actionType)
                        && "Llanowar Elves".equals(o.sourceCardName)),
                "summoning-sick Elves must not be offered as a {T} ability");
        final Card elves = battlefieldCard(session, "p2", "Llanowar Elves");
        Assert.assertTrue(elves.isFirstTurnControlled(),
                "native first-turn-control flag must show not controlled since turn began");
        session.shutdown(5000);
    }

    /**
     * C4(c). Kills the defect "clearing sickness globally so combat tests go
     * green". The Llanowar Elves the active player actually casts on turn 1 is
     * a new object and cannot attack, while the scenario-placed Runeclaw Bear
     * in the same game can: the discrimination is per object and per placement
     * point.
     */
    @Test(timeOut = 300000)
    public void activeSeatCreatureCastTurnOneCannotAttack() {
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Forest", "p1", false));
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final JsonObject hands = new JsonObject();
        final JsonArray hand = new JsonArray();
        hand.add("Llanowar Elves");
        hands.add("p1", hand);
        final BridgeSession session = scenarioGame("g1-c4c", 5613L, battlefield, hands);
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        final DecisionFrame main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 60);
        Assert.assertNotNull(main, "p1 never reached its main phase");
        // Tap the Forest, then cast the Elf from hand (real cast, real cost).
        final DecisionFrame.Option tapForest = find(main,
                o -> "activate_ability".equals(o.actionType) && "Forest".equals(o.sourceCardName),
                "Forest mana ability");
        submit(session, main, tapForest);
        final DecisionFrame castFrame = BridgeTestSupport.awaitFrame(session, 30000);
        Assert.assertNotNull(castFrame, "no frame after tapping Forest");
        final DecisionFrame.Option cast = find(castFrame,
                o -> "cast_spell".equals(o.actionType) && "Llanowar Elves".equals(o.sourceCardName),
                "Llanowar Elves cast");
        submit(session, castFrame, cast);
        final DecisionFrame attackers = driveToAttackersTurnOne(session, "p1");
        Assert.assertTrue(any(attackers, o -> o.label != null
                        && o.label.contains("Runeclaw Bear") && o.label.contains("-> p")),
                "the scenario-placed Bear must still be attack-eligible: " + attackers.options);
        Assert.assertFalse(any(attackers, o -> o.label != null
                        && o.label.contains("Llanowar Elves") && o.label.contains("-> p")),
                "the creature cast this turn must not be attack-eligible: " + attackers.options);
        final Card castElves = battlefieldCard(session, "p1", "Llanowar Elves");
        Assert.assertTrue(castElves.isFirstTurnControlled(),
                "a turn-1 cast creature is a new object, sick by native semantics");
        session.shutdown(5000);
    }

    // ---- C3: engine-native checkpoint readback ----

    /**
     * C3. Kills the defect "readiness is laundered by assuming attack
     * eligibility": the projected checkpoint exposes the engine-native
     * first-turn-control flag ({@code isFirstTurnControlled}, not
     * {@code hasSickness()}) per permanent, true only for the turn-one active
     * seat's placed permanents.
     */
    @Test(timeOut = 300000)
    public void controlledSinceTurnBeganIsEngineNative() {
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        battlefield.add(placement("Llanowar Elves", "p2", false));
        final BridgeSession session = scenarioGame("g1-c3", 5614L, battlefield,
                new JsonObject());
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        Assert.assertTrue(readbackFlag(session, "p1", "p1", "Runeclaw Bear",
                        "controlled_since_turn_began"),
                "the active seat's placed permanent is controlled since that turn began");
        Assert.assertFalse(readbackFlag(session, "p2", "p1", "Llanowar Elves",
                        "controlled_since_turn_began"),
                "a non-active seat's placed permanent is not");
        Assert.assertFalse(battlefieldCard(session, "p1", "Runeclaw Bear")
                .isFirstTurnControlled());
        Assert.assertTrue(battlefieldCard(session, "p2", "Llanowar Elves")
                .isFirstTurnControlled());
        session.shutdown(5000);
    }

    // ---- C1: fail-closed controls ----

    /**
     * Control 1 + 12. Kills "a missing subscriber invocation still yields a
     * usable game": with the subscriber never registered, the retained hook
     * must throw and the session must FAIL before any priority frame.
     */
    @Test(timeOut = 300000)
    public void subscriberNeverFiresFailsClosed() {
        BridgeSession.scenarioBootstrapFaultForTests = "never";
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final BridgeSession session = scenarioGame("g1-never", 5615L, battlefield,
                new JsonObject());
        Assert.assertTrue(driveStartExpectingFailure(session)
                        .contains("did not run exactly once"),
                "missing invocation must refuse continuation: " + session.getFailReason());
        session.shutdown(1000);
    }

    /**
     * Control 3 + 12. Kills "a throwing subscriber is swallowed by the event
     * bus and the game continues": the failure must be recorded on the session
     * and the retained hook must refuse continuation.
     */
    @Test(timeOut = 300000)
    public void subscriberThrowsFailsClosed() {
        BridgeSession.scenarioBootstrapFaultForTests = "throw";
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final BridgeSession session = scenarioGame("g1-throw", 5616L, battlefield,
                new JsonObject());
        Assert.assertTrue(driveStartExpectingFailure(session)
                        .contains("injected scenario bootstrap failure"),
                "throwing subscriber must refuse continuation: " + session.getFailReason());
        session.shutdown(1000);
    }

    /**
     * Control 2 + 12. Kills "a duplicate invocation is accepted": a second
     * turn-one handler run must fail closed and the retained hook must refuse.
     */
    @Test(timeOut = 300000)
    public void subscriberFiresTwiceFailsClosed() {
        BridgeSession.scenarioBootstrapFaultForTests = "double";
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final BridgeSession session = scenarioGame("g1-double", 5617L, battlefield,
                new JsonObject());
        Assert.assertTrue(driveStartExpectingFailure(session)
                        .contains("invoked more than once"),
                "duplicate invocation must refuse continuation: " + session.getFailReason());
        session.shutdown(1000);
    }

    /**
     * Control 4 + 12. Kills "the latch is satisfied by an event from another
     * game": the intended game's active player is the binding, and the
     * retained hook refuses continuation after the recorded failure.
     */
    @Test(timeOut = 120000)
    public void wrongGameIdentityFailsClosed() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("g1-wrong-game");
        final BridgeSession session = constructed.session;
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        session.setScenarioPlan(planFor(battlefield));
        constructed.game.getPhaseHandler().setPlayerTurn(constructed.game.getPlayers().get(0));
        final BridgeTestSupport.ConstructedGame foreign =
                BridgeTestSupport.buildConstructedGame("g1-foreign-game");
        final PlayerView foreignView = PlayerView.get(foreign.game.getPlayers().get(0));
        session.installScenarioBootstrap(constructed.game, session.getScenarioPlan());
        constructed.game.fireEvent(new GameEventTurnBegan(foreignView, 1));
        Assert.assertEquals(session.scenarioBootstrapInvocationsForTests(), 0,
                "a foreign game event must not consume the latch");
        Assert.assertNotNull(session.scenarioBootstrapFailureForTests());
        expectIllegalState(() -> session.scenarioStartGameHook(constructed.game,
                session.getScenarioPlan()).run(), "does not belong");
    }

    /**
     * Control 5. Kills "the latch is keyed to any turn-began event": a turn-two
     * event is a legitimate native event, never a bootstrap, so it leaves the
     * latch empty and the retained hook refuses.
     */
    @Test(timeOut = 120000)
    public void wrongTurnIdentityNeverSatisfiesTheLatch() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("g1-wrong-turn");
        final BridgeSession session = constructed.session;
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final ScenarioBootstrap.Plan plan = planFor(battlefield);
        session.setScenarioPlan(plan);
        session.installScenarioBootstrap(constructed.game, plan);
        constructed.game.fireEvent(new GameEventTurnBegan(
                PlayerView.get(constructed.game.getPlayers().get(0)), 2));
        Assert.assertEquals(session.scenarioBootstrapInvocationsForTests(), 0,
                "a turn-two event must not consume the latch");
        Assert.assertNull(session.scenarioBootstrapFailureForTests(),
                "later turns are native, not failures");
        expectIllegalState(() -> session.scenarioStartGameHook(constructed.game,
                session.getScenarioPlan()).run(), "did not run exactly once");
    }

    /**
     * Positive latch control against a fail-always implementation: the
     * intended game's turn-one event satisfies the latch exactly once, places
     * the plan, and the retained hook then runs without refusing.
     */
    @Test(timeOut = 120000)
    public void intendedTurnOneEventSatisfiesTheLatch() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("g1-latch-ok");
        final BridgeSession session = constructed.session;
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        battlefield.add(placement("Forest", "p1", false));
        final ScenarioBootstrap.Plan plan = planFor(battlefield);
        session.setScenarioPlan(plan);
        final Player p1 = constructed.game.getPlayers().get(0);
        constructed.game.getPhaseHandler().setPlayerTurn(p1);
        session.installScenarioBootstrap(constructed.game, plan);
        constructed.game.fireEvent(new GameEventTurnBegan(PlayerView.get(p1), 1));
        Assert.assertEquals(session.scenarioBootstrapInvocationsForTests(), 1);
        Assert.assertNull(session.scenarioBootstrapFailureForTests());
        Assert.assertEquals(session.scenarioPlacedCardsForTests().size(),
                plan.battlefield.size(), "every plan placement was made at TurnBegan");
        session.requireScenarioBootstrapCompleted();
    }

    /** Control 12. Kills "the hook proceeds when no bootstrap ever ran". */
    @Test(timeOut = 120000)
    public void hookRefusesContinuationWithoutBootstrap() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("g1-no-bootstrap");
        final BridgeSession session = constructed.session;
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        session.setScenarioPlan(planFor(battlefield));
        expectIllegalState(() -> session.scenarioStartGameHook(constructed.game,
                session.getScenarioPlan()).run(), "did not run exactly once");
    }

    // ---- C2: placement-point mutation controls ----

    /**
     * Control 6/9. Kills "place at the retained post-untap hook" (the
     * pre-G1-R1 point): the legacy point leaves the active seat's placed
     * creature summoning-sick, so the engine never parks a turn-one
     * declare-attackers frame. This is the same defect the positive C4(b)
     * control fails on against the pre-change head.
     */
    @Test(timeOut = 300000)
    public void latePlacementLeavesActiveSeatCreatureUnableToAttack() {
        BridgeSession.scenarioBootstrapFaultForTests = "late";
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final BridgeSession session = scenarioGame("g1-late", 5618L, battlefield,
                new JsonObject());
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        Assert.assertTrue(battlefieldCard(session, "p1", "Runeclaw Bear")
                .isFirstTurnControlled(), "the legacy placement point leaves it sick");
        assertNoTurnOneAttackerFrame(session, "p1");
        session.shutdown(5000);
    }

    /**
     * Controls 7 + event fabrication. The approved point places untapped and
     * taps silently after the untap step: the requested state is exact and no
     * {@code GameEventCardTapped} is fabricated for the permanent.
     */
    @Test(timeOut = 300000)
    public void requestedTappedStateExactAndEventless() {
        final TapEventRecorder recorder = new TapEventRecorder();
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Forest", "p1", true));
        final BridgeSession session = scenarioGame("g1-tapped", 5619L, battlefield,
                new JsonObject(), recorder);
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        Assert.assertTrue(battlefieldCard(session, "p1", "Forest").isTapped(),
                "requested tapped state must be exact");
        Assert.assertTrue(readbackFlag(session, "p1", "p1", "Forest", "tapped"));
        Assert.assertFalse(recorder.snapshot().contains("Forest:true"),
                "the silent post-untap tap must not fire a tap event: "
                        + recorder.snapshot());
        Assert.assertFalse(recorder.snapshot().contains("Forest:false"),
                "the untap step must not untap a never-tapped permanent: "
                        + recorder.snapshot());
        session.shutdown(5000);
    }

    /**
     * Control 7 (mutant). Kills "place requested permanents tapped before the
     * untap step": the untap step then untaps the active seat's permanent and
     * the public history gains a fabricated untap event.
     */
    @Test(timeOut = 300000)
    public void tappedAtBeginMutantFabricatesAnUntapEvent() {
        BridgeSession.scenarioBootstrapFaultForTests = "tapped_at_begin";
        final TapEventRecorder recorder = new TapEventRecorder();
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Forest", "p1", true));
        final BridgeSession session = scenarioGame("g1-tapped-mutant", 5619L, battlefield,
                new JsonObject(), recorder);
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        Assert.assertTrue(recorder.snapshot().contains("Forest:false"),
                "tapping before untap must fabricate an untap event: " + recorder.snapshot());
        session.shutdown(5000);
    }

    /**
     * Control 8. Counters are added exactly once, with no events, and are not
     * re-applied by the post-untap hook.
     */
    @Test(timeOut = 300000)
    public void countersAppliedExactlyOnceAtPlacement() {
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placementWithCounters("Runeclaw Bear", "p1", false, "P1P1", 2));
        final BridgeSession session = scenarioGame("g1-counters", 5622L, battlefield,
                new JsonObject());
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        Assert.assertEquals(battlefieldCard(session, "p1", "Runeclaw Bear")
                .getCounters(CounterEnumType.P1P1), 2, "counters exactly once");
        session.shutdown(5000);
    }

    /**
     * Control 8 (mutant). Kills "counters are re-applied after initial
     * placement": the additive native API doubles them.
     */
    @Test(timeOut = 300000)
    public void countersTwiceMutantDoublesCounters() {
        BridgeSession.scenarioBootstrapFaultForTests = "counters_twice";
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placementWithCounters("Runeclaw Bear", "p1", false, "P1P1", 2));
        final BridgeSession session = scenarioGame("g1-counters-mutant", 5622L, battlefield,
                new JsonObject());
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        Assert.assertEquals(battlefieldCard(session, "p1", "Runeclaw Bear")
                .getCounters(CounterEnumType.P1P1), 4,
                "re-applied counters must visibly double");
        session.shutdown(5000);
    }

    /**
     * Control 9 (mutant). Kills "the active seat's permanent is re-marked sick
     * after placement": the engine then parks no turn-one attacker frame.
     */
    @Test(timeOut = 300000)
    public void sickActiveMutantLosesTurnOneAttackEligibility() {
        BridgeSession.scenarioBootstrapFaultForTests = "sick_active";
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final BridgeSession session = scenarioGame("g1-sick-active", 5623L, battlefield,
                new JsonObject());
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        assertNoTurnOneAttackerFrame(session, "p1");
        session.shutdown(5000);
    }

    /**
     * Control 10 (mutant). Kills "every placed permanent receives turn-control
     * readiness": p2's Elves then becomes a legal {T} activation on p1's turn.
     */
    @Test(timeOut = 300000)
    public void readyAllMutantLetsNonActiveSeatTapTurnOne() {
        BridgeSession.scenarioBootstrapFaultForTests = "ready_all";
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Llanowar Elves", "p2", false));
        final BridgeSession session = scenarioGame("g1-ready-all", 5624L, battlefield,
                new JsonObject());
        final DecisionFrame p2Frame = p2PriorityOnTurnOne(session);
        Assert.assertTrue(any(p2Frame, o -> "activate_ability".equals(o.actionType)
                        && "Llanowar Elves".equals(o.sourceCardName)),
                "the mutant readiness must offer the {T} ability: " + p2Frame.options);
        session.shutdown(5000);
    }

    /**
     * Control 11 (mutant). Kills "clear sickness on every battlefield entry to
     * make combat tests green": the creature the active player casts on turn
     * one becomes attack-eligible.
     */
    @Test(timeOut = 300000)
    public void readyCastsMutantMakesTurnOneCastAttackEligible() {
        BridgeSession.scenarioBootstrapFaultForTests = "ready_casts";
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Forest", "p1", false));
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final JsonObject hands = new JsonObject();
        final JsonArray hand = new JsonArray();
        hand.add("Llanowar Elves");
        hands.add("p1", hand);
        final BridgeSession session = scenarioGame("g1-ready-casts", 5625L, battlefield, hands);
        BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        final DecisionFrame main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 60);
        Assert.assertNotNull(main, "p1 never reached its main phase");
        submit(session, main, find(main,
                o -> "activate_ability".equals(o.actionType) && "Forest".equals(o.sourceCardName),
                "Forest mana ability"));
        final DecisionFrame castFrame = BridgeTestSupport.awaitFrame(session, 30000);
        Assert.assertNotNull(castFrame, "no frame after tapping Forest");
        submit(session, castFrame, find(castFrame,
                o -> "cast_spell".equals(o.actionType)
                        && "Llanowar Elves".equals(o.sourceCardName),
                "Llanowar Elves cast"));
        final DecisionFrame attackers = driveToAttackersTurnOne(session, "p1");
        Assert.assertTrue(any(attackers, o -> o.label != null
                        && o.label.contains("Llanowar Elves") && o.label.contains("-> p")),
                "the mutant must make the cast creature attack-eligible: "
                        + optionSignature(attackers));
        session.shutdown(5000);
    }

    // ---- C5: the retained initial priority frame ----

    /**
     * Control 13. Kills "the changed implementation removes or changes the
     * required initial priority decision frame": the retained hook's
     * withheld-priority frame is still parked at the first-turn untap step and
     * its offered options are identical between the approved placement point
     * and the legacy post-untap point.
     */
    @Test(timeOut = 300000)
    public void initialPriorityDecisionFramePreserved() {
        final JsonArray battlefield = new JsonArray();
        battlefield.add(placement("Forest", "p1", false));
        battlefield.add(placement("Runeclaw Bear", "p1", false));
        final JsonObject hands = new JsonObject();
        final JsonArray hand = new JsonArray();
        hand.add("Llanowar Elves");
        hands.add("p1", hand);

        final BridgeSession approved = scenarioGame("g1-frame-approved", 5621L, battlefield,
                hands);
        final DecisionFrame approvedFirst = BridgeTestSupport.driveStartToPriority(approved,
                "p1", 120000);
        Assert.assertEquals(approvedFirst.kind, DecisionFrame.Kind.PRIORITY);
        Assert.assertEquals(approvedFirst.actorPlayerId, "p1");
        Assert.assertEquals(stateStep(approved), "UNTAP",
                "the hook's withheld-priority frame stays at the first-turn untap step");
        final List<String> signature = optionSignature(approvedFirst);
        Assert.assertTrue(any(approvedFirst, o -> o.isPass), "pass must be offered");
        approved.shutdown(5000);

        BridgeSession.scenarioBootstrapFaultForTests = "late";
        final BridgeSession legacy = scenarioGame("g1-frame-legacy", 5621L, battlefield, hands);
        final DecisionFrame legacyFirst = BridgeTestSupport.driveStartToPriority(legacy, "p1",
                120000);
        Assert.assertEquals(legacyFirst.kind, approvedFirst.kind);
        Assert.assertEquals(legacyFirst.actorPlayerId, approvedFirst.actorPlayerId);
        Assert.assertEquals(optionSignature(legacyFirst), signature,
                "placement timing must not change unrelated offered options");
        legacy.shutdown(5000);
    }
}
