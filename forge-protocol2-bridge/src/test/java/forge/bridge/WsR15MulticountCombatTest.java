package forge.bridge;

import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R15 multicount combat family: actual-card multiplayer combat at 2P/3P/5P
 * (4P retained via R9–R11 suites).
 *
 * <p>Per count: two 2/2 attackers split across defenders (2P: both at p2;
 * 3P/5P: Bear at p2, Grizzly at p3), no blocks on empty boards, exact
 * life accounting per seat (attacked -2 each, all others untouched).
 * Tests pick only engine-offered options; damage is accounted, never
 * injected. Mirrors the R10a combat-driving pattern.</p>
 */
public class WsR15MulticountCombatTest {

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
            seen.append('[').append(option.actionType).append('|')
                    .append(option.label).append(']');
        }
        throw new AssertionError(
                what + " not offered; kind=" + frame.kind + " options=" + seen);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static boolean frameMatches(DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind) {
        return frame.kind == kind && frame.actorPlayerId.equals(actorId)
                && frame.status == DecisionFrame.Status.SUPPORTED;
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

    private static void answerCommon(BridgeSession session, DecisionFrame frame) {
        if (frame.kind == DecisionFrame.Kind.PRIORITY) {
            submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
        } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
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

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static int life(int seat, BridgeSession session) {
        return session.getGame().getPlayers().get(seat).getLife();
    }

    private static void drain(BridgeSession session, int budget) {
        long lastRevision = -1;
        for (int i = 0; i < budget; i++) {
            DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame while draining");
            if (frame.revision == lastRevision) {
                return;
            }
            lastRevision = frame.revision;
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                return;
            }
            answerCommon(session, frame);
        }
    }

    // Attack split per count; returns after blockers declined.
    private static void attackSplit(BridgeSession session, int playerCount,
            String attackerSpec) {
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean has = false;
                for (DecisionFrame.Option o : f.options) {
                    if (o.label != null && o.label.contains("Runeclaw Bear")
                            && o.label.contains("Grizzly Bears")
                            && o.label.contains(attackerSpec)) {
                        has = true;
                    }
                }
                if (has) {
                    final String spec = attackerSpec;
                    submit(session, f, pickOption(f,
                            o -> o.label != null && o.label.contains("Runeclaw Bear")
                                    && o.label.contains("Grizzly Bears")
                                    && o.label.contains(spec),
                            "attack split " + spec));
                    break;
                }
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 20 * (playerCount - 1); i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("No block"),
                        "no blocks"));
            } else {
                answerCommon(session, f);
            }
            boolean quiet = true;
            for (int s = 1; s < playerCount; s++) {
                if (life(s, session) != 40) {
                    quiet = false;
                }
            }
            if (!quiet) {
                break;
            }
        }
    }

    private static BridgeTestSupport.ConstructedGame combatGame(String gameId, int players) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, players);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        return constructed;
    }

    @Test(timeOut = 300000)
    public void testTwoPlayerCombatBothAtP2() {
        final BridgeTestSupport.ConstructedGame constructed = combatGame("wsr15-combat-2p", 2);
        final BridgeSession session = constructed.session;
        attackSplit(session, 2, "Runeclaw Bear -> p2; Grizzly Bears -> p2;");
        drain(session, 30);
        Assert.assertEquals(life(0, session), 40, "p1 untouched");
        Assert.assertEquals(life(1, session), 36, "p2 takes 2+2");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testThreePlayerCombatSplit() {
        final BridgeTestSupport.ConstructedGame constructed = combatGame("wsr15-combat-3p", 3);
        final BridgeSession session = constructed.session;
        attackSplit(session, 3, "Runeclaw Bear -> p2; Grizzly Bears -> p3;");
        drain(session, 30);
        Assert.assertEquals(life(0, session), 40, "p1 untouched");
        Assert.assertEquals(life(1, session), 38, "p2 takes Bear 2");
        Assert.assertEquals(life(2, session), 38, "p3 takes Grizzly 2");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testFivePlayerCombatSplitOthersUntouched() {
        final BridgeTestSupport.ConstructedGame constructed = combatGame("wsr15-combat-5p", 5);
        final BridgeSession session = constructed.session;
        attackSplit(session, 5, "Runeclaw Bear -> p2; Grizzly Bears -> p3;");
        drain(session, 30);
        Assert.assertEquals(life(0, session), 40, "p1 untouched");
        Assert.assertEquals(life(1, session), 38, "p2 takes Bear 2");
        Assert.assertEquals(life(2, session), 38, "p3 takes Grizzly 2");
        Assert.assertEquals(life(3, session), 40, "p4 untouched");
        Assert.assertEquals(life(4, session), 40, "p5 untouched");
        session.shutdown(5000);
    }
}
