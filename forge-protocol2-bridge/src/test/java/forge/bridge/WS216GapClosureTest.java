package forge.bridge;

import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * WS216 Forge WS202 decision-gap closure: runtime qualification for the five
 * family-level UNKNOWNs plus bounded decline subpaths.
 *
 * <p>Every test drives a real native Forge callback with actual cards. The
 * engine owns legality and candidate domains; the bridge only parks complete
 * frames and submits exact offered options. No second Rules engine, no
 * card-name hacks, no defaults.
 */
public class WS216GapClosureTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

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

    private static Card findBattlefield(BridgeSession session, int seat, String name) {
        for (Card card : session.getGame().getPlayers().get(seat)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
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
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
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

    // ---- FAMILY 1: NUMBER_CHOICE small-range via Expel the Interlopers (0..10) ----

    @Test(timeOut = 300000)
    public void testNumberChoiceSmallRangeExpel() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws216-number");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Expel the Interlopers", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Llanowar Elves", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Carnage Tyrant", ZoneType.Battlefield);
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 5; i++) {
            tapLand(session, "p1", "Plains");
        }
        Assert.assertEquals(poolOf(session, 0, "W"), 5);
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Expel the Interlopers".equals(o.sourceCardName),
                "Expel cast"));
        DecisionFrame numberFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting NUMBER_CHOICE");
            if (parked.kind == DecisionFrame.Kind.NUMBER_CHOICE
                    && parked.actorPlayerId.equals("p1")) {
                numberFrame = parked;
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
            throw new AssertionError("unexpected " + parked.kind + " awaiting NUMBER_CHOICE");
        }
        Assert.assertNotNull(numberFrame, "NUMBER_CHOICE never parked for Expel");
        // Engine-authoritative small-range enumeration: exactly 0..10, no free input.
        Assert.assertFalse(numberFrame.freeInput, "small range must enumerate, not free-input");
        Assert.assertEquals(numberFrame.options.size(), 11, "exactly 0..10 must be offered");
        boolean hasTwo = false;
        boolean hasThree = false;
        for (DecisionFrame.Option option : numberFrame.options) {
            Assert.assertNotNull(option.intValue, "number option must carry int value");
            Assert.assertTrue(option.intValue >= 0 && option.intValue <= 10,
                    "option outside engine bounds: " + option.intValue);
            if (option.intValue == 2) {
                hasTwo = true;
            }
            if (option.intValue == 3) {
                hasThree = true;
            }
        }
        Assert.assertTrue(hasTwo && hasThree, "at least two distinct legal choices required");
        assertNegatives(session, numberFrame);
        // Enumerated-frame value rejection: pilot-supplied values are malformed here.
        final DecisionFrame.Option first = numberFrame.options.get(0);
        final BridgeSession.SubmitOutcome withValue = session.submit(numberFrame.actorPlayerId,
                first.optionId, first.actionType, numberFrame.revision, Long.valueOf(2));
        Assert.assertFalse(withValue.applied);
        Assert.assertEquals(withValue.errorCode, BridgeErrors.MALFORMED_REQUEST);
        // Below-minimum and above-maximum have no offered identity: unknown option.
        final BridgeSession.SubmitOutcome below = session.submit(numberFrame.actorPlayerId,
                "opt-does-not-exist", first.actionType, numberFrame.revision);
        Assert.assertFalse(below.applied);
        Assert.assertEquals(below.errorCode, BridgeErrors.UNKNOWN_OPTION);
        // Choose 2: Bear (2) and Tyrant (7) die, Elves (1) lives.
        submit(session, numberFrame, pickOption(numberFrame,
                o -> o.intValue != null && o.intValue == 2, "choose 2"));
        for (int i = 0; i < 30; i++) {
            boolean bearGone = findBattlefield(session, 0, "Runeclaw Bear") == null;
            boolean tyrantGone = findBattlefield(session, 1, "Carnage Tyrant") == null;
            boolean elvesAlive = findBattlefield(session, 0, "Llanowar Elves") != null;
            if (bearGone && tyrantGone && elvesAlive) {
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
        Assert.assertNull(findBattlefield(session, 0, "Runeclaw Bear"), "Bear power 2 must die");
        Assert.assertNull(findBattlefield(session, 1, "Carnage Tyrant"), "Tyrant power 7 must die");
        Assert.assertNotNull(findBattlefield(session, 0, "Llanowar Elves"),
                "Elves power 1 must survive chosen 2");
        session.shutdown(5000);
    }

    // ---- FAMILY 2: COMBAT_ORDER is legacy DAO, unreachable with current rules ----

    @Test(timeOut = 300000)
    public void testCombatOrderUnreachableLegacyOff() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws216-combat-order");
        final BridgeSession session = constructed.session;
        // Production bridge never enables legacy DAO: current CR 510 uses the
        // incremental CombatDamageDecision (COMBAT_DAMAGE), not assignment order.
        Assert.assertFalse(constructed.game.getRules().hasOrderCombatants(),
                "production bridge must leave legacyOrderCombatants false");
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
        submit(session, blockFrame, pickOption(blockFrame,
                o -> o.label != null && o.label.contains("Runeclaw Bear")
                        && o.label.contains("Llanowar Elves"),
                "double block"));
        // Modern damage assignment: incremental triples, no DAO frame.
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
                Assert.fail("unexpected " + parked.kind + " where COMBAT_ORDER must not park");
            }
        }
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 36);
        // No COMBAT_ORDER frame was ever parked in this production game.
        boolean combatOrderParked = false;
        for (BridgeSession.AuditEvent event : session.auditSnapshot()) {
            if (event.details.toString().contains("COMBAT_ORDER")) {
                combatOrderParked = true;
            }
        }
        Assert.assertFalse(combatOrderParked, "legacy COMBAT_ORDER must not park when flag is off");
        // Bound still fails closed if ever invoked: 5 combatants cannot be offered.
        final ExternalPlayerController controller =
                (ExternalPlayerController) constructed.game.getPlayers().get(0).getController();
        final List<Card> five = new ArrayList<>(constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield));
        // Ensure five distinct cards for the synthetic bound check.
        while (five.size() < 5) {
            five.add(BridgeTestSupport.addCard(constructed.game, 0, "Memnite",
                    ZoneType.Battlefield));
        }
        final forge.game.card.CardCollection fiveView =
                new forge.game.card.CardCollection(five.subList(0, 5));
        try {
            controller.orderBlockers(findBattlefield(session, 1, "Carnage Tyrant"), fiveView);
            throw new AssertionError("five-combatant ordering must fail closed");
        } catch (BridgeUnsupportedDecision expected) {
            Assert.assertTrue(expected.getMessage().contains("order_blockers")
                    || expected.getMessage().contains("too many"));
        }
        session.shutdown(5000);
    }

    // ---- FAMILY 3: STATIC_CHOICE via dual cost reducers ----

    @Test(timeOut = 300000)
    public void testStaticChoiceEnumeratedReducers() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws216-static");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Goblin Electromancer",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Helm of Awakening", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Divination", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Island");
        Assert.assertEquals(poolOf(session, 0, "U"), 1);
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Divination".equals(o.sourceCardName),
                "Divination cast"));
        DecisionFrame staticFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting STATIC_CHOICE");
            if (parked.kind == DecisionFrame.Kind.STATIC_CHOICE
                    && parked.actorPlayerId.equals("p1")) {
                staticFrame = parked;
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
            throw new AssertionError("unexpected " + parked.kind + " awaiting STATIC_CHOICE");
        }
        Assert.assertNotNull(staticFrame, "STATIC_CHOICE never parked for dual reducers");
        Assert.assertEquals(staticFrame.options.size(), 2, "exactly two reducer statics");
        assertNegatives(session, staticFrame);
        boolean hasGoblin = false;
        boolean hasHelm = false;
        for (DecisionFrame.Option option : staticFrame.options) {
            if (option.label != null && option.label.contains("Goblin Electromancer")) {
                hasGoblin = true;
            }
            if (option.label != null && option.label.contains("Helm of Awakening")) {
                hasHelm = true;
            }
        }
        Assert.assertTrue(hasGoblin && hasHelm, "both reducer hosts must be offered");
        // No first-option default: explicitly choose Goblin (not index 0 assumption).
        submit(session, staticFrame, pickOption(staticFrame,
                o -> o.label != null && o.label.contains("Goblin Electromancer"),
                "Goblin reducer first"));
        boolean divinationResolved = false;
        for (int i = 0; i < 30 && !divinationResolved; i++) {
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Graveyard)) {
                if (card.getName().equals("Divination")) {
                    divinationResolved = true;
                }
            }
            if (divinationResolved) {
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
        Assert.assertTrue(divinationResolved, "Divination must resolve after static choice");
        Assert.assertEquals(poolOf(session, 0, "U"), 0, "reduced U cost must be spent");
        session.shutdown(5000);
    }

    // ---- FAMILY 4: HIDDEN_ZONE_SELECTION via Faithless Looting effect discard ----

    @Test(timeOut = 300000)
    public void testHiddenZoneSelectionFaithlessLooting() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws216-hidden");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Faithless Looting", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Mountain");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Faithless Looting".equals(o.sourceCardName),
                "Looting cast"));
        DecisionFrame hiddenFrame = null;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting HIDDEN_ZONE_SELECTION");
            if (parked.kind == DecisionFrame.Kind.HIDDEN_ZONE_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                hiddenFrame = parked;
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
            throw new AssertionError("unexpected " + parked.kind + " awaiting hidden discard");
        }
        Assert.assertNotNull(hiddenFrame, "HIDDEN_ZONE_SELECTION never parked for Looting");
        Assert.assertFalse(hiddenFrame.options.isEmpty(), "hidden choice must offer options");
        assertNegatives(session, hiddenFrame);
        // Entitled chooser sees names; outsiders see nothing.
        final JsonObject asP1 = StateProjection.gameState(session, "p1");
        Assert.assertTrue(asP1.getAsJsonArray("legal_actions").size() > 0,
                "entitled chooser must see the frame");
        final JsonObject asP2 = StateProjection.gameState(session, "p2");
        Assert.assertEquals(asP2.getAsJsonArray("legal_actions").size(), 0,
                "outsider must see no options");
        for (DecisionFrame.Option option : hiddenFrame.options) {
            Assert.assertFalse(asP2.toString().contains(option.optionId),
                    "outsider must not see option identity");
        }
        // Foe hand view stays redacted; labels do not leak via state.
        for (Object element : asP2.getAsJsonArray("players")) {
            final JsonObject playerState = (JsonObject) element;
            if (!playerState.get("player_id").getAsString().equals("p1")) {
                continue;
            }
            for (Object card : playerState.getAsJsonObject("zones").getAsJsonArray("hand")) {
                Assert.assertEquals(((com.google.gson.JsonElement) card).getAsString(),
                        "<hidden>", "foe must not see p1 hand names");
            }
        }
        // Source metadata does not leak option identities to outsiders.
        final JsonObject metaP2 = StateProjection.bridgeMeta(session, "p2");
        Assert.assertTrue(metaP2.get("pending_decision").isJsonNull(),
                "outsider must not see pending decision");
        // Choose a discard containing Plains where offered, else first option.
        DecisionFrame.Option choice = null;
        for (DecisionFrame.Option option : hiddenFrame.options) {
            if (option.label != null && option.label.contains("Plains")) {
                choice = option;
                break;
            }
        }
        if (choice == null) {
            choice = hiddenFrame.options.get(0);
        }
        final String chosenLabel = choice.label;
        submit(session, hiddenFrame, choice);
        // Stale resubmission fails closed.
        final BridgeSession.SubmitOutcome stale = session.submit(hiddenFrame.actorPlayerId,
                choice.optionId, choice.actionType, hiddenFrame.revision);
        Assert.assertFalse(stale.applied);
        // Native consumption: chosen cards reach graveyard.
        boolean labelCardBuried = false;
        for (int i = 0; i < 30 && !labelCardBuried; i++) {
            // The chosen subset's first named card must appear in graveyard.
            for (Card card : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Graveyard)) {
                if (chosenLabel != null && chosenLabel.contains(card.getName())) {
                    labelCardBuried = true;
                    break;
                }
            }
            if (labelCardBuried) {
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
        Assert.assertTrue(labelCardBuried, "chosen discard must reach graveyard natively");
        session.shutdown(5000);
    }

    // ---- FAMILY 5: DIVIDED fail-closed (Arc Lightning) ----

    @Test(timeOut = 300000)
    public void testDividedFailClosed() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws216-divided");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 3; i++) {
            tapLand(session, "p1", "Mountain");
        }
        // Introduce the divided spell only after mana is floated: from turn 1 its
        // TARGETING blocker would park every PRIORITY UNSUPPORTED and prevent
        // even the taps. Adding mid-game proves the offering-stage fail-closed.
        BridgeTestSupport.addCard(constructed.game, 0, "Arc Lightning", ZoneType.Hand);
        final int p2LifeBefore = session.getGame().getPlayers().get(1).getLife();
        final int bearCountBefore = session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield).size();
        // The divided spell cannot be offered SUPPORTED: the next priority frame
        // for p1 fails closed with an explicit unsupported reason, never partial.
        // The current SUPPORTED priority (parked before Arc arrived) must be
        // passed first; the following revolution then blocks on TARGETING.
        DecisionFrame unsupported = null;
        for (int i = 0; i < 40; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked after introducing divided spell");
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.actorPlayerId.equals("p1")
                    && parked.status == DecisionFrame.Status.UNSUPPORTED) {
                unsupported = parked;
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
            if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "bystander decline"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting divided block");
        }
        Assert.assertNotNull(unsupported, "priority frame never re-parked after Arc added");
        Assert.assertEquals(unsupported.status, DecisionFrame.Status.UNSUPPORTED,
                "divided spell must block the whole priority frame, got " + unsupported.status);
        Assert.assertTrue(unsupported.reason.contains("TARGETING"),
                "reason must name the targeting blocker: " + unsupported.reason);
        Assert.assertTrue(unsupported.options.isEmpty(), "fail-closed offers no options");
        final BridgeSession.SubmitOutcome attempt = session.submit(unsupported.actorPlayerId,
                "opt-does-not-exist", "cast_spell", unsupported.revision);
        Assert.assertFalse(attempt.applied);
        // R16: actor/revision are checked before frame-specific data, so a wrong
        // actor sees WRONG_ACTOR even on an UNSUPPORTED frame without leaking.
        final String other = unsupported.actorPlayerId.equals("p1") ? "p2" : "p1";
        final BridgeSession.SubmitOutcome wrongActor = session.submit(other,
                "opt-does-not-exist", "cast_spell", unsupported.revision);
        Assert.assertFalse(wrongActor.applied);
        Assert.assertEquals(wrongActor.errorCode, BridgeErrors.WRONG_ACTOR);
        // Exact runtime target path fails closed before incorrect mutation.
        final ExternalPlayerController controller =
                (ExternalPlayerController) constructed.game.getPlayers().get(0).getController();
        Card arcCard = null;
        for (Card card : session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Hand)) {
            if (card.getName().equals("Arc Lightning")) {
                arcCard = card;
                break;
            }
        }
        Assert.assertNotNull(arcCard, "Arc Lightning must remain in hand (never mis-offered)");
        forge.game.spellability.SpellAbility dividedAbility = null;
        for (forge.game.spellability.SpellAbility ability : arcCard.getAllPossibleAbilities(
                session.getGame().getPlayers().get(0), true)) {
            if (ability.isSpell()) {
                dividedAbility = ability;
                break;
            }
        }
        Assert.assertNotNull(dividedAbility, "divided ability must exist natively");
        try {
            controller.chooseTargetsFor(dividedAbility);
            throw new AssertionError("divided allocation must fail closed");
        } catch (BridgeUnsupportedDecision expected) {
            Assert.assertTrue(expected.getMessage().contains("divided allocation")
                    || expected.getMessage().contains("chooseTargetsFor"));
        }
        // No incorrect mutation: life and battlefield unchanged.
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), p2LifeBefore);
        Assert.assertEquals(session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield).size(), bearCountBefore);
        session.shutdown(5000);
    }

    // ---- MANA_PAYMENT Decline on the live Propaganda tax path ----

    @Test(timeOut = 300000)
    public void testManaPaymentDeclinePropaganda() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws216-mana-decline");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Propaganda", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Island", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame attackFrame = driveTo(session, "p2",
                DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS, "DECLARE_ATTACKERS", 150);
        submit(session, attackFrame, pickOption(attackFrame,
                o -> o.label != null && o.label.contains("Runeclaw Bear")
                        && o.label.contains("-> p1"),
                "Bear attacks p1"));
        DecisionFrame tapFrame = null;
        for (int i = 0; i < 40; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame parked awaiting propaganda tax");
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT
                    && parked.actorPlayerId.equals("p2")) {
                tapFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("No blocks"), "no blocks"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting propaganda tax");
        }
        Assert.assertNotNull(tapFrame, "propaganda MANA_PAYMENT never parked");
        DecisionFrame.Option decline = null;
        for (DecisionFrame.Option option : tapFrame.options) {
            if (option.confirmValue != null && !option.confirmValue.booleanValue()) {
                decline = option;
            }
        }
        Assert.assertNotNull(decline, "explicit Decline must be offered on the tax path");
        assertNegatives(session, tapFrame);
        final BridgeSession.SubmitOutcome declined = session.submit(tapFrame.actorPlayerId,
                decline.optionId, decline.actionType, tapFrame.revision);
        Assert.assertTrue(declined.applied, "decline failed: " + declined.errorCode);
        // Native rollback: attacker leaves combat, stays untapped via cancel path,
        // Islands stay untapped, no damage.
        for (int i = 0; i < 40; i++) {
            if (session.getGame().getPlayers().get(0).getLife() == 40
                    && session.getGame().getCombat() != null
                    && session.getGame().getCombat().getAttackers().isEmpty()) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                // Second tax offers (if any) are also declined explicitly.
                DecisionFrame.Option secondDecline = null;
                for (DecisionFrame.Option option : parked.options) {
                    if (option.confirmValue != null && !option.confirmValue.booleanValue()) {
                        secondDecline = option;
                    }
                }
                if (secondDecline != null) {
                    session.submit(parked.actorPlayerId, secondDecline.optionId,
                            secondDecline.actionType, parked.revision);
                } else {
                    submit(session, parked, parked.options.get(0));
                }
            } else if (parked.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("No blocks"), "no blocks"));
            } else {
                break;
            }
            if (session.getGame().getPlayers().get(0).getLife() != 40) {
                break;
            }
        }
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 40,
                "declined tax must deal no damage");
        boolean islandsUntapped = false;
        for (Card card : session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Island") && !card.isTapped()) {
                islandsUntapped = true;
            }
        }
        Assert.assertTrue(islandsUntapped, "declined taps must leave Islands untapped");
        session.shutdown(5000);
    }

    // ---- COST_SELECTION Decline on the live Force pitch path ----

    @Test(timeOut = 300000)
    public void testCostSelectionDeclineForcePitch() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws216-cost-decline");
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
        driveToTurn(session, "p2", DecisionFrame.Kind.PRIORITY, "MAIN", 2, 150);
        tapLand(session, "p2", "Forest");
        DecisionFrame elvesFrame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, elvesFrame, pickOption(elvesFrame,
                o -> "cast_spell".equals(o.actionType)
                        && "Llanowar Elves".equals(o.sourceCardName),
                "Elves cast"));
        tapLand(session, "p2", "Forest");
        DecisionFrame fogFrame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, fogFrame, pickOption(fogFrame,
                o -> "cast_spell".equals(o.actionType) && "Fog".equals(o.sourceCardName),
                "Fog cast"));
        DecisionFrame forceFrame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 40);
        submit(session, forceFrame, pickOption(forceFrame,
                o -> "cast_spell".equals(o.actionType)
                        && "Force of Will".equals(o.sourceCardName)
                        && o.label != null && o.label.toLowerCase().contains("life"),
                "Force pitch cast"));
        DecisionFrame exileFrame = null;
        for (int i = 0; i < 40; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
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
                exileFrame = parked;
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
            throw new AssertionError("unexpected " + parked.kind + " in Force decline flow");
        }
        Assert.assertNotNull(exileFrame, "pitch exile selection never parked");
        Assert.assertEquals(exileFrame.options.size(), 3, "Frog, Ponder and Decline offered");
        DecisionFrame.Option decline = pickOption(exileFrame,
                o -> o.confirmValue != null && !o.confirmValue.booleanValue(), "Decline pitch");
        assertNegatives(session, exileFrame);
        final BridgeSession.SubmitOutcome declined = session.submit(exileFrame.actorPlayerId,
                decline.optionId, decline.actionType, exileFrame.revision);
        Assert.assertTrue(declined.applied, "cost decline failed: " + declined.errorCode);
        // Native cost rollback: neither Frog nor Ponder is exiled, Elves is not
        // countered by this Force (it remains on the stack or resolves), and the
        // declined Force never counters.
        for (int i = 0; i < 20; i++) {
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
            boolean elvesBuriedByForce = false;
            for (Card card : session.getGame().getPlayers().get(1)
                    .getCardsIn(ZoneType.Graveyard)) {
                if (card.getName().equals("Llanowar Elves")) {
                    elvesBuriedByForce = true;
                }
            }
            if (elvesBuriedByForce) {
                break;
            }
        }
        boolean frogExiled = false;
        boolean ponderExiled = false;
        for (Card card : session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Exile)) {
            if (card.getName().equals("Turn to Frog")) {
                frogExiled = true;
            }
            if (card.getName().equals("Ponder")) {
                ponderExiled = true;
            }
        }
        Assert.assertFalse(frogExiled, "declined pitch must not exile Frog");
        Assert.assertFalse(ponderExiled, "declined pitch must not exile Ponder");
        session.shutdown(5000);
    }
}
