package forge.gamesimulationtests.ws236;

import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * WS236 F4 discriminator: You-filtered SpellCast-family (Veyran / Harmonic).
 *
 * <p>A/B discriminator. Seam A (this file): direct sim execution with the
 * target-free sorcery Divination, removing the Lightning Bolt target-selection
 * confound present in the WS234 sim probes. Seam B:
 * {@code forge.bridge.WS236F4BridgeTest} via authoritative constructed-game
 * execution (real priority flow).
 *
 * <p>Root cause (runtime-proven): the Forge Rules Core is sound (dispatch,
 * You-filter matching, Panharmonicon doubling, ordering machinery,
 * resolution, pump). The legacy sim drain loop (resolveStack +
 * checkStateEffects only) never invokes the engine's own
 * addAllTriggeredAbilitiesToStack(), so simultaneously-queued cast triggers
 * never reach the stack in seam A. This file's drain loop performs that
 * engine-owned ordering step, exactly as priority passing would.
 *
 * <p>Controls: Clever Lumimancer exercises the identical
 * SpellCastOrCopy/You magecraft trigger shape with an explicit
 * {@code Defined$ Self} pump, isolating card-script cause from family cause.
 * Harmonic Prowess exercises the engine-generated SpellCast/You keyword path.
 *
 * <p>No manual trigger injection: the Forge Rules Core generates every
 * trigger and outcome. Observations are printed for the evidence seal.
 */
public class Ws236F4SpellcastDiscriminatorTest extends SimulationTest {

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

    private void stockLibrary(Game game, Player p, int n) {
        for (int i = 0; i < n; i++) {
            Card lib = createCard("Runeclaw Bear", p);
            lib.setGameTimestamp(game.getNextTimestamp());
            p.getZone(ZoneType.Library).add(lib);
        }
    }

    private void islands(Player p, int n) {
        for (int i = 0; i < n; i++) {
            bf("Island", p);
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

    // WS236 harness correction (family-general, no production semantics
    // touched, no manual outcomes): the engine queues triggered abilities
    // into the simultaneous-entry list (CR 603.3b) and only the engine's own
    // addAllTriggeredAbilitiesToStack() orders them onto the stack (real flow
    // calls it from passPriority/checkStateBasedEffects). The legacy sim
    // drain loop (resolveStack + checkStateEffects only) never invokes it, so
    // cast triggers never resolve in this seam. This loop performs the
    // engine-owned ordering step before each resolution, exactly as priority
    // passing would.
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
        game.getStack().addAllTriggeredAbilitiesToStack();
    }

    private String observeStack(Game game) {
        StringBuilder sb = new StringBuilder();
        sb.append("stackSize=").append(game.getStack().size());
        if (!game.getStack().isEmpty()) {
            SpellAbility top = game.getStack().peek().getSpellAbility();
            sb.append(" topHost=").append(top.getHostCard().getName());
            sb.append(" topIsTrigger=").append(top.isTrigger());
            sb.append(" topActivator=").append(top.getActivatingPlayer());
        }
        return sb.toString();
    }

    private String observeCollection(Game game, Player caster, SpellAbility divSa) {
        Map<AbilityKey, Object> runParams = AbilityKey.newMap();
        runParams.put(AbilityKey.Activator, caster);
        runParams.put(AbilityKey.SpellAbility, divSa);
        StringBuilder sb = new StringBuilder();
        java.util.List<TriggerType> modes = java.util.Arrays.asList(
                TriggerType.SpellCastOrCopy, TriggerType.SpellCast);
        for (TriggerType mode : modes) {
            List<Trigger> matched =
                    game.getTriggerHandler().getActiveTrigger(mode, runParams);
            sb.append(mode).append(" matched=").append(matched.size()).append(" [");
            for (Trigger t : matched) {
                sb.append(t.getHostCard().getName()).append('#')
                        .append(t.getMode()).append(';');
            }
            sb.append("] ");
        }
        return sb.toString();
    }

    @Test(timeOut = 60000)
    public void testVeyranDoubledMagecraftDivination() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card veyran = bf("Veyran, Voice of Duality", p1);
        islands(p1, 3);
        stockLibrary(game, p1, 7);
        // Pre-cast collection prediction with the real Divination ability.
        Card div = hand("Divination", p1);
        SpellAbility divSa = div.getFirstSpellAbility();
        divSa.setActivatingPlayer(p1);
        System.out.println("WS236-OBSERVE veyran pre-cast collection: "
                + observeCollection(game, p1, divSa));
        System.out.println("WS236-OBSERVE veyran triggers on host: "
                + veyran.getTriggers().size() + " statics: "
                + veyran.getStaticAbilities().size());
        int beforeP = veyran.getNetPower();
        int beforeT = veyran.getNetToughness();
        // Return Divination to deck flow: cast via engine like any hand spell.
        // (The prediction copy above stays in hand, unused; the cast below is
        // a fresh engine-driven cast from hand.)
        castFromHand(game, p1, "Divination");
        System.out.println("WS236-OBSERVE veyran post-cast: "
                + observeStack(game) + " caster=" + p1);
        if (!game.getStack().isEmpty()) {
            SpellAbility actualSa = game.getStack().peek().getSpellAbility();
            System.out.println("WS236-OBSERVE veyran post-cast collection: "
                    + observeCollection(game, p1, actualSa));
        }
        drainStack(game);
        System.out.println("WS236-OBSERVE veyran post-drain power="
                + veyran.getNetPower() + " toughness=" + veyran.getNetToughness()
                + " (before " + beforeP + "/" + beforeT + ")");
        // Own Panharmonicon doubles own magecraft (Permanent.YouCtrl covers Self):
        // one Divination must yield +2/+2.
        assertEquals(veyran.getNetPower(), beforeP + 2,
                "Veyran must get +2 power from doubled magecraft");
        assertEquals(veyran.getNetToughness(), beforeT + 2,
                "Veyran must get +2 toughness from doubled magecraft");
    }

    @Test(timeOut = 60000)
    public void testHarmonicProwessDivination() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card harm = bf("Harmonic Prodigy", p1);
        islands(p1, 3);
        stockLibrary(game, p1, 7);
        System.out.println("WS236-OBSERVE harmonic triggers on host: "
                + harm.getTriggers().size());
        int beforeP = harm.getNetPower();
        int beforeT = harm.getNetToughness();
        castFromHand(game, p1, "Divination");
        System.out.println("WS236-OBSERVE harmonic post-cast: "
                + observeStack(game) + " caster=" + p1);
        drainStack(game);
        System.out.println("WS236-OBSERVE harmonic post-drain power="
                + harm.getNetPower() + " toughness=" + harm.getNetToughness()
                + " (before " + beforeP + "/" + beforeT + ")");
        // Prowess is own-trigger, not "another Wizard": exactly +1/+1.
        assertEquals(harm.getNetPower(), beforeP + 1,
                "Harmonic must get Prowess +1 power");
        assertEquals(harm.getNetToughness(), beforeT + 1,
                "Harmonic must get Prowess +1 toughness");
    }

    @Test(timeOut = 60000)
    public void testLumimancerMagecraftControl() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card lumi = bf("Clever Lumimancer", p1);
        islands(p1, 3);
        stockLibrary(game, p1, 7);
        int beforeP = lumi.getNetPower();
        int beforeT = lumi.getNetToughness();
        castFromHand(game, p1, "Divination");
        System.out.println("WS236-OBSERVE lumimancer post-cast: "
                + observeStack(game) + " caster=" + p1);
        drainStack(game);
        System.out.println("WS236-OBSERVE lumimancer post-drain power="
                + lumi.getNetPower() + " toughness=" + lumi.getNetToughness()
                + " (before " + beforeP + "/" + beforeT + ")");
        assertEquals(lumi.getNetPower(), beforeP + 2,
                "Lumimancer control must get +2 power from magecraft");
        assertEquals(lumi.getNetToughness(), beforeT + 2,
                "Lumimancer control must get +2 toughness from magecraft");
    }

    @Test(timeOut = 60000)
    public void testVeyranDoublesOtherMagecraft() {
        // Veyran's Panharmonicon static doubling ANOTHER permanent's
        // You-filtered SpellCastOrCopy trigger (Clever Lumimancer magecraft).
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card veyran = bf("Veyran, Voice of Duality", p1);
        Card lumi = bf("Clever Lumimancer", p1);
        islands(p1, 3);
        stockLibrary(game, p1, 7);
        int veyranBeforeP = veyran.getNetPower();
        int lumiBeforeP = lumi.getNetPower();
        int lumiBeforeT = lumi.getNetToughness();
        castFromHand(game, p1, "Divination");
        drainStack(game);
        System.out.println("WS236-OBSERVE veyran-other post-drain veyran="
                + veyran.getNetPower() + " lumi=" + lumi.getNetPower()
                + "/" + lumi.getNetToughness());
        // Lumimancer magecraft doubled by Veyran: 2 x +2/+2.
        assertEquals(lumi.getNetPower(), lumiBeforeP + 4,
                "Lumimancer must get +4 power via Veyran doubling");
        assertEquals(lumi.getNetToughness(), lumiBeforeT + 4,
                "Lumimancer must get +4 toughness via Veyran doubling");
        // Veyran's own magecraft still doubled by itself.
        assertEquals(veyran.getNetPower(), veyranBeforeP + 2,
                "Veyran must still get +2 power from own doubled magecraft");
    }

    @Test(timeOut = 60000)
    public void testHarmonicDoublesOtherWizard() {
        // Harmonic's Panharmonicon static doubling ANOTHER Wizard's
        // You-filtered SpellCastOrCopy trigger (Clever Lumimancer magecraft).
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        Card harm = bf("Harmonic Prodigy", p1);
        Card lumi = bf("Clever Lumimancer", p1);
        islands(p1, 3);
        stockLibrary(game, p1, 7);
        int harmBeforeP = harm.getNetPower();
        int harmBeforeT = harm.getNetToughness();
        int lumiBeforeP = lumi.getNetPower();
        int lumiBeforeT = lumi.getNetToughness();
        castFromHand(game, p1, "Divination");
        drainStack(game);
        System.out.println("WS236-OBSERVE harmonic-other post-drain harmonic="
                + harm.getNetPower() + "/" + harm.getNetToughness()
                + " lumi=" + lumi.getNetPower() + "/" + lumi.getNetToughness());
        // Lumimancer magecraft doubled by Harmonic: 2 x +2/+2.
        assertEquals(lumi.getNetPower(), lumiBeforeP + 4,
                "Lumimancer must get +4 power via Harmonic doubling");
        assertEquals(lumi.getNetToughness(), lumiBeforeT + 4,
                "Lumimancer must get +4 toughness via Harmonic doubling");
        // Harmonic's own Prowess is not "another Wizard": exactly +1/+1.
        assertEquals(harm.getNetPower(), harmBeforeP + 1,
                "Harmonic must still get exactly +1 power from own Prowess");
        assertEquals(harm.getNetToughness(), harmBeforeT + 1,
                "Harmonic must still get exactly +1 toughness from own Prowess");
    }
}
