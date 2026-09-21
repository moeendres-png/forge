package forge.gamesimulationtests.wsr10;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * R10c PATH sim family: Path of Ancestry (CARD_27) commander-type scry.
 *
 * <p>WS234 proved enters-tapped; mana + sharing-type scry was NOT_RUN.
 * The Protocol-2 bridge cannot represent scry arrangement
 * (arrangeForScry unsupported) and drops Path tap production silently
 * (both filed as BRIDGE_DEFECT with reproduction; bridge is not the seam
 * for this branch). These four strict sim tests prove the native path:
 * scry trigger fires on shared-type spend (queued + resolved), stays
 * silent for non-sharing types and non-Path mana, plus enters-tapped
 * retention. AI scry keep/bottom pick is engine-AI discretion, documented
 * per pick, never harness-chosen.</p>
 */
public class WsR10PathSimFamilyTest extends SimulationTest {

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

    private void mountains(Player p, int n) {
        for (int i = 0; i < n; i++) {
            bf("Mountain", p);
        }
    }

    private void forests(Player p, int n) {
        for (int i = 0; i < n; i++) {
            bf("Forest", p);
        }
    }

    private Card findBf(Game game, String name) {
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    // WS236-S1 drain shape (test-only, engine-owned ordering).
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
        assertFalse(game.isGameOver(), "game must not be over");
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "no stranded simultaneous entries may remain after drain");
        assertTrue(game.getStack().isEmpty(), "stack must be empty after drain");
    }

    private boolean triggerQueued(Game game) {
        game.getStack().addAllTriggeredAbilitiesToStack();
        return game.getStack().hasSimultaneousStackEntries()
                || game.getStack().size() > 1;
    }

    @Test(timeOut = 60000)
    public void testPathScryFiresOnSharedType() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card gishath = createCard("Gishath, Sun's Avatar", p1);
        p1.getZone(ZoneType.Command).add(gishath);
        p1.addCommander(gishath);
        Card path = bf("Path of Ancestry", p1);
        mountains(p1, 1);
        Card hatch = hand("Raptor Hatchling", p1);
        assertTrue(hatch.sharesCreatureTypeWith(gishath), "Dinosaur must be shared");

        SpellAbility pathSa = null;
        for (SpellAbility a : path.getManaAbilities()) {
            pathSa = a;
            break;
        }
        assertNotNull(pathSa, "Path must offer its mana ability natively");
        pathSa.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, pathSa),
                "Path tap must produce (COLOR_CHOICE answered by engine AI)");

        SpellAbility sa = hatch.getFirstSpellAbility();
        sa.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, sa),
                "Hatchling must cast");
        assertTrue(triggerQueued(game), "scry trigger must queue on shared-type Path spend");
        drainStack(game);
        assertNotNull(findBf(game, "Raptor Hatchling"), "Hatchling must resolve");
    }

    @Test(timeOut = 60000)
    public void testPathSilentOnDifferentType() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card gishath = createCard("Gishath, Sun's Avatar", p1);
        p1.getZone(ZoneType.Command).add(gishath);
        p1.addCommander(gishath);
        Card path = bf("Path of Ancestry", p1);
        forests(p1, 2);
        Card bear = hand("Runeclaw Bear", p1);
        assertFalse(bear.sharesCreatureTypeWith(gishath), "Bear must not share type");

        SpellAbility pathSa = null;
        for (SpellAbility a : path.getManaAbilities()) {
            pathSa = a;
            break;
        }
        pathSa.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, pathSa),
                "Path tap must produce");

        SpellAbility sa = bear.getFirstSpellAbility();
        sa.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, sa),
                "Bear must cast");
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "no trigger may queue for non-sharing type");
        assertEquals(game.getStack().size(), 1, "only the Bear spell may be stacked");
        drainStack(game);
        assertNotNull(findBf(game, "Runeclaw Bear"), "Bear must resolve");
    }

    @Test(timeOut = 60000)
    public void testPathSilentOnOtherMana() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card gishath = createCard("Gishath, Sun's Avatar", p1);
        p1.getZone(ZoneType.Command).add(gishath);
        p1.addCommander(gishath);
        bf("Path of Ancestry", p1);
        mountains(p1, 2);
        Card hatch = hand("Raptor Hatchling", p1);

        SpellAbility sa = hatch.getFirstSpellAbility();
        sa.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, sa),
                "Hatchling must cast on Mountain mana");
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "no trigger may queue without Path mana");
        assertEquals(game.getStack().size(), 1, "only the Hatchling spell may be stacked");
        drainStack(game);
        assertNotNull(findBf(game, "Raptor Hatchling"), "Hatchling must resolve");
    }

    @Test(timeOut = 60000)
    public void testPathEntersTapped() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card path = hand("Path of Ancestry", p1);
        game.getAction().moveTo(ZoneType.Battlefield, path, null, null);
        game.getAction().checkStateEffects(true);
        assertTrue(path.isTapped(), "Path must enter tapped (replacement)");
        assertNotNull(findBf(game, "Path of Ancestry"), "Path must be on the battlefield");
    }
}
