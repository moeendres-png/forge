package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * Non-mana "unless" costs are the payer's decision.
 *
 * <p>Actual card: Watery Grave ("As Watery Grave enters, you may pay 2 life.
 * If you don't, it enters tapped."), scripted as UnlessCost$ PayLife&lt;2&gt;.
 * payCostToPreventEffect first threw for every cost and then accepted mana
 * only, so playing a shock land ended the session. The pilot now chooses; the
 * life payment runs through the framed native path.</p>
 */
public class ShockLandChoiceTest {

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

    private static Card grave(BridgeSession session) {
        for (Card card : session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals("Watery Grave")) {
                return card;
            }
        }
        return null;
    }

    private void playWateryGrave(boolean pay) {
        final BridgeTestSupport.ConstructedGame constructed = BridgeTestSupport
                .buildConstructedGame("shock-" + (pay ? "pay" : "decline"), 2);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Watery Grave", ZoneType.Hand);
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        submit(session, main, pick(main, o -> "play_land".equals(o.actionType)
                && "Watery Grave".equals(o.sourceCardName), "play Watery Grave"));
        boolean asked = false;
        for (int i = 0; i < 20 && grave(session) == null; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (f.options.stream().anyMatch(o -> "pay_to_prevent".equals(o.actionType))) {
                Assert.assertEquals(f.actorPlayerId, "p1");
                asked = true;
                submit(session, f, pick(f, o -> Boolean.valueOf(pay).equals(o.confirmValue),
                        pay ? "pay 2 life" : "do not pay"));
            } else if (f.kind == DecisionFrame.Kind.COST_SELECTION
                    && f.options.stream().anyMatch(o -> "cost_pay_life".equals(o.actionType))) {
                // The framed life-payment visit restates the same payment.
                submit(session, f, pick(f, o -> Boolean.TRUE.equals(o.confirmValue), "pay life"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        Assert.assertTrue(asked, "the land's controller chose whether to pay 2 life");
        final Card land = grave(session);
        Assert.assertNotNull(land, "Watery Grave entered the battlefield");
        Assert.assertEquals(land.isTapped(), !pay, pay ? "paid: untapped" : "declined: tapped");
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), pay ? 38 : 40);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void payingTwoLifeLetsTheShockLandEnterUntapped() {
        playWateryGrave(true);
    }

    @Test(timeOut = 300000)
    public void decliningEntersTappedAndKeepsLife() {
        playWateryGrave(false);
    }
}
