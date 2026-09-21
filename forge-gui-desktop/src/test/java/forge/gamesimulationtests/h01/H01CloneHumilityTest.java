package forge.gamesimulationtests.h01;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import java.util.List;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

/**
 * H01 corrected Clone/Humility contrast family (CR 614.12).
 *
 * Binding oracle: existing continuous effects that would apply to an entering
 * permanent are considered when determining applicable ETB replacement effects.
 * When Humility is already on the battlefield before Clone enters, Clone has no
 * applicable self copy-replacement ability in the would-be battlefield state:
 * no copy choice is offered, Clone enters as Clone (0/0 base, 1/1 under
 * Humility), and after Humility leaves it is 0/0 and dies to SBA 704.5f.
 *
 * Contrast: Clone-first (copy established before Humility) stays a Bear copy
 * (2/2 after Humility leaves); no-Humility control copies normally.
 *
 * Engine-direct actual cards, native mana payment; only the copy selection is
 * scripted. No production card-name logic; names below are test fixtures only.
 * Terminal 1/1/no-abilities alone is NOT used as the oracle (non-discriminating).
 */
public class H01CloneHumilityTest extends SimulationTest {

    /** Delegating AI controller with scripted copy-choice decisions. */
    public static class H01ScriptedController extends PlayerControllerAi {
        public String scriptedCopyName = null;
        public boolean confirmReplacements = true;
        public int confirmReplacementCalls = 0;
        public int copyChoiceCalls = 0;

        public H01ScriptedController(Game game, Player p, LobbyPlayer lp) {
            super(game, p, lp);
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
    }

    private H01ScriptedController script(Player p, String tag) {
        H01ScriptedController ctrl = new H01ScriptedController(p.getGame(), p,
                new LobbyPlayerAi(tag, null));
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

    private Card findIn(Game game, ZoneType zone, String name) {
        for (Card c : game.getCardsIn(zone)) {
            if (name.equals(c.getName())) {
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

    /** A permanent that entered via copy effect (copies take the copied name). */
    private Card findCopy(Game game) {
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCloned()) {
                return c;
            }
        }
        return null;
    }

    private Card castCloneFromHand(Game game, Player caster, H01ScriptedController ctrl) {
        Card clone = createCard("Clone", caster);
        clone.setGameTimestamp(game.getNextTimestamp());
        caster.getZone(ZoneType.Hand).add(clone);
        SpellAbility sa = clone.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        boolean ok = forge.game.player.PlaySpellAbility.playSpellAbility(ctrl, caster, sa);
        assertTrue(ok, "Clone must cast");
        assertFalse(game.getStack().isEmpty(), "Clone must reach the stack");
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
        return clone;
    }

    private void destroyHumility(Game game) {
        Card humility = findIn(game, ZoneType.Battlefield, "Humility");
        assertNotNull(humility, "Humility must be on the battlefield");
        game.getAction().destroy(humility, null, false, AbilityKey.newMap());
        game.getAction().checkStaticAbilities();
        game.getAction().checkStateEffects(true);
    }

    @Test(timeOut = 120000)
    public void testHumilityFirstNoCopyThenDiesAfterHumilityLeaves() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        H01ScriptedController ctrl = script(caster, "h01A");
        ctrl.scriptedCopyName = "Runeclaw Bear";

        addBattlefield(game, "Humility", opponent);
        Card bear = addBattlefield(game, "Runeclaw Bear", opponent);
        for (int i = 0; i < 4; i++) {
            addBattlefield(game, "Island", caster);
        }

        assertEquals(bear.getNetPower(), 1, "Bear must be 1 power under Humility");
        assertEquals(bear.getNetToughness(), 1, "Bear must be 1 toughness under Humility");

        castCloneFromHand(game, caster, ctrl);

        // CR 614.12: no applicable self copy-replacement ability exists in the
        // would-be battlefield state, so no copy choice may be offered.
        assertEquals(ctrl.copyChoiceCalls, 0, "No Clone copy-choice callback may occur when Humility is already on the battlefield");
        assertEquals(ctrl.confirmReplacementCalls, 0, "No optional ETB-copy replacement may engage when Humility is already on the battlefield");

        // Clone enters as Clone, not as a Runeclaw Bear copy.
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 1,
                "Exactly one Bear (the original) must be on the battlefield; Clone must not be a Bear copy");
        assertNull(findCopy(game), "No cloned permanent may exist when Humility pre-exists");
        Card entered = findIn(game, ZoneType.Battlefield, "Clone");
        assertNotNull(entered, "Clone must enter as itself (Clone) when Humility pre-exists");
        assertFalse(entered.isCloned(), "Clone must not be flagged cloned when Humility pre-exists");
        // Under Humility it is 1/1 (layer 7b overwrites the 0/0 base).
        assertEquals(entered.getNetPower(), 1, "Clone-as-itself must be 1 power under Humility");
        assertEquals(entered.getNetToughness(), 1, "Clone-as-itself must be 1 toughness under Humility");

        // Remove Humility: Clone is 0/0 and dies to SBA 704.5f.
        destroyHumility(game);
        assertNull(findIn(game, ZoneType.Battlefield, "Clone"),
                "Clone-as-itself must leave the battlefield once Humility leaves (0/0 SBA)");
        Card grave = findIn(game, ZoneType.Graveyard, "Clone");
        assertNotNull(grave, "Clone-as-itself must go to graveyard as 0/0 via SBA 704.5f");
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 1,
                "Original Bear must remain; no Bear copy may appear");
        List<Card> graveClones = new java.util.ArrayList<>(game.getCardsIn(ZoneType.Graveyard));
        boolean found = false;
        for (Card c : graveClones) {
            if ("Clone".equals(c.getName())) {
                found = true;
                assertEquals(c.getNetPower(), 0, "Dead Clone must be 0 power (0/0 base)");
                assertEquals(c.getNetToughness(), 0, "Dead Clone must be 0 toughness (0/0 base)");
            }
        }
        assertTrue(found, "Clone must be in graveyard");
    }

    @Test(timeOut = 120000)
    public void testCloneFirstCopyPersistsThroughHumility() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        H01ScriptedController ctrl = script(caster, "h01B");
        ctrl.scriptedCopyName = "Runeclaw Bear";

        Card bear = addBattlefield(game, "Runeclaw Bear", opponent);
        for (int i = 0; i < 4; i++) {
            addBattlefield(game, "Island", caster);
        }

        castCloneFromHand(game, caster, ctrl);

        // Copy choice occurs with no Humility present.
        assertTrue(ctrl.confirmReplacementCalls >= 1, "Optional copy replacement must engage with no Humility");
        assertTrue(ctrl.copyChoiceCalls >= 1, "Copy choice must be offered with no Humility");
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 2,
                "Clone must enter as a second Bear copy");
        Card entered = findCopy(game);
        assertNotNull(entered, "Clone-copy must be on the battlefield");
        assertEquals(entered.getNetPower(), 2, "Clone-copy must be 2 power");
        assertEquals(entered.getNetToughness(), 2, "Clone-copy must be 2 toughness");
        assertEquals(bear.getNetPower(), 2, "Sanity: Bear is 2 power with no Humility");

        // Then put Humility on the battlefield: the established copy effect
        // remains; Humility layers make it 1/1 with no abilities.
        addBattlefield(game, "Humility", opponent);
        game.getAction().checkStateEffects(true);
        Card copied = findCopy(game);
        assertNotNull(copied, "Established Bear copy must remain on the battlefield under Humility");
        assertEquals(copied.getNetPower(), 1, "Copied creature must be 1 power under Humility");
        assertEquals(copied.getNetToughness(), 1, "Copied creature must be 1 toughness under Humility");
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 2,
                "Copy identity persists under Humility (still two Bears by name)");

        // Remove Humility: it remains a Runeclaw Bear copy and is 2/2.
        destroyHumility(game);
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 2,
                "Bear copy must survive Humility leaving");
        Card survived = findCopy(game);
        assertNotNull(survived, "Bear copy must remain after Humility leaves");
        assertEquals(survived.getNetPower(), 2, "Bear copy must be 2 power once Humility leaves");
        assertEquals(survived.getNetToughness(), 2, "Bear copy must be 2 toughness once Humility leaves");
    }

    @Test(timeOut = 120000)
    public void testNoHumilityControl() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster);
        H01ScriptedController ctrl = script(caster, "h01C");
        ctrl.scriptedCopyName = "Runeclaw Bear";

        Card bear = addBattlefield(game, "Runeclaw Bear", opponent);
        for (int i = 0; i < 4; i++) {
            addBattlefield(game, "Island", caster);
        }

        castCloneFromHand(game, caster, ctrl);

        assertTrue(ctrl.confirmReplacementCalls >= 1, "Optional copy replacement must engage");
        assertTrue(ctrl.copyChoiceCalls >= 1, "Copy choice must be offered");
        assertEquals(countBattlefield(game, "Runeclaw Bear"), 2,
                "Clone must enter as a second Bear copy");
        Card entered = findCopy(game);
        assertNotNull(entered, "Clone-copy must be on the battlefield");
        assertEquals(entered.getNetPower(), bear.getNetPower(), "Clone must copy Bear power");
        assertEquals(entered.getNetToughness(), bear.getNetToughness(), "Clone must copy Bear toughness");
        assertEquals(entered.getNetPower(), 2, "Clone-copy must be 2 power");
        assertEquals(entered.getNetToughness(), 2, "Clone-copy must be 2 toughness");
    }
}
