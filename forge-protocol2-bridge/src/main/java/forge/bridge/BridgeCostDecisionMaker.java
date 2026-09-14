package forge.bridge;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
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
import forge.game.zone.ZoneType;
import forge.util.Aggregates;

import java.util.ArrayList;
import java.util.List;

/**
 * Cost decisions for externally controlled plays.
 *
 * <p>Choice-free resolutions (self tap/untap, computed add-mana amounts) are
 * answered directly, mirroring the Human shape. Discretionary cost selections
 * with a native controller surface — sacrifice, discard, exile and pay-life
 * costs plus the generic exact-count card selection — are parked as
 * authoritative COST_SELECTION frames through the payer's external controller;
 * pilot decline (where the cost is optional) or incompletely offerable sets
 * return null so the engine rolls the ability back with no state change. Every
 * other cost part declines ({@code null}) with the same rollback. Nothing here
 * selects heuristically or fabricates legality: legal lists are computed with
 * the same Core filters the Human path uses.
 */
public final class BridgeCostDecisionMaker extends CostDecisionMakerBase {

    public BridgeCostDecisionMaker(Player player, boolean effect, SpellAbility ability) {
        super(player, effect, ability, ability.getHostCard());
        boolean required = false;
        try {
            required = ability != null && ability.getPayCosts() != null
                    && ability.getPayCosts().isMandatory();
        } catch (Throwable t) {
            required = false;
        }
        this.mandatory = required;
    }

    @Override
    public boolean paysRightAfterDecision() {
        return true;
    }

    @Override
    public PaymentDecision visit(CostPartMana cost) {
        // Decision unused by CostPartMana.payAsDecided ("the whole payment is interactive");
        // the controller's pre-floated pool gate performs the real payment work.
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

    // WS202: discretionary cost selections with a native controller surface.
    // Each visit computes the legal list with the same Core filters the Human
    // path uses, then parks an authoritative COST_SELECTION frame through the
    // payer's external controller. Pilot decline (optional costs) and
    // incompletely offerable sets return null so the engine rolls back.
    // Remaining exotic shapes fail closed with an audit reason, never silently.

    private final boolean mandatory;

    private ExternalPlayerController controller() {
        if (player.getController() instanceof ExternalPlayerController) {
            return (ExternalPlayerController) player.getController();
        }
        return null;
    }

    private boolean isMandatory() {
        return mandatory;
    }

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
        // WS202: mirror the Human legal-list computation, then park an
        // authoritative COST_SELECTION frame. Engine randomness (Random
        // discards) stays engine-owned via Aggregates, never pilot-chosen.
        final ExternalPlayerController controller = controller();
        if (controller == null) {
            return null;
        }
        final CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
        final String discardType = cost.getType();
        if (cost.payCostFromSource()) {
            return hand.contains(source) ? PaymentDecision.card(source) : null;
        }
        if (discardType.equals("Hand")) {
            if (hand.size() > 1) {
                // Whole-hand graveyard ordering has no represented surface.
                return null;
            }
            if (!mandatory && !controller.frameCostConfirm("cost_discard",
                    "Discard your hand")) {
                return null;
            }
            return PaymentDecision.card(hand);
        }
        if (discardType.equals("LastDrawn")) {
            final Card lastDrawn = player.getLastDrawnCard();
            return hand.contains(lastDrawn) ? PaymentDecision.card(lastDrawn) : null;
        }
        int c = cost.getAbilityAmount(ability);
        if (discardType.equals("Random")) {
            if (!mandatory && !controller.frameCostConfirm("cost_discard",
                    "Discard " + c + " at random")) {
                return null;
            }
            return PaymentDecision.card(Aggregates.random(hand, c));
        }
        if (discardType.contains("+WithDifferentNames")) {
            return discardSequential(cost, c, true);
        }
        if (discardType.contains("+WithSameName")) {
            return discardSequential(cost, c, false);
        }
        final String[] validType = discardType.split(";");
        final CardCollectionView valid =
                CardLists.getValidCards(hand, validType, player, source, ability);
        if (valid.size() < 1) {
            return null;
        }
        final CardCollection chosen = controller.frameCostCards("cost_discard",
                "Discard " + c + " card(s)", valid, c, !mandatory);
        return chosen == null ? null : PaymentDecision.card(chosen);
    }

    private PaymentDecision discardSequential(CostDiscard cost, int count, boolean differentNames) {
        // WS202: mirror the Human sequential name-constrained picks exactly, with
        // each pick parked as an authoritative COST_SELECTION frame. Cancel (always
        // allowed here, mirroring Human) declines the whole cost.
        final ExternalPlayerController controller = controller();
        if (controller == null) {
            return null;
        }
        CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
        if (!differentNames) {
            final String type = cost.getType().replace("+WithSameName", "");
            hand = CardLists.getValidCards(hand, type.split(";"), player, source, ability);
            final CardCollection namesakes = new CardCollection();
            final List<Card> snapshot = new ArrayList<>();
            for (Card card : hand) {
                snapshot.add(card);
            }
            for (Card card : snapshot) {
                for (Card other : snapshot) {
                    if (!other.equals(card) && other.getName().equals(card.getName())) {
                        namesakes.add(card);
                        break;
                    }
                }
            }
            hand = namesakes;
            if (count == 0) {
                return PaymentDecision.card(new CardCollection());
            }
        }
        final CardCollection discarded = new CardCollection();
        int remaining = count;
        while (remaining > 0) {
            final CardCollection picked = controller.frameCostCards("cost_discard",
                    "Discard (" + discarded.size() + " chosen)", hand, 1, true);
            if (picked == null || picked.isEmpty()) {
                return null;
            }
            final Card first = picked.getFirst();
            discarded.add(first);
            if (!differentNames) {
                final CardCollection sameName = new CardCollection();
                for (Card card : hand) {
                    if (!card.equals(first) && card.getName().equals(first.getName())) {
                        sameName.add(card);
                    }
                }
                hand = sameName;
            } else {
                final CardCollection rest = new CardCollection();
                for (Card card : hand) {
                    boolean shared = false;
                    for (Card chosenCard : discarded) {
                        if (chosenCard.getName().equals(card.getName())) {
                            shared = true;
                            break;
                        }
                    }
                    if (!shared) {
                        rest.add(card);
                    }
                }
                hand = rest;
            }
            remaining--;
        }
        return PaymentDecision.card(discarded);
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
        // WS202: mirror the Human legal-list computation for the representable
        // shapes (source/host, all, single-zone exact count, top-of-library) with
        // authoritative COST_SELECTION framing. Sum/type-constrained and
        // multi-zone owner-choice shapes fail closed (null → rollback).
        final ExternalPlayerController controller = controller();
        if (controller == null) {
            return null;
        }
        String type = cost.getType();
        Card onlyPayable = null;
        if (cost.payCostFromSource()) {
            onlyPayable = source;
        }
        if (type.equals("OriginalHost")) {
            onlyPayable = ability.getOriginalHost();
        }
        if (onlyPayable != null) {
            final Card payable = onlyPayable;
            if (payable.canExiledBy(ability, isEffect())
                    && payable.getZone() == player.getZone(cost.getFrom().get(0))
                    && controller.frameCostConfirm("cost_exile", "Exile " + payable.getName())) {
                return PaymentDecision.card(payable);
            }
            return null;
        }
        if (type.contains("FromTopGrave") || type.contains("+withTotalCMC")
                || type.contains("+withTotalManaSymbols_") || type.contains("+withSharedCardType")
                || type.contains("+withTypesGE")) {
            return null;
        }
        final List<ZoneType> fromZones = cost.getFrom();
        CardCollection list;
        if (cost.zoneRestriction != 1) {
            list = new CardCollection(player.getGame().getCardsIn(fromZones));
        } else {
            list = new CardCollection(player.getCardsIn(fromZones));
        }
        if (type.equals("All")) {
            if (!controller.frameCostConfirm("cost_exile",
                    "Exile " + list.size() + " card(s)")) {
                return null;
            }
            return PaymentDecision.card(list);
        }
        final CardCollection valid =
                CardLists.getValidCards(list, type.split(";"), player, source, ability);
        final CardCollection payable =
                CardLists.filter(valid, CardPredicates.canExiledBy(ability, isEffect()));
        int c = cost.getAbilityAmount(ability);
        if (payable.size() < c) {
            return null;
        }
        if (c == 0) {
            return PaymentDecision.number(c);
        }
        if (fromZones.size() == 1) {
            final ZoneType fromZone = fromZones.get(0);
            if (fromZone == ZoneType.Library) {
                // Top-of-library exile is engine-ordered: no discretion to frame.
                if (!controller.frameCostConfirm("cost_exile",
                        "Exile " + c + " card(s) from your library")) {
                    return null;
                }
                return PaymentDecision.card(
                        new CardCollection(player.getCardsIn(ZoneType.Library, c)));
            }
            final CardCollection chosen = controller.frameCostCards("cost_exile",
                    "Exile " + c + " card(s)", payable, c, !mandatory);
            return chosen == null ? null : PaymentDecision.card(chosen);
        }
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
        // WS202: mirror Human — mandatory life payments resolve directly;
        // optional ones park an authoritative COST_SELECTION confirm. Unpayable
        // amounts decline so the engine rolls back.
        final ExternalPlayerController controller = controller();
        if (controller == null) {
            return null;
        }
        final Integer c = cost.getAbilityAmount(ability);
        if (mandatory) {
            return PaymentDecision.number(c);
        }
        if (!player.canPayLife(c, isEffect(), ability)) {
            return null;
        }
        if (controller.frameCostConfirm("cost_pay_life", "Pay " + c + " life")) {
            return PaymentDecision.number(c);
        }
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
        // WS202: mirror the Human legal-list computation, then park an
        // authoritative COST_SELECTION frame. Cancel is allowed exactly when the
        // cost is optional, mirroring Human.
        final ExternalPlayerController controller = controller();
        if (controller == null) {
            return null;
        }
        final String amount = cost.getAmount();
        String type = cost.getType();
        if (cost.payCostFromSource()) {
            if (source.getController() == ability.getActivatingPlayer()
                    && source.canBeSacrificedBy(ability, isEffect())
                    && (mandatory || controller.frameCostConfirm("cost_sacrifice",
                            "Sacrifice " + source.getName()))) {
                return PaymentDecision.card(source);
            }
            return null;
        }
        if (type.equals("OriginalHost")) {
            final Card host = ability.getOriginalHost();
            if (host.getController() == ability.getActivatingPlayer()
                    && host.canBeSacrificedBy(ability, isEffect())
                    && controller.frameCostConfirm("cost_sacrifice",
                            "Sacrifice " + host.getName())) {
                return PaymentDecision.card(host);
            }
            return null;
        }
        boolean differentNames = false;
        if (type.contains("+WithDifferentNames")) {
            type = type.replace("+WithDifferentNames", "");
            differentNames = true;
        }
        CardCollectionView list = CardLists.filter(
                player.getCardsIn(ZoneType.Battlefield),
                CardPredicates.canBeSacrificedBy(ability, isEffect()));
        list = CardLists.getValidCards(list, type.split(";"), player, source, ability);
        if (amount.equals("All")) {
            return PaymentDecision.card(list);
        }
        int c = cost.getAbilityAmount(ability);
        if (c == 0) {
            return PaymentDecision.number(0);
        }
        if (differentNames) {
            final CardCollection chosen = new CardCollection();
            CardCollectionView pool = list;
            while (c > 0) {
                final CardCollection picked = controller.frameCostCards("cost_sacrifice",
                        "Sacrifice (" + chosen.size() + " chosen)", pool, 1, true);
                if (picked == null || picked.isEmpty()) {
                    return null;
                }
                final Card first = picked.getFirst();
                chosen.add(first);
                final CardCollection rest = new CardCollection();
                for (Card card : pool) {
                    boolean shared = false;
                    for (Card chosenCard : chosen) {
                        if (chosenCard.getName().equals(card.getName())) {
                            shared = true;
                            break;
                        }
                    }
                    if (!shared) {
                        rest.add(card);
                    }
                }
                pool = rest;
                c--;
            }
            return PaymentDecision.card(chosen);
        }
        if (list.size() < c) {
            return null;
        }
        final CardCollection chosen = controller.frameCostCards("cost_sacrifice",
                "Sacrifice " + c + " permanent(s)", list, c, !mandatory);
        return chosen == null ? null : PaymentDecision.card(chosen);
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
