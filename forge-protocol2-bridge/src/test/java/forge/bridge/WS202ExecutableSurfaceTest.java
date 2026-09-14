package forge.bridge;

import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * WS202 executable-surface evidence: every newly framed decision family driven
 * through real Forge Rules execution with actual cards. Each test parks a real
 * native decision, records actor/revision/authoritative options, submits an
 * offered option from the exact frame, and asserts native before/after state
 * plus negative controls where noted. No mocks, no AI pilot, no fabrication:
 * the engine owns legality, costs, combat, triggers and randomness throughout.
 *
 * <p>Seat map in constructed games: seat N is principal "p{N+1}".
 */
public class WS202ExecutableSurfaceTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    // ---- scripted drivers (test pilot choices are explicit and recorded) ----

    private static DecisionFrame awaitNext(BridgeSession session, long lastRevision,
            long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.revision != lastRevision) {
                return frame;
            }
            if (session.isTerminal()) {
                return session.getCurrentFrame();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return session.getCurrentFrame();
            }
        }
        return session.getCurrentFrame();
    }

    private static DecisionFrame.Option pickOption(DecisionFrame frame,
            Predicate<DecisionFrame.Option> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
            seen.append('[').append(option.actionType).append('|')
                    .append(option.label).append(']');
        }
        throw new AssertionError(
                what + " not offered; kind=" + frame.kind + " options=" + seen);
    }

    private static BridgeSession.SubmitOutcome submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage
                        + " session=" + session.getStatus() + " fail=" + session.getFailReason());
        return outcome;
    }

    private static DecisionFrame passOnce(BridgeSession session, long lastRevision) {
        final DecisionFrame frame = awaitNext(session, lastRevision, 15000);
        Assert.assertNotNull(frame, "no frame parked");
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.PRIORITY);
        Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED);
        submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
        return frame;
    }

    /**
     * Passes priority until the parked frame matches kind/actor (and, when
     * non-null, a phase-name fragment and a minimum turn number). Answers
     * MANA_PAYMENT pool ties with the first offered pool option (an explicit
     * pilot tie-break, recorded in the audit) and passes SUPPORTED priorities.
     * Bystander combat declarations (another actor's attackers/blockers step)
     * are declined with the empty option so multi-turn drives stay on rails.
     * Fails loudly on any other unexpected frame kind. Tap-source offers are
     * never auto-answered here: mid-payment taps are scripted explicitly.
     */
    private static DecisionFrame driveTo(BridgeSession session, String actorId,
            DecisionFrame.Kind kind, String phaseFragment, int budget) {
        return driveToTurn(session, actorId, kind, phaseFragment, -1, budget);
    }

    private static DecisionFrame driveToTurn(BridgeSession session, String actorId,
            DecisionFrame.Kind kind, String phaseFragment, int minTurn, int budget) {
        long lastRevision = -1;
        final DecisionFrame current = session.getCurrentFrame();
        if (current != null) {
            lastRevision = current.revision;
            if (frameMatches(session, current, actorId, kind, phaseFragment, minTurn)) {
                return current;
            }
        }
        for (int i = 0; i < budget; i++) {
            final DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind + " for "
                    + actorId);
            lastRevision = frame.revision;
            if (frameMatches(session, frame, actorId, kind, phaseFragment, minTurn)) {
                return frame;
            }
            if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                BridgeTestSupport.submitKeep(session, frame);
                continue;
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                throw new AssertionError("blocked by " + frame.kind + " " + frame.status + " "
                        + frame.reason + " for " + frame.actorPlayerId + " while driving to "
                        + kind + " for " + actorId);
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
                continue;
            }
            if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                final DecisionFrame.Option first = frame.options.get(0);
                if ("tap_mana_source".equals(first.actionType)) {
                    throw new AssertionError("mid-payment tap offer while driving to " + kind
                            + " for " + actorId + "; script the taps explicitly");
                }
                submit(session, frame, first);
                continue;
            }
            if ((frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS)
                    && !frame.actorPlayerId.equals(actorId)) {
                submit(session, frame, pickOption(frame,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "bystander decline"));
                continue;
            }
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId
                    + " while driving to " + kind + " for " + actorId);
        }
        throw new AssertionError("never reached " + kind + " for " + actorId);
    }

    private static boolean frameMatches(BridgeSession session, DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind, String phaseFragment, int minTurn) {
        if (frame.kind != kind || !frame.actorPlayerId.equals(actorId)
                || frame.status != DecisionFrame.Status.SUPPORTED) {
            return false;
        }
        if (minTurn >= 0) {
            try {
                if (session.getGame().getPhaseHandler().getTurn() < minTurn) {
                    return false;
                }
            } catch (Throwable t) {
                return false;
            }
        }
        if (phaseFragment == null) {
            return true;
        }
        try {
            final String phase = session.getGame().getPhaseHandler().getPhase().name();
            return phase.contains(phaseFragment);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void tapLand(BridgeSession session, String actorId, String landName) {
        // Taps land in a MAIN phase so floated mana survives until the same-phase
        // cast (pools empty across phase changes). Nested choice-mana colors
        // from the activation itself are answered green-first (explicit pilot
        // tie-break); the first following actor PRIORITY frame is left parked.
        DecisionFrame frame = driveTo(session, actorId, DecisionFrame.Kind.PRIORITY, "MAIN",
                60);
        final DecisionFrame.Option tap = pickOption(frame,
                o -> "activate_ability".equals(o.actionType) && landName.equals(o.sourceCardName),
                landName + " tap");
        submit(session, frame, tap);
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked after tap");
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.actorPlayerId.equals(actorId)) {
                return;
            }
            if (parked.kind == DecisionFrame.Kind.COLOR_CHOICE
                    && parked.actorPlayerId.equals(actorId)) {
                DecisionFrame.Option green = null;
                for (DecisionFrame.Option option : parked.options) {
                    if (option.label != null && option.label.contains("green")) {
                        green = option;
                    }
                }
                submit(session, parked, green == null ? parked.options.get(0) : green);
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " after tap");
        }
        throw new AssertionError("priority never resumed after tap");
    }

    private static int poolOf(BridgeSession session, int seat, String color) {
        final JsonObject state = StateProjection.gameState(session, "p" + (seat + 1));
        for (Object element : state.getAsJsonArray("players")) {
            final JsonObject playerState = (JsonObject) element;
            if (playerState.get("player_id").getAsString().equals("p" + (seat + 1))) {
                return playerState.getAsJsonObject("mana_pool").get(color).getAsInt();
            }
        }
        throw new AssertionError("no pool for seat " + seat);
    }

    private static List<String> battlefieldNames(BridgeSession session, int seat) {
        final List<String> names = new ArrayList<>();
        for (Card card : session.getGame().getPlayers().get(seat)
                .getCardsIn(ZoneType.Battlefield)) {
            names.add(card.getName());
        }
        return names;
    }

    private static Card findBattlefield(BridgeSession session, int seat, String name) {
        for (Card card : session.getGame().getPlayers().get(seat)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    private static BridgeSession.SubmitOutcome submitValue(BridgeSession session,
            DecisionFrame frame, long value) {
        Assert.assertTrue(frame.freeInput, "not a free-input frame");
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                DecisionFrame.FREE_INPUT_ID, frame.options.get(0).actionType, frame.revision,
                Long.valueOf(value));
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage
                        + " session=" + session.getStatus() + " fail=" + session.getFailReason());
        return outcome;
    }

    private static void assertFreeInputNegatives(BridgeSession session, DecisionFrame frame) {
        Assert.assertTrue(frame.freeInput);
        final String actionType = frame.options.get(0).actionType;
        final String other = frame.actorPlayerId.equals("p1") ? "p2" : "p1";
        final BridgeSession.SubmitOutcome wrongActor = session.submit(other,
                DecisionFrame.FREE_INPUT_ID, actionType, frame.revision, frame.inputMin);
        Assert.assertFalse(wrongActor.applied);
        Assert.assertEquals(wrongActor.errorCode, BridgeErrors.WRONG_ACTOR);
        final BridgeSession.SubmitOutcome stale = session.submit(frame.actorPlayerId,
                DecisionFrame.FREE_INPUT_ID, actionType, frame.revision - 1, frame.inputMin);
        Assert.assertFalse(stale.applied);
        Assert.assertEquals(stale.errorCode, BridgeErrors.STALE_REVISION);
        final BridgeSession.SubmitOutcome unknown = session.submit(frame.actorPlayerId,
                "opt-does-not-exist", actionType, frame.revision, frame.inputMin);
        Assert.assertFalse(unknown.applied);
        Assert.assertEquals(unknown.errorCode, BridgeErrors.UNKNOWN_OPTION);
        final BridgeSession.SubmitOutcome missing = session.submit(frame.actorPlayerId,
                DecisionFrame.FREE_INPUT_ID, actionType, frame.revision, null);
        Assert.assertFalse(missing.applied);
        Assert.assertEquals(missing.errorCode, BridgeErrors.MALFORMED_REQUEST);
        final BridgeSession.SubmitOutcome outOfRange = session.submit(frame.actorPlayerId,
                DecisionFrame.FREE_INPUT_ID, actionType, frame.revision,
                Long.valueOf(frame.inputMax + 1));
        Assert.assertFalse(outOfRange.applied);
        Assert.assertEquals(outOfRange.errorCode, BridgeErrors.UNKNOWN_OPTION);
    }

    private static void assertNegatives(BridgeSession session, DecisionFrame frame) {
        final DecisionFrame.Option first = frame.options.get(0);
        final String other = frame.actorPlayerId.equals("p1") ? "p2" : "p1";
        final BridgeSession.SubmitOutcome wrongActor = session.submit(other, first.optionId,
                first.actionType, frame.revision);
        Assert.assertFalse(wrongActor.applied);
        Assert.assertEquals(wrongActor.errorCode, BridgeErrors.WRONG_ACTOR);
        final BridgeSession.SubmitOutcome stale = session.submit(frame.actorPlayerId,
                first.optionId, first.actionType, frame.revision - 1);
        Assert.assertFalse(stale.applied);
        Assert.assertEquals(stale.errorCode, BridgeErrors.STALE_REVISION);
        final BridgeSession.SubmitOutcome unknown = session.submit(frame.actorPlayerId,
                "opt-does-not-exist", first.actionType, frame.revision);
        Assert.assertFalse(unknown.applied);
        Assert.assertEquals(unknown.errorCode, BridgeErrors.UNKNOWN_OPTION);
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        // Constructed games start with empty libraries; the turn-1 draw from an
        // empty library loses the game natively. Fill with basics like every
        // other constructed fixture so draws succeed and the game proceeds.
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    // ---- A04: Stonecoil Serpent X=3 + Hardened Scales / Doubling Season ----

    @Test(timeOut = 300000)
    public void testA04SerpentReplacementOrdering() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-a04");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Stonecoil Serpent", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Doubling Season", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Hardened Scales", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame, "never reached p1 main phase");
        tapLand(session, "p1", "Forest");
        tapLand(session, "p1", "Forest");
        tapLand(session, "p1", "Forest");
        Assert.assertEquals(poolOf(session, 0, "G"), 3);
        frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        final DecisionFrame.Option cast = pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Stonecoil Serpent".equals(o.sourceCardName),
                "Stonecoil Serpent cast");
        submit(session, frame, cast);
        // X announcement: authoritative range including scripted X=3.
        DecisionFrame xFrame = awaitNext(session, frame.revision, 15000);
        Assert.assertNotNull(xFrame);
        Assert.assertEquals(xFrame.kind, DecisionFrame.Kind.X_ANNOUNCE);
        Assert.assertEquals(xFrame.actorPlayerId, "p1");
        Assert.assertTrue(xFrame.freeInput, "unbounded X must park validated free input");
        assertFreeInputNegatives(session, xFrame);
        submitValue(session, xFrame, 3);
        // Replacement ordering: exactly the two native replacers, divergent 8/7.
        long lastRevision = xFrame.revision;
        DecisionFrame orderFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting replacement order");
            lastRevision = parked.revision;
            if (parked.kind == DecisionFrame.Kind.REPLACEMENT_ORDER
                    && parked.actorPlayerId.equals("p1")) {
                orderFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting replacement order");
        }
        Assert.assertNotNull(orderFrame, "replacement order never parked");
        Assert.assertEquals(orderFrame.options.size(), 2, "exactly two legal orders");
        assertNegatives(session, orderFrame);
        final DecisionFrame.Option scalesFirst = pickOption(orderFrame,
                o -> o.label != null && o.label.contains("plus one"),
                "Hardened Scales first");
        submit(session, orderFrame, scalesFirst);
        // Drive passes until the Serpent lands with eight counters.
        Card serpent = null;
        for (int i = 0; i < 30 && serpent == null; i++) {
            serpent = findBattlefield(session, 0, "Stonecoil Serpent");
            if (serpent != null) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertNotNull(serpent, "Serpent never entered");
        Assert.assertEquals(serpent.getCounters(CounterEnumType.P1P1), 8);
        Assert.assertEquals(serpent.getNetPower(), 8);
        Assert.assertEquals(serpent.getNetToughness(), 8);
        session.shutdown(5000);
    }

    // ---- B01: Llanowar Elves + five Soul Warden triggers ----

    @Test(timeOut = 300000)
    public void testB01TriggerOrdering() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-b01");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Llanowar Elves", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Soul Warden", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Soul Warden", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Soul Warden", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 2, "Soul Warden", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 3, "Soul Warden", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Forest");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Llanowar Elves".equals(o.sourceCardName),
                "Llanowar Elves cast"));
        // P0's two simultaneous Warden triggers: authoritative order frame.
        DecisionFrame orderFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TRIGGER_ORDER
                    && parked.actorPlayerId.equals("p1")) {
                orderFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting trigger order");
        }
        Assert.assertNotNull(orderFrame, "P0 trigger order never parked");
        Assert.assertEquals(orderFrame.options.size(), 2);
        assertNegatives(session, orderFrame);
        submit(session, orderFrame, pickOption(orderFrame,
                o -> o.label != null && o.label.indexOf("#0;") >= 0
                        && o.label.indexOf("#1;") >= 0
                        && o.label.indexOf("#0;") < o.label.indexOf("#1;"),
                "Warden-A then Warden-B"));
        // Resolve the whole stack with passes; assert APNAP life totals.
        for (int i = 0; i < 40; i++) {
            if (session.getGame().getStack().isEmpty()) {
                final DecisionFrame parked = session.getCurrentFrame();
                if (parked != null && parked.kind == DecisionFrame.Kind.PRIORITY) {
                    break;
                }
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
            if (session.getGame().getStack().isEmpty()) {
                break;
            }
        }
        Assert.assertTrue(session.getGame().getStack().isEmpty(), "stack must empty");
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 42);
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 41);
        Assert.assertEquals(session.getGame().getPlayers().get(2).getLife(), 41);
        Assert.assertEquals(session.getGame().getPlayers().get(3).getLife(), 41);
        Assert.assertNotNull(findBattlefield(session, 0, "Llanowar Elves"));
        session.shutdown(5000);
    }

    // ---- F01: Rampant Growth search ----

    @Test(timeOut = 300000)
    public void testF01RampantGrowthSearch() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-f01");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Rampant Growth", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Library);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Forest");
        tapLand(session, "p1", "Forest");
        final int libraryBefore = session.getGame().getPlayers().get(0)
                .getCardsIn(ZoneType.Library).size();
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Rampant Growth".equals(o.sourceCardName),
                "Rampant Growth cast"));
        DecisionFrame searchFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.SEARCH_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                searchFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting search");
        }
        Assert.assertNotNull(searchFrame, "search selection never parked");
        Assert.assertTrue(searchFrame.options.size() >= 1);
        assertNegatives(session, searchFrame);
        // Principal scoping: the search offer names library cards to the entitled
        // searcher only; an outsider sees no options and no names.
        final JsonObject asP2 = StateProjection.gameState(session, "p2");
        Assert.assertEquals(asP2.getAsJsonArray("legal_actions").size(), 0);
        for (DecisionFrame.Option option : searchFrame.options) {
            Assert.assertFalse(asP2.toString().contains(option.optionId));
        }
        submit(session, searchFrame, searchFrame.options.get(0));
        boolean foundTapped = false;
        for (int i = 0; i < 24 && !foundTapped; i++) {
            int forests = 0;
            boolean tapped = false;
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Forest")) {
                    forests++;
                    tapped = tapped || card.isTapped();
                }
            }
            if (forests >= 3 && tapped) {
                foundTapped = true;
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(foundTapped, "searched Forest never arrived tapped");
        Assert.assertTrue(session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Library)
                .size() < libraryBefore, "library must shrink by the search");
        session.shutdown(5000);
    }

    // ---- G04: concession ----

    @Test(timeOut = 300000)
    public void testG04Concession() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-g04");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(frame);
        Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED);
        final DecisionFrame.Option concede = pickOption(frame, o -> o.isConcede, "concede");
        assertNegatives(session, frame);
        final BridgeSession.SubmitOutcome outcome = submit(session, frame, concede);
        Assert.assertTrue(outcome.executionOk, "concession must execute");
        // Registry identity survives leave-game cleanup (800.4 removes the loser
        // from the live game list, so live-list indexing would shift).
        Assert.assertTrue(session.playerById("p1").hasLost(), "p1 must have lost");
        Assert.assertTrue(session.playerById("p1").conceded(), "loss must record concession");
        Assert.assertFalse(session.isTerminal(), "game continues for remaining players");
        final DecisionFrame next = awaitNext(session, frame.revision, 15000);
        Assert.assertNotNull(next);
        Assert.assertFalse(next.actorPlayerId.equals("p1"), "priority must move on");
        // A stale concede from the loser fails closed.
        final BridgeSession.SubmitOutcome stale = session.submit("p1", concede.optionId,
                concede.actionType, frame.revision);
        Assert.assertFalse(stale.applied);
        session.shutdown(5000);
    }

    // ---- H01 family: Clone / Humility / Runeclaw Bear ----

    private void floatIslands(BridgeSession session, int count) {
        for (int i = 0; i < count; i++) {
            tapLand(session, "p1", "Island");
        }
    }

    private DecisionFrame castOwn(BridgeSession session, String cardName) {
        final DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 30);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType) && cardName.equals(o.sourceCardName),
                cardName + " cast"));
        return frame;
    }

    /**
     * Drains post-cast response rounds and resolution: passes priorities,
     * answers pool ties, and returns the first frame of a wanted kind owned by
     * the given actor. Any other framed kind fails loudly with its identity.
     */
    private static DecisionFrame drainToKind(BridgeSession session, String actorId,
            DecisionFrame.Kind wanted, int budget) {
        for (int i = 0; i < budget; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting " + wanted);
            if (parked.kind == wanted && parked.actorPlayerId.equals(actorId)) {
                return parked;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " for "
                    + parked.actorPlayerId + " awaiting " + wanted + " for " + actorId);
        }
        throw new AssertionError("never reached " + wanted + " for " + actorId);
    }

    @Test(timeOut = 300000)
    public void testH01NoHumilityCopyOfferedAndTaken() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-h01c");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Clone", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        // A second creature gives the copy choice genuine alternatives (a lone
        // legal creature would resolve forced with no frame, which is correct
        // but evidences nothing about the COPY_CHOICE kind).
        BridgeTestSupport.addCard(constructed.game, 1, "Memnite", ZoneType.Battlefield);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        floatIslands(session, 4);
        castOwn(session, "Clone");
        // Response rounds, then the native entry-replacement flow. An optional
        // replacement confirm (apply the copy replacement?) is answered Yes
        // explicitly and recorded; the copy target itself must be Bear.
        // Either order is accepted; anything else fails loudly.
        DecisionFrame parked = null;
        for (int i = 0; i < 40; i++) {
            final DecisionFrame next = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(next, "no frame parked awaiting Clone entry");
            if ((next.kind == DecisionFrame.Kind.REPLACEMENT_CONFIRM
                    || next.kind == DecisionFrame.Kind.COPY_CHOICE)
                    && next.actorPlayerId.equals("p1")) {
                parked = next;
                break;
            }
            if (next.kind == DecisionFrame.Kind.PRIORITY
                    && next.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, next, pickOption(next, o -> o.isPass, "pass"));
                continue;
            }
            if (next.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, next, next.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + next.kind + " awaiting Clone entry");
        }
        Assert.assertNotNull(parked, "Clone entry flow never parked");
        if (parked.kind == DecisionFrame.Kind.REPLACEMENT_CONFIRM) {
            submit(session, parked, pickOption(parked,
                    o -> o.confirmValue != null && o.confirmValue, "apply replacement"));
            parked = drainToKind(session, "p1", DecisionFrame.Kind.COPY_CHOICE, 20);
        }
        Assert.assertTrue(parked.options.size() >= 2, "copy choice must offer Bear");
        assertNegatives(session, parked);
        final DecisionFrame.Option bear = pickOption(parked,
                o -> o.label != null && o.label.contains("Runeclaw Bear"), "copy Bear");
        submit(session, parked, bear);
        Card copy = null;
        for (int i = 0; i < 30 && copy == null; i++) {
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Runeclaw Bear")) {
                    copy = card;
                }
            }
            if (copy != null) {
                break;
            }
            final DecisionFrame next = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(next);
            if (next.kind == DecisionFrame.Kind.PRIORITY
                    && next.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, next, pickOption(next, o -> o.isPass, "pass"));
            } else if (next.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, next, next.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertNotNull(copy, "Bear copy never entered");
        Assert.assertEquals(copy.getNetPower(), 2);
        Assert.assertEquals(copy.getNetToughness(), 2);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testH01CloneFirstRetainsBearThroughHumility() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-h01b");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Clone", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        // A second creature gives the copy choice genuine alternatives (a lone
        // legal creature would resolve forced with no frame, which is correct
        // but evidences nothing about the COPY_CHOICE kind).
        BridgeTestSupport.addCard(constructed.game, 1, "Memnite", ZoneType.Battlefield);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 2, "Humility", ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 2, "Plains", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        floatIslands(session, 4);
        castOwn(session, "Clone");
        DecisionFrame parked = null;
        for (int i = 0; i < 40; i++) {
            final DecisionFrame next = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(next, "no frame parked awaiting Clone entry");
            if ((next.kind == DecisionFrame.Kind.REPLACEMENT_CONFIRM
                    || next.kind == DecisionFrame.Kind.COPY_CHOICE)
                    && next.actorPlayerId.equals("p1")) {
                parked = next;
                break;
            }
            if (next.kind == DecisionFrame.Kind.PRIORITY
                    && next.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, next, pickOption(next, o -> o.isPass, "pass"));
                continue;
            }
            if (next.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, next, next.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + next.kind + " awaiting Clone entry");
        }
        Assert.assertNotNull(parked, "Clone entry flow never parked");
        if (parked.kind == DecisionFrame.Kind.REPLACEMENT_CONFIRM) {
            submit(session, parked, pickOption(parked,
                    o -> o.confirmValue != null && o.confirmValue, "apply replacement"));
            parked = drainToKind(session, "p1", DecisionFrame.Kind.COPY_CHOICE, 20);
        }
        submit(session, parked, pickOption(parked,
                o -> o.label != null && o.label.contains("Runeclaw Bear"), "copy Bear"));
        // Wait for the Bear copy on p1's battlefield.
        boolean entered = false;
        for (int i = 0; i < 30 && !entered; i++) {
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Runeclaw Bear")) {
                    entered = true;
                }
            }
            if (entered) {
                break;
            }
            final DecisionFrame next = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(next);
            if (next.kind == DecisionFrame.Kind.PRIORITY
                    && next.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, next, pickOption(next, o -> o.isPass, "pass"));
            } else if (next.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, next, next.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(entered, "Bear copy never entered before Humility");
        // P3 casts Humility afterwards on its own turn; under it the copy is
        // 1/1 but stays a Bear.
        driveToTurn(session, "p3", DecisionFrame.Kind.PRIORITY, "MAIN", 3, 200);
        for (int i = 0; i < 4; i++) {
            tapLand(session, "p3", "Plains");
        }
        DecisionFrame humilityFrame = driveTo(session, "p3", DecisionFrame.Kind.PRIORITY, null,
                20);
        submit(session, humilityFrame, pickOption(humilityFrame,
                o -> "cast_spell".equals(o.actionType) && "Humility".equals(o.sourceCardName),
                "Humility cast"));
        boolean humilityOut = false;
        for (int i = 0; i < 40 && !humilityOut; i++) {
            for (Card card : session.getGame().getPlayers().get(2)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Humility")) {
                    humilityOut = true;
                }
            }
            if (humilityOut) {
                break;
            }
            final DecisionFrame next = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(next);
            if (next.kind == DecisionFrame.Kind.PRIORITY
                    && next.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, next, pickOption(next, o -> o.isPass, "pass"));
            } else if (next.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, next, next.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(humilityOut, "Humility never resolved");
        Card copy = null;
        for (Card card : session.getGame().getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Runeclaw Bear")) {
                copy = card;
            }
        }
        Assert.assertNotNull(copy, "Bear copy identity lost under Humility");
        Assert.assertEquals(copy.getNetPower(), 1);
        Assert.assertEquals(copy.getNetToughness(), 1);
        // Humility leaves via its controller's concession: Bear copy returns 2/2.
        driveTo(session, "p3", DecisionFrame.Kind.PRIORITY, null, 60);
        final DecisionFrame concedeFrame = driveTo(session, "p3", DecisionFrame.Kind.PRIORITY,
                null, 20);
        submit(session, concedeFrame, pickOption(concedeFrame, o -> o.isConcede, "concede"));
        Assert.assertTrue(session.playerById("p3").hasLost());
        Card bear = null;
        for (int i = 0; i < 30 && bear == null; i++) {
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Runeclaw Bear") && card.getNetPower() == 2
                        && card.getNetToughness() == 2) {
                    bear = card;
                }
            }
            if (bear != null) {
                break;
            }
            final DecisionFrame next = BridgeTestSupport.awaitFrame(session, 15000);
            if (next == null) {
                break;
            }
            if (next.kind == DecisionFrame.Kind.PRIORITY
                    && next.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, next, pickOption(next, o -> o.isPass, "pass"));
            } else {
                break;
            }
        }
        Assert.assertNotNull(bear, "Bear copy did not return to 2/2 after Humility left");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testH01HumilityFirstOffersNoCopy() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-h01a");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Clone", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        // A second creature gives the copy choice genuine alternatives (a lone
        // legal creature would resolve forced with no frame, which is correct
        // but evidences nothing about the COPY_CHOICE kind).
        BridgeTestSupport.addCard(constructed.game, 1, "Memnite", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 2, "Humility", ZoneType.Battlefield);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        floatIslands(session, 4);
        castOwn(session, "Clone");
        // Drain to Clone's entry: MANA ties answered, PRIORITY passed. Any
        // copy-flavored frame for p1 (COPY_CHOICE, replacement/optional confirms
        // mentioning copy) falsifies H01-A immediately: under pre-existing
        // Humility no copy decision may be offered at all (CR 614.12).
        boolean entered = false;
        long lastRevision = -1;
        {
            final DecisionFrame current = session.getCurrentFrame();
            if (current != null) {
                lastRevision = current.revision;
            }
        }
        for (int i = 0; i < 40 && !entered; i++) {
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (card.getName().equals("Clone")) {
                    entered = true;
                }
                if (card.getName().equals("Runeclaw Bear")
                        || card.getName().equals("Memnite")) {
                    throw new AssertionError(
                            "H01-A falsified: Clone entered as a copy under Humility");
                }
            }
            if (entered) {
                break;
            }
            final DecisionFrame parked = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(parked);
            lastRevision = parked.revision;
            if (parked.actorPlayerId.equals("p1")
                    && (parked.kind == DecisionFrame.Kind.COPY_CHOICE
                            || parked.kind == DecisionFrame.Kind.REPLACEMENT_CONFIRM
                            || copyFlavored(parked))) {
                throw new AssertionError(
                        "H01-A falsified: copy choice offered under Humility, kind="
                                + parked.kind);
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            }
        }
        Assert.assertTrue(entered, "Clone never entered under Humility");
        Card clone = findBattlefield(session, 0, "Clone");
        Assert.assertNotNull(clone);
        Assert.assertEquals(clone.getNetPower(), 1);
        Assert.assertEquals(clone.getNetToughness(), 1);
        // Post-Humility discriminator: Humility leaves via concession, the
        // non-copy Clone returns to printed 0/0 and dies to SBA 704.5f.
        driveTo(session, "p3", DecisionFrame.Kind.PRIORITY, null, 60);
        final DecisionFrame concedeFrame = driveTo(session, "p3", DecisionFrame.Kind.PRIORITY,
                null, 20);
        submit(session, concedeFrame, pickOption(concedeFrame, o -> o.isConcede, "concede"));
        boolean diedAsClone = false;
        for (int i = 0; i < 30 && !diedAsClone; i++) {
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Graveyard)) {
                if (card.getName().equals("Clone")) {
                    diedAsClone = true;
                }
                if (card.getName().equals("Runeclaw Bear")
                        || card.getName().equals("Memnite")) {
                    throw new AssertionError(
                            "H01-A falsified: Bear copy survived Humility leaving");
                }
            }
            if (diedAsClone) {
                break;
            }
            final DecisionFrame next = BridgeTestSupport.awaitFrame(session, 15000);
            if (next == null) {
                break;
            }
            if (next.kind == DecisionFrame.Kind.PRIORITY
                    && next.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, next, pickOption(next, o -> o.isPass, "pass"));
            } else {
                break;
            }
        }
        Assert.assertTrue(diedAsClone, "Clone did not die as 0/0 after Humility left");
        session.shutdown(5000);
    }

    private static boolean copyFlavored(DecisionFrame frame) {
        for (DecisionFrame.Option option : frame.options) {
            final String label = option.label == null ? "" : option.label.toLowerCase();
            if (label.contains("copy")) {
                return true;
            }
        }
        return false;
    }

    // ---- A03: Lightning Bolt + regeneration shield ----

    @Test(timeOut = 300000)
    public void testA03BoltRegenerationShield() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-a03");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 1, "Drudge Skeletons", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Swamp", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Lightning Bolt", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Mountain");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Lightning Bolt".equals(o.sourceCardName),
                "Lightning Bolt cast"));
        // Single-target selection park with negatives and scoping.
        DecisionFrame targetFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                targetFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Bolt target");
        }
        Assert.assertNotNull(targetFrame, "Bolt target selection never parked");
        assertNegatives(session, targetFrame);
        submit(session, targetFrame, pickOption(targetFrame,
                o -> o.label != null && o.label.contains("Drudge Skeletons"),
                "Bolt -> Skeletons"));
        // P2 responds with the regeneration activation for {B}.
        driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, null, 40);
        tapLand(session, "p2", "Swamp");
        DecisionFrame regenFrame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, null, 20);
        final DecisionFrame.Option regen = pickOption(regenFrame,
                o -> "activate_ability".equals(o.actionType), "regenerate activation");
        final BridgeSession.SubmitOutcome regenOutcome = submit(session, regenFrame, regen);
        Assert.assertTrue(regenOutcome.executionOk,
                "engine declined: " + session.getLastExecutionError());
        // Pass everything out: shield replaces destruction automatically.
        for (int i = 0; i < 40; i++) {
            boolean boltGone = true;
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Hand)) {
                if (card.getName().equals("Lightning Bolt")) {
                    boltGone = false;
                }
            }
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Stack)) {
                if (card.getName().equals("Lightning Bolt")) {
                    boltGone = false;
                }
            }
            boolean boltBuried = false;
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Graveyard)) {
                if (card.getName().equals("Lightning Bolt")) {
                    boltBuried = true;
                }
            }
            if (boltGone && boltBuried) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        final Card skeletons = findBattlefield(session, 1, "Drudge Skeletons");
        Assert.assertNotNull(skeletons, "Skeletons must stay on battlefield");
        Assert.assertTrue(skeletons.isTapped(), "shield taps the Skeletons");
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 40);
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 40);
        boolean swampTapped = false;
        for (Card card : session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Swamp") && card.isTapped()) {
                swampTapped = true;
            }
        }
        Assert.assertTrue(swampTapped, "Swamp tapped for {B}");
        session.shutdown(5000);
    }

    // ---- C01-shape: Force of Will pitch (alternate cost + exile + life) ----

    @Test(timeOut = 300000)
    public void testForcePitchAlternateCost() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-c01");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 1, "Llanowar Elves", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Fog", ZoneType.Hand);
        for (int i = 0; i < 2; i++) {
            BridgeTestSupport.addCard(constructed.game, 1, "Forest", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Force of Will", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Turn to Frog", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Ponder", ZoneType.Hand);
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        // Two p2 spells on the stack give Force a genuine two-target choice,
        // so TARGET_SELECTION parks authoritatively (a lone stack spell would
        // resolve forced with no frame, which is correct but evidences nothing
        // about the TARGET_SELECTION kind). Fog is the second spell because it
        // is instant-speed with no targets: a second creature could not be cast
        // while Elves sits on the stack.
        driveToTurn(session, "p2", DecisionFrame.Kind.PRIORITY, "MAIN", 2, 150);
        tapLand(session, "p2", "Forest");
        DecisionFrame elvesFrame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, null,
                20);
        submit(session, elvesFrame, pickOption(elvesFrame,
                o -> "cast_spell".equals(o.actionType)
                        && "Llanowar Elves".equals(o.sourceCardName),
                "Elves cast"));
        tapLand(session, "p2", "Forest");
        DecisionFrame fogFrame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, null,
                20);
        submit(session, fogFrame, pickOption(fogFrame,
                o -> "cast_spell".equals(o.actionType)
                        && "Fog".equals(o.sourceCardName),
                "Fog cast"));
        // P1 answers with Force of Will aimed at the Elves spell on the stack.
        // Engine truth: the hard cast and the pitch are distinct native
        // candidates, discriminated by their engine-owned cost text (not by a
        // later COST_SELECTION: the route is chosen at cast time).
        DecisionFrame forceFrame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 40);
        int forceRoutes = 0;
        for (DecisionFrame.Option o : forceFrame.options) {
            if ("cast_spell".equals(o.actionType)
                    && "Force of Will".equals(o.sourceCardName)) {
                forceRoutes++;
            }
        }
        Assert.assertEquals(forceRoutes, 2, "hard cast and pitch must both be offered");
        assertNegatives(session, forceFrame);
        submit(session, forceFrame, pickOption(forceFrame,
                o -> "cast_spell".equals(o.actionType)
                        && "Force of Will".equals(o.sourceCardName)
                        && o.label != null && o.label.toLowerCase().contains("life"),
                "Force pitch cast"));
        DecisionFrame targetFrame = null;
        DecisionFrame exileFrame = null;
        // Targets are chosen before costs (setupTargets precedes payCost), so
        // the pitch's pay-life confirm and hidden-zone exile selection park in
        // this same loop right after the target.
        for (int i = 0; i < 40; i++) {
            if (targetFrame != null && exileFrame != null) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                if (targetFrame == null) {
                    assertNegatives(session, parked);
                }
                targetFrame = parked;
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("Llanowar Elves"),
                        "Force -> Elves spell"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.COST_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                boolean confirmOnly = true;
                for (DecisionFrame.Option o : parked.options) {
                    if (o.confirmValue == null) {
                        confirmOnly = false;
                        break;
                    }
                }
                if (confirmOnly) {
                    submit(session, parked, pickOption(parked,
                            o -> o.confirmValue != null && o.confirmValue, "pay life"));
                    continue;
                }
                if (exileFrame == null) {
                    assertNegatives(session, parked);
                }
                exileFrame = parked;
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("Turn to Frog"),
                        "pitch Frog"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " in Force flow");
        }
        Assert.assertNotNull(targetFrame, "Force never asked for its stack target");
        Assert.assertEquals(targetFrame.options.size(), 2, "Elves and Fog must both be offered");
        Assert.assertNotNull(exileFrame, "pitch exile selection never parked");
        // Frog and Ponder singletons plus the engine-allowed Decline route.
        Assert.assertEquals(exileFrame.options.size(), 3, "Frog, Ponder and Decline offered");
        // Resolve out: the targeted Elves is countered to p2's graveyard; Force
        // reaches p1's graveyard, Frog is exiled, p1 sits at 39.
        for (int i = 0; i < 40; i++) {
            boolean elvesBuried = false;
            for (Card card : session.getGame().getPlayers().get(1)
                    .getCardsIn(ZoneType.Graveyard)) {
                if (card.getName().equals("Llanowar Elves")) {
                    elvesBuried = true;
                }
            }
            if (elvesBuried) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        boolean elvesBuried = false;
        for (Card card : session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Graveyard)) {
            if (card.getName().equals("Llanowar Elves")) {
                elvesBuried = true;
            }
        }
        Assert.assertTrue(elvesBuried, "Elves spell must be countered to graveyard");
        boolean frogExiled = false;
        for (Card card : session.getGame().getPlayers().get(0)
                .getCardsIn(ZoneType.Exile)) {
            if (card.getName().equals("Turn to Frog")) {
                frogExiled = true;
            }
        }
        Assert.assertTrue(frogExiled, "pitched Frog must be exiled");
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 39);
        session.shutdown(5000);
    }

    // ---- C03-shape: Fireball X multi-target ----

    @Test(timeOut = 300000)
    public void testC03FireballMultiTarget() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-c03");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Fireball", ZoneType.Hand);
        for (int i = 0; i < 7; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 7; i++) {
            tapLand(session, "p1", "Mountain");
        }
        Assert.assertEquals(poolOf(session, 0, "R"), 7);
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType) && "Fireball".equals(o.sourceCardName),
                "Fireball cast"));
        DecisionFrame xFrame = awaitNext(session, frame.revision, 15000);
        Assert.assertNotNull(xFrame);
        Assert.assertEquals(xFrame.kind, DecisionFrame.Kind.X_ANNOUNCE);
        Assert.assertTrue(xFrame.freeInput, "unbounded X must park validated free input");
        submitValue(session, xFrame, 5);
        // Two-player target set from the authoritative candidate set.
        DecisionFrame targetFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                targetFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Fireball targets");
        }
        Assert.assertNotNull(targetFrame, "Fireball targets never parked");
        assertNegatives(session, targetFrame);
        final DecisionFrame.Option both = pickOption(targetFrame,
                o -> o.label != null && o.label.contains("p2") && o.label.contains("p3"),
                "Fireball -> p2 + p3");
        submit(session, targetFrame, both);
        for (int i = 0; i < 40; i++) {
            if (session.getGame().getPlayers().get(1).getLife() == 38
                    && session.getGame().getPlayers().get(2).getLife() == 38) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 38);
        Assert.assertEquals(session.getGame().getPlayers().get(2).getLife(), 38);
        session.shutdown(5000);
    }

    // ---- D06-shape: Casualties of War modes + per-mode targets ----

    @Test(timeOut = 300000)
    public void testD06CasualtiesModes() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-d06");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Casualties of War", ZoneType.Hand);
        // Green comes from two Birds (kept off the creature-mode candidate list is
        // impossible, so match targets by name instead); my lands avoid Forests
        // so the opponent's Forest is the unique Forest label at target time.
        // Two Birds are required: Casualties costs {2}{B}{B}{G}{G}.
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Birds of Paradise", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Birds of Paradise", ZoneType.Battlefield);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Ornithopter", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Forest", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 3; i++) {
            tapLand(session, "p1", "Swamp");
        }
        tapLand(session, "p1", "Birds of Paradise");
        tapLand(session, "p1", "Birds of Paradise");
        for (int i = 0; i < 4; i++) {
            tapLand(session, "p1", "Plains");
        }
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Casualties of War".equals(o.sourceCardName),
                "Casualties cast"));
        // Mode subset first (resolution-time Charm), then per-mode single targets.
        DecisionFrame modeFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.MODE_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                modeFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                throw new AssertionError(
                        "targets arrived before modes; cannot script D06 deterministically");
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting modes");
        }
        Assert.assertNotNull(modeFrame, "mode selection never parked");
        // Engine truth: this Casualties scripting offers three modes (artifact,
        // creature, land), hence every non-empty subset = 7 authoritative options.
        Assert.assertEquals(modeFrame.options.size(), 7);
        assertNegatives(session, modeFrame);
        // Scripted [artifact; creature; land]: the unique subset naming all three.
        final DecisionFrame.Option threeModes = pickOption(modeFrame,
                o -> o.label != null && o.label.contains("artifact")
                        && o.label.contains("creature") && o.label.contains("land"),
                "artifact+creature+land modes");
        submit(session, modeFrame, threeModes);
        // Resolve out through per-mode targets: the artifact and land modes are
        // forced singles (sole legal targets); the creature mode offers every
        // creature and must take the Bear, never the already-doomed Ornithopter.
        // The opponent's Forest is the unique Forest label (my lands are
        // Swamps, Plains and Birds).
        boolean bearTargeted = false;
        for (int i = 0; i < 60; i++) {
            boolean ornithopterGone = findBattlefield(session, 1, "Ornithopter") == null;
            boolean bearGone = findBattlefield(session, 1, "Runeclaw Bear") == null;
            if (ornithopterGone && bearGone) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                boolean wantsBear = false;
                for (DecisionFrame.Option option : parked.options) {
                    if (option.label != null && option.label.contains("Runeclaw Bear")) {
                        wantsBear = true;
                    }
                }
                if (wantsBear) {
                    submit(session, parked, pickOption(parked,
                            o -> o.label != null && o.label.contains("Runeclaw Bear"),
                            "creature mode -> Bear"));
                    bearTargeted = true;
                } else {
                    submit(session, parked, pickOption(parked,
                            o -> o.label != null && o.label.contains("Forest"),
                            "land mode -> Forest"));
                }
            } else if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(bearTargeted, "creature mode never asked for its target");
        Assert.assertNull(findBattlefield(session, 1, "Ornithopter"), "Ornithopter destroyed");
        Assert.assertNull(findBattlefield(session, 1, "Runeclaw Bear"), "Bear destroyed");
        session.shutdown(5000);
    }

    // ---- E01-shape: Propaganda attack tax ----

    @Test(timeOut = 300000)
    public void testE01PropagandaAttack() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-e01");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Propaganda", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Island", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        // Reach p2's declare-attackers step on its own turn; p1 fields no
        // attackers on turn 1 so no bystander frame intervenes here.
        DecisionFrame attackFrame = driveTo(session, "p2",
                DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS, "DECLARE_ATTACKERS", 150);
        assertNegatives(session, attackFrame);
        final DecisionFrame.Option split = pickOption(attackFrame,
                o -> o.label != null && o.label.contains("-> p1")
                        && o.label.contains("-> p3"),
                "Bear-A -> p1, Bear-B -> p3");
        submit(session, attackFrame, split);
        // Propaganda tax for the P0-bound attacker is collected mid-declaration:
        // tap both Islands through parked tap offers, then damage resolves.
        for (int i = 0; i < 80; i++) {
            if (session.getGame().getPlayers().get(0).getLife() == 38
                    && session.getGame().getPlayers().get(2).getLife() == 38) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                // Tap offers name their Island; pool tie-breaks take the first.
                DecisionFrame.Option payment = null;
                for (DecisionFrame.Option option : parked.options) {
                    if (option.label != null && option.label.contains("Island")) {
                        payment = option;
                        break;
                    }
                }
                if (payment == null) {
                    payment = parked.options.get(0);
                }
                submit(session, parked, payment);
            } else if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("No blocks"), "no blocks"));
            } else {
                break;
            }
        }
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 38);
        Assert.assertEquals(session.getGame().getPlayers().get(2).getLife(), 38);
        session.shutdown(5000);
    }

    // ---- E02-shape: double-blocked trampler ----

    @Test(timeOut = 300000)
    public void testE02TrampleDamageDivision() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-e02");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 1, "Carnage Tyrant", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Llanowar Elves", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame attackFrame = driveTo(session, "p2",
                DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS, "DECLARE_ATTACKERS", 150);
        submit(session, attackFrame, pickOption(attackFrame,
                o -> o.label != null && o.label.contains("Carnage Tyrant")
                        && o.label.contains("-> p1"),
                "Tyrant attacks p1"));
        final DecisionFrame blockFrame = driveTo(session, "p1",
                DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS, null, 40);
        assertNegatives(session, blockFrame);
        submit(session, blockFrame, pickOption(blockFrame,
                o -> o.label != null && o.label.contains("Runeclaw Bear")
                        && o.label.contains("Llanowar Elves"),
                "double block"));
        // Incremental Core-owned damage view: 2 to Bear, 1 to Elves, 4 to P0.
        assignDamage(session, "Carnage Tyrant", "Runeclaw Bear", 2);
        assignDamage(session, "Carnage Tyrant", "Llanowar Elves", 1);
        assignDamage(session, "Carnage Tyrant", "player p1", 4);
        for (int i = 0; i < 40; i++) {
            if (session.getGame().getPlayers().get(0).getLife() == 36) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 36);
        boolean bearDead = true;
        boolean elvesDead = true;
        for (Card card : session.getGame().getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Runeclaw Bear")) {
                bearDead = false;
            }
            if (card.getName().equals("Llanowar Elves")) {
                elvesDead = false;
            }
        }
        Assert.assertTrue(bearDead, "Bear must die");
        Assert.assertTrue(elvesDead, "Elves must die");
        Assert.assertNotNull(findBattlefield(session, 1, "Carnage Tyrant"), "Tyrant must live");
        session.shutdown(5000);
    }

    private static void assignDamage(BridgeSession session, String source, String recipientFragment,
            int amount) {
        boolean negativesDone = false;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "damage frame never parked for " + recipientFragment);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind != DecisionFrame.Kind.COMBAT_DAMAGE) {
                throw new AssertionError("expected COMBAT_DAMAGE, got " + parked.kind);
            }
            DecisionFrame.Option match = null;
            for (DecisionFrame.Option option : parked.options) {
                if (option.label != null && option.label.contains(source)
                        && option.label.contains(recipientFragment)
                        && option.label.contains(String.valueOf(amount))) {
                    match = option;
                    break;
                }
            }
            if (match == null) {
                final StringBuilder seen = new StringBuilder();
                for (DecisionFrame.Option option : parked.options) {
                    seen.append('[').append(option.label).append(']');
                }
                throw new AssertionError("no " + amount + " to " + recipientFragment + " in "
                        + seen);
            }
            if (!negativesDone) {
                assertNegatives(session, parked);
                negativesDone = true;
            }
            final BridgeSession.SubmitOutcome outcome = session.submit(parked.actorPlayerId,
                    match.optionId, match.actionType, parked.revision);
            Assert.assertTrue(outcome.applied, "damage submit failed: " + outcome.errorCode
                    + " fail=" + session.getFailReason());
            return;
        }
        throw new AssertionError("damage assignment never settled for " + recipientFragment);
    }

    // ---- G02-shape: commander movement + recast tax (Isamaru stands in) ----

    @Test(timeOut = 300000)
    public void testG02CommanderMovementAndTax() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-g02");
        final BridgeSession session = constructed.session;
        // Real commander identity (command zone + native registration): the
        // fixture decks do not register commanders on the constructed path.
        BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
        BridgeTestSupport.addCard(constructed.game, 1, "Murder", ZoneType.Hand);
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 1, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        // P1 casts its real commander Isamaru from the command zone for {W}.
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Plains");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType) && "Isamaru, Hound of Konda".equals(
                        o.sourceCardName),
                "Isamaru cast"));
        boolean isamaruOut = false;
        for (int i = 0; i < 30 && !isamaruOut; i++) {
            isamaruOut = findBattlefield(session, 0, "Isamaru, Hound of Konda") != null;
            if (isamaruOut) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(isamaruOut, "Isamaru never entered");
        // P2 Murders Isamaru in response on the same turn (instant speed); the
        // SBA commander choice parks COMMANDER_MOVE.
        driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, null, 40);
        tapLand(session, "p2", "Swamp");
        tapLand(session, "p2", "Swamp");
        tapLand(session, "p2", "Swamp");
        DecisionFrame murderFrame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, murderFrame, pickOption(murderFrame,
                o -> "cast_spell".equals(o.actionType) && "Murder".equals(o.sourceCardName),
                "Murder cast"));
        DecisionFrame targetFrame = null;
        // Murder's lone legal target resolves forced with no frame (correct);
        // a genuine multi-option target frame is answered for Isamaru.
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p2")) {
                targetFrame = parked;
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("Isamaru"),
                        "Murder -> Isamaru"));
                break;
            }
            if (parked.kind == DecisionFrame.Kind.COMMANDER_MOVE) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Murder target");
        }
        DecisionFrame moveFrame = null;
        for (int i = 0; i < 40; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.COMMANDER_MOVE) {
                moveFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting commander move");
        }
        Assert.assertNotNull(moveFrame, "COMMANDER_MOVE never parked");
        Assert.assertEquals(moveFrame.actorPlayerId, "p1");
        assertNegatives(session, moveFrame);
        submit(session, moveFrame, pickOption(moveFrame,
                o -> o.confirmValue != null && o.confirmValue, "to command zone"));
        boolean inCommand = false;
        for (int i = 0; i < 20 && !inCommand; i++) {
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Command)) {
                if (card.getName().equals("Isamaru, Hound of Konda")) {
                    inCommand = true;
                }
            }
            if (inCommand) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else {
                break;
            }
        }
        Assert.assertTrue(inCommand, "Isamaru never reached the command zone");
        // Recast from command with the engine-owned {2} tax, on p1's next own
        // turn: only the active player's lands untap, so p1's Plains stay
        // tapped through p2/p3/p4's turns (overall turn = seat index + 1 mod 4).
        int recastTurn = session.getGame().getPhaseHandler().getTurn() + 1;
        while ((recastTurn - 1) % 4 != 0) {
            recastTurn++;
        }
        driveToTurn(session, "p1", DecisionFrame.Kind.PRIORITY, "MAIN", recastTurn, 400);
        tapLand(session, "p1", "Plains");
        tapLand(session, "p1", "Plains");
        tapLand(session, "p1", "Plains");
        DecisionFrame recastFrame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        final DecisionFrame.Option recast = pickOption(recastFrame,
                o -> "cast_spell".equals(o.actionType)
                        && "Isamaru, Hound of Konda".equals(o.sourceCardName),
                "Isamaru recast with tax");
        final BridgeSession.SubmitOutcome recastOutcome = submit(session, recastFrame, recast);
        Assert.assertTrue(recastOutcome.executionOk,
                "engine declined taxed recast: " + session.getLastExecutionError());
        // Engine-owned {2} tax proof: all three floated mana spent, and the
        // native cast count is 2 (initial command-zone cast plus taxed recast;
        // the {2} paid above is exactly the tax for one prior cast).
        Assert.assertEquals(poolOf(session, 0, "W"), 0);
        Card commander = null;
        for (Card card : session.playerById("p1").getCommanders()) {
            if (card.getName().equals("Isamaru, Hound of Konda")) {
                commander = card;
            }
        }
        Assert.assertNotNull(commander);
        Assert.assertEquals(session.playerById("p1").getCommanderCast(commander), 2);
        session.shutdown(5000);
    }

    // ---- G03-shape: lone commander attack records commander damage ----

    @Test(timeOut = 300000)
    public void testG03CommanderAttackDamage() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-g03");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
        // Anger in graveyard plus a Mountain gives the freshly cast commander
        // haste through real static-ability Rules, so it can attack the turn it
        // is cast with no multi-turn drive.
        BridgeTestSupport.addCard(constructed.game, 0, "Anger", ZoneType.Graveyard);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Plains");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Isamaru, Hound of Konda".equals(o.sourceCardName),
                "commander cast"));
        boolean isamaruOut = false;
        for (int i = 0; i < 30 && !isamaruOut; i++) {
            isamaruOut = findBattlefield(session, 0, "Isamaru, Hound of Konda") != null;
            if (isamaruOut) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(isamaruOut, "commander never entered");
        final DecisionFrame attackFrame = driveTo(session, "p1",
                DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS, "DECLARE_ATTACKERS", 60);
        submit(session, attackFrame, pickOption(attackFrame,
                o -> o.label != null && o.label.contains("Isamaru")
                        && o.label.contains("-> p2"),
                "commander attacks p2"));
        for (int i = 0; i < 60; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("No blocks"), "no blocks"));
            } else {
                break;
            }
            if (commanderDamageDealt(session, "p1")) {
                break;
            }
        }
        Assert.assertTrue(commanderDamageDealt(session, "p1"),
                "commander damage must be recorded natively");
        session.shutdown(5000);
    }

    private static boolean commanderDamageDealt(BridgeSession session, String attackerId) {
        for (Player defender : session.getGame().getPlayers()) {
            if (session.playerIdOf(defender).equals(attackerId)) {
                continue;
            }
            try {
                for (Object entry : defender.getCommanderDamage()) {
                    return true;
                }
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    // ---- J02-shape: Delina attack trigger over native RNG ----

    @Test(timeOut = 300000)
    public void testJ02DelinaAttackTrigger() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-j02");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Delina, Wild Mage", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame attackFrame = driveTo(session, "p1",
                DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS, "DECLARE_ATTACKERS", 150);
        submit(session, attackFrame, pickOption(attackFrame,
                o -> o.label != null && o.label.contains("Delina, Wild Mage")
                        && o.label.contains("Runeclaw Bear"),
                "Delina + Bear attack"));
        // The attack trigger resolves natively (d20 over engine RNG); answer any
        // framed follow-ups explicitly and insist the game proceeds sanely.
        for (int i = 0; i < 60; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("No blocks"), "no blocks"));
            } else if (parked.kind == DecisionFrame.Kind.GENERIC_CONFIRM
                    || parked.kind == DecisionFrame.Kind.TRIGGER_PLAY
                    || parked.kind == DecisionFrame.Kind.BINARY_CHOICE) {
                submit(session, parked, parked.options.get(0));
            } else if (parked.kind == DecisionFrame.Kind.COPY_CHOICE
                    || parked.kind == DecisionFrame.Kind.GENERIC_SELECTION
                    || parked.kind == DecisionFrame.Kind.SEARCH_SELECTION
                    || parked.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, parked, parked.options.get(0));
            } else if (parked.kind == DecisionFrame.Kind.COMBAT_DAMAGE
                    || parked.kind == DecisionFrame.Kind.AMOUNT_DISTRIBUTION) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
            if (session.getGame().isGameOver()) {
                break;
            }
        }
        Assert.assertFalse(session.getStatus() == BridgeSession.Status.FAILED,
                "Delina flow failed: " + session.getFailReason());
        session.shutdown(5000);
    }

    // ---- specifyManaCombo: bounded combination framing on its own ----

    @Test(timeOut = 300000)
    public void testSpecifyManaComboFraming() throws Exception {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-combo");
        final BridgeSession session = constructed.session;
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        final forge.game.player.PlayerController raw =
                session.playerById("p1").getController();
        Assert.assertTrue(raw instanceof ExternalPlayerController);
        final ExternalPlayerController controller = (ExternalPlayerController) raw;
        // The blocking controller call runs on a worker thread while the test
        // thread plays pilot: identical actor/revision/option discipline as
        // engine-driven parks.
        final java.util.concurrent.atomic.AtomicReference<Map<Byte, Integer>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Thread worker = new Thread(() -> {
            try {
                result.set(controller.specifyManaCombo(null, forge.card.ColorSet.WUBRG, 3,
                        false));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "ws202-combo-caller");
        worker.setDaemon(true);
        worker.start();
        DecisionFrame comboFrame = null;
        long seenRevision = -1;
        final DecisionFrame parked0 = session.getCurrentFrame();
        if (parked0 != null) {
            seenRevision = parked0.revision;
        }
        for (int i = 0; i < 40 && comboFrame == null; i++) {
            final DecisionFrame parked = awaitNext(session, seenRevision, 15000);
            Assert.assertNotNull(parked);
            seenRevision = parked.revision;
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT
                    && parked.actorPlayerId.equals("p1")) {
                comboFrame = parked;
            }
            // Anything else belongs to the live game underneath; leave it parked.
        }
        Assert.assertNotNull(comboFrame, "mana-combo choice never parked");
        // 3 mana over 5 colors = C(7,4) = 35 complete combinations, no truncation.
        Assert.assertEquals(comboFrame.options.size(), 35);
        assertNegatives(session, comboFrame);
        // The real submit blocks in settle (no next engine frame follows a
        // direct call), so it runs on its own thread; shutdown releases it.
        final DecisionFrame.Option ggg = pickOption(comboFrame,
                o -> o.label != null && o.label.contains("Gx3"), "GGG combo");
        final long comboRevision = comboFrame.revision;
        final java.util.concurrent.atomic.AtomicReference<BridgeSession.SubmitOutcome>
                submitOutcome = new java.util.concurrent.atomic.AtomicReference<>();
        final Thread submitter = new Thread(() -> submitOutcome.set(session.submit("p1",
                ggg.optionId, ggg.actionType, comboRevision)), "ws202-combo-submit");
        submitter.setDaemon(true);
        submitter.start();
        worker.join(15000);
        Assert.assertNull(failure.get(), "combo call failed: " + failure.get());
        final Map<Byte, Integer> map = result.get();
        Assert.assertNotNull(map);
        Assert.assertEquals(map.size(), 1);
        Assert.assertEquals(map.get(Byte.valueOf(forge.card.MagicColor.GREEN)).intValue(), 3);
        session.shutdown(5000);
        submitter.join(15000);
        Assert.assertNotNull(submitOutcome.get());
        Assert.assertFalse(submitOutcome.get().applied);
        Assert.assertEquals(submitOutcome.get().errorCode, BridgeErrors.SESSION_CLOSED);
    }

    // ---- Choice mana: Birds of Paradise + Black Lotus fund a cast ----
    @Test(timeOut = 300000)
    public void testChoiceManaFundsCast() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-mana");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Birds of Paradise", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Black Lotus", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Llanowar Elves", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        // Birds: any-color output parks an authoritative COLOR_CHOICE.
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "activate_ability".equals(o.actionType)
                        && "Birds of Paradise".equals(o.sourceCardName),
                "Birds activation"));
        DecisionFrame colorFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.COLOR_CHOICE
                    && parked.actorPlayerId.equals("p1")) {
                colorFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting color choice");
        }
        Assert.assertNotNull(colorFrame, "COLOR_CHOICE never parked");
        Assert.assertTrue(colorFrame.options.size() >= 5);
        assertNegatives(session, colorFrame);
        submit(session, colorFrame, pickOption(colorFrame,
                o -> o.label != null && o.label.contains("green"), "green mana"));
        Assert.assertEquals(poolOf(session, 0, "G"), 1);
        // Lotus: 0-cost cast, then sacrifice for a specified GGG combo.
        frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType) && "Black Lotus".equals(o.sourceCardName),
                "Lotus cast"));
        boolean lotusOut = false;
        for (int i = 0; i < 20 && !lotusOut; i++) {
            lotusOut = findBattlefield(session, 0, "Black Lotus") != null;
            if (lotusOut) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(lotusOut, "Lotus never entered");
        frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "activate_ability".equals(o.actionType)
                        && "Black Lotus".equals(o.sourceCardName),
                "Lotus activation"));
        DecisionFrame comboFrame = null;
        for (int i = 0; i < 12 && poolOf(session, 0, "G") < 4; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT
                    && parked.actorPlayerId.equals("p1")) {
                boolean combo = false;
                for (DecisionFrame.Option option : parked.options) {
                    if (option.actionType != null && option.actionType.contains("combo")) {
                        combo = true;
                    }
                }
                if (combo) {
                    comboFrame = parked;
                    break;
                }
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.COLOR_CHOICE
                    && parked.actorPlayerId.equals("p1")) {
                // Any-color outputs resolving one color at a time (Lotus shape):
                // answer green explicitly and keep draining.
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("green"), "green mana"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.COST_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                // Self-sacrifice confirm for the Lotus cost: answer Yes explicitly.
                submit(session, parked, pickOption(parked,
                        o -> o.confirmValue != null && o.confirmValue, "sacrifice Lotus"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting mana combo");
        }
        if (comboFrame != null) {
            submit(session, comboFrame, pickOption(comboFrame,
                    o -> o.label != null && o.label.contains("Gx3"), "GGG combo"));
        }
        Assert.assertTrue(poolOf(session, 0, "G") >= 4, "choice mana must fund the pool");
        // The choice-funded pool now casts Elves natively.
        frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        final BridgeSession.SubmitOutcome castOutcome = submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Llanowar Elves".equals(o.sourceCardName),
                "Elves cast"));
        Assert.assertTrue(castOutcome.executionOk,
                "engine declined: " + session.getLastExecutionError());
        session.shutdown(5000);
    }

    // ---- Discard: Tormenting Voice exacts its cost from hand ----

    @Test(timeOut = 300000)
    public void testDiscardTormentingVoice() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-discard");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Tormenting Voice", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Mountain");
        tapLand(session, "p1", "Mountain");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Tormenting Voice".equals(o.sourceCardName),
                "Voice cast"));
        DecisionFrame discardFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.COST_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                discardFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting discard");
        }
        Assert.assertNotNull(discardFrame, "discard COST_SELECTION never parked");
        assertNegatives(session, discardFrame);
        submit(session, discardFrame, pickOption(discardFrame,
                o -> o.label != null && o.label.contains("Plains"), "discard Plains"));
        boolean plainsDiscarded = false;
        for (int i = 0; i < 30 && !plainsDiscarded; i++) {
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Graveyard)) {
                if (card.getName().equals("Plains")) {
                    plainsDiscarded = true;
                }
            }
            if (plainsDiscarded) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(plainsDiscarded, "Plains never reached graveyard as cost");
        session.shutdown(5000);
    }

    // ---- L8 proof: counters, commander damage, tapped, attach via bootstrap ----

    @Test(timeOut = 300000)
    public void testBootstrapStateProof() {
        final com.google.gson.JsonObject neutral = new com.google.gson.JsonObject();
        final com.google.gson.JsonArray battlefield = new com.google.gson.JsonArray();
        battlefield.add(placement("Runeclaw Bear", "p1", "p1", false, "P1P1", 1, null));
        battlefield.add(placement("Pacifism", "p1", "p2", false, null, 0, "Runeclaw Bear"));
        battlefield.add(placement("Forest", "p1", "p1", true, null, 0, null));
        neutral.add("battlefield", battlefield);
        final com.google.gson.JsonObject hands = new com.google.gson.JsonObject();
        final com.google.gson.JsonArray hand = new com.google.gson.JsonArray();
        hand.add("Momentary Blink");
        hands.add("p1", hand);
        neutral.add("hands", hands);
        final com.google.gson.JsonObject players = new com.google.gson.JsonObject();
        neutral.add("players", players);
        final ScenarioBootstrap.Plan plan = ScenarioBootstrap.parse(neutral);
        Assert.assertEquals(plan.battlefield.size(), 3);
        Assert.assertEquals(plan.hands.get("p1").size(), 1);
        // Rejection proofs: outcomes, decisions and bad counters never parse.
        final com.google.gson.JsonObject bad = new com.google.gson.JsonObject();
        final com.google.gson.JsonArray stack = new com.google.gson.JsonArray();
        stack.add("x");
        bad.add("stack", stack);
        try {
            ScenarioBootstrap.parse(bad);
            throw new AssertionError("stack injection must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("stack"));
        }
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-l8");
        ScenarioBootstrap.apply(constructed.session, constructed.game, plan);
        Card bear = null;
        Card pacifism = null;
        for (Card card : constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Runeclaw Bear")) {
                bear = card;
            }
            if (card.getName().equals("Pacifism")) {
                pacifism = card;
            }
        }
        Assert.assertNotNull(bear);
        Assert.assertNotNull(pacifism);
        Assert.assertEquals(bear.getCounters(CounterEnumType.P1P1), 1);
        Assert.assertTrue(pacifism.getEntityAttachedTo() == bear,
                "Pacifism must attach to the Bear");
        boolean forestTapped = false;
        for (Card card : constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Forest") && card.isTapped()) {
                forestTapped = true;
            }
        }
        Assert.assertTrue(forestTapped, "tapped placement must arrive tapped");
        boolean blinkInHand = false;
        for (Card card : constructed.game.getPlayers().get(0).getCardsIn(ZoneType.Hand)) {
            if (card.getName().equals("Momentary Blink")) {
                blinkInHand = true;
            }
        }
        Assert.assertTrue(blinkInHand, "scripted hand must be established");
        constructed.session.shutdown(1000);
    }

    private static com.google.gson.JsonObject placement(String card, String controller,
            String owner, boolean tapped, String counter, int counters, String attachedTo) {
        final com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
        entry.addProperty("card", card);
        entry.addProperty("controller", controller);
        entry.addProperty("owner", owner);
        entry.addProperty("tapped", tapped);
        if (counter != null) {
            final com.google.gson.JsonObject counterMap = new com.google.gson.JsonObject();
            counterMap.addProperty(counter, counters);
            entry.add("counters", counterMap);
        }
        if (attachedTo != null) {
            entry.addProperty("attached_to", attachedTo);
        }
        return entry;
    }

    // ---- Modes: Boros Charm triple choice ----

    @Test(timeOut = 300000)
    public void testModesBorosCharm() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws202-modes");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Boros Charm", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Mountain");
        tapLand(session, "p1", "Plains");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType) && "Boros Charm".equals(o.sourceCardName),
                "Charm cast"));
        // Engine order is modes-at-cast (Charm API), then targets: the damage
        // mode is chosen first, and only it needs the player target.
        DecisionFrame modeFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.MODE_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                modeFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                throw new AssertionError("targets arrived before modes in this build");
            }
            throw new AssertionError("unexpected " + parked.kind + " in Charm flow");
        }
        Assert.assertNotNull(modeFrame, "MODE_SELECTION never parked");
        for (DecisionFrame.Option option : modeFrame.options) {
            System.err.println("[evidence] charm mode option: " + option.label);
        }
        Assert.assertTrue(modeFrame.options.size() >= 2, "a real modal choice must be offered");
        assertNegatives(session, modeFrame);
        submit(session, modeFrame, pickOption(modeFrame,
                o -> o.label != null && o.label.contains("4 damage"), "damage mode"));
        DecisionFrame targetFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                targetFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Charm target");
        }
        Assert.assertNotNull(targetFrame, "Charm target never parked");
        submit(session, targetFrame, pickOption(targetFrame,
                o -> o.label != null && o.label.contains("p2"), "Charm -> p2"));
        for (int i = 0; i < 40; i++) {
            if (session.getGame().getPlayers().get(1).getLife() == 36) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 36);
        session.shutdown(5000);
    }

    // ---- RNG twins: same seed, same scenario, same semantic opening ----

    @Test(timeOut = 300000)
    public void testSeedTwinsDeterministic() throws Exception {
        final String first = runSeededTwin("ws202-twin-a", 424242L);
        final String second = runSeededTwin("ws202-twin-b", 424242L);
        Assert.assertEquals(second, first, "same-seed twins must deal the same opening");
        final String third = runSeededTwin("ws202-twin-c", 777L);
        Assert.assertFalse(third.isEmpty());
    }

    private static String runSeededTwin(String gameId, long seed) throws Exception {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine);
        final StringBuilder decks = new StringBuilder();
        for (int i = 0; i < handles.size(); i++) {
            if (i > 0) {
                decks.append(',');
            }
            decks.append('"').append(handles.get(i)).append('"');
        }
        final JsonObject created = BridgeTestSupport.rpc(engine, "{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"twin-" + gameId + "\",\"message_type\":\"create_commander_game\","
                + "\"payload\":{\"request\":{\"game_id\":\"" + gameId + "\",\"format\":\"commander\","
                + "\"seed\":" + seed + ",\"deck_handles\":[" + decks + "]}}}");
        BridgeTestSupport.assertOk(created);
        BridgeTestSupport.startGame(engine, gameId);
        final BridgeSession session = engine.sessionsForTests().get(gameId);
        final DecisionFrame first = BridgeTestSupport.driveStartToPriority(session, "p1", 120000);
        Assert.assertNotNull(first);
        final JsonObject state = StateProjection.gameState(session, "p1");
        Assert.assertEquals(state.get("seed").getAsLong(), seed);
        final StringBuilder hand = new StringBuilder();
        for (Object element : state.getAsJsonArray("players")) {
            final JsonObject playerState = (JsonObject) element;
            if (!playerState.get("player_id").getAsString().equals("p1")) {
                continue;
            }
            final List<String> names = new ArrayList<>();
            for (Object card : playerState.getAsJsonObject("zones").getAsJsonArray("hand")) {
                names.add(((com.google.gson.JsonElement) card).getAsString());
            }
            names.sort(String::compareTo);
            for (String name : names) {
                hand.append(name).append(';');
            }
        }
        session.shutdown(5000);
        return hand.toString();
    }
}
