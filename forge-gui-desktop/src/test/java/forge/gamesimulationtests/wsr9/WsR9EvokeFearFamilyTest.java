package forge.gamesimulationtests.wsr9;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * R9a EVOKE/FEAR family: Shriekmaw (CARD_18) evoke-branch closure.
 *
 * <p>WS234 proved the hard-cast ETB (destroy Bear) and left Evoke {1}{B} +
 * evoke-sac + Fear evasion NOT_RUN. These five strict actual-card tests
 * prove the native evoke path: alternative-cost enumeration (base 4B vs
 * evoke 1B), evoke cast from real Swamps with engine-chosen ETB target,
 * evoke-sacrifice on entry, hard-cast survival (no sac without evoke),
 * and Fear block legality (CR 702.34) via CombatUtil. Fail-closed
 * negatives included. No manual outcome injection.</p>
 */
public class WsR9EvokeFearFamilyTest extends SimulationTest {

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

    private void swamps(Player p, int n) {
        for (int i = 0; i < n; i++) {
            bf("Swamp", p);
        }
    }

    private SpellAbility findBase(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (!sa.isAlternativeCost(AlternativeCost.Evoke)) {
                return sa;
            }
        }
        return null;
    }

    private SpellAbility findEvoke(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isAlternativeCost(AlternativeCost.Evoke)) {
                return sa;
            }
        }
        return null;
    }

    private int countTappedSwamps(Game game) {
        int n = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if ("Swamp".equals(c.getName()) && c.isTapped()) {
                n++;
            }
        }
        return n;
    }

    private boolean inZone(Player p, ZoneType zone, String name) {
        for (Card c : p.getZone(zone).getCards()) {
            if (name.equals(c.getName())) {
                return true;
            }
        }
        return false;
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

    @Test(timeOut = 60000)
    public void testEvokeAbilityEnumeratedNatively() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card maw = hand("Shriekmaw", p1);

        SpellAbility base = findBase(maw);
        SpellAbility evoke = findEvoke(maw);
        assertNotNull(base, "base SpellAbility must exist natively");
        assertNotNull(evoke, "evoke SpellAbility must exist natively");
        assertNotSame(evoke, base, "evoke must be a distinct ability");
        assertTrue(base.isBasicSpell(), "base must be the basic spell");
        assertFalse(evoke.isBasicSpell(), "evoke variant must be non-basic");
        assertEquals(evoke.getPayCosts().getTotalMana().toString(), "{1}{B}",
                "evoke mana must be exactly 1B");
        assertTrue(maw.hasKeyword("Fear"), "Shriekmaw must carry Fear natively");
    }

    @Test(timeOut = 60000)
    public void testEvokeCastEntersTriggersEtbThenSacrificed() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        bf("Runeclaw Bear", p2);
        swamps(p1, 3);

        Card maw = hand("Shriekmaw", p1);
        SpellAbility evoke = findEvoke(maw);
        assertNotNull(evoke, "evoke SpellAbility must exist natively");
        evoke.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, evoke),
                "evoke cast for 1B must succeed");
        assertEquals(countTappedSwamps(game), 2,
                "evoke cast must pay exactly 1B from real Swamps");
        drainStack(game);

        assertTrue(inZone(p1, ZoneType.Graveyard, "Shriekmaw"),
                "evoked Shriekmaw must be sacrificed on entry (evoke cost)");
        assertEquals(countBf(game, "Runeclaw Bear"), 0,
                "evoke ETB must destroy the nonblack nonartifact Bear");
    }

    private int countBf(Game game, String name) {
        int n = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(c.getName())) {
                n++;
            }
        }
        return n;
    }

    @Test(timeOut = 60000)
    public void testHardCastSurvivesWithoutSacrifice() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        bf("Runeclaw Bear", p2);
        swamps(p1, 6);

        Card maw = hand("Shriekmaw", p1);
        SpellAbility base = findBase(maw);
        assertNotNull(base, "base SpellAbility must exist natively");
        base.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, base),
                "hard cast for 4B must succeed");
        assertEquals(countTappedSwamps(game), 5,
                "hard cast must pay exactly 4B from real Swamps");
        drainStack(game);

        assertTrue(inZone(p1, ZoneType.Battlefield, "Shriekmaw"),
                "hard-cast Shriekmaw must survive (no evoke sacrifice)");
        assertEquals(countBf(game, "Runeclaw Bear"), 0,
                "hard-cast ETB must destroy the Bear");
    }

    @Test(timeOut = 60000)
    public void testFearBlockLegalityMatrix() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card maw = bf("Shriekmaw", p1);
        Card bear = bf("Runeclaw Bear", p2);
        Card shade = bf("Nantuko Shade", p2);
        Card ornithopter = bf("Ornithopter", p2);

        assertFalse(CombatUtil.canBlock(maw, bear),
                "nonblack nonartifact Bear must not block Fear (CR 702.34)");
        assertTrue(CombatUtil.canBlock(maw, shade),
                "black Shade must block Fear");
        assertTrue(CombatUtil.canBlock(maw, ornithopter),
                "artifact Ornithopter must block Fear");
    }

    @Test(timeOut = 60000)
    public void testEvokeInsufficientManaFailsClosed() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        swamps(p1, 1);

        Card maw = hand("Shriekmaw", p1);
        SpellAbility evoke = findEvoke(maw);
        assertNotNull(evoke, "evoke SpellAbility must exist natively");
        evoke.setActivatingPlayer(p1);
        assertFalse(PlaySpellAbility.playSpellAbility(p1.getController(), p1, evoke),
                "evoke with only 1 mana available must fail closed");
        assertEquals(countTappedSwamps(game), 0,
                "failed evoke must leave mana sources untapped");
        assertTrue(inZone(p1, ZoneType.Hand, "Shriekmaw"),
                "unpaid Shriekmaw must remain in hand");
    }
}
