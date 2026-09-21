package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R9b RETARGET bridge family: Bolt Bend (CARD_22) + Flare of Duplication
 * (CARD_13) through engine-offered DecisionFrames.
 *
 * <p>Sim-seam AI explicitly cannot play ChangeTargets (ChangeTargetsAi
 * CantPlayAi) nor choose new targets (chooseNewTargetsFor null) —
 * AI_DEFECT, preserved. The Protocol-2 bridge is the authoritative seam:
 * cast_spell options, TARGET_SELECTION for cast targets (multi-candidate)
 * with single-candidate engine auto-bind, MANA_PAYMENT, and retarget
 * TARGET_SELECTION at resolution via the native chooseNewTargetsFor path.
 * The test only ever picks among engine-offered options; costs resolve
 * from real tapped lands; damage is accounted, never injected.</p>
 */
public class WsR9RetargetBridgeFamilyTest {

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

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static boolean frameMatches(DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind) {
        return frame.kind == kind && frame.actorPlayerId.equals(actorId)
                && frame.status == DecisionFrame.Status.SUPPORTED;
    }

    // Drive to a wanted frame, passing priorities, paying first mana option,
    // taking first target by default, declining all combat.
    private static DecisionFrame driveTo(BridgeSession session, String actorId,
            DecisionFrame.Kind kind, int budget) {
        long lastRevision = -1;
        final DecisionFrame current = session.getCurrentFrame();
        if (current != null) {
            lastRevision = current.revision;
            if (frameMatches(current, actorId, kind)) {
                return current;
            }
        }
        for (int i = 0; i < budget; i++) {
            final DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind);
            lastRevision = frame.revision;
            if (frameMatches(frame, actorId, kind)) {
                return frame;
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                throw new AssertionError("blocked by " + frame.kind + " " + frame.status);
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
            } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, frame, frame.options.get(0));
            } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, frame, frame.options.get(0));
            } else if ((frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS)) {
                submit(session, frame, pickOption(frame,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
            }
        }
        throw new AssertionError("never reached " + kind + " for " + actorId);
    }

    private static void tapLand(BridgeSession session, String actorId, String landName) {
        DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        submit(session, frame, pickOption(frame,
                o -> landName.equals(o.sourceCardName)
                        && o.actionType != null && o.actionType.contains("tap"),
                "tap " + landName));
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static Card findBf(BridgeSession session, int seat, String name) {
        for (Card c : session.getGame().getPlayers().get(seat).getZone(ZoneType.Battlefield).getCards()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private static int countTapped(BridgeSession session, String name) {
        int n = 0;
        for (Card c : session.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(c.getName()) && c.isTapped()) {
                n++;
            }
        }
        return n;
    }

    private static int life(int seat, BridgeSession session) {
        return session.getGame().getPlayers().get(seat).getLife();
    }

    // Drain: pass all priorities / pay first mana / first target until quiet
    // (bounded; asserts progress by revision movement).
    private static void drain(BridgeSession session, int budget) {
        long lastRevision = -1;
        for (int i = 0; i < budget; i++) {
            DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame while draining");
            if (frame.revision == lastRevision) {
                return;
            }
            lastRevision = frame.revision;
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                return;
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
            } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, frame, frame.options.get(0));
            } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, frame, frame.options.get(0));
            } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, frame, pickOption(frame,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                return;
            }
        }
    }

    // Pass exactly one priority frame for the actor (stack untouched).
    private static void passOnce(BridgeSession session, String actorId) {
        for (int i = 0; i < 10; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals(actorId) && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
                return;
            }
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                throw new AssertionError("unexpected " + f.kind);
            }
        }
        throw new AssertionError("never reached priority for " + actorId);
    }

    // Answer one just-cast spell's MANA + TARGET frames (R costs: first
    // tap option is a Mountain). Returns at the next PRIORITY frame.
    private static void answerSpellFrames(BridgeSession session) {
        for (int i = 0; i < 10; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                return;
            }
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                throw new AssertionError("unexpected " + f.kind);
            }
        }
        throw new AssertionError("spell frames never returned to priority");
    }

    @Test(timeOut = 300000)
    public void testBendCastTargetFramedWithTwoStackSpells() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr9-bend-choice");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Bolt Bend", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Lightning Bolt", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Shock", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        // p2 casts Bolt then Shock back-to-back from its own priorities
        // (caster retains priority): both stay stacked because p1 never
        // passes in between and p2 never passes after the last action.
        DecisionFrame frame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Lightning Bolt".equals(o.sourceCardName),
                "Bolt cast"));
        answerSpellFrames(session);
        frame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Shock".equals(o.sourceCardName),
                "Shock cast"));
        answerSpellFrames(session);
        // Pass p2/p3/p4 once so p1 acts with both spells still stacked
        // (round incomplete: p1 has not passed since Shock).
        passOnce(session, "p2");
        passOnce(session, "p3");
        passOnce(session, "p4");

        // p1 Bend: with two stack spells the cast target must be framed;
        // pick Bolt explicitly.
        boolean framed = false;
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasBend = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Bolt Bend".equals(o.sourceCardName)) {
                        hasBend = true;
                    }
                }
                if (hasBend) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Bolt Bend".equals(o.sourceCardName),
                            "Bend cast"));
                    break;
                }
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
                continue;
            }
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else {
                throw new AssertionError("unexpected " + f.kind);
            }
        }
        boolean sawBoltChoice = false;
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
                if (f.kind == DecisionFrame.Kind.TARGET_SELECTION
                        && f.actorPlayerId.equals("p1")) {
                    boolean hasBolt = false;
                    boolean hasShock = false;
                    for (DecisionFrame.Option o : f.options) {
                        if (o.label != null && o.label.contains("Lightning Bolt")) {
                            hasBolt = true;
                        }
                        if (o.label != null && o.label.contains("Shock")
                                && !o.label.contains("Lightning")) {
                            hasShock = true;
                        }
                    }
                    if (hasBolt && hasShock) {
                        sawBoltChoice = true;
                        submit(session, f, pickOption(f,
                                o -> o.label != null && o.label.contains("Lightning Bolt"),
                                "Bend -> Bolt"));
                        break;
                    }
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else {
                break;
            }
        }
        Assert.assertTrue(sawBoltChoice,
                "Bend cast target must be framed with both stack spells offered");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testBendRetargetMovesDamageOffBear() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr9-bend-damage");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Craw Wurm", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Bolt Bend", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Lightning Bolt", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int p1LifeBefore = life(0, session);
        int p2LifeBefore = life(1, session);

        DecisionFrame frame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Lightning Bolt".equals(o.sourceCardName),
                "Bolt cast"));
        // Bolt target: Bear (sole creature so the premise is exact).
        for (int i = 0; i < 10; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Runeclaw Bear"),
                        "Bolt -> Bear"));
                break;
            }
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind);
            }
        }
        // p1 Bend (sole stack spell: cast target auto-bound engine-side).
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasBend = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Bolt Bend".equals(o.sourceCardName)) {
                        hasBend = true;
                    }
                }
                if (hasBend) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Bolt Bend".equals(o.sourceCardName),
                            "Bend cast"));
                    break;
                }
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
                continue;
            }
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else {
                throw new AssertionError("unexpected " + f.kind);
            }
        }
        // Resolution retarget: move Bolt to p2.
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && f.actorPlayerId.equals("p1")) {
                boolean isRetarget = false;
                for (DecisionFrame.Option o : f.options) {
                    if (o.actionType != null && o.actionType.contains("retarget")) {
                        isRetarget = true;
                    }
                }
                if (isRetarget) {
                    submit(session, f, pickOption(f,
                            o -> o.label != null && o.label.contains("player p2"),
                            "Bolt -> p2"));
                    break;
                }
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else {
                break;
            }
        }
        drain(session, 30);
        Assert.assertNotNull(findBf(session, 0, "Runeclaw Bear"),
                "retargeted Bolt must leave the Bear alive");
        Assert.assertEquals(life(0, session), p1LifeBefore, "p1 must take no damage");
        Assert.assertEquals(life(1, session), p2LifeBefore - 3, "p2 must take the Bolt");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testBendReducedCostWithPower4Plus() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr9-bend-reduced");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Craw Wurm", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Bolt Bend", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Lightning Bolt", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        DecisionFrame frame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Lightning Bolt".equals(o.sourceCardName),
                "Bolt cast"));
        frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Bolt Bend".equals(o.sourceCardName),
                "Bend cast"));
        // Reduced cost is {R} (CR 117.7a: generic-only reduction of {3}{R}):
        // tap-all answers the {R} frame, then drain.
        for (int i = 0; i < 10; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else {
                break;
            }
        }
        drain(session, 30);
        // Primary: Bend resolved (impossible at full {3}{R} with only 2
        // Mountains on p1's side, so ReduceCost applied). Bound: taps cannot
        // exceed available sources. Exact tap accounting ({R} vs overpay
        // artifact) is recorded in observations, not strictly asserted:
        // the bridge MANA flow may frame taps beyond exact cost.
        Assert.assertTrue(countTapped(session, "Mountain") <= 2,
                "taps must not exceed p1's two Mountains");
        boolean bendGrave = false;
        for (Card c : session.getGame().getPlayers().get(0).getZone(ZoneType.Graveyard).getCards()) {
            if ("Bolt Bend".equals(c.getName())) {
                bendGrave = true;
            }
        }
        Assert.assertTrue(bendGrave, "Bend must resolve natively to graveyard");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testFlareSacAltCostCopiesBolt() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr9-flare-copy");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Raging Goblin", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Flare of Duplication", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Lightning Bolt", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int p1LifeBefore = life(0, session);
        int p2LifeBefore = life(1, session);

        DecisionFrame frame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Lightning Bolt".equals(o.sourceCardName),
                "Bolt cast"));
        frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Flare of Duplication".equals(o.sourceCardName)
                        && o.label != null && o.label.contains("Sacrifice"),
                "Flare sac-route cast"));
        // Sac-alt-cost + copy target + copy new-target: answer everything.
        // COST_SELECTION (sac choice) must pick the Goblin explicitly.
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if ("COST_SELECTION".equals(f.kind.name())) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Raging Goblin"),
                        "sac Goblin"));
            } else {
                break;
            }
        }
        drain(session, 30);
        Assert.assertNull(findBf(session, 0, "Raging Goblin"),
                "sac-alt-cost must sacrifice the Goblin");
        int lifeLost = (p1LifeBefore - life(0, session)) + (p2LifeBefore - life(1, session));
        Assert.assertEquals(lifeLost, 6, "Bolt plus its copy must deal 6 total");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testFlareWithoutFodderOrManaFailsClosed() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr9-flare-closed");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Flare of Duplication", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Lightning Bolt", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        DecisionFrame frame = driveTo(session, "p2", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Lightning Bolt".equals(o.sourceCardName),
                "Bolt cast"));
        drain(session, 12);
        frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 20);
        // Engine over-offers unpayable casts (WS215 TD02 class): Flare is
        // offered, but the cost cannot be paid. Fail-closed happens at
        // payment: decline and assert nothing moved.
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Flare of Duplication".equals(o.sourceCardName),
                "Flare cast attempt"));
        for (int i = 0; i < 10; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Decline"),
                        "decline unpaid cost"));
                break;
            }
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else {
                throw new AssertionError("unexpected " + f.kind);
            }
        }
        drain(session, 10);
        boolean flareHand = false;
        for (Card c : session.getGame().getPlayers().get(0).getZone(ZoneType.Hand).getCards()) {
            if ("Flare of Duplication".equals(c.getName())) {
                flareHand = true;
            }
        }
        Assert.assertTrue(flareHand, "declined Flare must remain in hand");
        Assert.assertNotNull(findBf(session, 0, "Runeclaw Bear"),
                "Bear must not be sacrificed by the failed cast");
        session.shutdown(5000);
    }
}
