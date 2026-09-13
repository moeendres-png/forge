package forge.bridge;

import forge.game.cost.CostAddMana;
import forge.game.cost.CostBehold;
import forge.game.cost.CostBeholdExile;
import forge.game.cost.CostBlight;
import forge.game.cost.CostChooseColor;
import forge.game.cost.CostChooseCreatureType;
import forge.game.cost.CostCollectEvidence;
import forge.game.cost.CostDamage;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostDraw;
import forge.game.cost.CostEnlist;
import forge.game.cost.CostExert;
import forge.game.cost.CostExile;
import forge.game.cost.CostExileFromStack;
import forge.game.cost.CostExiledMoveToGrave;
import forge.game.cost.CostFlipCoin;
import forge.game.cost.CostForage;
import forge.game.cost.CostGainControl;
import forge.game.cost.CostGainLife;
import forge.game.cost.CostMill;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayEnergy;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPayShards;
import forge.game.cost.CostPromiseGift;
import forge.game.cost.CostPutCardToLib;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostPutCounterYou;
import forge.game.cost.CostRemoveAnyCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostReturn;
import forge.game.cost.CostReveal;
import forge.game.cost.CostRevealChosen;
import forge.game.cost.CostRollDice;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.cost.CostTapType;
import forge.game.cost.CostUnattach;
import forge.game.cost.CostUntap;
import forge.game.cost.CostUntapType;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Cost decisions for externally controlled plays.
 *
 * <p>Mirrors ONLY provably choice-free resolutions: self tap/untap and computed
 * add-mana amounts. The mana-cost visit returns an unused placeholder because the
 * real gate lives in the controller, which accepts only zero/no-cost mana payment
 * and declines anything nonzero before execution (no Forge weighted pool
 * auto-payment is reachable). Every other cost part declines ({@code null}), which
 * makes the engine roll the ability back with no state change. Nothing here
 * selects, orders, targets or confirms anything — any discretionary cost was
 * already excluded from supported frames by the structural classifier and would
 * fail closed there.
 */
public final class BridgeCostDecisionMaker extends CostDecisionMakerBase {

    public BridgeCostDecisionMaker(Player player, boolean effect, SpellAbility ability) {
        super(player, effect, ability, ability.getHostCard());
    }

    @Override
    public boolean paysRightAfterDecision() {
        return true;
    }

    @Override
    public PaymentDecision visit(CostPartMana cost) {
        // Decision unused by CostPartMana.payAsDecided ("the whole payment is interactive");
        // the controller's zero/no-cost-only gate performs the real fail-closed check.
        // Mirrors HumanCostDecision's placeholder shape without its interactive path.
        return new PaymentDecision(0);
    }

    @Override
    public PaymentDecision visit(CostTap cost) {
        // Tap self: fully forced, no selection. Mirrors HumanCostDecision.
        return PaymentDecision.number(1);
    }

    @Override
    public PaymentDecision visit(CostUntap cost) {
        // Untap self: fully forced. Mirrors HumanCostDecision.
        return PaymentDecision.number(1);
    }

    @Override
    public PaymentDecision visit(CostAddMana cost) {
        // Pure amount computation, no selection. Mirrors HumanCostDecision.
        return PaymentDecision.number(cost.getAbilityAmount(ability));
    }

    // Everything below requires a human choice, a confirm, ordering or a selection,
    // so the bridge declines and the engine rolls the play back. Fail closed.

    @Override
    public PaymentDecision visit(CostBehold cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostBeholdExile cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostGainControl cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostChooseColor cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostChooseCreatureType cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostCollectEvidence cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostDiscard cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostDamage cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostDraw cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostExile cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostExileFromStack cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostExiledMoveToGrave cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostExert cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostEnlist cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostFlipCoin cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostForage cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostRollDice cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostMill cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostPayLife cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostPayEnergy cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostGainLife cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostPromiseGift cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostPutCardToLib cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostSacrifice cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostReturn cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostReveal cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostRevealChosen cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostRemoveAnyCounter cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostRemoveCounter cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostPutCounter cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostPutCounterYou cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostUntapType cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostUnattach cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostTapType cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostPayShards cost) {
        return null;
    }

    @Override
    public PaymentDecision visit(CostBlight cost) {
        return null;
    }
}
