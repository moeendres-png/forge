package forge.gamesimulationtests.ws234;

import java.util.List;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Independent DeepSeek completion lab: the split-card alternate half must be
 * discovered as a playable ability in the zone where its own restriction
 * permits it.
 *
 * <p>WS234 proved the Aftermath keyword, the zone gate and the exile-on-resolve
 * replacement are sound, but it only asserted the graveyard half through
 * {@code getState(RightSplit).getFirstSpellAbility()}. It never asserted that
 * {@code getAllPossibleAbilities} surfaces that half when the card is actually
 * in the graveyard, which is the surface every external consumer (bridge, AI)
 * enumerates. The bridge probe showed the consequence: the grant index contains
 * the card and the enumeration yields zero abilities.
 *
 * <p>Bound to Cut // Ribbons (a genuine Aftermath card); the original binding to
 * Find // Finality rested on a false premise (Finality has no Aftermath).
 *
 * <p>These tests pin the enumeration, not the keyword: a graveyard Aftermath
 * half must be offered, the hand must not offer it, the front half must not be
 * offered from the graveyard, and no half may be duplicated.
 */
public class DeepseekAftermathDiscoveryTest extends SimulationTest {

    // A genuine Aftermath split card. Oracle: Ribbons has "Aftermath (Cast this spell only
    // from your graveyard. Then exile it.)". Find // Finality is NOT an Aftermath card
    // (plain split card; see AftermathKeywordOracleConsistencyTest / PB-07 source truth),
    // so this regression is bound to Cut // Ribbons instead. Verified: without the
    // Card.getAllPossibleAbilities alternate-split-state enumeration these tests fail 3/6.
    private static final String SPLIT = "Cut // Ribbons";

    private Card addHand(String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Hand).add(c);
        return c;
    }

    private Card addGraveyard(String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Graveyard).add(c);
        return c;
    }

    /**
     * The exact state a split card has after its front half was cast: the cast
     * path calls {@code setSplitStateToPlayAbility} with the front SA, so the
     * card's current state becomes LeftSplit. That is the state the bridge probe
     * observed in the graveyard, and it is the state these tests must cover.
     */
    private Card addGraveyardAfterFrontCastState(String name, Player p) {
        Card c = addGraveyard(name, p);
        CardState left = c.getState(CardStateName.LeftSplit);
        SpellAbility findSa = left.getFirstSpellAbility();
        assertNotNull(findSa, "the left half must have a spell ability");
        findSa.setActivatingPlayer(p);
        c.setSplitStateToPlayAbility(findSa);
        assertEquals(c.getCurrentStateName(), CardStateName.LeftSplit,
                "the probe-faithful fixture must hold the front-half state");
        return c;
    }

    private static int countAftermath(List<SpellAbility> abilities, Player p) {
        int count = 0;
        for (SpellAbility sa : abilities) {
            sa.setActivatingPlayer(p);
            if (sa.isAftermath()) {
                count++;
            }
        }
        return count;
    }

    private static int countNonAftermathSpells(List<SpellAbility> abilities, Player p) {
        int count = 0;
        for (SpellAbility sa : abilities) {
            sa.setActivatingPlayer(p);
            if (sa.isSpell() && !sa.isAftermath()) {
                count++;
            }
        }
        return count;
    }

    @Test(timeOut = 60000)
    public void aftermathHalfIsEnumeratedFromGraveyard() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card inGrave = addGraveyardAfterFrontCastState(SPLIT, p1);
        System.out.println("[deepseek-aftermath] currentState=" + inGrave.getCurrentStateName()
                + " currentAbilities=" + inGrave.getSpellAbilities().size()
                + " alternate=" + (inGrave.getAlternateState() == null ? "null"
                        : inGrave.getAlternateState().getStateName())
                + " alternateAbilities=" + (inGrave.getAlternateState() == null ? -1
                        : inGrave.getAlternateState().getSpellAbilities().size()));
        List<SpellAbility> options = inGrave.getAllPossibleAbilities(p1, true);
        for (SpellAbility sa : options) {
            sa.setActivatingPlayer(p1);
            System.out.println("[deepseek-aftermath] option after=" + sa.isAftermath()
                    + " spell=" + sa.isSpell() + " zone="
                    + (sa.getRestrictions() == null ? null : sa.getRestrictions().getZone()));
        }
        assertEquals(countAftermath(options, p1), 1,
                "the graveyard Aftermath half must be enumerated exactly once: " + options);
    }

    @Test(timeOut = 60000)
    public void freshGraveyardCardIsAlsoEnumerated() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card inGrave = addGraveyard(SPLIT, p1);
        List<SpellAbility> options = inGrave.getAllPossibleAbilities(p1, true);
        assertEquals(countAftermath(options, p1), 1,
                "a directly materialized graveyard card must stay enumerable: " + options);
    }

    @Test(timeOut = 60000)
    public void aftermathHalfIsNotEnumeratedFromHand() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card inHand = addHand(SPLIT, p1);
        List<SpellAbility> options = inHand.getAllPossibleAbilities(p1, true);
        assertTrue(countNonAftermathSpells(options, p1) >= 1,
                "the front half must be castable from the hand: " + options);
        assertEquals(countAftermath(options, p1), 0,
                "the Aftermath half must not be castable from the hand: " + options);
    }

    @Test(timeOut = 60000)
    public void frontHalfIsNotEnumeratedFromGraveyard() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card inGrave = addGraveyardAfterFrontCastState(SPLIT, p1);
        List<SpellAbility> options = inGrave.getAllPossibleAbilities(p1, true);
        assertEquals(countNonAftermathSpells(options, p1), 0,
                "the front half must not be castable from the graveyard: " + options);
    }

    @Test(timeOut = 60000)
    public void enumeratedAftermathKeepsItsGraveyardRestriction() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card inGrave = addGraveyardAfterFrontCastState(SPLIT, p1);
        SpellAbility aftermath = null;
        for (SpellAbility sa : inGrave.getAllPossibleAbilities(p1, true)) {
            sa.setActivatingPlayer(p1);
            if (sa.isAftermath()) {
                aftermath = sa;
            }
        }
        assertNotNull(aftermath, "the graveyard Aftermath half must be enumerated");
        assertEquals(aftermath.getRestrictions().getZone(), ZoneType.Graveyard,
                "the enumerated ability must keep the engine's graveyard gate");
    }

    @Test(timeOut = 60000)
    public void graveyardEnumerationDoesNotDuplicateTheHalf() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card inGrave = addGraveyardAfterFrontCastState(SPLIT, p1);
        List<SpellAbility> options = inGrave.getAllPossibleAbilities(p1, true);
        int occurrences = 0;
        for (SpellAbility sa : options) {
            sa.setActivatingPlayer(p1);
            if (sa.isAftermath()) {
                occurrences++;
            }
        }
        assertEquals(occurrences, 1, "the half must appear once, not per state: " + options);
        assertEquals(options.size(), 1,
                "only the legal half is offered from the graveyard: " + options);
    }
}
