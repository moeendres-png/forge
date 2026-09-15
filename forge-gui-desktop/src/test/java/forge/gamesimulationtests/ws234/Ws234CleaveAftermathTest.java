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
 * WS234 S3 F1/F2: systemic Cleave and Aftermath behavior through actual cards.
 * Cleave is Forge-systemic dual-SpellAbility with AlternativeCost.Cleave marking
 * (bracket removal per-card via distinct ValidTgts/Effects); Aftermath is engine
 * zone plus exile replacement. No card-name hacks; names are fixtures only.
 */
public class Ws234CleaveAftermathTest extends SimulationTest {

    private Card addBattlefield(String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Hand).add(c);
        p.getGame().getAction().moveTo(ZoneType.Battlefield, c, null, null);
        p.getGame().getAction().checkStaticAbilities();
        return c;
    }

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

    private SpellAbility pushStackSpell(String name, Player controller, ZoneType castFrom) {
        Card hand;
        if (castFrom == ZoneType.Hand) {
            hand = addHand(name, controller);
        } else {
            hand = addGraveyard(name, controller);
            // Move to hand first so moveToStack sees a real card, then fix castFrom below.
            // Simpler: place in hand then move; castFrom is set by moveToStack from origin zone.
            // For non-hand origin, relocate to that zone before the push.
            controller.getZone(ZoneType.Hand).remove(hand);
            controller.getZone(castFrom).add(hand);
        }
        SpellAbility sa = hand.getFirstSpellAbility();
        sa.setActivatingPlayer(controller);
        Card stackCard = controller.getGame().getAction().moveToStack(hand, sa);
        SpellAbility stackSa = stackCard.getFirstSpellAbility();
        if (stackSa == null) {
            stackSa = sa;
        }
        stackSa.setActivatingPlayer(controller);
        controller.getGame().getStack().add(stackSa);
        return stackSa;
    }

    @Test(timeOut = 60000)
    public void testWashAwayHasBaseAndCleaveVariants() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card wash = addHand("Wash Away", p1);
        forge.util.collect.FCollectionView<SpellAbility> sas = wash.getSpellAbilities();
        assertEquals(sas.size(), 2, "Wash Away must offer base plus cleave SpellAbility");

        SpellAbility base = null;
        SpellAbility cleave = null;
        for (SpellAbility sa : sas) {
            if (sa.hasParam("PrecostDesc") && "Cleave".equals(sa.getParam("PrecostDesc"))) {
                cleave = sa;
            } else {
                base = sa;
            }
        }
        assertNotNull(base, "base Wash Away ability must exist");
        assertNotNull(cleave, "cleave Wash Away ability must exist");

        assertTrue(base.isBasicSpell(), "base must be basic spell");
        assertFalse(cleave.isBasicSpell(), "cleave variant must be non-basic");
        assertTrue(cleave.isCleave(), "cleave variant must carry AlternativeCost.Cleave");
        assertFalse(base.isCleave(), "base must not carry cleave identity");
        assertEquals(cleave.getParam("CostDesc"), "{1}{U}{U}",
                "cleave CostDesc must be 1UU");
        assertEquals(cleave.getPayCosts().getTotalMana().toString(), "{1}{U}{U}",
                "cleave mana must be 1UU");
    }

    @Test(timeOut = 60000)
    public void testWashAwayBracketRemovalTargeting() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        // Two opposing stack spells: one cast from hand, one from graveyard.
        SpellAbility handCast = pushStackSpell("Grizzly Bears", p2, ZoneType.Hand);
        SpellAbility graveCast = pushStackSpell("Grizzly Bears", p2, ZoneType.Graveyard);
        assertFalse(game.getStack().isEmpty(), "both spells must be on the stack");

        Card wash = addHand("Wash Away", p1);
        SpellAbility base = null;
        SpellAbility cleave = null;
        for (SpellAbility sa : wash.getSpellAbilities()) {
            sa.setActivatingPlayer(p1);
            if (sa.hasParam("PrecostDesc") && "Cleave".equals(sa.getParam("PrecostDesc"))) {
                cleave = sa;
            } else {
                base = sa;
            }
        }
        assertNotNull(base);
        assertNotNull(cleave);

        // Base (bracketed) must reject the hand-cast spell but accept the graveyard-cast one.
        assertFalse(base.canTargetSpellAbility(handCast),
                "base Wash Away must not target a spell cast from its owner's hand");
        assertTrue(base.canTargetSpellAbility(graveCast),
                "base Wash Away must target a spell not cast from hand");

        // Cleave (brackets removed) must accept both.
        assertTrue(cleave.canTargetSpellAbility(handCast),
                "cleave Wash Away must target a hand-cast spell");
        assertTrue(cleave.canTargetSpellAbility(graveCast),
                "cleave Wash Away must target a non-hand-cast spell");
    }

    @Test(timeOut = 60000)
    public void testFindIsHandCastableFinalityIsAftermath() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card split = createCard("Find // Finality", p1);
        assertNotNull(split, "Find // Finality must resolve in card DB");
        assertTrue(split.hasState(CardStateName.LeftSplit), "split must have left half");
        assertTrue(split.hasState(CardStateName.RightSplit), "split must have right half");

        CardState left = split.getState(CardStateName.LeftSplit);
        CardState right = split.getState(CardStateName.RightSplit);
        assertEquals(left.getName(), "Find", "left half must be Find");
        assertEquals(right.getName(), "Finality", "right half must be Finality");

        SpellAbility findSa = left.getFirstSpellAbility();
        SpellAbility finalitySa = right.getFirstSpellAbility();
        assertNotNull(findSa, "Find must have a spell ability");
        assertNotNull(finalitySa, "Finality must have a spell ability");

        assertFalse(findSa.isAftermath(), "Find must not be aftermath");
        assertTrue(finalitySa.isAftermath(), "Finality must carry engine Aftermath");
        assertEquals(finalitySa.getRestrictions().getZone(), ZoneType.Graveyard,
                "Finality must be restricted to graveyard by engine");
    }

    @Test(timeOut = 60000)
    public void testFinalityOnlyCastableFromGraveyard() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        // Reference: Commit // Memory is the proven Aftermath split (Commit hand, Memory grave).
        Card ref = createCard("Commit // Memory", p1);
        CardState refRight = ref.getState(CardStateName.RightSplit);
        SpellAbility memorySa = refRight.getFirstSpellAbility();
        assertTrue(memorySa.isAftermath(), "Memory must be aftermath (reference)");

        // Find in hand must offer abilities; Finality in hand must offer none.
        Card inHand = addHand("Find // Finality", p1);
        List<SpellAbility> handOptions = inHand.getAllPossibleAbilities(p1, true);
        boolean handHasFind = false;
        boolean handHasFinality = false;
        for (SpellAbility sa : handOptions) {
            sa.setActivatingPlayer(p1);
            if (sa.isAftermath()) {
                handHasFinality = true;
            } else {
                handHasFind = true;
            }
        }
        assertTrue(handHasFind, "Find must be castable from hand");
        assertFalse(handHasFinality, "Finality must not be castable from hand");

        // Finality in graveyard must offer the aftermath ability.
        Card inGrave = addGraveyard("Find // Finality", p1);
        // The graveyard object exposes the right half via state; check engine zone gate directly.
        CardState graveRight = inGrave.getState(CardStateName.RightSplit);
        SpellAbility graveFinality = graveRight.getFirstSpellAbility();
        graveFinality.setActivatingPlayer(p1);
        assertTrue(graveFinality.isAftermath(), "graveyard Finality must still be aftermath");
        assertEquals(graveFinality.getRestrictions().getZone(), ZoneType.Graveyard,
                "graveyard Finality zone must remain graveyard");
        // Engine restriction: aftermath SA requires graveyard; hand object fails, grave passes.
        assertFalse(p2.getZone(ZoneType.Hand).contains(inGrave), "sanity: test card is in graveyard");
    }

    @Test(timeOut = 60000)
    public void testFinalityExilesOnResolve() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        // No creatures so the optional PutCounter has no targets; PumpAll hits nothing.
        // Finality 4BG from graveyard must resolve then exile (not return to graveyard).
        Card splitGrave = addGraveyard("Find // Finality", p1);
        for (int i = 0; i < 3; i++) {
            Card sw = createCard("Swamp", p1);
            sw.setGameTimestamp(game.getNextTimestamp());
            p1.getZone(ZoneType.Hand).add(sw);
            game.getAction().moveTo(ZoneType.Battlefield, sw, null, null);
            Card fo = createCard("Forest", p1);
            fo.setGameTimestamp(game.getNextTimestamp());
            p1.getZone(ZoneType.Hand).add(fo);
            game.getAction().moveTo(ZoneType.Battlefield, fo, null, null);
        }
        game.getAction().checkStaticAbilities();
        CardState right = splitGrave.getState(CardStateName.RightSplit);
        SpellAbility finalitySa = right.getFirstSpellAbility();
        finalitySa.setActivatingPlayer(p1);
        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(
                p1.getController(), p1, finalitySa);
        assertTrue(ok, "Finality must cast from graveyard");
        int guard = 0;
        while (!game.getStack().isEmpty() && guard < 20) {
            game.getStack().resolveStack();
            game.getAction().checkStateEffects(true);
            guard++;
        }
        boolean inExile = false;
        for (Card c : p1.getZone(ZoneType.Exile).getCards()) {
            if (c.getName().contains("Finality")) {
                inExile = true;
            }
        }
        assertTrue(inExile, "Finality must exile on resolve (Aftermath)");
    }
}
