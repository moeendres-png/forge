package forge.gamesimulationtests.ws76;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import java.util.List;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.SimulationTest;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * WS76 — engine-direct immediate-concession hardening (CR 104.3a).
 *
 * A synchronous concession issued from inside the engine's own cleanup
 * {@code autoPassCancel} player sweep (the WS68 {@code PhaseHandler} sweep
 * context) must leave the game immediately without breaking the sweep's
 * collection traversal. No provider/CPL code is on this path: the conceding
 * decision is issued by an engine AI controller synchronously inside its
 * {@code autoPassCancel} callback, and the sweep is driven through the
 * production {@code PhaseHandler} ({@code endTurnByEffect} &rarr; CLEANUP
 * {@code onPhaseBegin}).
 *
 * Controls: concession from a normal priority-adjacent dev-mode position
 * (no enclosing player iteration) in 2P/4P, plus sweep-context concession
 * in 2P/4P/5P with native CR 800.4 leave-game cleanup.
 */
public class Ws76ImmediateConcessionTest extends SimulationTest {

    /** Engine AI controller that concedes synchronously inside autoPassCancel. */
    public static class SyncConcedeController extends PlayerControllerAi {
        public boolean concedeOnNextAutoPass = false;
        public int autoPassCancelCalls = 0;
        public int priorityConsultations = 0;

        public SyncConcedeController(Game game, Player p, LobbyPlayer lp) {
            super(game, p, lp);
        }

        @Override
        public void autoPassCancel() {
            autoPassCancelCalls++;
            if (concedeOnNextAutoPass) {
                concedeOnNextAutoPass = false;
                // Synchronous immediate concession through the native Rules-Core
                // seam (PlayerController.concede -> GameAction.concede ->
                // Game.onPlayerLost), issued while the engine sweep is
                // traversing the live player list.
                concede();
                return;
            }
            super.autoPassCancel();
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            priorityConsultations++;
            return super.chooseSpellAbilityToPlay();
        }
    }

    private SyncConcedeController installSyncController(Player p, int index) {
        SyncConcedeController ctrl = new SyncConcedeController(p.getGame(), p,
                new LobbyPlayerAi("ws76-" + index, null));
        p.dangerouslySetController(ctrl);
        return ctrl;
    }

    private Game initNPlayerGame(int n) {
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck deck = new Deck();
        for (int i = 0; i < n; i++) {
            players.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi("ws76p" + i, null)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        game.AI_TIMEOUT = FModel.getPreferences().getPrefInt(FPref.MATCH_AI_TIMEOUT);
        game.AI_CAN_USE_TIMEOUT = true;
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    private Card addBattlefield(Game game, String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(game.getNextTimestamp());
        p.getZone(ZoneType.Battlefield).add(c);
        game.getAction().checkStaticAbilities();
        return c;
    }

    /**
     * Drive the production CLEANUP begin-phase sweep (the WS68 sweep context):
     * {@code endTurnByEffect} sets CLEANUP and runs {@code onPhaseBegin},
     * which traverses the live in-game player list calling
     * {@code autoPassCancel} on each controller.
     */
    private void driveCleanupSweep(Game game, Player playerTurn) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, playerTurn);
        game.getPhaseHandler().endTurnByEffect();
    }

    @Test(timeOut = 30000)
    public void testPriorityConcede2PControl() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        // No enclosing player iteration: baseline immediate-concession semantics.
        p1.getController().concede();

        assertTrue(p1.hasLost(), "Conceding player must have lost");
        assertTrue(p1.conceded(), "Outcome must be Conceded");
        assertFalse(p1.isInGame(), "Conceding player must leave immediately");
        assertFalse(game.getPlayers().contains(p1), "Player list updates immediately");
        assertTrue(game.getLostPlayers().contains(p1), "Conceder recorded among lost players");
        assertTrue(game.isGameOver(), "Two-player concession ends the game");
        assertTrue(p2.hasWon(), "Remaining player wins");
    }

    @Test(timeOut = 30000)
    public void testSyncConcedeDuringCleanupSweep2P() {
        Game game = initAndCreateGame();
        Player conceder = game.getPlayers().get(0);
        Player survivor = game.getPlayers().get(1);
        SyncConcedeController ctrl = installSyncController(conceder, 0);
        SyncConcedeController survivorCtrl = installSyncController(survivor, 1);
        ctrl.concedeOnNextAutoPass = true;

        // Must not throw ConcurrentModificationException: the sweep traversal
        // has to tolerate the synchronous structural removal.
        driveCleanupSweep(game, survivor);

        assertTrue(conceder.conceded(), "Outcome must be Conceded");
        assertFalse(conceder.isInGame(), "Conceder leaves before the sweep returns");
        assertFalse(game.getPlayers().contains(conceder), "Player list updates immediately");
        assertTrue(game.getLostPlayers().contains(conceder), "Conceder recorded among lost players");
        assertEquals(ctrl.autoPassCancelCalls, 1, "Departed player is consulted exactly once");
        assertEquals(survivorCtrl.autoPassCancelCalls, 1,
                "Sweep must still consult every remaining player (no skipped steps)");
        assertTrue(game.isGameOver(), "Two-player concession ends the game");
        assertTrue(survivor.hasWon(), "Remaining player wins");
    }

    @Test(timeOut = 30000)
    public void testSyncConcedeDuringCleanupSweep4P() {
        Game game = initNPlayerGame(4);
        Player conceder = game.getPlayers().get(0);
        SyncConcedeController ctrl = installSyncController(conceder, 0);
        List<SyncConcedeController> survivorCtrls = Lists.newArrayList();
        for (int i = 1; i < 4; i++) {
            survivorCtrls.add(installSyncController(game.getPlayers().get(i), i));
        }

        // CR 800.4a fixture: conceder owns permanents that must leave with them.
        Card owned = addBattlefield(game, "Grizzly Bears", conceder);
        // 800.4 control correction fixture: Bears owned by P1 but controlled by conceder.
        Player owner = game.getPlayers().get(1);
        Card stolen = addBattlefield(game, "Grizzly Bears", owner);
        stolen.addTempController(conceder, game.getNextTimestamp());
        game.getAction().checkStaticAbilities();
        assertEquals(stolen.getController(), conceder, "Steal precondition for fixture");

        ctrl.concedeOnNextAutoPass = true;
        driveCleanupSweep(game, game.getPlayers().get(1));

        // Immediate leave-game semantics: no deferral to a later priority window.
        assertTrue(conceder.conceded(), "Outcome must be Conceded");
        assertFalse(conceder.isInGame(), "Conceder leaves before the sweep returns");
        assertEquals(game.getPlayers().size(), 3, "Remaining player count updates immediately");
        assertFalse(game.getPlayers().contains(conceder), "Player list updates immediately");
        assertTrue(game.getLostPlayers().contains(conceder), "Conceder recorded among lost players");
        assertFalse(game.isGameOver(), "Four-player game continues with three remaining");
        assertEquals(ctrl.autoPassCancelCalls, 1, "Departed player is consulted exactly once");
        for (SyncConcedeController survivorCtrl : survivorCtrls) {
            assertEquals(survivorCtrl.autoPassCancelCalls, 1,
                    "Sweep must still consult every remaining player (no skipped steps)");
        }

        // Native CR 800.4 leave-game cleanup owned by the engine.
        assertFalse(owned.isInZone(ZoneType.Battlefield), "Conceder's owned objects leave the game");
        assertTrue(stolen.isInZone(ZoneType.Battlefield), "Others-owned permanents stay in game");
        assertNotEquals(stolen.getController(), conceder, "Temporary control by leaver ends");
        assertEquals(stolen.getController(), owner, "Control returns to owner");
    }

    @Test(timeOut = 30000)
    public void testSyncConcedeDuringCleanupSweep5P() {
        Game game = initNPlayerGame(5);
        Player conceder = game.getPlayers().get(0);
        SyncConcedeController ctrl = installSyncController(conceder, 0);
        List<SyncConcedeController> survivorCtrls = Lists.newArrayList();
        for (int i = 1; i < 5; i++) {
            survivorCtrls.add(installSyncController(game.getPlayers().get(i), i));
        }
        ctrl.concedeOnNextAutoPass = true;

        driveCleanupSweep(game, game.getPlayers().get(1));

        assertTrue(conceder.conceded(), "Outcome must be Conceded");
        assertFalse(conceder.isInGame(), "Conceder leaves before the sweep returns");
        assertEquals(game.getPlayers().size(), 4, "Remaining player count updates immediately");
        assertFalse(game.isGameOver(), "Five-player game continues with four remaining");
        assertEquals(ctrl.autoPassCancelCalls, 1, "Departed player is consulted exactly once");
        for (SyncConcedeController survivorCtrl : survivorCtrls) {
            assertEquals(survivorCtrl.autoPassCancelCalls, 1,
                    "Sweep must still consult every remaining player (no skipped steps)");
        }
    }

    @Test(timeOut = 60000)
    public void testNoFurtherDecisionsAndCoherentTurnAfterSweepConcede4P() {
        Game game = initNPlayerGame(4);
        Player conceder = game.getPlayers().get(0);
        SyncConcedeController ctrl = installSyncController(conceder, 0);
        ctrl.concedeOnNextAutoPass = true;

        driveCleanupSweep(game, game.getPlayers().get(1));
        assertFalse(conceder.isInGame(), "Precondition: conceder has left");

        // Advance the live game: turn/combat/priority machinery must stay coherent
        // and must never consult the departed player again.
        Player turnAtLeave = game.getPhaseHandler().getPlayerTurn();
        int steps = 0;
        while (!game.isGameOver() && steps < 150
                && game.getPhaseHandler().getPlayerTurn() == turnAtLeave) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
            assertNotEquals(game.getPhaseHandler().getPriorityPlayer(), conceder,
                    "Departed player must never hold priority");
        }
        assertNotEquals(game.getPhaseHandler().getPlayerTurn(), turnAtLeave,
                "Turn must advance past the concession turn");
        assertTrue(game.getPlayers().contains(game.getPhaseHandler().getPlayerTurn()),
                "Current turn player must be a remaining player");
        assertEquals(ctrl.priorityConsultations, 0,
                "Departed player must receive no further priority decisions");
        assertEquals(ctrl.autoPassCancelCalls, 1,
                "Departed player must receive no further sweep consultations");
        assertFalse(game.isGameOver(), "Four-player game continues after turn advance");
    }
}
