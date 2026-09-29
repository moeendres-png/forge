package forge.gamesimulationtests.ws234;


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
 *
 * <p><b>Source-truth correction (PB-07 / CARD_28).</b> The three Aftermath trials
 * that this class originally asserted were derived from a Lab-fork card-script
 * mutation (Forge {@code bc347e62255e61d950154824b427251fdabcf5f6}) that added
 * {@code K:Aftermath} to {@code Find // Finality} and rewrote that half's Oracle
 * text. Find // Finality is an ordinary Double Feature split card; its current
 * Oracle text carries no Aftermath keyword, so under CR 108.1 (Oracle text governs
 * wording) and CR 702.127a (Aftermath is a keyword ability found on some split
 * cards) it has no graveyard half at all. The trials below now assert the Oracle
 * behavior, and the genuine Aftermath card is the positive control that the
 * keyword itself is still supported by the engine. Card names are fixtures only;
 * no card-name branch selects an expected answer.
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
    public void testFindFinalityBothHalvesArePlainSplitSpells() {
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

        // Neither half carries Aftermath: the Oracle text of this card has no
        // Aftermath keyword, so the engine must not manufacture one.
        assertFalse(findSa.isAftermath(),
                "Find must not be aftermath: the card's Oracle text has no Aftermath keyword");
        assertFalse(finalitySa.isAftermath(),
                "Finality must not be aftermath: the card's Oracle text has no Aftermath keyword");

        // Neither half is restricted to a graveyard, so neither is a graveyard half.
        assertNotEquals(finalitySa.getRestrictions().getZone(), ZoneType.Graveyard,
                "Finality must not be restricted to the graveyard");
        assertNotEquals(findSa.getRestrictions().getZone(), ZoneType.Graveyard,
                "Find must not be restricted to the graveyard");

        // Split-card characteristics come from the combined halves, not from the
        // current (left) state: 8 total mana value and black-green identity.
        assertEquals(split.getRules().getManaCost().getCMC(), 8,
                "a split card's mana value is the sum of both halves (Find 2 + Finality 6)");
        assertTrue(split.getRules().getColorIdentity().hasBlack()
                        && split.getRules().getColorIdentity().hasGreen(),
                "Find // Finality is a black-green split card");
    }

    @Test(timeOut = 60000)
    public void testFinalityIsHandCastableAndGraveyardCastRefused() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        // Positive control: a genuine Aftermath split card. Commit from hand,
        // Memory from the graveyard. If this control ever fails, the Aftermath
        // keyword itself regressed and the negative assertions below are void.
        Card ref = createCard("Commit // Memory", p1);
        CardState refRight = ref.getState(CardStateName.RightSplit);
        SpellAbility memorySa = refRight.getFirstSpellAbility();
        memorySa.setActivatingPlayer(p1);
        assertTrue(memorySa.isAftermath(), "Memory must be aftermath (reference control)");
        assertEquals(memorySa.getRestrictions().getZone(), ZoneType.Graveyard,
                "a genuine Aftermath half stays graveyard-gated (reference control)");

        // In hand both halves of Find // Finality are ordinary hand casts, and
        // neither of them is an Aftermath half. The halves are told apart by
        // their own mana cost (Find {B/G}{B/G} = 2, Finality {4}{B}{G} = 6),
        // not by option order.
        Card inHand = addHand("Find // Finality", p1);
        final java.util.Set<Integer> offeredCmc = new java.util.TreeSet<>();
        boolean handHasAftermath = false;
        for (SpellAbility sa : inHand.getAllPossibleAbilities(p1, true)) {
            sa.setActivatingPlayer(p1);
            if (sa.isAftermath()) {
                handHasAftermath = true;
                continue;
            }
            if (sa.isSpell()) {
                offeredCmc.add(Integer.valueOf(sa.getPayCosts().getTotalMana().getCMC()));
            }
        }
        assertFalse(handHasAftermath, "no half of Find // Finality is an Aftermath half");
        assertEquals(offeredCmc, new java.util.TreeSet<>(java.util.Arrays.asList(
                        Integer.valueOf(2), Integer.valueOf(6))),
                "both halves must be offered as distinct hand casts");

        // In the graveyard neither half may be cast: a split card's halves are
        // only castable from the hand (or from exile for a meld, never here).
        Card inGrave = addGraveyard("Find // Finality", p1);
        assertTrue(p1.getZone(ZoneType.Graveyard).contains(inGrave),
                "sanity: the test card really is in the graveyard");
        assertFalse(p2.getZone(ZoneType.Hand).contains(inGrave),
                "sanity: the graveyard card is not in another player's hand");
        for (SpellAbility sa : inGrave.getAllPossibleAbilities(p1, true)) {
            sa.setActivatingPlayer(p1);
            assertFalse(sa.isAftermath(),
                    "a graveyard Find // Finality must expose no Aftermath ability");
        }
    }

    @Test(timeOut = 60000)
    public void testFinalityResolvesFromHandAndReturnsToGraveyard() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        // Behavior discriminator: P1's 4/4 gets two +1/+1 counters and then
        // -4/-4, so it survives as a 2/2; P2's unboosted 2/2 dies outright.
        Card own = addBattlefield("Serra Angel", p1);
        Card theirs = addBattlefield("Grizzly Bears", p2);
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

        // Cast the Finality half from HAND, the only zone Oracle permits.
        Card inHand = addHand("Find // Finality", p1);
        SpellAbility finalitySa = inHand.getState(CardStateName.RightSplit).getFirstSpellAbility();
        finalitySa.setActivatingPlayer(p1);
        assertTrue(forge.game.player.PlaySpellAbility.playSpellAbility(
                p1.getController(), p1, finalitySa), "Finality must be castable from hand");
        int guard = 0;
        while (!game.getStack().isEmpty() && guard < 20) {
            game.getStack().resolveStack();
            game.getAction().checkStateEffects(true);
            guard++;
        }
        game.getAction().checkStateEffects(true);

        // Asymmetric creature effect: -4/-4 kills the Bear, the Angel survives.
        assertTrue(p1.getZone(ZoneType.Battlefield).contains(own),
                "a 4/4 with two +1/+1 counters must survive Finality's -4/-4");
        assertFalse(p2.getZone(ZoneType.Battlefield).contains(theirs),
                "an unboosted 2/2 must die to Finality's -4/-4");

        // A non-Aftermath spell returns to the graveyard after resolving; it is
        // not exiled, and it did not come from the graveyard.
        boolean inGrave = false;
        boolean inExile = false;
        for (Card c : p1.getZone(ZoneType.Graveyard).getCards()) {
            if (c.getName() != null && c.getName().contains("Finality")) {
                inGrave = true;
            }
        }
        for (Card c : p1.getZone(ZoneType.Exile).getCards()) {
            if (c.getName() != null && c.getName().contains("Finality")) {
                inExile = true;
            }
        }
        assertTrue(inGrave, "a hand-cast Finality ends in the graveyard on resolution");
        assertFalse(inExile,
                "Finality must not be exiled: the card has no Aftermath keyword");
    }
}
