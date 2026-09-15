package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * WS236 F4 bridge discriminator (seam B): You-filtered SpellCast-family via
 * authoritative constructed-game execution. Adapted from the disabled WS234
 * Veyran probe (never runtime-observed); WS234 bytes stay untouched.
 *
 * <p>Engine owns legality throughout: real cards, real casts through the
 * bridge decision boundary, real priority flow (which performs the engine's
 * simultaneous-trigger ordering step). No manual triggers, no injected
 * outcomes.
 */
public class WS236F4BridgeTest {

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
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
        return outcome;
    }

    private static DecisionFrame driveTo(BridgeSession session, String actorId,
            DecisionFrame.Kind kind, String phaseFragment, int budget) {
        long lastRevision = -1;
        final DecisionFrame current = session.getCurrentFrame();
        if (current != null) {
            lastRevision = current.revision;
            if (frameMatches(session, current, actorId, kind, phaseFragment)) {
                return current;
            }
        }
        for (int i = 0; i < budget; i++) {
            final DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind);
            lastRevision = frame.revision;
            if (frameMatches(session, frame, actorId, kind, phaseFragment)) {
                return frame;
            }
            if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                BridgeTestSupport.submitKeep(session, frame);
                continue;
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                throw new AssertionError("blocked by " + frame.kind + " " + frame.status);
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
                continue;
            }
            if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, frame, frame.options.get(0));
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
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
        }
        throw new AssertionError("never reached " + kind + " for " + actorId);
    }

    private static boolean frameMatches(BridgeSession session, DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind, String phaseFragment) {
        if (frame.kind != kind || !frame.actorPlayerId.equals(actorId)
                || frame.status != DecisionFrame.Status.SUPPORTED) {
            return false;
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
        DecisionFrame frame = driveTo(session, actorId, DecisionFrame.Kind.PRIORITY, "MAIN", 60);
        final DecisionFrame.Option tap = pickOption(frame,
                o -> "activate_ability".equals(o.actionType) && landName.equals(o.sourceCardName),
                landName + " tap");
        submit(session, frame, tap);
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame after tap");
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.actorPlayerId.equals(actorId)) {
                return;
            }
            if (parked.kind == DecisionFrame.Kind.COLOR_CHOICE
                    && parked.actorPlayerId.equals(actorId)) {
                submit(session, parked, parked.options.get(0));
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

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static Card findBf(BridgeSession session, int seat, String name) {
        for (Card card : session.getGame().getPlayers().get(seat)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    private static void drainPasses(BridgeSession session) {
        for (int i = 0; i < 60; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else if (parked.kind == DecisionFrame.Kind.TRIGGER_ORDER) {
                // WS236: Veyran's Panharmonicon doubling yields two identical
                // +1/+1 magecraft triggers; the bridge correctly parks
                // TRIGGER_ORDER (CR 603.3b). Both permutations are
                // outcome-equivalent here; answer the first offered order and
                // let the engine play them. This is test-driver completion,
                // not an outcome: the engine offered every order.
                System.out.println("WS236-OBSERVE bridge trigger_order options="
                        + parked.options.size() + " first="
                        + parked.options.get(0).label);
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
    }

    @Test(timeOut = 300000)
    public void testVeyranDoubledMagecraftViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws236-veyran");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Veyran, Voice of Duality",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Divination", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Island");
        tapLand(session, "p1", "Island");
        tapLand(session, "p1", "Island");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        Card veyranBefore = findBf(session, 0, "Veyran, Voice of Duality");
        Assert.assertNotNull(veyranBefore);
        int beforeP = veyranBefore.getNetPower();
        int beforeT = veyranBefore.getNetToughness();
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Divination".equals(o.sourceCardName),
                "Divination cast"));
        System.out.println("WS236-OBSERVE bridge veyran before=" + beforeP + "/" + beforeT);
        drainPasses(session);
        Card veyran = findBf(session, 0, "Veyran, Voice of Duality");
        Assert.assertNotNull(veyran);
        System.out.println("WS236-OBSERVE bridge veyran after="
                + veyran.getNetPower() + "/" + veyran.getNetToughness());
        int activations = 0;
        for (forge.game.trigger.Trigger t : veyran.getTriggers()) {
            activations += t.getActivationsThisTurn();
        }
        int handAfter = session.getGame().getPlayers().get(0)
                .getZone(forge.game.zone.ZoneType.Hand).size();
        boolean divInGrave = false;
        for (Card c : session.getGame().getPlayers().get(0)
                .getCardsIn(forge.game.zone.ZoneType.Graveyard)) {
            if ("Divination".equals(c.getName())) {
                divInGrave = true;
            }
        }
        System.out.println("WS236-OBSERVE bridge veyran activationsThisTurn="
                + activations + " handAfter=" + handAfter
                + " divinationInGrave=" + divInGrave);
        // Own Panharmonicon doubles own magecraft: one Divination -> +2/+2.
        Assert.assertEquals(veyran.getNetPower(), beforeP + 2,
                "Veyran must get +2 power from doubled magecraft via bridge");
        Assert.assertEquals(veyran.getNetToughness(), beforeT + 2,
                "Veyran must get +2 toughness from doubled magecraft via bridge");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testHarmonicProwessViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws236-harmonic");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Harmonic Prodigy",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Divination", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Island");
        tapLand(session, "p1", "Island");
        tapLand(session, "p1", "Island");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        Card harmBefore = findBf(session, 0, "Harmonic Prodigy");
        Assert.assertNotNull(harmBefore);
        int beforeP = harmBefore.getNetPower();
        int beforeT = harmBefore.getNetToughness();
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Divination".equals(o.sourceCardName),
                "Divination cast"));
        System.out.println("WS236-OBSERVE bridge harmonic before=" + beforeP + "/" + beforeT);
        drainPasses(session);
        Card harm = findBf(session, 0, "Harmonic Prodigy");
        Assert.assertNotNull(harm);
        System.out.println("WS236-OBSERVE bridge harmonic after="
                + harm.getNetPower() + "/" + harm.getNetToughness());
        // Prowess is own-trigger, not "another Wizard": exactly +1/+1.
        Assert.assertEquals(harm.getNetPower(), beforeP + 1,
                "Harmonic must get Prowess +1 power via bridge");
        Assert.assertEquals(harm.getNetToughness(), beforeT + 1,
                "Harmonic must get Prowess +1 toughness via bridge");
        session.shutdown(5000);
    }
}
