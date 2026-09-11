package forge.bridge;

import forge.LobbyPlayer;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameType;
import forge.game.PlanarDice;
import forge.game.ability.ApiType;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPartWithList;
import forge.game.cost.CostTap;
import forge.game.cost.CostAddMana;
import forge.game.cost.CostUntap;
import forge.game.GameActionUtil;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.DelayedReveal;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController;
import forge.game.player.PlayerView;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * External-decision player controller: the native interception boundary.
 *
 * <p>Priority and mulligan callbacks rendezvous with the protocol thread through
 * parked {@link DecisionFrame}s. The starting-player callback honors the seat the
 * caller supplied at game creation (audited). Every other discretionary callback
 * throws {@link BridgeUnsupportedDecision} so an unrepresented decision aborts loudly
 * instead of being answered by AI, defaults, first-option, randomness or silent pass.
 *
 * <p>Execution uses the real {@link PlaySpellAbility} pipeline; the engine keeps full
 * legality, cost and timing authority, including rollback on failure.
 */
public final class ExternalPlayerController extends PlayerController {
    private final BridgeSession session;
    private SpellAbility lastReturnedAbility;

    /**
     * Systemic test seam (package-private, test-only): when set, native enumeration
     * throws {@link BridgeNativeEnumerationException} for the matching zone. Never
     * written by production code.
     */
    static volatile ZoneType enumerationFaultZoneForTests;

    public ExternalPlayerController(Game game, Player player, LobbyPlayer lobbyPlayer,
            BridgeSession session) {
        super(game, player, lobbyPlayer);
        this.session = session;
    }

    private String actorId() {
        try {
            return session.playerIdOf(player);
        } catch (Throwable t) {
            return player.getName();
        }
    }

    private BridgeUnsupportedDecision unsupported(String callback, String detail) {
        return new BridgeUnsupportedDecision(callback, actorId(), detail);
    }

    // ---- core rendezvous ----

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final List<SpellAbility> candidates;
        try {
            candidates = enumerateCandidates();
        } catch (BridgeNativeEnumerationException e) {
            // Atomicity: never present a partial set as SUPPORTED. Zero options, explicit
            // blocker; the session stays alive for diagnosis and clean shutdown. The park
            // below can only exit via session abort (no option exists to submit), whose
            // exception propagates; anything else is unreachable.
            session.audit("enumeration_failed",
                    BridgeSession.detail("reason", "NATIVE_ENUMERATION_FAILED zone=" + e.getZone()));
            session.parkFrame(DecisionFrame.Kind.PRIORITY, player,
                    DecisionFrame.Status.UNSUPPORTED, "NATIVE_ENUMERATION_FAILED",
                    new ArrayList<DecisionFrame.Option>());
            throw new AssertionError("unreachable: option-less frame cannot be answered");
        }
        final Map<String, Integer> complex = new LinkedHashMap<>();
        final List<DecisionFrame.Option> options = new ArrayList<>();
        options.add(DecisionFrame.passOption());
        for (SpellAbility sa : candidates) {
            final String blocker = classifyComplex(sa);
            if (blocker != null) {
                complex.put(blocker, complex.getOrDefault(blocker, 0) + 1);
                continue;
            }
            options.add(DecisionFrame.spellOption(actionTypeOf(sa), describe(sa),
                    sa.getHostCard().getName(), sa));
        }
        final BridgeSession.FrameAnswer answer;
        if (!complex.isEmpty()) {
            final StringBuilder reason = new StringBuilder("non-representable legal options present:");
            for (Map.Entry<String, Integer> entry : complex.entrySet()) {
                reason.append(' ').append(entry.getKey()).append('=').append(entry.getValue()).append(';');
            }
            answer = session.parkFrame(DecisionFrame.Kind.PRIORITY, player,
                    DecisionFrame.Status.UNSUPPORTED, reason.toString(),
                    new ArrayList<DecisionFrame.Option>());
        } else {
            answer = session.parkFrame(DecisionFrame.Kind.PRIORITY, player,
                    DecisionFrame.Status.SUPPORTED, "", options);
        }
        final DecisionFrame.Option selected = answer.selected;
        if (selected.isPass) {
            lastReturnedAbility = null;
            return null;
        }
        final SpellAbility chosen = selected.nativeBinding;
        if (chosen == null) {
            throw new IllegalStateException("supported option without a native binding");
        }
        lastReturnedAbility = chosen;
        final List<SpellAbility> result = new ArrayList<>(1);
        result.add(chosen);
        return result;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (sa != lastReturnedAbility) {
            throw new IllegalStateException("engine submitted an ability the bridge did not return");
        }
        lastReturnedAbility = null;
        try {
            final boolean ok = PlaySpellAbility.playSpellAbility(this, player, sa);
            if (!ok) {
                session.setLastExecutionError("engine declined the submitted option (rolled back)");
                session.audit("execution_declined",
                        BridgeSession.detail("label", sa.getHostCard().getName()));
            }
            return ok;
        } catch (BridgeUnsupportedDecision e) {
            session.setLastExecutionError(e.getMessage());
            throw e;
        }
    }

    /**
     * Structural representability classifier. Returns null when the candidate can be
     * offered; otherwise a stable reason code. Only engine-observable structure is
     * inspected — never card names, never game outcomes. Any uncertainty resolves to
     * a blocker, never to an offer.
     */
    static String classifyComplex(SpellAbility sa) {
        if (sa.usesTargeting()) {
            return "TARGETING";
        }
        if (sa.getApi() == ApiType.Charm) {
            return "MODAL";
        }
        if (sa.hasParam("AnnounceType")) {
            return "ANNOUNCE";
        }
        if (sa.costHasX() || sa.getPayCosts().hasXInAnyCostPart() || sa.isAnnouncing("X")) {
            return "X_VALUE";
        }
        if (!GameActionUtil.getOptionalCostValues(sa).isEmpty()) {
            return "OPTIONAL_COST";
        }
        final String manaBlocker = classifyMana(sa);
        if (manaBlocker != null) {
            return manaBlocker;
        }
        final Cost cost = sa.getPayCosts();
        if (cost != null) {
            for (CostPart part : cost.getCostParts()) {
                if (part instanceof CostPartMana) {
                    continue;
                }
                if (part instanceof CostTap) {
                    continue;
                }
                if (part instanceof CostUntap) {
                    continue;
                }
                if (part instanceof CostAddMana) {
                    continue;
                }
                return "COMPLEX_COST:" + part.getClass().getSimpleName();
            }
        }
        return null;
    }

    /**
     * R6 mana boundary. A candidate that can require a nonzero mana payment makes the
     * decision unrepresentable (the engine's weighted auto-payment would otherwise
     * choose strategically without an external decision). Only provably-zero mana
     * costs pass. A mana ability with a color/output choice is likewise blocked; only
     * fixed-output mana abilities pass, as proven by native AbilityManaPart structure.
     */
    private static String classifyMana(SpellAbility sa) {
        final Cost cost = sa.getPayCosts();
        if (cost != null) {
            for (CostPart part : cost.getCostParts()) {
                if (part instanceof CostPartMana) {
                    // Provably-zero means the engine's ZERO cost or its "no cost" sentinel
                    // (lands): both require literally no mana payment decision. Anything else
                    // would invoke weighted auto-payment, so it blocks the whole frame.
                    final boolean zero;
                    try {
                        final forge.card.mana.ManaCost derived =
                                ((CostPartMana) part).getManaCostFor(sa);
                        zero = derived.isZero() || derived.isNoCost();
                    } catch (Throwable t) {
                        return "MANA_PAYMENT_CHOICE";
                    }
                    if (!zero) {
                        return "MANA_PAYMENT_CHOICE";
                    }
                }
            }
        }
        if (sa.isManaAbility()) {
            SpellAbility tail = sa;
            while (tail != null) {
                final AbilityManaPart manaPart;
                try {
                    manaPart = tail.getManaPart();
                } catch (Throwable t) {
                    return "MANA_OUTPUT_CHOICE";
                }
                if (manaPart != null) {
                    if (manaPart.isAnyMana() || manaPart.isComboMana() || manaPart.isSpecialMana()) {
                        return "MANA_OUTPUT_CHOICE";
                    }
                    try {
                        if (manaPart.getOrigProduced().contains("Chosen")) {
                            return "MANA_OUTPUT_CHOICE";
                        }
                    } catch (Throwable t) {
                        return "MANA_OUTPUT_CHOICE";
                    }
                }
                try {
                    tail = tail.getSubAbility();
                } catch (Throwable t) {
                    return "MANA_OUTPUT_CHOICE";
                }
            }
        }
        return null;
    }

    private static String actionTypeOf(SpellAbility sa) {
        if (sa.isLandAbility()) {
            return "play_land";
        }
        if (sa.isSpell()) {
            return "cast_spell";
        }
        return "activate_ability";
    }

    private static String describe(SpellAbility sa) {
        try {
            return sa.getHostCard().getName() + " [" + actionTypeOf(sa) + "]";
        } catch (Throwable t) {
            return "[option]";
        }
    }

    private List<SpellAbility> enumerateCandidates() {
        final List<SpellAbility> result = new ArrayList<>();
        final ZoneType[] zones = new ZoneType[] { ZoneType.Hand, ZoneType.Battlefield,
                ZoneType.Command, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Library };
        for (ZoneType zone : zones) {
            if (zone == enumerationFaultZoneForTests) {
                throw new BridgeNativeEnumerationException(zone, "injected test fault", null);
            }
            final List<Card> cards;
            try {
                cards = new ArrayList<>(player.getCardsIn(zone));
            } catch (Throwable t) {
                throw new BridgeNativeEnumerationException(zone, "zone read failed", t);
            }
            for (Card card : cards) {
                try {
                    result.addAll(card.getAllPossibleAbilities(player, true));
                } catch (Throwable t) {
                    throw new BridgeNativeEnumerationException(zone, "card enumeration failed", t);
                }
            }
        }
        return result;
    }

    // ---- mulligan: binary external choice, parked ----

    @Override
    public boolean mulliganKeepHand(Player mulliganingPlayer, int cardsToReturn) {
        final List<DecisionFrame.Option> options = new ArrayList<>(2);
        options.add(DecisionFrame.keepOption());
        options.add(DecisionFrame.shipOption());
        final BridgeSession.FrameAnswer answer = session.parkFrame(DecisionFrame.Kind.MULLIGAN,
                player, DecisionFrame.Status.SUPPORTED, "", options);
        return answer.selected.isKeep;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        throw unsupported("tuckCardsViaMulligan",
                "London-tuck card selection is not externally represented");
    }

    // ---- starting player: real external frame for Forge's chosen chooser ----

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        // Forge (dice/rules) selected this controller to choose. Offer every player Forge
        // permits — the same complete set the human UI offers (turn order) — with the
        // native Player binding retained bridge-side. No default, no seat, no randomness.
        final List<Player> order;
        try {
            order = new ArrayList<>(getGame().getPlayersInTurnOrder());
        } catch (Throwable t) {
            throw unsupported("chooseStartingPlayer", "turn order unreadable");
        }
        if (order.isEmpty()) {
            throw unsupported("chooseStartingPlayer", "no players offered by engine");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(order.size());
        for (Player candidate : order) {
            final String candidateId = session.playerIdOf(candidate);
            options.add(DecisionFrame.startingPlayerOption(candidateId,
                    "Choose starting player: " + candidateId, candidate));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(DecisionFrame.Kind.STARTING_PLAYER,
                player, DecisionFrame.Status.SUPPORTED, "", options);
        final Player chosen = answer.selected.nativePlayer;
        if (chosen == null) {
            throw new IllegalStateException("starting-player option without a native binding");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("chooser", actorId());
        details.put("choice", session.playerIdOf(chosen));
        session.audit("starting_player_chosen", details);
        return chosen;
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        throw unsupported("chooseStartingHand", "filtered-hands selection is not represented");
    }

    // ---- mana/cost execution: pool-only, forced-only ----

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        // R6: zero-mana only. A nonzero payment would invoke the engine's weighted
        // auto-payment heuristic without an external decision, so decline it and let the
        // engine roll the play back. ZERO and the engine's "no cost" sentinel (lands)
        // deduct nothing; the heuristic is unreachable.
        if (toPay == null || !(toPay.isZero() || toPay.isNoCost())) {
            return false;
        }
        return true;
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt,
            ManaConversionMatrix matrix, boolean effect) {
        // R6: only an already-paid (vacuous) balance passes. Anything outstanding would
        // require a discretionary payment choice, so fail closed with no deduction.
        return toPay != null && toPay.isPaid();
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability,
            boolean effect, String prompt) {
        return new BridgeCostDecisionMaker(player, effect, ability);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String message, SpellAbility sa) {
        // Decline rather than auto-confirm: the engine rolls the play back.
        return false;
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        if (costs.size() <= 1) {
            return costs;
        }
        throw unsupported("orderCosts", "cost ordering is a choice");
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa,
            CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        throw unsupported("chooseCardsForCost", "cost card selection is a choice");
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        throw unsupported("chooseManaFromPool", "pool mana selection is a choice");
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount,
            boolean different) {
        throw unsupported("specifyManaCombo", "mana combo specification is a choice");
    }

    // ---- forced trivial cases ----

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs.size() <= 1) {
            return activePlayerSAs;
        }
        throw unsupported("orderSimultaneousSa", "trigger ordering is a choice");
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        if (usableFromOpeningHand.isEmpty()) {
            return usableFromOpeningHand;
        }
        throw unsupported("chooseSaToActivateFromOpeningHand", "opening-hand activation is a choice");
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        if (attackers.isEmpty()) {
            return attackers;
        }
        throw unsupported("exertAttackers", "exert selection is a choice");
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        if (attackers.isEmpty()) {
            return attackers;
        }
        throw unsupported("enlistAttackers", "enlist selection is a choice");
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return null;
    }

    // ---- human-sync only: no-ops ----

    @Override
    public void autoPassCancel() {
    }

    @Override
    public void awaitNextInput() {
    }

    @Override
    public void cancelAwaitNextInput() {
    }

    // ---- reveal/notify: audit-only, no decision ----

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix,
            boolean addMsgSuffix) {
        auditReveal(cards == null ? 0 : cards.size(), zone);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix,
            boolean addMsgSuffix) {
        auditReveal(cards == null ? 0 : cards.size(), zone);
    }

    private void auditReveal(int count, ZoneType zone) {
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("count", Integer.toString(count));
        details.put("zone", zone == null ? "?" : zone.name());
        session.audit("cards_revealed", details);
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject relatedTarget, String value) {
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("value", value == null ? "" : value);
        session.audit("notified_of_value", details);
    }

    @Override
    public void revealAnte(String message, com.google.common.collect.Multimap<Player, PaperCard> removedAnteCards) {
        session.audit("reveal_ante", BridgeSession.detail("actor", actorId()));
    }

    @Override
    public void revealAISkipCards(String message,
            Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        session.audit("reveal_ai_skip", BridgeSession.detail("actor", actorId()));
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        int count = 0;
        if (unsupported != null) {
            for (List<PaperCard> cards : unsupported.values()) {
                count += cards.size();
            }
        }
        if (count == 0) {
            return;
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("count", Integer.toString(count));
        session.audit("reveal_unsupported_cards", details);
    }

    // ---- everything else: fail closed ----

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities,
            ITriggerEvent triggerEvent) {
        // The play pipeline calls this with the additional-cost variants of the chosen
        // ability. A singleton is forced (no choice exists); multiples are a genuine
        // alternative-cost choice the bridge does not represent.
        if (abilities.size() == 1) {
            return abilities.get(0);
        }
        throw unsupported("getAbilityToPlay",
                "alternative additional-cost selection is not represented");
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        throw unsupported("playSpellAbilityNoStack", "no-stack triggers are not represented");
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        throw unsupported("orderAndPlaySimultaneousSa", "simultaneous stack entries are not represented");
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        throw unsupported("playTrigger", "trigger play is not represented");
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        throw unsupported("playSaFromPlayEffect", "play-from-effect is not represented");
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        throw unsupported("chooseCardsYouWonToAddToDeck", "ante card choice is not represented");
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
            CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        throw unsupported("assignCombatDamage", "combat damage assignment is not represented");
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected,
            int shieldAmount) {
        throw unsupported("divideShield", "shield division is not represented");
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
            CardCollectionView validTargets, String message) {
        throw unsupported("choosePermanentsToSacrifice", "sacrifice selection is not represented");
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
            CardCollectionView validTargets, String message) {
        throw unsupported("choosePermanentsToDestroy", "destroy selection is not represented");
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        throw unsupported("announceRequirements", "announced values are not represented");
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter,
            boolean optional) {
        throw unsupported("chooseNewTargetsFor", "target selection is not represented");
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        throw unsupported("chooseTargetsFor", "target selection is not represented");
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa,
            List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        throw unsupported("chooseTarget", "target selection is not represented");
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return false;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa,
            String title, int max) {
        throw unsupported("choosePlayerToAssistPayment", "assist payment is not represented");
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa,
            String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        throw unsupported("chooseCardsForEffect", "effect card selection is not represented");
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap,
            SpellAbility sa, String title, boolean isOptional) {
        throw unsupported("chooseCardsForEffectMultiple", "effect card selection is not represented");
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList,
            DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional,
            Player relatedPlayer, Map<String, Object> params) {
        throw unsupported("chooseSingleEntityForEffect", "entity selection is not represented");
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min,
            int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer,
            Map<String, Object> params) {
        throw unsupported("chooseEntitiesForEffect", "entity selection is not represented");
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa,
            String title, int num, Map<String, Object> params) {
        throw unsupported("chooseSpellAbilitiesForEffect", "spell selection is not represented");
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa,
            String title, Map<String, Object> params) {
        throw unsupported("chooseSingleSpellForEffect", "spell selection is not represented");
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        throw unsupported("confirmAction", "confirms are not represented");
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string,
            int bid, Player winner) {
        throw unsupported("confirmBidAction", "bid confirms are not represented");
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
            GameEntity affected, String question) {
        throw unsupported("confirmReplacementEffect", "replacement confirms are not represented");
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message,
            String logic) {
        throw unsupported("confirmStaticApplication", "static confirms are not represented");
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        throw unsupported("confirmTrigger", "trigger confirms are not represented");
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        throw unsupported("declareAttackers", "combat declaration is not represented");
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        throw unsupported("declareBlockers", "combat declaration is not represented");
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        throw unsupported("orderBlockers", "combat ordering is not represented");
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        throw unsupported("orderBlocker", "combat ordering is not represented");
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        throw unsupported("orderAttackers", "combat ordering is not represented");
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        throw unsupported("arrangeForScry", "scry arrangement is not represented");
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        throw unsupported("arrangeForSurveil", "surveil arrangement is not represented");
    }

    @Override
    public boolean willPutCardOnTop(Card card) {
        throw unsupported("willPutCardOnTop", "library arrangement is not represented");
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
            SpellAbility source) {
        throw unsupported("orderMoveToZoneList", "zone ordering is not represented");
    }

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
            CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        throw unsupported("chooseCardsToDiscardFrom", "discard selection is not represented");
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand,
            String[] unlessTypes, SpellAbility sa) {
        throw unsupported("chooseCardsToDiscardUnlessType", "discard selection is not represented");
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        throw unsupported("chooseCardsToDiscardToMaximumHandSize", "discard selection is not represented");
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        throw unsupported("chooseCardsToDelve", "delve selection is not represented");
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost,
            CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        throw unsupported("chooseCardsForConvokeOrImprovise", "convoke selection is not represented");
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        throw unsupported("chooseCardsForSplice", "splice selection is not represented");
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        throw unsupported("chooseCardsToRevealFromHand", "reveal selection is not represented");
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes,
            boolean isOptional) {
        throw unsupported("chooseSomeType", "type choice is not represented");
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        throw unsupported("chooseSector", "sector choice is not represented");
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        throw unsupported("chooseContraptionsToCrank", "contraption choice is not represented");
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        throw unsupported("chooseSprocket", "sprocket choice is not represented");
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        throw unsupported("choosePDRollToIgnore", "planar dice choice is not represented");
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        throw unsupported("chooseRollToIgnore", "dice choice is not represented");
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        throw unsupported("chooseDiceToReroll", "dice choice is not represented");
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        throw unsupported("chooseRollToModify", "dice choice is not represented");
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        throw unsupported("chooseRollToSwap", "dice choice is not represented");
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power,
            int toughness) {
        throw unsupported("chooseRollSwapValue", "dice choice is not represented");
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
            com.google.common.collect.ListMultimap<Object, Player> votes, Player forPlayer,
            boolean optional) {
        throw unsupported("vote", "voting is not represented");
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min,
            int num, boolean allowRepeat) {
        throw unsupported("chooseModeForAbility", "mode choice is not represented");
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        throw unsupported("chooseNumberForCostReduction", "number choice is not represented");
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword,
            String prompt, int max) {
        throw unsupported("chooseNumberForKeywordCost", "number choice is not represented");
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        throw unsupported("chooseNumber", "number choice is not represented");
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        throw unsupported("chooseNumber", "number choice is not represented");
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice,
            Boolean defaultChoice) {
        throw unsupported("chooseBinary", "binary choice is not represented");
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        throw unsupported("chooseFlipResult", "flip choice is not represented");
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        throw unsupported("chooseColor", "color choice is not represented");
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card card, ColorSet colors) {
        throw unsupported("chooseColorAllowColorless", "color choice is not represented");
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        throw unsupported("chooseColors", "color choice is not represented");
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp,
            String name) {
        throw unsupported("chooseSingleCardFace", "face choice is not represented");
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        throw unsupported("chooseSingleCardFace", "face choice is not represented");
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message,
            Map<String, Object> params) {
        throw unsupported("chooseSingleCardState", "state choice is not represented");
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2,
            String faceUp) {
        throw unsupported("chooseCardsPile", "pile choice is not represented");
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt,
            Map<String, Object> params) {
        throw unsupported("chooseCounterType", "counter choice is not represented");
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt,
            Card targetCard) {
        throw unsupported("chooseKeywordForPump", "keyword choice is not represented");
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        throw unsupported("chooseSingleReplacementEffect", "replacement choice is not represented");
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        throw unsupported("chooseSingleStaticAbility", "static choice is not represented");
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        throw unsupported("chooseProtectionType", "protection choice is not represented");
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
            List<OptionalCostValue> optionalCostValues) {
        throw unsupported("chooseOptionalCosts", "optional costs are not represented");
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid,
            FCollectionView<Player> allPayers) {
        throw unsupported("payCostToPreventEffect", "prevention payment choice is not represented");
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        throw unsupported("payCostDuringRoll", "roll payment choice is not represented");
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        throw unsupported("payCombatCost", "combat cost choice is not represented");
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        throw unsupported("chooseCardName", "name choice is not represented");
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        throw unsupported("chooseCardName", "name choice is not represented");
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt,
            boolean isOptional, Player decider) {
        throw unsupported("chooseSingleCardForZoneChange", "zone-change selection is not represented");
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal,
            String selectPrompt, Player decider) {
        throw unsupported("chooseCardsForZoneChange", "zone-change selection is not represented");
    }
}
