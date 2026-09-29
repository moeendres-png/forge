package forge.bridge;

import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * Optional additional costs (CR 601.2b/601.2f) are an external pilot decision.
 *
 * <p>A kicker spell in hand used to make the whole priority frame UNSUPPORTED
 * (OPTIONAL_COST), halting the game for that player. The engine-supplied
 * optional costs are now offered as every subset; paying them is validated
 * natively. Actual card: Burst Lightning (2 damage, 4 if kicked).</p>
 */
public class OptionalCostChoiceTest {

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

    private static DecisionFrame nextP1Priority(BridgeSession session) {
        for (int i = 0; i < 20; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.kind == DecisionFrame.Kind.PRIORITY && f.actorPlayerId.equals("p1")) {
                return f;
            }
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + f.kind + " for " + f.actorPlayerId);
        }
        throw new AssertionError("p1 priority never returned");
    }

    private void castBurstLightning(boolean kicked, int expectedLife) {
        final BridgeTestSupport.ConstructedGame constructed = BridgeTestSupport
                .buildConstructedGame("optional-cost-" + (kicked ? "kicked" : "plain"), 2);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Burst Lightning", ZoneType.Hand);
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        // Float {R}{R}{R}{R}{R}: the bridge pays mana from the pre-floated pool.
        for (int i = 0; i < 5; i++) {
            Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED,
                    "priority frame blocked: " + frame.reason);
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && "Mountain".equals(o.sourceCardName), "Mountain tap"));
            frame = nextP1Priority(session);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Burst Lightning".equals(o.sourceCardName), "Burst Lightning cast"));
        boolean optionalCostsOffered = false;
        boolean targeted = false;
        for (int i = 0; i < 60; i++) {
            if (session.getGame().getPlayers().get(1).getLife() != 40) {
                break;
            }
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.kind == DecisionFrame.Kind.COST_SELECTION
                    && f.options.stream().anyMatch(o -> "choose_optional_costs".equals(o.actionType))) {
                optionalCostsOffered = true;
                Assert.assertEquals(f.options.size(), 2, "pay nothing or pay kicker");
                submit(session, f, pick(f, o -> kicked
                        ? o.label.startsWith("Pay optional")
                        : o.label.equals("Pay no optional costs"), kicked ? "kicker" : "no kicker"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                targeted = true;
                submit(session, f, pick(f, o -> o.label != null && o.label.contains("p2"), "p2"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.status + " " + f.reason);
            }
        }
        Assert.assertTrue(optionalCostsOffered, "the kicker choice was offered to the pilot");
        Assert.assertTrue(targeted, "Burst Lightning was targeted");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), expectedLife);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void kickedBurstLightningDealsFour() {
        castBurstLightning(true, 36);
    }

    @Test(timeOut = 300000)
    public void unkickedBurstLightningDealsTwo() {
        castBurstLightning(false, 38);
    }
}
