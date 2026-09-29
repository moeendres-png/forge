package forge.bridge;

import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Block declarations offered to an external pilot are exactly the complete
 * declarations the Rules Core accepts (CR 509.1a-c).
 *
 * <p>Menace: a lone blocker on a menace attacker is illegal, so it must never
 * be offered; with a single potential blocker the only legal declaration is
 * no block. Multi-block: a creature that can block any number of creatures
 * must be offered a declaration blocking several attackers at once. Both
 * games use actual cards and pick only engine-offered options.</p>
 */
public class CombatBlockLegalityTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static DecisionFrame.Option pickOption(DecisionFrame frame,
            Predicate<DecisionFrame.Option> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
            seen.append('[').append(option.label).append(']');
        }
        throw new AssertionError(what + " not offered; kind=" + frame.kind + " options=" + seen);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static void answerCommon(BridgeSession session, DecisionFrame frame) {
        if (frame.kind == DecisionFrame.Kind.PRIORITY) {
            submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DAMAGE) {
            // CR 510.1d: a multi-blocker's controller divides its damage. Any
            // engine-offered division serves this test.
            Assert.assertFalse(frame.options.isEmpty(), "damage division offered");
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
            submit(session, frame, pickOption(frame,
                    o -> o.label != null && (o.label.contains("No attack")
                            || o.label.contains("No block")),
                    "decline combat"));
        } else {
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
        }
    }

    private static BridgeTestSupport.ConstructedGame game(String id, String[] attackers,
            String blocker) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(id, 2);
        for (String attacker : attackers) {
            BridgeTestSupport.addCard(constructed.game, 0, attacker, ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, blocker, ZoneType.Battlefield);
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(constructed.session, "p1", 12);
        return constructed;
    }

    /** Attack with everything at p2, then hand every blocker frame to {@code onBlock}. */
    private static List<DecisionFrame> attackAll(BridgeSession session, String attackLabel,
            java.util.function.Consumer<DecisionFrame> onBlock, int lifeBefore) {
        final List<DecisionFrame> blockFrames = new ArrayList<>();
        boolean attacked = false;
        for (int i = 0; i < 80; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (!attacked && f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.equals(attackLabel), attackLabel));
                attacked = true;
                continue;
            }
            if (attacked && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                blockFrames.add(f);
                onBlock.accept(f);
                continue;
            }
            if (attacked && f.kind == DecisionFrame.Kind.PRIORITY
                    && session.getGame().getPhaseHandler().getPhase()
                            .isAfter(forge.game.phase.PhaseType.COMBAT_DAMAGE)) {
                return blockFrames;
            }
            answerCommon(session, f);
        }
        throw new AssertionError("combat did not complete; life=" + lifeBefore);
    }

    @Test(timeOut = 300000)
    public void loneBlockerIsNeverOfferedAgainstMenace() {
        final BridgeTestSupport.ConstructedGame constructed = game("block-menace",
                new String[] {"Boggart Brute"}, "Grizzly Bears");
        final BridgeSession session = constructed.session;
        final List<DecisionFrame> frames = attackAll(session, "Attack Boggart Brute -> p2;",
                f -> {
                    for (DecisionFrame.Option o : f.options) {
                        Assert.assertFalse(o.label != null && o.label.contains("blocks Boggart Brute"),
                                "illegal lone block on a menace attacker offered: " + o.label);
                    }
                    submit(session, f, pickOption(f,
                            o -> o.label != null && o.label.contains("No block"), "no blocks"));
                }, 40);
        Assert.assertTrue(frames.isEmpty(),
                "no block frame expected: the only legal declaration is no block");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 37,
                "menace attacker is unblocked and deals 3");
        session.shutdown(5000);
    }

    private static BridgeTestSupport.ConstructedGame wideGame(String id, String[] attackers,
            String[] blockers) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(id, 2);
        for (String attacker : attackers) {
            BridgeTestSupport.addCard(constructed.game, 0, attacker, ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        for (String blocker : blockers) {
            BridgeTestSupport.addCard(constructed.game, 1, blocker, ZoneType.Battlefield);
        }
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(constructed.session, "p1", 12);
        return constructed;
    }

    @Test(timeOut = 300000)
    public void fiveAttackersAreDeclaredIncrementally() {
        final BridgeTestSupport.ConstructedGame constructed = wideGame("attack-wide",
                new String[] {"Grizzly Bears", "Grizzly Bears", "Grizzly Bears",
                        "Grizzly Bears", "Grizzly Bears"}, new String[0]);
        final BridgeSession session = constructed.session;
        int steps = 0;
        for (int i = 0; i < 120 && steps < 5; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS) {
                steps++;
                final String step = "(" + steps + "/5) -> p2";
                Assert.assertEquals(f.options.size(), 2, "stay home or attack p2");
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains(step), step));
                continue;
            }
            answerCommon(session, f);
        }
        Assert.assertEquals(steps, 5, "one attack step per candidate attacker");
        for (int i = 0; i < 40; i++) {
            if (session.getGame().getPlayers().get(1).getLife() == 30) {
                break;
            }
            answerCommon(session, BridgeTestSupport.awaitFrame(session, 15000));
        }
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 30,
                "five unblocked 2/2 attackers deal 10");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void invalidIncrementalBlockIsRejectedAndAskedAgain() {
        final BridgeTestSupport.ConstructedGame constructed = wideGame("block-wide-menace",
                new String[] {"Boggart Brute"}, new String[] {"Grizzly Bears", "Grizzly Bears",
                        "Grizzly Bears", "Grizzly Bears", "Grizzly Bears"});
        final BridgeSession session = constructed.session;
        int blockSteps = 0;
        boolean attacked = false;
        for (int i = 0; i < 160; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (!attacked && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.equals("Attack Boggart Brute -> p2;"),
                        "attack"));
                attacked = true;
                continue;
            }
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                blockSteps++;
                // Attempt 1 (steps 1-5): only the first bear blocks, which menace
                // forbids. Attempt 2 (steps 6-10): the first two bears block.
                final boolean block = blockSteps == 1 || blockSteps == 6 || blockSteps == 7;
                submit(session, f, pickOption(f, o -> o.label != null
                        && (block ? o.label.contains("blocks Boggart Brute")
                                : o.label.endsWith(": no block")), block ? "block" : "no block"));
                continue;
            }
            if (attacked && f.kind == DecisionFrame.Kind.PRIORITY
                    && session.getGame().getPhaseHandler().getPhase()
                            .isAfter(forge.game.phase.PhaseType.COMBAT_DAMAGE)) {
                break;
            }
            answerCommon(session, f);
        }
        Assert.assertEquals(blockSteps, 10, "the invalid declaration is asked again once");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 40,
                "the legal double block stops all damage");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void mustAttackCreatureIsNeverOfferedNoAttack() {
        final BridgeTestSupport.ConstructedGame constructed = game("attack-required",
                new String[] {"Bloodrock Cyclops"}, "Grizzly Bears");
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 80; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS) {
                Assert.fail("a must-attack creature with one legal defender leaves exactly one "
                        + "legal declaration, so no attack frame may be offered; options="
                        + f.options.size());
            }
            if (session.getGame().getCombat() != null
                    && session.getGame().getCombat().isAttacking(
                            session.getGame().getPlayers().get(0)
                                    .getCardsIn(ZoneType.Battlefield).stream()
                                    .filter(c -> c.getName().equals("Bloodrock Cyclops"))
                                    .findFirst().orElseThrow())) {
                session.shutdown(5000);
                return;
            }
            answerCommon(session, f);
        }
        Assert.fail("Bloodrock Cyclops never attacked");
    }

    @Test(timeOut = 300000)
    public void blockerThatCanBlockAnyNumberIsOfferedAMultiBlock() {
        final BridgeTestSupport.ConstructedGame constructed = game("block-multi",
                new String[] {"Runeclaw Bear", "Grizzly Bears"}, "Palace Guard");
        final BridgeSession session = constructed.session;
        final List<DecisionFrame> frames = attackAll(session,
                "Attack Runeclaw Bear -> p2; Grizzly Bears -> p2;",
                f -> submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Palace Guard blocks ")
                                && o.label.contains("Runeclaw Bear")
                                && o.label.contains(" and ")
                                && o.label.contains("Grizzly Bears"),
                        "Palace Guard blocking both attackers")), 40);
        Assert.assertEquals(frames.size(), 1, "exactly one block declaration");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 40,
                "both attackers are blocked, so p2 takes no damage");
        session.shutdown(5000);
    }
}
