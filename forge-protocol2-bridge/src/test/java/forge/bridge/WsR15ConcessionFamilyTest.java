package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R15 concession family: multiplayer concession terminals at 2P/3P/5P
 * (4P retained via existing surface tests).
 *
 * <p>Per count: a mid-game concede is submitted through the offered
 * option; 2P ends the game (sole survivor wins, session terminal);
 * 3P/5P remove the player plus owned permanents (CR 800.4 cleanup)
 * while the game continues with clean shutdown. Tests pick only
 * engine-offered options; zones and lives are accounted.</p>
 */
public class WsR15ConcessionFamilyTest {

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

    private static int playerCount(BridgeSession session) {
        return session.getGame().getPlayers().size();
    }

    private static boolean bearOnAnyBf(BridgeSession session) {
        final int n = session.getGame().getPlayers().size();
        for (int s = 0; s < n; s++) {
            for (Card c : session.getGame().getPlayers().get(s)
                    .getZone(ZoneType.Battlefield).getCards()) {
                if ("Runeclaw Bear".equals(c.getName())) {
                    return true;
                }
            }
        }
        return false;
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

    // Concede as the given actor at its next priority; Bear belongs to the
    // conceder in 3P/5P (cleanup observable).
    private static BridgeTestSupport.ConstructedGame concedeGame(String gameId, int players) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId, players);
        final BridgeSession session = constructed.session;
        if (players > 2) {
            BridgeTestSupport.addCard(constructed.game, players - 1, "Runeclaw Bear",
                    ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        return constructed;
    }

    private static void concedeAs(BridgeSession session, String actorId) {
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals(actorId) && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> "concede".equals(o.actionType),
                        actorId + " concedes"));
                return;
            }
            answerCommon(session, f);
        }
        throw new AssertionError("never reached concede priority for " + actorId);
    }

    @Test(timeOut = 300000)
    public void testTwoPlayerConcedeEndsGame() {
        final BridgeTestSupport.ConstructedGame constructed = concedeGame("wsr15-concede-2p", 2);
        final BridgeSession session = constructed.session;
        concedeAs(session, "p2");
        Assert.assertTrue(session.isTerminal(), "2P concede must terminate the session");
        Assert.assertTrue(session.getGame().isGameOver(), "2P concede must end the game");
        Assert.assertEquals(playerCount(session), 1, "one survivor remains");
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 40,
                "survivor untouched");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testThreePlayerConcedeCleansUpAndContinues() {
        final BridgeTestSupport.ConstructedGame constructed = concedeGame("wsr15-concede-3p", 3);
        final BridgeSession session = constructed.session;
        concedeAs(session, "p3");
        drain(session, 20);
        Assert.assertFalse(session.isTerminal(), "3P concede must not end the session");
        Assert.assertEquals(playerCount(session), 2, "two players remain");
        Assert.assertFalse(bearOnAnyBf(session), "conceder's Bear must leave the game (800.4)");
        Assert.assertEquals(session.getGame().getPlayers().get(0).getLife(), 40, "p1 untouched");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 40, "p2 untouched");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testFivePlayerConcedeCleansUpAndContinues() {
        final BridgeTestSupport.ConstructedGame constructed = concedeGame("wsr15-concede-5p", 5);
        final BridgeSession session = constructed.session;
        concedeAs(session, "p5");
        drain(session, 20);
        Assert.assertFalse(session.isTerminal(), "5P concede must not end the session");
        Assert.assertEquals(playerCount(session), 4, "four players remain");
        Assert.assertFalse(bearOnAnyBf(session), "conceder's Bear must leave the game (800.4)");
        for (int s = 0; s < 4; s++) {
            Assert.assertEquals(session.getGame().getPlayers().get(s).getLife(), 40,
                    "seat " + s + " untouched");
        }
        session.shutdown(5000);
    }
}
