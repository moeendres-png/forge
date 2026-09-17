package forge.gamesimulationtests.wsr8;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * R8 OVERLOAD family: Vandalblast (CARD_14) overload branch closure.
 *
 * <p>WS234 proved the base spell (destroy one targeted artifact) and left the
 * Overload {4}{R} branch CODE_DERIVED presence-only. These five strict
 * actual-card tests prove the native overload path through real engine
 * gameplay under CR 702.96 (2026-04-17): alternative-cost selection,
 * no-target legality distinction (702.96b), and each-artifact resolution
 * against mixed controls. No bridge-side target legality, no manual outcome
 * injection: costs are paid from real Mountains, targets (base only) are
 * chosen by the engine AI, and resolution is the engine's own Destroy effect
 * reading the overload Defined each-set built by CardFactoryUtil.
 */
public class WsR8OverloadFamilyTest extends SimulationTest {

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

    private int countBf(Game game, String name) {
        int n = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(c.getName())) {
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

    private SpellAbility findOverload(Card blast) {
        for (SpellAbility sa : blast.getSpellAbilities()) {
            if (sa.isAlternativeCost(AlternativeCost.Overload)) {
                return sa;
            }
        }
        return null;
    }

    private int countTappedMountains(Game game) {
        int n = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if ("Mountain".equals(c.getName()) && c.isTapped()) {
                n++;
            }
        }
        return n;
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
        assertFalse(game.isGameOver(), "game must not be over");
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().hasSimultaneousStackEntries(),
                "no stranded simultaneous entries may remain after drain");
        assertTrue(game.getStack().isEmpty(), "stack must be empty after drain");
    }

    @Test(timeOut = 60000)
    public void testOverloadAbilityEnumeratedNatively() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card blast = hand("Vandalblast", p1);

        assertEquals(blast.getSpellAbilities().size(), 2,
                "Vandalblast must offer exactly base plus overload SpellAbility");
        SpellAbility base = blast.getFirstSpellAbility();
        SpellAbility overload = findOverload(blast);
        assertNotNull(overload, "overload SpellAbility must exist natively");
        assertNotSame(overload, base, "overload must be a distinct ability");

        // Base: ordinary targeted spell (single target when overload unpaid).
        assertTrue(base.isBasicSpell(), "base must be the basic spell");
        assertTrue(base.usesTargeting(), "base must use targeting");
        assertNotNull(base.getTargetRestrictions(), "base must carry target restrictions");

        // Overload: alternative cost, no targets (CR 702.96b), each-set Defined.
        assertFalse(overload.isBasicSpell(), "overload variant must be non-basic");
        assertEquals(overload.getParam("PrecostDesc"), "Overload",
                "overload must carry the Overload precost identity");
        assertEquals(overload.getPayCosts().getTotalMana().toString(), "{4}{R}",
                "overload mana must be exactly 4R");
        assertFalse(overload.usesTargeting(), "overloaded spell must require no targets");
        assertNull(overload.getTargetRestrictions(),
                "overloaded spell must carry no target restrictions");
        assertNotNull(overload.getParam("Defined"), "overload must define the each-set");
        assertTrue(overload.getParam("Defined").contains("Artifact"),
                "overload each-set must cover artifacts");
        assertTrue(overload.getParam("StackDescription").contains("each"),
                "overload stack text must use each (target->each change)");
    }

    @Test(timeOut = 60000)
    public void testBaseDestroysExactlyOneTarget() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        bf("Memnite", p2);
        bf("Ornithopter", p2);
        bf("Memnite", p1);
        mountains(p1, 2);
        int p1LifeBefore = p1.getLife();
        int p2LifeBefore = p2.getLife();

        Card blast = hand("Vandalblast", p1);
        SpellAbility base = blast.getFirstSpellAbility();
        base.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, base),
                "base Vandalblast must cast");
        assertEquals(countTappedMountains(game), 1,
                "base cast must pay exactly R from real Mountains");
        drainStack(game);

        assertEquals(countBf(game, "Memnite") + countBf(game, "Ornithopter"), 2,
                "base must destroy exactly one of the three artifacts");
        assertTrue(inZone(p1, ZoneType.Battlefield, "Memnite"),
                "caster's own artifact must survive the base spell");
        assertTrue(inZone(p1, ZoneType.Graveyard, "Vandalblast"),
                "base Vandalblast must resolve natively to graveyard");
        assertEquals(p1.getLife(), p1LifeBefore, "no life must change");
        assertEquals(p2.getLife(), p2LifeBefore, "no life must change");
    }

    @Test(timeOut = 60000)
    public void testOverloadDestroysEachOpponentArtifact() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        bf("Memnite", p2);
        bf("Ornithopter", p2);
        bf("Memnite", p1);
        mountains(p1, 6);
        int p1LifeBefore = p1.getLife();
        int p2LifeBefore = p2.getLife();

        Card blast = hand("Vandalblast", p1);
        SpellAbility overload = findOverload(blast);
        assertNotNull(overload, "overload SpellAbility must exist natively");
        overload.setActivatingPlayer(p1);
        assertTrue(PlaySpellAbility.playSpellAbility(p1.getController(), p1, overload),
                "overloaded Vandalblast must cast for 4R");
        assertEquals(countTappedMountains(game), 5,
                "overload cast must pay exactly 4R from real Mountains");
        drainStack(game);

        assertEquals(countBf(game, "Memnite"), 1,
                "overload must destroy each artifact the caster does not control");
        assertEquals(countBf(game, "Ornithopter"), 0,
                "overload must destroy each artifact the caster does not control");
        assertTrue(inZone(p1, ZoneType.Battlefield, "Memnite"),
                "caster's own artifact must survive overload (each you do not control)");
        assertTrue(inZone(p1, ZoneType.Graveyard, "Vandalblast"),
                "overloaded Vandalblast must resolve natively to graveyard");
        assertEquals(p1.getLife(), p1LifeBefore, "no life must change");
        assertEquals(p2.getLife(), p2LifeBefore, "no life must change");
    }

    @Test(timeOut = 60000)
    public void testOverloadInsufficientManaFailsClosed() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        bf("Memnite", p2);
        bf("Ornithopter", p2);
        mountains(p1, 2);

        Card blast = hand("Vandalblast", p1);
        SpellAbility overload = findOverload(blast);
        assertNotNull(overload, "overload SpellAbility must exist natively");
        overload.setActivatingPlayer(p1);
        assertFalse(PlaySpellAbility.playSpellAbility(p1.getController(), p1, overload),
                "overload with only 2 mana available must fail closed");
        assertEquals(countTappedMountains(game), 0,
                "failed overload cast must leave all mana sources untapped");
        drainStack(game);

        assertEquals(countBf(game, "Memnite"), 1, "no artifact may be destroyed");
        assertEquals(countBf(game, "Ornithopter"), 1, "no artifact may be destroyed");
        assertTrue(inZone(p1, ZoneType.Hand, "Vandalblast"),
                "unpaid Vandalblast must remain in hand after failed cast");
    }

    @Test(timeOut = 60000)
    public void testBaseRequiresLegalTarget() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        mountains(p1, 2);

        // No artifacts on any battlefield: the targeted base spell cannot be
        // cast, while the overload variant structurally requires no targets.
        Card blast = hand("Vandalblast", p1);
        SpellAbility base = blast.getFirstSpellAbility();
        base.setActivatingPlayer(p1);
        assertFalse(PlaySpellAbility.playSpellAbility(p1.getController(), p1, base),
                "base Vandalblast with no legal target must fail closed");
        drainStack(game);

        SpellAbility overload = findOverload(blast);
        assertNotNull(overload, "overload SpellAbility must exist natively");
        assertFalse(overload.usesTargeting(),
                "overload must require no targets even with an empty board (CR 702.96b)");
        assertTrue(inZone(p1, ZoneType.Hand, "Vandalblast"),
                "uncast Vandalblast must remain in hand");
        assertTrue(game.getStack().isEmpty(), "stack must stay empty");
    }
}
