package forge.gamesimulationtests.ws59;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * WS59 G04 — engine-native authoritative concession action/seam.
 * CR 104.3a at any time (not priority-gated); CR 800.4 leave-game cleanup preserved.
 * No provider fabrication, no orchestration direct-call bypass in tests.
 */
public class Ws59G04ConcessionTest extends SimulationTest {

    private Card addBattlefield(String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    @Test(timeOut = 30000)
    public void testConcessionOfferedAtAnyTimeNotPriorityGated() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p2);

        // Even though it is p2's turn/priority, p1 must still be able to concede.
        assertTrue(p1.getController().canConcede(), "Concession must be offered at any time");
        assertTrue(p2.getController().canConcede(), "Concession must be offered at any time");

        // Engine-native seam (not direct Player.concede() from orchestration).
        p1.getController().concede();

        assertTrue(p1.hasLost(), "Conceding player must have lost");
        assertTrue(p1.conceded(), "Outcome must be Conceded");
        assertFalse(p1.isInGame(), "Conceding player must leave the game");
        // Two-player concession ends the game; winner has won (not still in game).
        assertTrue(game.isGameOver(), "Two-player concession ends the game");
        assertTrue(p2.hasWon(), "Remaining player wins");
        assertFalse(p1.getController().canConcede(), "Lost player cannot concede again (fail closed)");
        assertFalse(p2.getController().canConcede(), "Game over: no further concession (fail closed)");
    }

    @Test(timeOut = 30000)
    public void testConcessionTriggersNativeLeaveGameCleanup() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card bears = addBattlefield("Grizzly Bears", p1);
        game.getAction().checkStaticAbilities();

        p2.getController().concede();

        assertTrue(game.getPlayers().contains(p1), "Winner remains in game");
        assertFalse(game.getPlayers().contains(p2), "Conceder removed from ingame");
        assertTrue(game.getLostPlayers().contains(p2), "Conceder in lost players");
        assertTrue(bears.isInZone(ZoneType.Battlefield), "Unrelated permanent remains");
    }

    @Test(timeOut = 30000)
    public void testMultiplayerConcessionPreserves800d4Cleanup() {
        Game game = initAndCreateThreePlayerGame();
        assertEquals(game.getPlayers().size(), 3, "Need three players");
        Player pa = game.getPlayers().get(0);
        Player pb = game.getPlayers().get(1);
        Player pc = game.getPlayers().get(2);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, pa);

        // pa owns Bears; pb steals it via control effect to exercise 800.4 correction.
        Card bears = addBattlefield("Grizzly Bears", pa);
        // Simulate stolen control generally (no card-name logic): temp controller pb.
        bears.addTempController(pb, game.getNextTimestamp());
        game.getAction().checkStaticAbilities();
        assertEquals(bears.getController(), pb, "Bears stolen by pb for fixture");

        // Middle player concedes via engine seam at a non-priority moment.
        assertTrue(pb.getController().canConcede());
        pb.getController().concede();

        assertFalse(pb.isInGame(), "Conceder leaves");
        assertTrue(pa.isInGame() && pc.isInGame(), "Others remain");
        // Native 800.4 correction must have run: leaving player's temporary control ends.
        assertNotEquals(bears.getController(), pb, "Stolen control must end after leave");
        // Game must not be over with two players remaining.
        assertFalse(game.isGameOver(), "Three-player game continues with two remaining");
        assertEquals(game.getAge() == GameStage.GameOver, false);
    }

    @Test(timeOut = 30000)
    public void testConcessionFailClosedWhenNotLegal() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        p1.getController().concede();
        assertFalse(p1.getController().canConcede());
        try {
            p1.getController().concede();
            fail("Second concession must fail closed");
        } catch (IllegalStateException expected) {
            assertEquals(expected.getMessage(), "FORGE_CONCESSION_NOT_LEGAL");
        }

        try {
            game.getAction().concede(p1);
            fail("GameAction.concede on lost player must fail closed");
        } catch (IllegalStateException expected) {
            // expected
        }
    }
}
