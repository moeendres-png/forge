package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * "Tap an untapped creature you control" is a pilot-chosen cost.
 *
 * <p>Actual card: Springleaf Drum ("{T}, Tap an untapped creature you
 * control: Add one mana of any color."), a staple of the Lab's own Rog/Shai
 * deck. CostTapType was unframed and classified COMPLEX_COST, so with the
 * Drum and a creature out the player's whole priority frame was
 * UNSUPPORTED. The pilot now chooses which creature to tap.</p>
 */
public class TapTypeCostTest {

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
            seen.append('[').append(option.actionType).append('|').append(option.label)
                    .append('|').append(option.sourceCardName).append(']');
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

    private static Card find(BridgeSession session, String name) {
        for (Card card : session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    @Test(timeOut = 300000)
    public void pilotChoosesWhichCreatureSpringleafDrumTaps() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("tap-type-drum", 2);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Springleaf Drum", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Elvish Mystic", ZoneType.Hand);
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertEquals(main.status, DecisionFrame.Status.SUPPORTED,
                "priority frame blocked: " + main.reason);
        submit(session, main, pick(main, o -> "activate_ability".equals(o.actionType)
                && "Springleaf Drum".equals(o.sourceCardName), "Springleaf Drum activation"));
        boolean costFramed = false;
        DecisionFrame frame = null;
        for (int i = 0; i < 10; i++) {
            frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame, "no frame");
            if (frame.kind == DecisionFrame.Kind.PRIORITY && frame.actorPlayerId.equals("p1")) {
                break;
            }
            if (frame.options.stream().anyMatch(o -> "cost_tap_type".equals(o.actionType))) {
                costFramed = true;
                // Either bear may be tapped, or the activation abandoned (the
                // cost is not mandatory, mirroring Human's cancel).
                Assert.assertEquals(frame.options.size(), 3, "two bears plus decline");
                Assert.assertTrue(frame.options.stream().anyMatch(o -> Boolean.FALSE
                        .equals(o.confirmValue)), "decline offered");
                submit(session, frame, pick(frame, o -> o.label != null
                        && o.label.contains("Runeclaw Bear"), "tap Runeclaw Bear"));
            } else if (frame.kind == DecisionFrame.Kind.COLOR_CHOICE) {
                submit(session, frame, pick(frame, o -> o.label != null
                        && o.label.toLowerCase().contains("green"), "green"));
            } else {
                throw new AssertionError("unexpected " + frame.kind + " " + frame.status
                        + " " + frame.reason);
            }
        }
        Assert.assertTrue(costFramed, "the tapped creature was the pilot's choice");
        Assert.assertTrue(find(session, "Springleaf Drum").isTapped(), "Drum tapped");
        Assert.assertTrue(find(session, "Runeclaw Bear").isTapped(), "chosen bear tapped");
        Assert.assertFalse(find(session, "Grizzly Bears").isTapped(), "other bear untouched");
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Elvish Mystic".equals(o.sourceCardName), "Elvish Mystic cast"));
        for (int i = 0; i < 30 && find(session, "Elvish Mystic") == null; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.status + " " + f.reason);
            }
        }
        Assert.assertNotNull(find(session, "Elvish Mystic"), "the Drum's green paid for Mystic");
        session.shutdown(5000);
    }
}
