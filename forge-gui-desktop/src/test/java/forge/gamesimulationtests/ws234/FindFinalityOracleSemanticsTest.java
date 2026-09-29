package forge.gamesimulationtests.ws234;

import org.testng.annotations.Test;

import forge.ai.simulation.SimulationTest;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Regression for the Lab-authored WS234 Find // Finality script drift.
 *
 * Find // Finality is an ordinary split card. Neither half has Aftermath:
 * both halves are castable from hand at sorcery timing, and neither half is
 * castable from the graveyard without a separate permission effect.
 */
public class FindFinalityOracleSemanticsTest extends SimulationTest {

    private static final String SPLIT = "Find // Finality";

    private static SpellAbility half(Card card, CardStateName state, Player player) {
        SpellAbility sa = card.getState(state).getFirstSpellAbility();
        assertNotNull(sa, state + " must expose a spell ability");
        sa.setActivatingPlayer(player);
        return sa;
    }

    private Card addToZone(String name, Player player, ZoneType zone) {
        Card card = createCard(name, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }

    @Test(timeOut = 60000)
    public void bothHalvesAreOrdinarySplitSpellsFromHand() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card card = addToZone(SPLIT, p1, ZoneType.Hand);
        SpellAbility find = half(card, CardStateName.LeftSplit, p1);
        SpellAbility finality = half(card, CardStateName.RightSplit, p1);

        assertFalse(find.isAftermath(), "Find must not be an Aftermath spell");
        assertFalse(finality.isAftermath(), "Finality must not be an Aftermath spell");
        assertTrue(find.canPlay(true), "Find must be castable from hand at sorcery timing");
        assertTrue(finality.canPlay(true), "Finality must be castable from hand at sorcery timing");
    }

    @Test(timeOut = 60000)
    public void neitherHalfGetsImplicitGraveyardPermission() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card card = addToZone(SPLIT, p1, ZoneType.Graveyard);
        SpellAbility find = half(card, CardStateName.LeftSplit, p1);
        SpellAbility finality = half(card, CardStateName.RightSplit, p1);

        assertFalse(find.isAftermath(), "Find must remain a normal split half");
        assertFalse(finality.isAftermath(), "Finality must remain a normal split half");
        assertFalse(find.canPlay(true), "Find needs an external permission to be cast from graveyard");
        assertFalse(finality.canPlay(true),
                "Finality needs an external permission to be cast from graveyard");
    }
}
