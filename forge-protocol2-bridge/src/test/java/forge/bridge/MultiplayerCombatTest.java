package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Multiplayer combat (CR 506.2, 508.1b, 509.1a, 802.2) with actual cards in
 * 4- and 5-player Commander: the active player may attack different
 * opponents with different creatures; each defending player is asked for
 * blocks separately and may block only creatures attacking them; players
 * who are not attacked are not asked; damage lands on the right player.
 */
public class MultiplayerCombatTest {

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
        throw new AssertionError(what + " not offered; kind=" + frame.kind + " actor="
                + frame.actorPlayerId + " status=" + frame.status + " reason=" + frame.reason
                + " options=" + seen);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private void splitAttack(int players) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("mp-combat-" + players, players);
        final BridgeSession session = constructed.session;
        final Card bears = BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears",
                ZoneType.Battlefield);
        final Card runeclaw = BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Llanowar Elves", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 2, "Elvish Mystic", ZoneType.Battlefield);
        for (int seat = 0; seat < players; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        bears.setSickness(false);
        runeclaw.setSickness(false);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        boolean attacked = false;
        final List<String> blockersAsked = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            if (attacked && session.getGame().getPhaseHandler().getPhase()
                    .isAfter(forge.game.phase.PhaseType.COMBAT_DAMAGE)) {
                break;
            }
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS) {
                Assert.assertEquals(f.actorPlayerId, "p1");
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.contains("Grizzly Bears -> p2")
                        && o.label.contains("Runeclaw Bear -> p3"),
                        "Bears at p2 and Runeclaw at p3"));
                attacked = true;
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                blockersAsked.add(f.actorPlayerId);
                final String own = "p2".equals(f.actorPlayerId) ? "Grizzly Bears"
                        : "p3".equals(f.actorPlayerId) ? "Runeclaw Bear" : null;
                Assert.assertNotNull(own, "only attacked players are asked: " + f.actorPlayerId);
                final String other = own.equals("Grizzly Bears") ? "Runeclaw Bear"
                        : "Grizzly Bears";
                for (DecisionFrame.Option o : f.options) {
                    Assert.assertFalse(o.label != null && o.label.contains("blocks " + other),
                            f.actorPlayerId + " offered a block on a creature attacking someone "
                                    + "else: " + o.label);
                }
                if ("p2".equals(f.actorPlayerId)) {
                    submit(session, f, pick(f, o -> o.label != null
                            && o.label.contains("Llanowar Elves blocks Grizzly Bears"),
                            "Elves block the Bears"));
                } else {
                    submit(session, f, pick(f, o -> "No blocks".equals(o.label), "no blocks"));
                }
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DAMAGE) {
                submit(session, f, BridgeTestSupport.reachabilityOnlyChoice(f,
                        "the test asserts block legality/assignment, not the damage division"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " for " + f.actorPlayerId + " "
                        + f.reason);
            }
        }
        Assert.assertTrue(attacked, "p1 attacked");
        Assert.assertEquals(blockersAsked.stream().sorted().toList(), List.of("p2", "p3"),
                "exactly the two attacked players declared blocks");
        final List<forge.game.player.Player> ps = session.getGame().getPlayers();
        Assert.assertEquals(ps.get(1).getLife(), 40, "p2 blocked");
        Assert.assertEquals(ps.get(2).getLife(), 38, "p3 took Runeclaw Bear's 2");
        for (int seat = 3; seat < players; seat++) {
            Assert.assertEquals(ps.get(seat).getLife(), 40, "p" + (seat + 1) + " not attacked");
        }
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void fourPlayersSplitAttackAndPerDefenderBlocks() {
        splitAttack(4);
    }

    @Test(timeOut = 300000)
    public void fivePlayersSplitAttackAndPerDefenderBlocks() {
        splitAttack(5);
    }

    // ---- Goad (CR 701.38): until p1's next turn the goaded creature attacks
    // each combat if able, and a player other than p1 if able.

    @Test(timeOut = 300000)
    public void goadedCreatureMustAttackSomeoneOtherThanTheGoader() {
        final int players = 4;
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("mp-goad", players);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Disrupt Decorum", ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Grizzly Bears", ZoneType.Battlefield);
        for (int seat = 0; seat < players; seat++) {
            for (int i = 0; i < 10; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 4; i++) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && "Mountain".equals(o.sourceCardName), "Mountain"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Disrupt Decorum".equals(o.sourceCardName), "cast Disrupt Decorum"));
        DecisionFrame p2Attack = null;
        for (int i = 0; i < 200 && p2Attack == null; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && "p2".equals(f.actorPlayerId)) {
                p2Attack = f;
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS) {
                submit(session, f, pick(f, o -> "No attacks".equals(o.label), "no attacks"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, BridgeTestSupport.equivalentPayment(f));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " for " + f.actorPlayerId);
            }
        }
        Assert.assertNotNull(p2Attack, "p2 reached its declare-attackers decision");
        final List<String> labels = new ArrayList<>();
        p2Attack.options.forEach(o -> labels.add(o.label));
        Assert.assertEquals(labels.stream().sorted().toList(),
                List.of("Attack Grizzly Bears -> p3;", "Attack Grizzly Bears -> p4;"),
                "goaded: must attack, and not the goader p1: " + labels);
        submit(session, p2Attack, pick(p2Attack, o -> o.label.contains("-> p4"), "attack p4"));
        session.shutdown(5000);
    }
}
