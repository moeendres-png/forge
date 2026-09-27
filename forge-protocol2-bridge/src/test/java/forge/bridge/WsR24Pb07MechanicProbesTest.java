package forge.bridge;

import com.google.gson.JsonObject;
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

        // Aftermath survey: is Finality offered from the graveyard?
        boolean aftermathSeen = false;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, "p1", DecisionFrame.Kind.PRIORITY)) {
                for (DecisionFrame.Option o : frame.options) {
                    if (o.sourceCardName != null
                            && o.sourceCardName.contains("Finality")) {
                        aftermathSeen = true;
                    }
                }
                logOptions("aftermath-survey", frame);
                break;
            }
            answerCommon(session, frame);
        }
        System.err.println("[wsr24-probe] aftermath offered: " + aftermathSeen);
        // Engine-side diagnosis (privileged read, characterization only): does
        // the engine surface Finality through its external-zone grant index?
        try {
            final forge.game.player.Player p1 =
                    constructed.game.getPlayers().get(0);
            boolean inGrantIndex = false;
            for (Card c : p1.getCardsActivatableInExternalZones(true)) {
                if (c != null && c.getName() != null
                        && c.getName().contains("Finality")) {
                    inGrantIndex = true;
                }
            }
            System.err.println("[wsr24-probe] aftermath in grant index: " + inGrantIndex);
        } catch (Throwable t) {
            System.err.println("[wsr24-probe] grant index unreadable: " + t);
        }
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
}
