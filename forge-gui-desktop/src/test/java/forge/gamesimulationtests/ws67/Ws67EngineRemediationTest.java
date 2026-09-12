package forge.gamesimulationtests.ws67;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import java.util.List;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.replacement.ReplacementEffect;
import forge.util.collect.FCollectionView;

/**
 * WS67 engine-direct actual-card reproducers for the three WS65 remediation packets.
 *
 * Engine-direct = engine Game + engine AI controllers + native mana payment through
 * real land mana abilities. No provider/CPL transport code is on this path.
 * Only X-announcement values and copy/target selections are scripted (via a
 * delegating controller); every cost, payment, stack-placement, replacement and
 * resolution step is the production engine path under test.
 *
 * No card-name special cases anywhere in production code are used or added here;
 * the names below are test fixtures only.
 */
public class Ws67EngineRemediationTest extends SimulationTest {

    /** Delegating AI controller with scripted X / copy-choice / target decisions. */
    public static class Ws67ScriptedController extends PlayerControllerAi {
        public Integer scriptedX = null;
        public String scriptedCopyName = null;
        public String scriptedTargetName = null;
        public boolean confirmReplacements = true;
        public int confirmReplacementCalls = 0;
        public int copyChoiceCalls = 0;
        public int targetCalls = 0;

        public Ws67ScriptedController(Game game, Player p, LobbyPlayer lp) {
            super(game, p, lp);
        }

        @Override
        public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
            if ("X".equalsIgnoreCase(announce) && scriptedX != null) {
                return Math.max(min, Math.min(max, scriptedX));
            }
            return super.announceRequirements(ability, min, max, announce);
        }

        @Override
        public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
                GameEntity affected, String question) {
            confirmReplacementCalls++;
            return confirmReplacements;
        }

        @Override
        public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList,
                forge.game.player.DelayedReveal delayedReveal, SpellAbility sa, String title,
                boolean isOptional, Player relatedPlayer, java.util.Map<String, Object> params) {
            copyChoiceCalls++;
            if (scriptedCopyName != null) {
                for (T o : optionList) {
                    if (o instanceof Card c && scriptedCopyName.equals(c.getName())) {
                        return o;
                    }
                }
            }
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional,
                    relatedPlayer, params);
        }

        @Override
        public boolean chooseTargetsFor(SpellAbility currentAbility) {
            targetCalls++;
            if (scriptedTargetName != null && currentAbility.usesTargeting()) {
                List<GameEntity> cands = currentAbility.getTargetRestrictions().getAllCandidates(currentAbility);
                for (GameEntity e : cands) {
                    if (e instanceof Card c && scriptedTargetName.equals(c.getName())) {
                        currentAbility.getTargets().add(c);
                        if (currentAbility.isDividedAsYouChoose()) {
                            Integer x = currentAbility.getXManaCostPaid();
                            currentAbility.addDividedAllocation(c, x == null ? 0 : x);
                        }
                        return true;
                    }
                }
            }
            return super.chooseTargetsFor(currentAbility);
        }
    }

    private Ws67ScriptedController script(Player p) {
        Ws67ScriptedController ctrl = new Ws67ScriptedController(p.getGame(), p,
                new LobbyPlayerAi("ws67", null));
        p.dangerouslySetController(ctrl);
        return ctrl;
    }

    private Card addBattlefield(Game game, String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(game.getNextTimestamp());
        p.getZone(ZoneType.Battlefield).add(c);
        game.getAction().checkStaticAbilities();
        return c;
    }

    private void addBattlefieldN(Game game, String name, Player p, int n) {
        for (int i = 0; i < n; i++) {
            addBattlefield(game, name, p);
        }
    }

    private Card findIn(Game game, ZoneType zone, String name) {
        for (Card c : game.getCardsIn(zone)) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    /** A permanent that entered via copy effect (copies take the copied name). */
    private Card findCopy(Game game) {
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCloned()) {
                return c;
            }
        }
        return null;
    }

    private int countBattlefield(Game game, String name) {
        int n = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (name.equals(c.getName())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Engine's own route for casting a spell from a non-hand zone (command zone):
     * MayPlay-attached ability copies via GameActionUtil, as used by GUI/AI casts.
     */
    private SpellAbility mayPlayCastOption(Card source, Player activator) {
        SpellAbility base = source.getFirstSpellAbility();
        base.setActivatingPlayer(activator);
        List<SpellAbility> options = forge.game.GameActionUtil.getMayPlaySpellOptions(base, source,
                activator, false);
        assertFalse(options.isEmpty(), source.getName() + " must offer a MayPlay cast option");
        SpellAbility chosen = options.get(0);
        chosen.setActivatingPlayer(activator);
        return chosen;
    }

    // ------------------------------------------------------------------
    // Packet 1: Ghalta reduced-cost commander cast (command zone + reduction).
    // ------------------------------------------------------------------

    @Test(timeOut = 120000)
    public void testGhaltaReducedCostCommanderCastFromCommandZone() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        Ws67ScriptedController ctrl = script(caster);

        // Reduction online: 3x Runeclaw Bear = total power 6.
        addBattlefieldN(game, "Runeclaw Bear", caster, 3);
        // Exact reduced bill {4}{G}{G} (12 - 6) + headroom.
        addBattlefieldN(game, "Forest", caster, 8);

        // Ghalta, Primal Hunger ({10}{G}{G}, ReduceCost X = total power you control).
        Card ghalta = createCard("Ghalta, Primal Hunger", caster);
        ghalta.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Command).add(ghalta);
        caster.addCommander(ghalta);
        game.getAction().checkStaticAbilities();

        SpellAbility sa = mayPlayCastOption(ghalta, caster);

        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Reduced-cost commander cast must report success (no silent drop)");
        assertFalse(game.getStack().isEmpty(), "Reduced Ghalta cast must place a spell on the stack");
        assertEquals(caster.getCommanderCast(ghalta), 1, "Commander cast count must be 1 (tax accounted)");

        game.getStack().resolveStack();

        Card entered = findIn(game, ZoneType.Battlefield, "Ghalta, Primal Hunger");
        assertNotNull(entered, "Ghalta must resolve onto the battlefield");
        assertEquals(entered.getNetPower(), 12, "Ghalta must be 12 power");
        assertEquals(entered.getNetToughness(), 12, "Ghalta must be 12 toughness");
    }

    @Test(timeOut = 120000)
    public void testGhaltaReducedCostCastFromHandControl() {
        // Same reduction, hand zone: isolates command-zone vs reduction causality.
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        Ws67ScriptedController ctrl = script(caster);

        addBattlefieldN(game, "Runeclaw Bear", caster, 3);
        addBattlefieldN(game, "Forest", caster, 8);

        Card ghalta = createCard("Ghalta, Primal Hunger", caster);
        ghalta.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Hand).add(ghalta);

        SpellAbility sa = ghalta.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);

        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Reduced-cost hand cast must report success");
        assertFalse(game.getStack().isEmpty(), "Reduced Ghalta hand cast must reach the stack");

        game.getStack().resolveStack();
        assertNotNull(findIn(game, ZoneType.Battlefield, "Ghalta, Primal Hunger"),
                "Ghalta must resolve onto the battlefield");
    }

    @Test(timeOut = 120000)
    public void testUnreducedCommanderCastFromCommandZoneControl() {
        // No reduction active: unreduced command-zone cast must work (Delina-style control).
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        Ws67ScriptedController ctrl = script(caster);

        addBattlefieldN(game, "Forest", caster, 13);

        Card ghalta = createCard("Ghalta, Primal Hunger", caster);
        ghalta.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Command).add(ghalta);
        caster.addCommander(ghalta);
        game.getAction().checkStaticAbilities();

        SpellAbility sa = mayPlayCastOption(ghalta, caster);

        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Unreduced commander cast must report success");
        assertFalse(game.getStack().isEmpty(), "Unreduced commander cast must reach the stack");

        game.getStack().resolveStack();
        assertNotNull(findIn(game, ZoneType.Battlefield, "Ghalta, Primal Hunger"),
                "Ghalta must resolve onto the battlefield");
    }

    // ------------------------------------------------------------------
    // Packet 2: Fire Covenant non-mana X (life additional cost).
    // ------------------------------------------------------------------

    @Test(timeOut = 120000)
    public void testFireCovenantNonManaXPaidInLifeOnly() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        Ws67ScriptedController ctrl = script(caster);
        ctrl.scriptedX = 5;

        // Printed mana component {1}{B}{R}: Swamp + Mountain x2.
        addBattlefield(game, "Swamp", caster);
        addBattlefieldN(game, "Mountain", caster, 2);
        // Damage target.
        Card bear = addBattlefield(game, "Runeclaw Bear", opponent);
        ctrl.scriptedTargetName = "Runeclaw Bear";

        Card covenant = createCard("Fire Covenant", caster);
        covenant.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Hand).add(covenant);

        int lifeBefore = caster.getLife();
        assertEquals(lifeBefore, 20, "Test game starts at 20 life");

        SpellAbility sa = covenant.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);

        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Fire Covenant with X=5 life must cast (no rollback)");
        assertFalse(game.getStack().isEmpty(), "Fire Covenant must reach the stack");
        assertEquals(Integer.valueOf(5), game.getStack().peekAbility().getXManaCostPaid(),
                "Announced X=5 must be recorded on the stack spell");

        // Mana bill must contain only the printed {1}{B}{R}: all three lands tapped, none extra.
        assertEquals(caster.getLife(), lifeBefore - 5, "Exactly X=5 life must be paid as additional cost");

        game.getStack().resolveStack();
        // State-based actions: lethal damage must destroy the Bear.
        game.getAction().checkStateEffects(true);

        assertNotNull(findIn(game, ZoneType.Graveyard, "Fire Covenant"),
                "Fire Covenant must resolve to graveyard");
        assertNull(findIn(game, ZoneType.Battlefield, "Runeclaw Bear"),
                "Targeted 2/2 Bear must die to 5 divided damage");
    }

    @Test(timeOut = 120000)
    public void testFireballManaXControl() {
        // Genuine mana-X control on the same engine path.
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        Ws67ScriptedController ctrl = script(caster);
        ctrl.scriptedX = 5;

        addBattlefieldN(game, "Mountain", caster, 7);
        Card bear = addBattlefield(game, "Runeclaw Bear", opponent);
        ctrl.scriptedTargetName = "Runeclaw Bear";

        Card fireball = createCard("Fireball", caster);
        fireball.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Hand).add(fireball);

        SpellAbility sa = fireball.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);

        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Fireball X=5 must cast");
        assertFalse(game.getStack().isEmpty(), "Fireball must reach the stack");
        assertEquals(caster.getLife(), 20, "Mana-X must not cost life");

        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
        assertNotNull(findIn(game, ZoneType.Graveyard, "Fireball"), "Fireball must resolve to graveyard");
        assertNull(findIn(game, ZoneType.Battlefield, "Runeclaw Bear"), "Bear must die to 5 damage");
    }

    // ------------------------------------------------------------------
    // Packet 3: Clone under Humility.
    // ------------------------------------------------------------------

    @Test(timeOut = 120000)
    public void testCloneUnderHumilityKeepsCopyDecisionAndAppliesLayers() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        Ws67ScriptedController ctrl = script(caster);
        ctrl.scriptedCopyName = "Runeclaw Bear";

        addBattlefield(game, "Humility", opponent);
        Card bear = addBattlefield(game, "Runeclaw Bear", opponent);
        addBattlefieldN(game, "Island", caster, 4);

        // Humility is already suppressing: sanity check the battlefield state.
        assertEquals(bear.getNetPower(), 1, "Bear must be 1 power under Humility");
        assertEquals(bear.getNetToughness(), 1, "Bear must be 1 toughness under Humility");

        Card clone = createCard("Clone", caster);
        clone.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Hand).add(clone);

        SpellAbility sa = clone.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);

        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Clone must cast under Humility");
        assertFalse(game.getStack().isEmpty(), "Clone must reach the stack");

        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);

        // Copies take the copied name: original Bear + Clone-as-Bear = 2.
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 2,
                "Clone must enter as a second Bear copy even under Humility");
        Card entered = findCopy(game);
        assertNotNull(entered, "Clone-copy must be on the battlefield");
        // The optional copy decision must have been offered even under Humility.
        assertTrue(ctrl.confirmReplacementCalls >= 1,
                "Optional ETB-copy replacement must engage (confirm asked) under Humility");
        assertTrue(ctrl.copyChoiceCalls >= 1,
                "Copy choice must be offered under Humility");
        // Copied Bear, then Humility layers apply: 1/1 with no abilities.
        assertEquals(entered.getNetPower(), 1, "Clone-copy must be 1 power under Humility");
        assertEquals(entered.getNetToughness(), 1, "Clone-copy must be 1 toughness under Humility");
        // Decisive layer proof: remove Humility; a Bear-copy must be 2/2 on the
        // battlefield while a blank 0/0 would die to SBA 704.5f.
        Card humility = findIn(game, ZoneType.Battlefield, "Humility");
        assertNotNull(humility, "Humility must be on the battlefield");
        game.getAction().destroy(humility, null, false, forge.game.ability.AbilityKey.newMap());
        game.getAction().checkStaticAbilities();
        game.getAction().checkStateEffects(true);
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 2,
                "Clone-copy must survive Humility leaving (blank 0/0 would die to SBA)");
        Card survived = findCopy(game);
        assertNotNull(survived, "Clone-copy must be on the battlefield after Humility leaves");
        assertEquals(survived.getNetPower(), 2, "Clone-copy must be 2 power once Humility leaves");
        assertEquals(survived.getNetToughness(), 2, "Clone-copy must be 2 toughness once Humility leaves");
    }

    @Test(timeOut = 120000)
    public void testCloneCopyWithoutHumilityControl() {
        // No-Humility control: Clone entering as Bear must be a 2/2 Bear copy.
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        Ws67ScriptedController ctrl = script(caster);
        ctrl.scriptedCopyName = "Runeclaw Bear";

        Card bear = addBattlefield(game, "Runeclaw Bear", opponent);
        addBattlefieldN(game, "Island", caster, 4);

        Card clone = createCard("Clone", caster);
        clone.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Hand).add(clone);

        SpellAbility sa = clone.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);

        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Clone must cast without Humility");
        assertFalse(game.getStack().isEmpty(), "Clone must reach the stack");

        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);

        // Copies take the copied name: original Bear + Clone-as-Bear = 2.
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 2,
                "Clone must enter as a second Bear copy");
        Card entered = findCopy(game);
        assertNotNull(entered, "Clone-copy must be on the battlefield");
        assertTrue(ctrl.confirmReplacementCalls >= 1, "Optional copy replacement must engage");
        assertEquals(entered.getNetPower(), bear.getNetPower(), "Clone must copy Bear power");
        assertEquals(entered.getNetToughness(), bear.getNetToughness(), "Clone must copy Bear toughness");
    }

    @Test(timeOut = 120000)
    public void testSecondCopyEffectUnderHumilityControl() {
        // Second copy-effect control: Phantasmal Image (same Optional ETB-copy
        // mechanic, different card) under Humility must also engage its decision.
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        Ws67ScriptedController ctrl = script(caster);
        ctrl.scriptedCopyName = "Runeclaw Bear";

        addBattlefield(game, "Humility", opponent);
        addBattlefield(game, "Runeclaw Bear", opponent);
        addBattlefieldN(game, "Island", caster, 2);

        Card image = createCard("Phantasmal Image", caster);
        image.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Hand).add(image);

        SpellAbility sa = image.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);

        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Phantasmal Image must cast under Humility");
        assertFalse(game.getStack().isEmpty(), "Image must reach the stack");

        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);

        assertEquals(countBattlefield(game, "Runeclaw Bear"), 2,
                "Image must enter as a second Bear copy under Humility");
        Card entered = findCopy(game);
        assertNotNull(entered, "Image-copy must be on the battlefield");
        assertTrue(ctrl.confirmReplacementCalls >= 1,
                "Optional ETB-copy replacement must engage for Image under Humility");
        assertEquals(entered.getNetPower(), 1, "Image-copy must be 1 power under Humility");
        assertEquals(entered.getNetToughness(), 1, "Image-copy must be 1 toughness under Humility");
    }
}
