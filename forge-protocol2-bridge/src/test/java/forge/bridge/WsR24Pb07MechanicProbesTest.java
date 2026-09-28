package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * WSR24 PB-07 mechanic probes: characterize the six construction-only
 * denominator cards' actual pipeline behavior (full execution vs exact
 * fail-closed seam). Discovery-first: each probe attempts the complete
 * real-card path and pins the demonstrated truth, never a wish.
 */
public class WsR24Pb07MechanicProbesTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    /** Revisions already answered through the helpers below (drain never re-answers). */
    private static final Set<Long> ANSWERED =
            Collections.synchronizedSet(new HashSet<Long>());

    @BeforeMethod
    public void clearAnswered() {
        ANSWERED.clear();
    }

    private static BridgeSession.SubmitOutcome submit(BridgeSession session,
            DecisionFrame frame, DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
        Assert.assertTrue(outcome.executionOk,
                "submit applied but not executed: " + outcome.errorCode);
        ANSWERED.add(Long.valueOf(frame.revision));
        return outcome;
    }

    /**
     * Intermediate decision selections (cost counts, delve picks): the bridge
     * applies them without an execution phase, so only delivery is asserted.
     */
    private static BridgeSession.SubmitOutcome submitChoice(BridgeSession session,
            DecisionFrame frame, DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
        ANSWERED.add(Long.valueOf(frame.revision));
        return outcome;
    }

    private static boolean frameMatches(DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind) {
        return frame.kind == kind && frame.actorPlayerId.equals(actorId)
                && frame.status == DecisionFrame.Status.SUPPORTED;
    }

    private static void payManaFirstWorking(BridgeSession session) {
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.MANA_PAYMENT);
        for (DecisionFrame.Option option : frame.options) {
            final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                    option.optionId, option.actionType, frame.revision);
            if (outcome.applied) {
                ANSWERED.add(Long.valueOf(frame.revision));
                return;
            }
        }
        throw new AssertionError("no offered mana payment applied");
    }

    private static void settleMana(BridgeSession session) {
        final long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.kind == DecisionFrame.Kind.MANA_PAYMENT
                    && frame.status == DecisionFrame.Status.SUPPORTED) {
                payManaFirstWorking(session);
                continue;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void answerCommon(BridgeSession session, DecisionFrame frame) {
        if (frame.kind == DecisionFrame.Kind.PRIORITY) {
            DecisionFrame.Option pass = null;
            for (DecisionFrame.Option o : frame.options) {
                if (o.isPass) {
                    pass = o;
                    break;
                }
            }
            Assert.assertNotNull(pass, "pass must be offered");
            submit(session, frame, pass);
        } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
            payManaFirstWorking(session);
        } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.TRIGGER_PLAY
                || frame.kind == DecisionFrame.Kind.TRIGGER_ORDER) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
            for (DecisionFrame.Option o : frame.options) {
                if (o.label != null && (o.label.contains("No attack")
                        || o.label.contains("No block"))) {
                    submit(session, frame, o);
                    return;
                }
            }
            throw new AssertionError("no combat decline offered");
        } else {
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
        }
    }

    private static void castFromHand(BridgeSession session, String actorId, String cardName) {
        for (int i = 0; i < 40; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, actorId, DecisionFrame.Kind.PRIORITY)) {
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType) && cardName.equals(o.sourceCardName)) {
                        submit(session, frame, o);
                        settleMana(session);
                        return;
                    }
                }
            }
            answerCommon(session, frame);
        }
        throw new AssertionError("never reached cast of " + cardName + " for " + actorId);
    }

    /**
     * Answers parked frames until no unanswered frame appears and the stack is
     * empty (bounded). The entry frame is answered first when still pending
     * (it may have parked between the last explicit submit and this call);
     * answered revisions are never re-answered. Returns on the first
     * UNSUPPORTED frame so callers can assert the exact fail-closed seam.
     */
    private static void drainToResolution(BridgeSession session) {
        final DecisionFrame entry = session.getCurrentFrame();
        if (entry != null && entry.status == DecisionFrame.Status.SUPPORTED
                && !ANSWERED.contains(Long.valueOf(entry.revision))) {
            System.err.println("[wsr24-probe] drain answering entry " + entry.kind
                    + " actor=" + entry.actorPlayerId + " rev=" + entry.revision);
            answerCommon(session, entry);
        }
        int quietPasses = 0;
        for (int i = 0; i < 60; i++) {
            DecisionFrame frame = null;
            final long deadline = System.currentTimeMillis() + 5000;            long lastLog = 0;
            while (System.currentTimeMillis() < deadline) {
                final DecisionFrame cur = session.getCurrentFrame();
                if (cur != null && !ANSWERED.contains(Long.valueOf(cur.revision))) {
                    frame = cur;
                    break;
                }
                if (session.isTerminal()) {
                    frame = session.getCurrentFrame();
                    break;
                }
                if (System.currentTimeMillis() - lastLog > 1000) {
                    lastLog = System.currentTimeMillis();
                    final DecisionFrame c = session.getCurrentFrame();
                    System.err.println("[wsr24-probe] drain wait cur="
                            + (c == null ? "null" : (c.kind + "/" + c.status + "/"
                            + c.actorPlayerId + "/rev=" + c.revision
                            + "/opts=" + c.options.size()))
                            + " stack=" + session.getGame().getStack().size());
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (frame == null) {
                if (session.getGame().getStack().isEmpty()) {
                    return;
                }
                final StringBuilder stackDump = new StringBuilder();
                try {
                    for (Object si : session.getGame().getStack()) {
                        stackDump.append('[').append(si).append(']');
                    }
                } catch (Throwable t) {
                    stackDump.append("unreadable");
                }
                String phase = "?";
                try {
                    phase = session.getGame().getPhaseHandler().getPhase().name();
                } catch (Throwable t) {
                    phase = "unreadable";
                }
                System.err.println("[wsr24-probe] STALL phase=" + phase
                        + " stack=" + stackDump);
                throw new AssertionError("drain stalled with a non-empty stack");
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                System.err.println("[wsr24-probe] drain hit UNSUPPORTED "
                        + frame.kind + " reason=" + frame.reason);
                return;
            }
            System.err.println("[wsr24-probe] drain answering " + frame.kind
                    + " actor=" + frame.actorPlayerId + " rev=" + frame.revision
                    + " stack=" + session.getGame().getStack().size());
            answerCommon(session, frame);
            if (frame.kind == DecisionFrame.Kind.PRIORITY
                    && session.getGame().getStack().isEmpty()) {
                quietPasses++;
                if (quietPasses >= 4) {
                    return;
                }
            } else {
                quietPasses = 0;
            }
        }
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static void logOptions(String tag, DecisionFrame frame) {
        final StringBuilder seen = new StringBuilder("[wsr24-probe] ").append(tag)
                .append(" kind=").append(frame.kind).append(" status=").append(frame.status);
        for (DecisionFrame.Option o : frame.options) {
            seen.append(" |[").append(o.actionType).append('|').append(o.label).append(']');
        }
        System.err.println(seen);
    }

    // ------------------------------------------------------------------
    // Dig Through Time: delve + look-7-pick-2, then bottom-ordering seam.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testDigThroughTimeDelveThenOrderingSeam() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-dig", 4);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Dig Through Time", ZoneType.Hand);
        for (int i = 0; i < 6; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Graveyard);
        }
        fillLibraries(constructed, 10);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Dig Through Time");

        // Delve count + sequential picks must be framed (COST_SELECTION).
        boolean delveSeen = false;
        boolean pickSeen = false;
        boolean orderedSeen = false;
        for (int i = 0; i < 80 && !orderedSeen; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame, "delve/pick/order frame never parked");
            System.err.println("[wsr24-probe] dig-loop " + frame.kind + "/" + frame.status
                    + " actor=" + frame.actorPlayerId + " rev=" + frame.revision
                    + " opts=" + frame.options.size());
            if (frameMatches(frame, "p1", DecisionFrame.Kind.COST_SELECTION)) {
                delveSeen = true;
                logOptions("dig-delve", frame);
                final String action = frame.options.isEmpty() ? ""
                        : frame.options.get(0).actionType;
                if ("delve_count".equals(action)) {
                    // Pay the maximum: exile all six Memnites for the {6}.
                    submitChoice(session, frame,
                            frame.options.get(frame.options.size() - 1));
                } else {
                    // Sequential delve picks; "Stop delving" sorts last, so
                    // the first option is always a real graveyard card.
                    submitChoice(session, frame, frame.options.get(0));
                }
                continue;
            }
            if (frame.status == DecisionFrame.Status.SUPPORTED
                    && (frame.kind == DecisionFrame.Kind.GENERIC_SELECTION
                    || frame.kind == DecisionFrame.Kind.COPY_CHOICE)) {
                pickSeen = true;
                logOptions("dig-pick", frame);
                Assert.assertEquals(frame.options.size(), 21,
                        "look-7-pick-2 must offer the complete subset set");
                submit(session, frame, frame.options.get(0));
                continue;
            }
            if (frame.status == DecisionFrame.Status.SUPPORTED
                    && frame.kind == DecisionFrame.Kind.ORDER_CHOICE) {
                orderedSeen = true;
                logOptions("dig-order", frame);
                Assert.assertEquals(frame.options.size(), 120,
                        "bottom-5 must offer the complete permutation set");
                submit(session, frame, frame.options.get(0));
                continue;
            }
            if (frame.status == DecisionFrame.Status.UNSUPPORTED) {
                Assert.fail("Dig must fully resolve; fail-closed at " + frame.kind
                        + ": " + frame.reason);
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(delveSeen, "delve choice must be offered");
        Assert.assertTrue(pickSeen, "look-7-pick-2 must be offered");
        Assert.assertTrue(orderedSeen, "bottom-5 arrangement must be offered");
        drainToResolution(session);
        // End state: 6 Memnites delved to exile, 2 picks in hand (library
        // 10-1 draw-2 picks = 7), Dig in graveyard, Islands tapped for UU.
        int exiledMemnites = 0;
        for (Card c : constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Exile).getCards()) {
            if ("Memnite".equals(c.getName())) {
                exiledMemnites++;
            }
        }
        final int librarySize = constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Library).getCards().size();
        final int handSize = constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Hand).getCards().size();
        boolean digYard = false;
        for (Card c : constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Graveyard).getCards()) {
            if ("Dig Through Time".equals(c.getName())) {
                digYard = true;
            }
        }
        System.err.println("[wsr24-probe] delved=" + exiledMemnites + " lib=" + librarySize
                + " hand=" + handSize + " digYard=" + digYard);
        Assert.assertEquals(exiledMemnites, 6, "delve must exile six");
        Assert.assertEquals(librarySize, 7, "two picks must leave the library");
        Assert.assertEquals(handSize, 3, "draw plus two picks in hand");
        Assert.assertTrue(digYard, "Dig must resolve to graveyard");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Vandalblast: normal cast + overload attempt.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testVandalblastNormalAndOverload() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-vandal", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 6; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Vandalblast", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Vandalblast", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Ornithopter", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Ornithopter", ZoneType.Battlefield);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        // Survey the offered Vandalblast variants before casting.
        int vandalOptions = 0;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.PRIORITY)) {
                vandalOptions = 0;
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Vandalblast".equals(o.sourceCardName)) {
                        vandalOptions++;
                    }
                }
                logOptions("vandal-priority", frame);
                if (vandalOptions > 0) {
                    break;
                }
            }
            answerCommon(session, frame);
        }
        System.err.println("[wsr24-probe] vandal variants offered: " + vandalOptions);
        Assert.assertTrue(vandalOptions >= 1, "normal Vandalblast must be offered");

        castFromHand(session, "p1", "Vandalblast");
        boolean targeted = false;
        for (int i = 0; i < 30 && !targeted; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.TARGET_SELECTION)) {
                logOptions("vandal-target", frame);
                submit(session, frame, frame.options.get(0));
                targeted = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(targeted, "Vandalblast target must be chosen");
        drainToResolution(session);
        int ornithopters = 0;
        for (Card c : constructed.game.getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Ornithopter".equals(c.getName())) {
                ornithopters++;
            }
        }
        System.err.println("[wsr24-probe] ornithopters left: " + ornithopters);
        Assert.assertEquals(ornithopters, 1, "exactly one artifact must be destroyed");

        // Overload execution: the second copy pays {4}{R} to destroy each.
        boolean overloadCast = false;
        for (int i = 0; i < 40 && !overloadCast; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.PRIORITY)) {
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Vandalblast".equals(o.sourceCardName)
                            && o.label != null && o.label.contains("{4}{R}")) {
                        logOptions("vandal-overload", frame);
                        submit(session, frame, o);
                        settleMana(session);
                        overloadCast = true;
                        break;
                    }
                }
                if (overloadCast) {
                    break;
                }
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(overloadCast, "overload variant must be castable");
        drainToResolution(session);
        ornithopters = 0;
        for (Card c : constructed.game.getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Ornithopter".equals(c.getName())) {
                ornithopters++;
            }
        }
        System.err.println("[wsr24-probe] ornithopters after overload: " + ornithopters);
        Assert.assertEquals(ornithopters, 0, "overload must destroy each artifact");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Shriekmaw: hardcast + evoke attempt.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testShriekmawHardcastAndEvoke() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-shriek", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 7; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Shriekmaw", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Shriekmaw", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Battlefield);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        int shriekOptions = 0;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.PRIORITY)) {
                shriekOptions = 0;
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Shriekmaw".equals(o.sourceCardName)) {
                        shriekOptions++;
                    }
                }
                logOptions("shriek-priority", frame);
                if (shriekOptions > 0) {
                    break;
                }
            }
            answerCommon(session, frame);
        }
        System.err.println("[wsr24-probe] shriek variants offered: " + shriekOptions);

        castFromHand(session, "p1", "Shriekmaw");
        boolean triggerTargeted = false;
        for (int i = 0; i < 40 && !triggerTargeted; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.TARGET_SELECTION)) {
                logOptions("shriek-etb", frame);
                submit(session, frame, frame.options.get(0));
                triggerTargeted = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(triggerTargeted, "Shriekmaw ETB target must be chosen");
        drainToResolution(session);
        int bears = 0;
        for (Card c : constructed.game.getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Grizzly Bears".equals(c.getName())) {
                bears++;
            }
        }
        System.err.println("[wsr24-probe] bears left: " + bears);
        Assert.assertEquals(bears, 1, "ETB must destroy exactly one Bear");

        // Evoke execution: the second copy pays {1}{B}, enters, is sacrificed,
        // and its ETB destroys the last Bear.
        boolean evokeCast = false;
        for (int i = 0; i < 40 && !evokeCast; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.PRIORITY)) {
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Shriekmaw".equals(o.sourceCardName)
                            && o.label != null && o.label.contains("{1}{B}")) {
                        logOptions("shriek-evoke", frame);
                        submit(session, frame, o);
                        settleMana(session);
                        evokeCast = true;
                        break;
                    }
                }
                if (evokeCast) {
                    break;
                }
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(evokeCast, "evoke variant must be castable");
        // The evoked ETB finds a sole legal target (one Bear left), which the
        // engine forces with no frame; ordering and sacrifice resolve via drain.
        drainToResolution(session);
        bears = 0;
        for (Card c : constructed.game.getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Grizzly Bears".equals(c.getName())) {
                bears++;
            }
        }
        int shriekYard = 0;
        for (Card c : constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Graveyard).getCards()) {
            if ("Shriekmaw".equals(c.getName())) {
                shriekYard++;
            }
        }
        System.err.println("[wsr24-probe] bears=" + bears + " evoked-yard=" + shriekYard);
        Assert.assertEquals(bears, 0, "evoked ETB must destroy the last Bear");
        Assert.assertTrue(shriekYard >= 1, "evoked Shriekmaw must be sacrificed");
        int shriekBattlefield = 0;
        for (Card c : constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Shriekmaw".equals(c.getName())) {
                shriekBattlefield++;
            }
        }
        Assert.assertEquals(shriekBattlefield, 1,
                "hardcast Shriekmaw must survive (only evoke sacrifices)");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Find // Finality: Find from hand + aftermath attempt.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testFindAndAftermath() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-find", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Find // Finality", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Graveyard);
        BridgeTestSupport.addCard(constructed.game, 0, "Ornithopter", ZoneType.Graveyard);
        // Finality behaviour discriminator: P1's 4/4 survives after two
        // +1/+1 counters then -4/-4; P2's unboosted 2/2 dies.
        BridgeTestSupport.addCard(constructed.game, 0, "Serra Angel", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Battlefield);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Find // Finality");
        boolean returned = false;
        for (int i = 0; i < 40 && !returned; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.TARGET_SELECTION)) {
                // TargetMin 0 offers the empty set first; choose both creatures.
                DecisionFrame.Option both = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null && o.label.contains("Memnite")
                            && o.label.contains("Ornithopter")) {
                        both = o;
                        break;
                    }
                }
                Assert.assertNotNull(both, "both-creatures target must be offered");
                logOptions("find-choice", frame);
                submit(session, frame, both);
                returned = true;
                continue;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(returned, "Find targets must be chosen");
        drainToResolution(session);
        int handCreatures = 0;
        for (Card c : constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Hand).getCards()) {
            if ("Memnite".equals(c.getName()) || "Ornithopter".equals(c.getName())) {
                handCreatures++;
            }
        }
        System.err.println("[wsr24-probe] creatures returned: " + handCreatures);
        Assert.assertTrue(handCreatures >= 1, "Find must return at least one creature");

        // Aftermath: Finality must now be engine-enumerated from the graveyard,
        // offered as a real cast, paid through the normal mana pipeline, and
        // resolved with its actual asymmetric creature effect.
        DecisionFrame.Option finality = null;
        DecisionFrame finalityFrame = null;
        for (int i = 0; i < 20 && finality == null; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.PRIORITY)) {
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType)
                            && o.sourceCardName != null
                            && o.sourceCardName.contains("Finality")) {
                        finality = o;
                        finalityFrame = frame;
                        break;
                    }
                }
                if (finality != null) {
                    break;
                }
            }
            answerCommon(session, frame);
        }
        Assert.assertNotNull(finality, "Finality aftermath cast must be engine-offered");
        Assert.assertNotNull(finalityFrame);
        submit(session, finalityFrame, finality);
        settleMana(session);
        drainToResolution(session);

        boolean serraSurvives = false;
        for (Card card : constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Serra Angel".equals(card.getName())) {
                serraSurvives = true;
            }
        }
        boolean opponentBearSurvives = false;
        for (Card card : constructed.game.getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Grizzly Bears".equals(card.getName())) {
                opponentBearSurvives = true;
            }
        }
        boolean aftermathExiled = false;
        for (Card card : constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Exile).getCards()) {
            if (card.getName() != null && card.getName().contains("Finality")) {
                aftermathExiled = true;
            }
        }
        Assert.assertTrue(serraSurvives,
                "own 4/4 must survive Finality after +2/+2 counters and -4/-4");
        Assert.assertFalse(opponentBearSurvives,
                "opponent 2/2 must die to Finality's -4/-4");
        Assert.assertTrue(aftermathExiled,
                "an aftermath spell cast from the graveyard must be exiled on resolution");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Basilisk Collar: cast + equip + keywords.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testBasiliskCollarEquip() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-collar", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Basilisk Collar", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Battlefield);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Basilisk Collar");
        boolean equipped = false;
        for (int i = 0; i < 40 && !equipped; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.PRIORITY)) {
                DecisionFrame.Option equip = null;
                for (DecisionFrame.Option o : frame.options) {
                    if ("activate_ability".equals(o.actionType)
                            && "Basilisk Collar".equals(o.sourceCardName)) {
                        equip = o;
                        break;
                    }
                }
                if (equip != null) {
                    logOptions("collar-equip", frame);
                    submit(session, frame, equip);
                    settleMana(session);
                    equipped = true;
                    break;
                }
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(equipped, "equip ability must be offered");
        // Equip targets the sole friendly creature: the engine forces a lone
        // legal target with no frame, so drain straight to resolution.
        drainToResolution(session);
        Card memnite = null;
        for (Card c : constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Memnite".equals(c.getName())) {
                memnite = c;
            }
        }
        Assert.assertNotNull(memnite, "Memnite must survive");
        System.err.println("[wsr24-probe] equipped=" + memnite.isEquipped()
                + " deathtouch=" + memnite.hasKeyword("Deathtouch")
                + " lifelink=" + memnite.hasKeyword("Lifelink"));
        Assert.assertTrue(memnite.isEquipped(), "Memnite must be equipped");
        Assert.assertTrue(memnite.hasKeyword("Deathtouch"), "collar grants deathtouch");
        Assert.assertTrue(memnite.hasKeyword("Lifelink"), "collar grants lifelink");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Gratuitous Violence: damage doubling through real combat.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testGratuitousViolenceDoublesDamage() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-gratuitous", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Gratuitous Violence",
                ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Battlefield);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Gratuitous Violence");

        // Advance to combat and attack p2 with Memnite (1 power).
        boolean attacked = false;
        for (int i = 0; i < 40 && !attacked; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS)) {
                DecisionFrame.Option attack = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null && o.label.contains("Memnite")
                            && o.label.contains("p2")) {
                        attack = o;
                        break;
                    }
                }
                Assert.assertNotNull(attack, "Memnite attack on p2 must be offered");
                logOptions("gratuitous-attack", frame);
                submit(session, frame, attack);
                attacked = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(attacked, "attack declaration must park");
        // p2 controls no creatures, so the engine forces no-blocks with no
        // frame; answer one explicitly if it ever parks, then resolve.
        for (int i = 0; i < 10; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 5000);
            if (frame == null) {
                break;
            }
            if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS
                    && frame.status == DecisionFrame.Status.SUPPORTED) {
                DecisionFrame.Option noBlock = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null && o.label.contains("No block")) {
                        noBlock = o;
                        break;
                    }
                }
                if (noBlock != null) {
                    submit(session, frame, noBlock);
                    break;
                }
            }
            answerCommon(session, frame);
        }
        drainToResolution(session);
        final int p2Life = constructed.game.getPlayers().get(1).getLife();
        System.err.println("[wsr24-probe] p2 life=" + p2Life);
        Assert.assertEquals(p2Life, 38, "1-power attacker must deal 2 under doubling");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Ishai: opponent spell triggers a +1/+1 counter (Memnite is free).
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testIshaiCounterOnOpponentSpell() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-ishai", 4);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Ishai, Ojutai Dragonspeaker",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Memnite", ZoneType.Hand);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        boolean p2Main = false;
        for (int i = 0; i < 60 && !p2Main; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            String turnPlayer = "?";
            try {
                turnPlayer = session.playerIdOf(
                        session.getGame().getPhaseHandler().getPlayerTurn());
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
            if (turnPlayer.equals("p2") && BridgeTestSupport.isMainPhase(session)
                    && frameMatches(frame, "p2", DecisionFrame.Kind.PRIORITY)) {
                p2Main = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(p2Main, "never reached p2 main phase");
        castFromHand(session, "p2", "Memnite");
        drainToResolution(session);
        Card ishai = null;
        for (Card c : constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Ishai, Ojutai Dragonspeaker".equals(c.getName())) {
                ishai = c;
            }
        }
        Assert.assertNotNull(ishai, "Ishai must hold the battlefield");
        int counters = 0;
        final StringBuilder counterNames = new StringBuilder();
        try {
            for (forge.game.card.CounterType type : ishai.getCounters().elementSet()) {
                if (type != null) {
                    final int n = ishai.getCounters(type);
                    counters += n;
                    counterNames.append(type.getName()).append('x').append(n).append(';');
                }
            }
        } catch (Throwable t) {
            Assert.fail("counter read failed: " + t);
        }
        System.err.println("[wsr24-probe] ishai counters=" + counters
                + " [" + counterNames + "]");
        Assert.assertEquals(counters, 1, "opponent spell must grow Ishai once");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Wash Away via cleave: counter a hand-cast spell in response.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testWashAwayCleaveCountersHandCast() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-wash", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Wash Away", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Memnite", ZoneType.Hand);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        boolean p2Main = false;
        for (int i = 0; i < 60 && !p2Main; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            String turnPlayer = "?";
            try {
                turnPlayer = session.playerIdOf(
                        session.getGame().getPhaseHandler().getPlayerTurn());
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
            if (turnPlayer.equals("p2") && BridgeTestSupport.isMainPhase(session)
                    && frameMatches(frame, "p2", DecisionFrame.Kind.PRIORITY)) {
                p2Main = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(p2Main, "never reached p2 main phase");
        castFromHand(session, "p2", "Memnite");
        // Respond before Memnite resolves: the {U} base mode cannot touch a
        // hand-cast spell, so the {1}{U}{U} cleave variant must be taken.
        boolean responded = false;
        for (int i = 0; i < 30 && !responded; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.PRIORITY)) {
                DecisionFrame.Option cleave = null;
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Wash Away".equals(o.sourceCardName)
                            && o.label != null && o.label.contains("{1}{U}{U}")) {
                        cleave = o;
                        break;
                    }
                }
                if (cleave != null) {
                    logOptions("wash-cleave", frame);
                    submit(session, frame, cleave);
                    settleMana(session);
                    responded = true;
                    break;
                }
                throw new AssertionError("Wash Away must be offered in response");
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(responded, "cleave response must be cast");
        // Memnite is the lone spell on the stack: the engine forces the sole
        // legal target with no frame, so drain straight to resolution.
        drainToResolution(session);
        boolean memniteYard = false;
        for (Card c : constructed.game.getPlayers().get(1)
                .getZone(ZoneType.Graveyard).getCards()) {
            if ("Memnite".equals(c.getName())) {
                memniteYard = true;
            }
        }
        boolean memniteBf = false;
        for (Card c : constructed.game.getPlayers().get(1)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Memnite".equals(c.getName())) {
                memniteBf = true;
            }
        }
        System.err.println("[wsr24-probe] wash memniteYard=" + memniteYard
                + " memniteBf=" + memniteBf);
        Assert.assertTrue(memniteYard, "countered Memnite must be in graveyard");
        Assert.assertFalse(memniteBf, "countered Memnite must never land");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Psychosis Crawler: card draw drains every opponent.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testPsychosisCrawlerDrainsOnDraw() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-crawler", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Psychosis Crawler", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Opt", ZoneType.Hand);
        fillLibraries(constructed, 8);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Psychosis Crawler");
        // The Crawler must resolve BEFORE Opt is cast: otherwise Opt resolves
        // first (LIFO) while the trigger source is still on the stack.
        boolean crawlerLanded = false;
        for (int i = 0; i < 30 && !crawlerLanded; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                for (Card c : constructed.game.getPlayers().get(0)
                        .getCardsIn(ZoneType.Battlefield)) {
                    if ("Psychosis Crawler".equals(c.getName())) {
                        crawlerLanded = true;
                    }
                }
                if (crawlerLanded) {
                    break;
                }
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(crawlerLanded, "Crawler must resolve first");
        castFromHand(session, "p1", "Opt");
        boolean scryed = false;
        for (int i = 0; i < 30 && !scryed; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.GENERIC_SELECTION)) {
                submit(session, frame, frame.options.get(0));
                scryed = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(scryed, "Opt scry must resolve");
        drainToResolution(session);
        final int p1Life = constructed.game.getPlayers().get(0).getLife();
        final int p2Life = constructed.game.getPlayers().get(1).getLife();
        final int p3Life = constructed.game.getPlayers().get(2).getLife();
        final int p4Life = constructed.game.getPlayers().get(3).getLife();
        System.err.println("[wsr24-probe] crawler lives=" + p1Life + "/" + p2Life
                + "/" + p3Life + "/" + p4Life);
        Assert.assertEquals(p1Life, 40, "controller untouched");
        Assert.assertEquals(p2Life, 39, "draw must drain p2");
        Assert.assertEquals(p3Life, 39, "draw must drain p3");
        Assert.assertEquals(p4Life, 39, "draw must drain p4");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Kaervek: opponent spell burns any target for its mana value.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testKaervekBurnsManaValue() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-kaervek", 4);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Kaervek the Merciless",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Hand);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        boolean p2Main = false;
        for (int i = 0; i < 60 && !p2Main; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            String turnPlayer = "?";
            try {
                turnPlayer = session.playerIdOf(
                        session.getGame().getPhaseHandler().getPlayerTurn());
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
            if (turnPlayer.equals("p2") && BridgeTestSupport.isMainPhase(session)
                    && frameMatches(frame, "p2", DecisionFrame.Kind.PRIORITY)) {
                p2Main = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(p2Main, "never reached p2 main phase");
        castFromHand(session, "p2", "Grizzly Bears");
        boolean burned = false;
        for (int i = 0; i < 40 && !burned; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.TARGET_SELECTION)) {
                logOptions("kaervek-target", frame);
                DecisionFrame.Option victim = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null && o.label.contains("p2")) {
                        victim = o;
                        break;
                    }
                }
                Assert.assertNotNull(victim, "p2 must be targetable");
                submit(session, frame, victim);
                burned = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(burned, "Kaervek trigger target must be chosen");
        drainToResolution(session);
        final int p2Life = constructed.game.getPlayers().get(1).getLife();
        System.err.println("[wsr24-probe] kaervek p2 life=" + p2Life);
        Assert.assertEquals(p2Life, 38, "MV-2 spell must burn p2 for 2");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Warstorm Surge: entering creature hits any target for its power.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testWarstormSurgeHitsForPower() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-surge", 4);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Warstorm Surge",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Hand);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Memnite");
        boolean shot = false;
        for (int i = 0; i < 40 && !shot; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.TARGET_SELECTION)) {
                logOptions("surge-target", frame);
                DecisionFrame.Option victim = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null && o.label.contains("p2")) {
                        victim = o;
                        break;
                    }
                }
                Assert.assertNotNull(victim, "p2 must be targetable");
                submit(session, frame, victim);
                shot = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(shot, "Surge trigger target must be chosen");
        drainToResolution(session);
        final int p2Life = constructed.game.getPlayers().get(1).getLife();
        System.err.println("[wsr24-probe] surge p2 life=" + p2Life);
        Assert.assertEquals(p2Life, 39, "1-power entry must hit p2 for 1");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Burn Down the House, Devils mode: three hasty tokens.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testBurnDownTheHouseDevils() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-burn", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Burn Down the House",
                ZoneType.Hand);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Burn Down the House");
        boolean moded = false;
        for (int i = 0; i < 30 && !moded; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.MODE_SELECTION)) {
                logOptions("burn-mode", frame);
                DecisionFrame.Option devils = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null
                            && o.label.toLowerCase(java.util.Locale.ROOT)
                                    .contains("devil")) {
                        devils = o;
                        break;
                    }
                }
                Assert.assertNotNull(devils, "Devils mode must be offered");
                submit(session, frame, devils);
                moded = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(moded, "modal choice must park");
        drainToResolution(session);
        int devils = 0;
        for (Card c : constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if (c.getName() != null
                    && c.getName().toLowerCase(java.util.Locale.ROOT)
                            .contains("devil")) {
                devils++;
            }
        }
        System.err.println("[wsr24-probe] devils=" + devils);
        Assert.assertEquals(devils, 3, "Devils mode must create three tokens");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Butcher of Malakir: own death forces every opponent to sacrifice.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testButcherForcesSacrifice() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-butcher", 4);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Butcher of Malakir",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Lightning Bolt", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 2, "Ornithopter", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 3, "Phyrexian Walker",
                ZoneType.Battlefield);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        boolean p2Main = false;
        for (int i = 0; i < 60 && !p2Main; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            String turnPlayer = "?";
            try {
                turnPlayer = session.playerIdOf(
                        session.getGame().getPhaseHandler().getPlayerTurn());
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
            if (turnPlayer.equals("p2") && BridgeTestSupport.isMainPhase(session)
                    && frameMatches(frame, "p2", DecisionFrame.Kind.PRIORITY)) {
                p2Main = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(p2Main, "never reached p2 main phase");
        castFromHand(session, "p2", "Lightning Bolt");
        // Bolt targets p1's Memnite (unique name on the board).
        boolean bolted = false;
        for (int i = 0; i < 30 && !bolted; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p2", DecisionFrame.Kind.TARGET_SELECTION)) {
                logOptions("butcher-bolt", frame);
                DecisionFrame.Option victim = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null && o.label.contains("Memnite")) {
                        victim = o;
                        break;
                    }
                }
                Assert.assertNotNull(victim, "p1 Memnite must be targetable");
                submit(session, frame, victim);
                bolted = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(bolted, "Bolt target must be chosen");
        drainToResolution(session);
        final Set<String> p3Yard = new HashSet<>();
        for (Card c : constructed.game.getPlayers().get(2)
                .getZone(ZoneType.Graveyard).getCards()) {
            p3Yard.add(c.getName());
        }
        final Set<String> p4Yard = new HashSet<>();
        for (Card c : constructed.game.getPlayers().get(3)
                .getZone(ZoneType.Graveyard).getCards()) {
            p4Yard.add(c.getName());
        }
        boolean p1MemniteYard = false;
        for (Card c : constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Graveyard).getCards()) {
            if ("Memnite".equals(c.getName())) {
                p1MemniteYard = true;
            }
        }
        System.err.println("[wsr24-probe] butcher p3yard=" + p3Yard + " p4yard=" + p4Yard
                + " p1memniteYard=" + p1MemniteYard);
        Assert.assertTrue(p1MemniteYard, "Bolt must kill p1 Memnite");
        Assert.assertTrue(p3Yard.contains("Ornithopter"), "p3 must sacrifice Ornithopter");
        Assert.assertTrue(p4Yard.contains("Phyrexian Walker"), "p4 must sacrifice Walker");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Makeshift Mannequin: reanimate with counter.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testMakeshiftMannequinReanimate() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-mannequin", 4);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Makeshift Mannequin", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Graveyard);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Makeshift Mannequin");
        // Sole legal target (own Memnite in graveyard) is forced with no frame.
        drainToResolution(session);
        Card memnite = null;
        for (Card c : constructed.game.getPlayers().get(0)
                .getCardsIn(ZoneType.Battlefield)) {
            if ("Memnite".equals(c.getName())) {
                memnite = c;
            }
        }
        Assert.assertNotNull(memnite, "Memnite must be reanimated");
        int counters = 0;
        try {
            for (forge.game.card.CounterType type
                    : memnite.getCounters().elementSet()) {
                if (type != null && type.getName() != null
                        && type.getName().toUpperCase(java.util.Locale.ROOT)
                                .contains("MANNEQUIN")) {
                    counters = memnite.getCounters(type);
                }
            }
        } catch (Throwable t) {
            counters = -1;
        }
        System.err.println("[wsr24-probe] mannequin counters=" + counters);
        Assert.assertTrue(counters > 0, "mannequin counter must be present");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Narset, Parter of Veils: opponent extra draws are prevented.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testNarsetPreventsExtraDraw() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-narset", 4);
        final BridgeSession session = constructed.session;
        // Narset is HARDCAST (not placed): engine resolution sets proper
        // last-known-zone bookkeeping, which zone-gated statics (CantDraw)
        // require. Direct battlefield placement leaves LKI stale.
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Narset, Parter of Veils",
                ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Opt", ZoneType.Hand);
        fillLibraries(constructed, 8);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Narset, Parter of Veils");
        boolean narsetLanded = false;
        for (int i = 0; i < 30 && !narsetLanded; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                for (Card c : constructed.game.getPlayers().get(0)
                        .getCardsIn(ZoneType.Battlefield)) {
                    if ("Narset, Parter of Veils".equals(c.getName())) {
                        narsetLanded = true;
                    }
                }
                if (narsetLanded) {
                    break;
                }
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(narsetLanded, "Narset must resolve to the battlefield");

        // Advance to p2's main phase (draw step already passed: library stable).
        boolean p2Main = false;
        for (int i = 0; i < 60 && !p2Main; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            final String turnPlayer;
            try {
                turnPlayer = session.playerIdOf(
                        session.getGame().getPhaseHandler().getPlayerTurn());
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
            if (turnPlayer.equals("p2") && BridgeTestSupport.isMainPhase(session)
                    && frameMatches(frame, "p2", DecisionFrame.Kind.PRIORITY)) {
                p2Main = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(p2Main, "never reached p2 main phase");
        final int libBefore = constructed.game.getPlayers().get(1)
                .getZone(ZoneType.Library).getCards().size();
        // Privileged static diagnostics (characterization only).
        try {
            final forge.game.player.Player p2diag =
                    constructed.game.getPlayers().get(1);
            System.err.println("[wsr24-probe] narset drawnThisTurn="
                    + p2diag.getNumDrawnThisTurn() + " clamp1="
                    + forge.game.staticability.StaticAbilityCantDraw.canDrawAmount(
                            p2diag, 1));
            for (final forge.game.card.Card ca : constructed.game.getCardsIn(
                    forge.game.zone.ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
                for (final forge.game.staticability.StaticAbility stAb
                        : ca.getStaticAbilities()) {
                    if (stAb.getParam("Mode") != null
                            && stAb.getParam("Mode").contains("CantDraw")) {
                        System.err.println("[wsr24-probe] narset static on "
                                + ca.getName() + " conditions="
                                + stAb.checkConditions(forge.game.staticability
                                        .StaticAbilityMode.CantDraw)
                                + " modes=" + stAb.getMode()
                                + " suppressed=" + stAb.isSuppressed()
                                + " zonesCheck=" + stAb.zonesCheck()
                                + " inPlay=" + ca.isInPlay()
                                + " controller=" + ca.getController());
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[wsr24-probe] narset diag failed: " + t);
        }

        castFromHand(session, "p2", "Opt");
        // Scry resolves normally (look is unaffected); only the draw is cut.
        boolean scryed = false;
        for (int i = 0; i < 30 && !scryed; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p2", DecisionFrame.Kind.GENERIC_SELECTION)) {
                submit(session, frame, frame.options.get(0));
                scryed = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(scryed, "Opt scry must resolve under Narset");
        drainToResolution(session);
        final int handAfter = constructed.game.getPlayers().get(1)
                .getZone(ZoneType.Hand).getCards().size();
        final int libAfter = constructed.game.getPlayers().get(1)
                .getZone(ZoneType.Library).getCards().size();
        boolean optYard = false;
        for (Card c : constructed.game.getPlayers().get(1)
                .getZone(ZoneType.Graveyard).getCards()) {
            if ("Opt".equals(c.getName())) {
                optYard = true;
            }
        }
        System.err.println("[wsr24-probe] narset hand=" + handAfter + " lib=" + libBefore
                + "->" + libAfter + " optYard=" + optYard);
        Assert.assertTrue(optYard, "Opt must resolve to graveyard");
        Assert.assertEquals(handAfter, 1,
                "extra draw prevented: only the turn-draw Plains remains");
        Assert.assertEquals(libAfter, libBefore, "library must not shrink by a draw");
        session.shutdown(5000);
    }

    // ------------------------------------------------------------------
    // Esior, Wardwing Familiar: flying evasion through real combat.
    // ------------------------------------------------------------------

    @Test(timeOut = 300000)
    public void testEsiorFlyingEvasion() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-esior", 4);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Esior, Wardwing Familiar",
                ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Battlefield);
        fillLibraries(constructed, 5);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Esior, Wardwing Familiar");
        Card esior = null;
        for (int i = 0; i < 30 && esior == null; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                for (Card c : constructed.game.getPlayers().get(0)
                        .getCardsIn(ZoneType.Battlefield)) {
                    if ("Esior, Wardwing Familiar".equals(c.getName())) {
                        esior = c;
                    }
                }
                if (esior != null) {
                    break;
                }
            }
            answerCommon(session, frame);
        }
        Assert.assertNotNull(esior, "Esior must resolve to the battlefield");
        boolean flying = false;
        try {
            flying = esior.hasKeyword("Flying");
        } catch (Throwable t) {
            flying = false;
        }
        Assert.assertTrue(flying, "Esior must fly");

        boolean attacked = false;
        // Esior is summoning-sick on the cast turn: keep passing (answering
        // everything) until a later turn's declaration offers it.
        for (int i = 0; i < 160 && !attacked; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS)) {
                DecisionFrame.Option attack = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null && o.label.contains("Esior")
                            && o.label.contains("p2")) {
                        attack = o;
                        break;
                    }
                }
                Assert.assertNotNull(attack, "Esior attack on p2 must be offered");
                submit(session, frame, attack);
                attacked = true;
                break;
            }
            answerCommon(session, frame);
        }
        Assert.assertTrue(attacked, "attack declaration must park");
        // Ground-only defenders cannot block a flyer: tolerate a No-block
        // frame or none at all, then resolve.
        for (int i = 0; i < 10; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 5000);
            if (frame == null) {
                break;
            }
            if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS
                    && frame.status == DecisionFrame.Status.SUPPORTED) {
                DecisionFrame.Option noBlock = null;
                for (DecisionFrame.Option o : frame.options) {
                    if (o.label != null && o.label.contains("No block")) {
                        noBlock = o;
                        break;
                    }
                }
                if (noBlock != null) {
                    submit(session, frame, noBlock);
                    break;
                }
            }
            answerCommon(session, frame);
        }
        drainToResolution(session);
        final int p2Life = constructed.game.getPlayers().get(1).getLife();
        System.err.println("[wsr24-probe] esior p2 life=" + p2Life);
        Assert.assertEquals(p2Life, 39, "flyer must deal 1 through ground defenders");
        session.shutdown(5000);
    }
}
