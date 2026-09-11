package forge.gamesimulationtests.ws59;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.LobbyPlayer;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardUtil;
import forge.game.cost.CostExile;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.PlayerControllerForTests;

/**
 * WS59R C01 — authoritative stack-spell target-decision continuation (non-bypass).
 *
 * Boundary disposition (see WS55R_C01_COST_PITCH authority packet):
 * A. variant enumeration — DIRECTLY_VERIFIED (engine-enumerated, structural identity)
 * B. alternate-cost selection — DIRECTLY_VERIFIED (exact engine object, no reconstruction)
 * C. hidden-zone pitch selection — TECHNICALLY_CONFORMANT / NOT_RUN (costs preserved
 *    structurally; selection deferred to First-Wave behavior; never DIRECTLY_VERIFIED here)
 * D. target decision callback — DIRECTLY_VERIFIED (native controller seam reached)
 * E. target binding — DIRECTLY_VERIFIED (stack SpellAbility becomes actual target)
 * F. payment/life/exile — NOT_RUN (deferred to First-Wave behavior)
 * G. resolution/counter outcome — NOT_RUN (deferred to First-Wave behavior)
 *
 * No provider filtering/solver. No manual target injection in test methods: the only
 * target addition lives inside the engine-invoked controller callback (native path,
 * mirroring Human/AI controllers). No display-string heuristics.
 */
public class Ws59C01CostPitchTest extends SimulationTest {

    /**
     * Native decision harness using Forge's PlayerController seam.
     * Engine invokes chooseTargetsFor via SpellAbility.setupTargets (the path that
     * previously aborted before any callback). This override enumerates ONLY via
     * engine authority (stack + canTargetSpellAbility), requires an exact
     * single-match (fail closed otherwise), and records the callback.
     */
    static final class Ws59NativeTargetHarness extends PlayerControllerForTests {
        boolean callbackReached = false;
        SpellAbility selected = null;

        Ws59NativeTargetHarness(Game game, Player player, LobbyPlayer lobbyPlayer) {
            super(game, player, lobbyPlayer);
        }

        @Override
        public boolean chooseTargetsFor(SpellAbility ability) {
            callbackReached = true;
            List<SpellAbility> nativeOptions = new ArrayList<>();
            for (forge.game.spellability.SpellAbilityStackInstance si : getGame().getStack()) {
                SpellAbility candidate = si.getSpellAbility();
                if (ability.canTargetSpellAbility(candidate)) {
                    nativeOptions.add(candidate);
                }
            }
            if (nativeOptions.size() != 1) {
                return false;
            }
            selected = nativeOptions.get(0);
            // Native controller decision path (engine-invoked, as in Human/AI):
            // bind the engine-presented stack SpellAbility as the actual target.
            ability.getTargets().add(selected);
            return true;
        }
    }

    private Card addHand(String name, Player p) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Hand).add(c);
        return c;
    }

    private SpellAbility pushStackSpell(String name, Player controller) {
        Card hand = addHand(name, controller);
        SpellAbility sa = hand.getFirstSpellAbility();
        sa.setActivatingPlayer(controller);
        Card stackCard = controller.getGame().getAction().moveToStack(hand, sa);
        SpellAbility stackSA = stackCard.getFirstSpellAbility();
        if (stackSA == null) {
            stackSA = sa;
        }
        stackSA.setActivatingPlayer(controller);
        controller.getGame().getStack().add(stackSA);
        return stackSA;
    }

    private static boolean isPitchVariant(SpellAbility sa) {
        if (sa.getPayCosts() == null) {
            return false;
        }
        return sa.getPayCosts().hasSpecificCostType(CostExile.class)
                && sa.getPayCosts().hasSpecificCostType(CostPayLife.class);
    }

    private static boolean isNormalManaVariant(SpellAbility sa) {
        if (sa.getPayCosts() == null) {
            return false;
        }
        return sa.getPayCosts().hasSpecificCostType(CostPartMana.class)
                && !sa.getPayCosts().hasSpecificCostType(CostExile.class)
                && !sa.getPayCosts().hasSpecificCostType(CostPayLife.class);
    }

    @Test(timeOut = 30000)
    public void testExactFixtureForceOfWillPitchAuthoritativeDecision() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        SpellAbility elvesSA = pushStackSpell("Llanowar Elves", p2);
        assertFalse(game.getStack().isEmpty());

        Card fow = addHand("Force of Will", p1);
        Card frog = addHand("Turn to Frog", p1);
        assertTrue(frog.isBlue());
        assertTrue(frog.getZone() != null && frog.getZone().is(ZoneType.Hand));

        List<SpellAbility> options = fow.getAllPossibleAbilities(p1, true);
        assertFalse(options.isEmpty());
        SpellAbility pitchSA = null;
        SpellAbility normalSA = null;
        for (SpellAbility sa : options) {
            sa.setActivatingPlayer(p1);
            if (isPitchVariant(sa)) {
                pitchSA = sa;
            } else if (isNormalManaVariant(sa)) {
                normalSA = sa;
            }
        }
        assertNotNull(normalSA);
        assertNotNull(pitchSA);
        assertNotSame(pitchSA, normalSA);

        pitchSA.setActivatingPlayer(p1);
        CostExile exilePart = pitchSA.getPayCosts().getCostPartByType(CostExile.class);
        assertNotNull(exilePart);
        assertTrue(exilePart.getFrom().contains(ZoneType.Hand));

        assertTrue(pitchSA.getTargetRestrictions().hasCandidates(pitchSA));
        assertTrue(pitchSA.getTargetRestrictions().getNumCandidates(pitchSA) >= 1);
        assertFalse(pitchSA.getTargetRestrictions().getAllCandidates(pitchSA).isEmpty());
        CardCollection valid = CardUtil.getValidCardsToTarget(pitchSA);
        assertFalse(valid.isEmpty());
        assertTrue(pitchSA.canTargetSpellAbility(elvesSA));
        assertTrue(pitchSA.canTarget(elvesSA.getHostCard()));

        Ws59NativeTargetHarness harness =
                new Ws59NativeTargetHarness(game, p1, p1.getController().getLobbyPlayer());
        p1.dangerouslySetController(harness);

        assertTrue(pitchSA.getTargets().isEmpty());
        assertFalse(harness.callbackReached);

        boolean setupOk = pitchSA.setupTargets();

        assertTrue(setupOk);
        assertTrue(harness.callbackReached);
        assertNotNull(harness.selected);
        assertSame(harness.selected, elvesSA);
        assertSame(pitchSA.getTargets().getFirstTargetedSpell(), elvesSA);
    }

    @Test(timeOut = 30000)
    public void testGenericCounterspellAuthoritativeDecision() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        SpellAbility bearsSA = pushStackSpell("Grizzly Bears", p2);
        Card cancel = addHand("Cancel", p1);
        SpellAbility cancelSA = cancel.getFirstSpellAbility();
        cancelSA.setActivatingPlayer(p1);

        assertTrue(cancelSA.getTargetRestrictions().hasCandidates(cancelSA));
        assertTrue(cancelSA.canTargetSpellAbility(bearsSA));
        assertTrue(cancelSA.canTarget(bearsSA.getHostCard()));
        assertFalse(CardUtil.getValidCardsToTarget(cancelSA).isEmpty());

        Ws59NativeTargetHarness harness =
                new Ws59NativeTargetHarness(game, p1, p1.getController().getLobbyPlayer());
        p1.dangerouslySetController(harness);

        assertTrue(cancelSA.getTargets().isEmpty());

        boolean setupOk = cancelSA.setupTargets();

        assertTrue(setupOk);
        assertTrue(harness.callbackReached);
        assertSame(harness.selected, bearsSA);
        assertSame(cancelSA.getTargets().getFirstTargetedSpell(), bearsSA);
    }
}
