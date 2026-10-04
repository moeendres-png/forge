package forge.bridge;

import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

/**
 * The multiplayer free mulligan (CR 103.5c) needs no external card selection.
 *
 * <p>Forge's London mulligan asks the controller which cards to put on the
 * bottom even when it owes none. A taken first mulligan in a 3+ player game
 * returns zero cards, so the bridge answers with nothing and the game reaches
 * priority with a seven-card hand. A card actually owed (the first mulligan of
 * a two-player game) still has no external selection surface and fails the
 * session closed instead of being chosen for the player.
 */
public class FreeMulliganTuckTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static BridgeSession startedSession(String tag, int playerCount) {
        final BridgeEngine engine = new BridgeEngine();
        BridgeTestSupport.startEngine(engine);
        final List<String> handles = BridgeTestSupport.importPod(engine, playerCount);
        BridgeTestSupport.assertOk(BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"c-" + tag + "\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"" + tag + "\",\"format\":\"commander\","
                        + "\"deck_handles\":[\"" + String.join("\",\"", handles) + "\"]}}}"));
        BridgeTestSupport.startGame(engine, tag);
        final BridgeSession session = engine.sessionsForTests().get(tag);
        Assert.assertNotNull(session);
        final DecisionFrame starting = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(starting);
        Assert.assertEquals(starting.kind, DecisionFrame.Kind.STARTING_PLAYER);
        Assert.assertTrue(BridgeTestSupport.submitStartingPlayer(session, starting, "p1").applied);
        return session;
    }

    private static DecisionFrame mulliganOf(BridgeSession session, String playerId) {
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(frame, "no frame parked; session " + session.getStatus()
                + " " + session.getFailReason());
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.MULLIGAN);
        Assert.assertEquals(frame.actorPlayerId, playerId);
        return frame;
    }

    private static void ship(BridgeSession session, DecisionFrame frame) {
        for (DecisionFrame.Option option : frame.options) {
            if (!option.isKeep) {
                Assert.assertTrue(session.submit(frame.actorPlayerId, option.optionId,
                        option.actionType, frame.revision).applied);
                return;
            }
        }
        throw new AssertionError("no mulligan option parked");
    }

    @Test(timeOut = 240000)
    public void aFreeMulliganInAFourPlayerGameReachesPriorityWithSevenCards() {
        final BridgeSession session = startedSession("free-mulligan-4p", 4);
        ship(session, mulliganOf(session, "p1"));
        for (String keeper : new String[] {"p2", "p3", "p4"}) {
            Assert.assertTrue(BridgeTestSupport.submitKeep(session, mulliganOf(session, keeper)).applied);
        }
        Assert.assertTrue(BridgeTestSupport.submitKeep(session, mulliganOf(session, "p1")).applied);

        final DecisionFrame priority = BridgeTestSupport.awaitFrame(session, 60000);
        Assert.assertNotNull(priority, "session " + session.getStatus() + " "
                + session.getFailReason());
        Assert.assertEquals(priority.kind, DecisionFrame.Kind.PRIORITY);
        for (Player player : session.registryPlayers()) {
            Assert.assertEquals(player.getCardsIn(ZoneType.Hand).size(), 7,
                    session.playerIdOf(player) + " must hold seven cards");
        }
    }

    @Test(timeOut = 240000)
    public void anOwedBottomCardStillFailsClosed() {
        // Two players: the first mulligan is not free, one card is owed.
        // Forge may carry out the mulligan, and ask for the owed card, as soon as
        // it is declared; whenever it asks, the session must fail closed and no
        // card may be chosen for the player.
        final BridgeSession session = startedSession("owed-tuck-2p", 2);
        final DecisionFrame first = mulliganOf(session, "p1");
        for (DecisionFrame.Option option : first.options) {
            if (!option.isKeep) {
                session.submit(first.actorPlayerId, option.optionId, option.actionType,
                        first.revision);
            }
        }
        for (int step = 0; step < 4 && !session.isTerminal(); step++) {
            final DecisionFrame next = BridgeTestSupport.awaitFrame(session, 30000);
            if (next == null || session.isTerminal()) {
                break;
            }
            Assert.assertEquals(next.kind, DecisionFrame.Kind.MULLIGAN,
                    "no priority may be reached while a card is owed");
            BridgeTestSupport.submitKeep(session, next);
        }

        Assert.assertTrue(session.awaitTerminal(60000), "the owed selection must end the session");
        Assert.assertEquals(session.getStatus(), BridgeSession.Status.FAILED);
        Assert.assertTrue(session.getFailReason().contains("tuckCardsViaMulligan"),
                session.getFailReason());
    }
}
