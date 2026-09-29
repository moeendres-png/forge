package forge.bridge;

import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * "... unless that player pays {X}" is the payer's decision.
 *
 * <p>Actual card: Rhystic Study ("Whenever an opponent casts a spell, you may
 * draw a card unless that player pays {1}."). payCostToPreventEffect used to
 * throw unsupported, ending the session the first time the trigger resolved.
 * The payer now chooses pay / do not pay; payment runs natively.</p>
 */
public class PayToPreventChoiceTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static DecisionFrame.Option pick(DecisionFrame frame,
            Predicate<DecisionFrame.Option> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
            seen.append('[').append(option.actionType).append('|').append(option.label).append(']');
        }
        throw new AssertionError(what + " not offered; kind=" + frame.kind + " status="
                + frame.status + " reason=" + frame.reason + " options=" + seen);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static boolean onBattlefield(BridgeSession session, int seat, String name) {
        return session.getGame().getPlayers().get(seat).getCardsIn(ZoneType.Battlefield)
                .stream().anyMatch(c -> c.getName().equals(name));
    }

    private void castIntoRhysticStudy(boolean pay) {
        final BridgeTestSupport.ConstructedGame constructed = BridgeTestSupport
                .buildConstructedGame("rhystic-" + (pay ? "pay" : "decline"), 2);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 1, "Rhystic Study", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Hand);
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        }
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 3; i++) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && "Forest".equals(o.sourceCardName), "Forest tap"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
            while (frame.kind != DecisionFrame.Kind.PRIORITY || !frame.actorPlayerId.equals("p1")) {
                Assert.assertEquals(frame.kind, DecisionFrame.Kind.MANA_PAYMENT, "after tap");
                submit(session, frame, frame.options.get(0));
                frame = BridgeTestSupport.awaitFrame(session, 15000);
            }
        }
        final int handBefore = session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Hand).size();
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Grizzly Bears".equals(o.sourceCardName), "Grizzly Bears cast"));
        boolean asked = false;
        for (int i = 0; i < 60; i++) {
            if (asked && onBattlefield(session, 0, "Grizzly Bears")
                    && session.getGame().getStack().isEmpty()) {
                break;
            }
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (f.options.stream().anyMatch(o -> "pay_to_prevent".equals(o.actionType))) {
                Assert.assertEquals(f.actorPlayerId, "p1", "the caster decides whether to pay");
                asked = true;
                submit(session, f, pick(f, o -> Boolean.valueOf(pay).equals(o.confirmValue),
                        pay ? "pay" : "do not pay"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else if (f.actorPlayerId.equals("p2") && f.options.stream()
                    .anyMatch(o -> Boolean.TRUE.equals(o.confirmValue))) {
                // Rhystic Study's "you may draw": p2 takes the draw when offered.
                submit(session, f, pick(f, o -> Boolean.TRUE.equals(o.confirmValue), "draw"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " for " + f.actorPlayerId);
            }
        }
        Assert.assertTrue(asked, "the payer was asked whether to pay {1}");
        final int handAfter = session.getGame().getPlayers().get(1)
                .getCardsIn(ZoneType.Hand).size();
        Assert.assertEquals(handAfter - handBefore, pay ? 0 : 1,
                pay ? "paying {1} prevents the draw" : "declining lets Rhystic Study draw");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void payingPreventsTheDraw() {
        castIntoRhysticStudy(true);
    }

    @Test(timeOut = 300000)
    public void decliningLetsTheOpponentDraw() {
        castIntoRhysticStudy(false);
    }
}
