package forge.bridge;

import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R15 multicount trigger family: Kediss commander-damage fan-out at
 * 2P/3P/5P (4P retained via R10a).
 *
 * <p>Per count: Kediss cast from the command zone attacks p2; every other
 * opponent takes exactly the trigger damage, non-opponents take nothing.
 * Tests pick only engine-offered options; lives are accounted, never
 * injected. Mirrors the R10a tight post-cast attack flow.</p>
 */
public class WsR15MulticountTriggerTest {

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

    // Cast Kediss from command, then attack p2 at the FIRST p2-free
    // attackers frame (tight flow: never let generic answering consume it).
    private static void kedissAttackP2(BridgeSession session) {
        DecisionFrame frame = null;
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasKediss = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Kediss, Emberclaw Familiar".equals(o.sourceCardName)) {
                        hasKediss = true;
                    }
                }
                if (hasKediss) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Kediss, Emberclaw Familiar".equals(o.sourceCardName),
                            "Kediss cast"));
                    frame = f;
                    break;
                }
            }
            answerCommon(session, f);
        }
        Assert.assertNotNull(frame, "Kediss cast never submitted");
        for (int i = 0; i < 60; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Kediss, Emberclaw Familiar")
                                && o.label.contains("p2"),
                        "Kediss -> p2"));
                break;
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("No block"),
                        "no blocks"));
                break;
            }
            answerCommon(session, f);
        }
    }

    private static BridgeTestSupport.ConstructedGame kedissGame(String gameId, int players) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, players);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Kediss, Emberclaw Familiar");
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        return constructed;
    }

    @Test(timeOut = 300000)
    public void testKedissTwoPlayerNoFanout() {
        final BridgeTestSupport.ConstructedGame constructed = kedissGame("wsr15-kediss-2p", 2);
        final BridgeSession session = constructed.session;
        int p1Life = life(0, session);
        int p2Life = life(1, session);
        kedissAttackP2(session);
        drain(session, 40);
        Assert.assertEquals(life(0, session), p1Life, "p1 untouched");
        Assert.assertEquals(life(1, session), p2Life - 1, "p2 takes commander combat");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testKedissThreePlayerFanout() {
        final BridgeTestSupport.ConstructedGame constructed = kedissGame("wsr15-kediss-3p", 3);
        final BridgeSession session = constructed.session;
        int p1Life = life(0, session);
        int p2Life = life(1, session);
        int p3Life = life(2, session);
        kedissAttackP2(session);
        drain(session, 40);
        Assert.assertEquals(life(0, session), p1Life, "p1 untouched");
        Assert.assertEquals(life(1, session), p2Life - 1, "p2 takes commander combat");
        Assert.assertEquals(life(2, session), p3Life - 1, "p3 takes trigger");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testKedissFivePlayerFanout() {
        final BridgeTestSupport.ConstructedGame constructed = kedissGame("wsr15-kediss-5p", 5);
        final BridgeSession session = constructed.session;
        int[] before = new int[5];
        for (int s = 0; s < 5; s++) {
            before[s] = life(s, session);
        }
        kedissAttackP2(session);
        drain(session, 40);
        Assert.assertEquals(life(0, session), before[0], "p1 untouched");
        Assert.assertEquals(life(1, session), before[1] - 1, "p2 takes commander combat");
        for (int s = 2; s < 5; s++) {
            Assert.assertEquals(life(s, session), before[s] - 1, "p" + (s + 1) + " takes trigger");
        }
        session.shutdown(5000);
    }
}
