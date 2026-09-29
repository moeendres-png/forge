package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * Crew (CR 702.122a) is a pilot-chosen cost: tap any untapped creatures with
 * total power N or greater.
 *
 * <p>Actual card: Smuggler's Copter (Crew 1). Crew is a CostTapType with a
 * total-power threshold; it was unframed and classified COMPLEX_COST, so an
 * uncrewed vehicle beside a creature made the controller's whole priority
 * frame UNSUPPORTED. Every creature set reaching the threshold is now one
 * option.</p>
 */
public class CrewCostTest {

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
    public void pilotChoosesTheCrewForSmugglersCopter() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("crew-copter", 2);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Smuggler's Copter", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Raging Goblin", ZoneType.Battlefield);
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
                && "Smuggler's Copter".equals(o.sourceCardName), "Crew activation"));
        boolean crewFramed = false;
        for (int i = 0; i < 20; i++) {
            final Card copter = find(session, "Smuggler's Copter");
            if (crewFramed && copter.isCreature() && session.getGame().getStack().isEmpty()) {
                break;
            }
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.options.stream().anyMatch(o -> "cost_tap_total_power".equals(o.actionType))) {
                crewFramed = true;
                // Three creatures, Crew 1: every non-empty set reaches power 1
                // (7 sets), plus declining the optional activation.
                Assert.assertEquals(f.options.size(), 8, "7 crew sets plus decline");
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.endsWith("[Raging Goblin;]"), "crew with the goblin alone"));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.status + " " + f.reason);
            }
        }
        Assert.assertTrue(crewFramed, "the crew was the pilot's choice");
        Assert.assertTrue(find(session, "Smuggler's Copter").isCreature(), "Copter crewed");
        Assert.assertTrue(find(session, "Raging Goblin").isTapped(), "goblin crewed it");
        Assert.assertFalse(find(session, "Grizzly Bears").isTapped(), "bears untouched");
        Assert.assertFalse(find(session, "Runeclaw Bear").isTapped(), "bear untouched");
        session.shutdown(5000);
    }
}
