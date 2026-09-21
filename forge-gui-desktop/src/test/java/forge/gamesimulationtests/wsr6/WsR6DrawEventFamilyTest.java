package forge.gamesimulationtests.wsr6;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * R6 DRAW_EVENT family: the Player draw path (DrawCards replacement gate,
 * per-card canDraw/CantDraw static, doDraw, Drawn trigger) proven with two
 * actual cards whose WS234 gaps shared this seam: Psychosis Crawler Drawn
 * lifeloss (CARD_16) and Narset CantDraw DrawLimit-1 lock (CARD_07).
 *
 * <p>Engine ownership is untouched: draws go through
 * {@code Player.drawCards} and queued Drawn triggers resolve through the
 * engine's own {@code addAllTriggeredAbilitiesToStack} ordering (WS236-S1
 * drain shape). Libraries are stocked explicitly: the legacy disabled
 * Crawler probe drew from an empty fixture library, which decks the drawing
 * player instead of firing Drawn (fixture defect, never engine proof).
 */
public class WsR6DrawEventFamilyTest extends SimulationTest {

    private Card bf(String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Hand).add(c);
        p.getGame().getAction().moveTo(ZoneType.Battlefield, c, null, null);
        p.getGame().getAction().checkStaticAbilities();
        return c;
    }

    private void stockLibrary(Game game, Player p, String name, int n) {
        for (int i = 0; i < n; i++) {
            Card lib = createCard(name, p);
            lib.setGameTimestamp(game.getNextTimestamp());
            p.getZone(ZoneType.Library).add(lib);
        }
    }

    private void stockHand(Game game, Player p, String name, int n) {
        for (int i = 0; i < n; i++) {
            Card c = createCard(name, p);
            c.setGameTimestamp(game.getNextTimestamp());
            p.getZone(ZoneType.Hand).add(c);
        }
    }

    // WS236-S1 drain shape (test-only, engine-owned ordering): resolve the
    // stack and order engine-queued simultaneous entries until quiet.
    // Guards fail closed; game-over is never treated as success.
    private void drainStack(Game game) {
        int guard = 0;
        while ((!game.getStack().isEmpty()
                || game.getStack().hasSimultaneousStackEntries())
                && !game.isGameOver() && guard < 100) {
            game.getStack().addAllTriggeredAbilitiesToStack();
            if (!game.getStack().isEmpty()) {
                game.getStack().resolveStack();
            }
            game.getAction().checkStateEffects(true);
            guard++;
        }
        assertTrue(guard < 100, "drain must terminate (guard exhausted)");
        assertFalse(game.isGameOver(), "game must not be over: fixture must stock the library");
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "no stranded simultaneous entries may remain after drain");
        assertTrue(game.getStack().isEmpty(), "stack must be empty after drain");
    }

    @Test(timeOut = 60000)
    public void testCrawlerDrawCausesOpponentLifeloss() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        stockHand(game, p1, "Grizzly Bears", 2);
        stockLibrary(game, p1, "Runeclaw Bear", 5);
        Card crawler = bf("Psychosis Crawler", p1);
        assertEquals(crawler.getNetPower(), 2, "Crawler P/T must equal hand size 2");
        int handBefore = p1.getZone(ZoneType.Hand).size();
        int libBefore = p1.getZone(ZoneType.Library).size();
        int p2LifeBefore = p2.getLife();
        int p1LifeBefore = p1.getLife();
        p1.drawCards(1);
        drainStack(game);
        assertEquals(p1.getZone(ZoneType.Hand).size(), handBefore + 1,
                "exactly one card must be drawn");
        assertEquals(p1.getZone(ZoneType.Library).size(), libBefore - 1,
                "library must shrink by exactly one");
        assertEquals(p2.getLife(), p2LifeBefore - 1,
                "Crawler Drawn trigger must make each opponent lose exactly 1 life");
        assertEquals(p1.getLife(), p1LifeBefore, "controller life must be untouched");
        assertEquals(crawler.getNetPower(), 3, "Crawler P/T must track the new hand size 3");
    }

    @Test(timeOut = 60000)
    public void testCrawlerSilentOnOpponentDraw() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        stockHand(game, p1, "Grizzly Bears", 2);
        stockLibrary(game, p2, "Runeclaw Bear", 5);
        bf("Psychosis Crawler", p1);
        int p1LifeBefore = p1.getLife();
        int p2LifeBefore = p2.getLife();
        // "Whenever YOU draw a card": opponent draws must not trigger Crawler.
        p2.drawCards(1);
        drainStack(game);
        assertEquals(p2.getZone(ZoneType.Hand).size(), 1, "opponent must still draw the card");
        assertEquals(p1.getLife(), p1LifeBefore, "opponent draw must not trigger Crawler");
        assertEquals(p2.getLife(), p2LifeBefore, "opponent draw must not trigger Crawler");
    }

    @Test(timeOut = 60000)
    public void testDrawWithoutCrawlerCausesNoLifeloss() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        stockLibrary(game, p1, "Runeclaw Bear", 5);
        int p2LifeBefore = p2.getLife();
        p1.drawCards(1);
        drainStack(game);
        assertEquals(p1.getZone(ZoneType.Hand).size(), 1, "card must be drawn");
        assertEquals(p2.getLife(), p2LifeBefore,
                "draw with no Crawler must cause no lifeloss (control)");
    }

    @Test(timeOut = 60000)
    public void testNarsetDrawLock() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card narset = bf("Narset, Parter of Veils", p1);
        assertNotNull(narset, "Narset must enter");
        stockLibrary(game, p2, "Runeclaw Bear", 5);
        int handBefore = p2.getZone(ZoneType.Hand).size();
        int libBefore = p2.getZone(ZoneType.Library).size();
        // Opponent attempts two draws: the CantDraw DrawLimit-1 static allows
        // exactly one, then blocks.
        int drawn = p2.drawCards(2).size();
        drainStack(game);
        assertEquals(drawn, 1, "Narset must cap the opponent at exactly one drawn card");
        assertEquals(p2.getZone(ZoneType.Hand).size(), handBefore + 1,
                "exactly one card must reach the hand");
        assertEquals(p2.getZone(ZoneType.Library).size(), libBefore - 1,
                "library must shrink by exactly one");
        assertEquals(p2.getNumDrawnThisTurn(), 1, "draw count must be exactly one");
        // A further draw attempt this turn is blocked entirely.
        int drawnAgain = p2.drawCards(1).size();
        drainStack(game);
        assertEquals(drawnAgain, 0, "second draw this turn must be blocked");
        assertEquals(p2.getZone(ZoneType.Hand).size(), handBefore + 1,
                "hand must be unchanged after the blocked draw");
    }

    @Test(timeOut = 60000)
    public void testDrawCapWithoutNarsetDrawsTwo() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        stockLibrary(game, p2, "Runeclaw Bear", 5);
        int drawn = p2.drawCards(2).size();
        drainStack(game);
        assertEquals(drawn, 2, "with no Narset both draws must succeed (control)");
        assertEquals(p2.getZone(ZoneType.Hand).size(), 2, "hand must gain both cards");
    }
}
