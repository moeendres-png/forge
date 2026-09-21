package forge.gamesimulationtests.ws236s1;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * WS236-S1 strict CARD_17 Kaervek reprobe plus bounded trigger-drain family
 * evidence.
 *
 * <p>Oracle (Kaervek the Merciless): "Whenever an opponent casts a spell,
 * Kaervek the Merciless deals damage equal to that spell's mana value to any
 * target." Grizzly Bears has mana value 2, so an opponent-cast Bears must deal
 * exactly 2; Divination has mana value 3, so exactly 3.
 *
 * <p>Root cause under test (WS236 HARNESS_DEFECT): the legacy sim drain loop
 * ({@code resolveStack + checkStateEffects} only) never invokes the engine's
 * own {@code addAllTriggeredAbilitiesToStack()}, stranding CR 603.3b
 * simultaneous entries for every cast-trigger filter. This file's corrected
 * drain performs that engine-owned ordering step before each resolution,
 * exactly as priority passing would. Target and ordering choices inside the
 * engine step are made by the AI controllers through the engine path
 * ({@code orderAndPlaySimultaneousSa}); the harness never selects targets,
 * orders, or injects outcomes.
 */
public class Ws236S1KaervekDrainTest extends SimulationTest {

    private Card bf(String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Hand).add(c);
        p.getGame().getAction().moveTo(ZoneType.Battlefield, c, null, null);
        p.getGame().getAction().checkStaticAbilities();
        return c;
    }

    private Card hand(String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Hand).add(c);
        return c;
    }

    private void lands(Player p, String name, int n) {
        for (int i = 0; i < n; i++) {
            bf(name, p);
        }
    }

    private Card castFromHand(Game game, Player caster, String name) {
        Card c = hand(name, caster);
        SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(
                caster.getController(), caster, sa);
        assertTrue(ok, name + " must cast");
        return c;
    }

    private Card findBf(Game game, String name) {
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    // Corrected drain: engine-owned simultaneous ordering before each
    // resolution (CR 603.3b), same shape as the WS236 discriminator drain.
    // Guard is progression/terminal fail-closed: exceeding it fails the test.
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
        assertTrue(!game.isGameOver(), "game must not be over after drain");
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "no stranded simultaneous entries may remain after drain");
    }

    private String observeStack(Game game) {
        StringBuilder sb = new StringBuilder();
        sb.append("stackSize=").append(game.getStack().size());
        for (SpellAbilityStackInstance si : game.getStack()) {
            SpellAbility sa = si.getSpellAbility();
            sb.append(" [host=").append(sa.getHostCard().getName());
            sb.append(" isTrigger=").append(sa.isTrigger());
            sb.append(" activator=").append(sa.getActivatingPlayer());
            sb.append(" targets=").append(sa.getTargets());
            sb.append("]");
        }
        sb.append(" simultaneous=").append(game.getStack().hasSimultaneousStackEntries());
        return sb.toString();
    }

    @Test(timeOut = 60000)
    public void testKaervekOpponentCastStrict() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card kaervek = bf("Kaervek the Merciless", p1);
        lands(p2, "Forest", 6);
        int p1Before = p1.getLife();
        int p2Before = p2.getLife();
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "pre-state must be clean (zero false-positive queue)");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p2);
        // Opponent (p2) casts Bears, MV 2. Kaervek (p1) must trigger.
        castFromHand(game, p2, "Grizzly Bears");
        System.out.println("WS236S1-OBSERVE kaervek post-cast: " + observeStack(game));
        assertTrue(game.getStack().hasSimultaneousStackEntries(),
                "Kaervek SpellCast trigger must be queued before ordering (nonvacuous gate)");
        // Engine-owned ordering onto the stack (target chosen by the AI
        // controller through the engine path, never by the harness).
        game.getStack().addAllTriggeredAbilitiesToStack();
        System.out.println("WS236S1-OBSERVE kaervek post-order: " + observeStack(game));
        boolean kaervekOnStack = false;
        for (SpellAbilityStackInstance si : game.getStack()) {
            SpellAbility sa = si.getSpellAbility();
            if (sa.isTrigger() && "Kaervek the Merciless".equals(sa.getHostCard().getName())) {
                kaervekOnStack = true;
            }
        }
        assertTrue(kaervekOnStack, "Kaervek trigger must reach the stack via engine ordering");
        drainStack(game);
        System.out.println("WS236S1-OBSERVE kaervek post-drain: p1=" + p1.getLife()
                + " p2=" + p2.getLife() + " kaervekMarked=" + kaervek.getDamage()
                + " " + observeStack(game));
        assertNotNull(findBf(game, "Grizzly Bears"), "Bears spell must resolve to the battlefield");
        assertTrue(game.getStack().isEmpty(), "stack must be empty after drain");
        // Strict Oracle accounting: exactly MV=2 damage to the AI-chosen legal
        // target (life loss on either player plus marked damage on Kaervek, the
        // only battlefield creature at trigger-resolution time).
        int lifeLost = (p1Before - p1.getLife()) + (p2Before - p2.getLife());
        assertEquals(lifeLost + kaervek.getDamage(), 2,
                "Kaervek must deal exactly 2 (Bears MV) to its chosen legal target");
        // Observed engine target policy (DIRECTLY_VERIFIED, sealed in
        // observations): the trigger controller's AI deals the 2 to the
        // opposing player. Lobby-name note: local p1 is driven by
        // LobbyPlayerAi("p2") and local p2 by LobbyPlayerAi("p1"), so the
        // observed targets=[p1] is local p2, the opponent of Kaervek's
        // controller. p2 20->18, p1 untouched, Kaervek unmarked.
        assertEquals(p2.getLife(), p2Before - 2,
                "Kaervek AI must deal its 2 to opponent p2");
        assertEquals(p1.getLife(), p1Before,
                "Kaervek AI must not damage its own controller");
        assertEquals(kaervek.getDamage(), 0,
                "Kaervek AI must not mark its own host in this board state");
        assertNotNull(findBf(game, "Kaervek the Merciless"), "Kaervek must remain");
    }

    @Test(timeOut = 60000)
    public void testKaervekControllerCastNoTrigger() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        bf("Kaervek the Merciless", p1);
        lands(p1, "Forest", 6);
        int p1Before = p1.getLife();
        int p2Before = p2.getLife();
        // Controller's own cast: Opponent filter must stay silent.
        castFromHand(game, p1, "Grizzly Bears");
        System.out.println("WS236S1-OBSERVE own-cast post-cast: " + observeStack(game));
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "own cast must queue no Kaervek trigger (zero false-positive)");
        drainStack(game);
        assertNotNull(findBf(game, "Grizzly Bears"), "Bears spell must resolve to the battlefield");
        assertEquals(p1.getLife(), p1Before, "no damage on own cast (p1)");
        assertEquals(p2.getLife(), p2Before, "no damage on own cast (p2)");
    }

    @Test(timeOut = 60000)
    public void testVeyranDoubledPlusKaervekSilentOnOwnCast() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card veyran = bf("Veyran, Voice of Duality", p1);
        bf("Kaervek the Merciless", p1);
        lands(p1, "Island", 3);
        for (int i = 0; i < 7; i++) {
            Card lib = createCard("Runeclaw Bear", p1);
            lib.setGameTimestamp(game.getNextTimestamp());
            p1.getZone(ZoneType.Library).add(lib);
        }
        int veyranBeforeP = veyran.getNetPower();
        int veyranBeforeT = veyran.getNetToughness();
        int p1Before = p1.getLife();
        Player p2 = game.getPlayers().get(1);
        int p2Before = p2.getLife();
        // Own Divination: Veyran magecraft (doubled, same-controller
        // multi-trigger ordering) fires; Kaervek Opponent filter stays silent.
        castFromHand(game, p1, "Divination");
        assertTrue(game.getStack().hasSimultaneousStackEntries(),
                "Veyran triggers must be queued before ordering");
        game.getStack().addAllTriggeredAbilitiesToStack();
        int veyranTriggers = 0;
        boolean kaervekTrigger = false;
        for (SpellAbilityStackInstance si : game.getStack()) {
            SpellAbility sa = si.getSpellAbility();
            if (sa.isTrigger() && "Veyran, Voice of Duality".equals(sa.getHostCard().getName())) {
                veyranTriggers++;
            }
            if (sa.isTrigger() && "Kaervek the Merciless".equals(sa.getHostCard().getName())) {
                kaervekTrigger = true;
            }
        }
        System.out.println("WS236S1-OBSERVE own-divination post-order: " + observeStack(game));
        assertEquals(veyranTriggers, 2, "Veyran doubled magecraft must put 2 triggers on the stack");
        assertFalse(kaervekTrigger, "Kaervek must not trigger on controller's own cast");
        drainStack(game);
        assertEquals(veyran.getNetPower(), veyranBeforeP + 2,
                "Veyran must get +2 power from doubled magecraft");
        assertEquals(veyran.getNetToughness(), veyranBeforeT + 2,
                "Veyran must get +2 toughness from doubled magecraft");
        assertEquals(p1.getLife(), p1Before, "Kaervek silent: no damage (p1)");
        assertEquals(p2.getLife(), p2Before, "Kaervek silent: no damage (p2)");
    }
}

