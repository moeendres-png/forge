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
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.combat.CombatDamageDecisionView;
import forge.game.combat.CombatDamageSelection;
import forge.game.player.AmountDistributionDecisionView;
import forge.game.player.AmountDistributionSelection;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * External-decision player controller: the native interception boundary.
 *
 * <p>Priority, mulligan and starting-player callbacks rendezvous with the protocol
 * thread through parked {@link DecisionFrame}s. Forge rules/RNG selects which
 * controller receives the starting-player choice; that chooser is offered the
 * complete native player set with no preset seat and no default. Every other
 * discretionary callback throws {@link BridgeUnsupportedDecision} so an
 * unrepresented decision aborts loudly instead of being answered by AI, defaults,
 * first-option, randomness or silent pass.
 *
 * <p>Execution uses the real {@link PlaySpellAbility} pipeline; the engine keeps full
 * legality, cost and timing authority, including rollback on failure.
 */
public final class ExternalPlayerController extends PlayerController {
    private final BridgeSession session;
    private SpellAbility lastReturnedAbility;

    /**
     * Systemic test seam (package-private, test-only): when set, native enumeration
     * throws {@link BridgeNativeEnumerationException} instead of reading sources.
     * Never written by production code.
     */
    static volatile boolean enumerationFaultForTests;

    public ExternalPlayerController(Game game, Player player, LobbyPlayer lobbyPlayer,
            BridgeSession session) {
        super(game, player, lobbyPlayer);
        this.session = session;
    }

    private String actorId() {
        // R11: registry identity only. An unregistered controller player is a corrupt
        // session and must fail explicitly, never fall back to a display name.
        return session.playerIdOf(player);
    }

    private BridgeUnsupportedDecision unsupported(String callback, String detail) {
        return new BridgeUnsupportedDecision(callback, actorId(), detail);
    }

    // ---- WS202 generic framing helpers ----
    //
    // Every helper parks a complete authoritative frame and returns the native
    // selection. No heuristics, no defaults, no AI. Single-option forced paths
    // return without parking (no discretion). Optional declines are explicit
    // options, never silent skips.

    private boolean parkBinary(DecisionFrame.Kind kind, String actionType, String prompt) {
        final List<DecisionFrame.Option> options = new ArrayList<>(2);
        options.add(DecisionFrame.confirmOption(actionType, prompt + " [Yes]", true));
        options.add(DecisionFrame.confirmOption(actionType, prompt + " [No]", false));
        final BridgeSession.FrameAnswer answer = session.parkFrame(kind, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        final Boolean value = answer.selected.confirmValue;
        if (value == null) {
            throw new IllegalStateException("binary option without confirm value");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("kind", kind.name());
        details.put("choice", value.toString());
        session.audit("binary_choice", details);
        return value.booleanValue();
    }

    private <T> T parkSingleChoice(DecisionFrame.Kind kind, String actionType,
            List<T> legal, java.util.function.Function<T, String> labeler, String payloadKind) {
        if (legal == null || legal.isEmpty()) {
            throw unsupported("parkSingleChoice", "empty legal set cannot be framed");
        }
        if (legal.size() == 1) {
            return legal.get(0);
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(legal.size());
        for (T item : legal) {
            final String label;
            try {
                label = labeler.apply(item);
            } catch (Throwable t) {
                throw unsupported("parkSingleChoice", "label unreadable");
            }
            options.add(DecisionFrame.payloadOption(actionType, label, null, item, payloadKind));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(kind, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final T chosen = (T) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("single-choice option without native payload");
        }
        return chosen;
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
        // WS202: concession is always Rules-legal when the player is in the game
        // (CR 104.3a via canConcede). Offered alongside pass in every SUPPORTED
        // priority frame so the complete legal set stays unfiltered. Selection
        // executes the native concede path and returns pass for engine accounting.
        if (canConcede()) {
            options.add(DecisionFrame.concedeOption());
        }
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
        if (selected.isConcede) {
            // Native authoritative concession (CR 104.3a/800.4). Rules alone owns
            // legality via canConcede; provider only transports the decision.
            // audit + concede + pass-return lets the engine reassign priority.
            final java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
            details.put("actor", actorId());
            session.audit("concession_executed", details);
            concede();
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
     *
     * <p>WS202: X/announce (X_ANNOUNCE frames), modal Charm (MODE_SELECTION frames),
     * alternative costs (COST_SELECTION frames) and pre-floated pool mana payment
     * (MANA_PAYMENT frames) are now representable. Targeting, AnnounceType,
     * optional costs, choice mana outputs and non-forced cost parts still fail
     * closed until their families qualify.
     */
    static String classifyComplex(SpellAbility sa) {
        if (sa.usesTargeting()) {
            return "TARGETING";
        }
        if (sa.hasParam("AnnounceType")) {
            return "ANNOUNCE";
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
     * WS202 mana boundary. Payment from the pre-floated pool is representable via
     * native ManaPool deduction with framed chooseManaFromPool on ambiguity; no
     * heuristic auto-tap is reachable. Only mana abilities with a color/output
     * choice still block; fixed-output mana abilities pass, as proven by native
     * AbilityManaPart structure.
     */
    private static String classifyMana(SpellAbility sa) {
        // WS202: nonzero mana payment is representable via pre-floated pool
        // deduction (see payManaCost/applyManaToCost). No payment blocker here.
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

    /**
     * R8 action-source-zone policy (bounded Commander bridge).
     *
     * <p>Sources are united from two engine-owned collections, then every candidate
     * passes the engine's own {@code canPlay(true)} filter inside
     * {@code getAllPossibleAbilities} — the bridge adds no legality of its own:
     * <ul>
     *   <li>{@code Player.getAllCards()}: every card of the acting player in every
     *   Forge-tracked zone (Hand, Battlefield, Command, Graveyard, Exile, Library,
     *   Sideboard incl. companions, Ante remnants, Merged, variant decks, tokens).
     *   Physical sweep, so no zone is silently omitted.</li>
     *   <li>{@code Player.getCardsActivatableInExternalZones(true)}: the engine's own
     *   grant index — cards the player may activate outside its own zones, i.e.
     *   opponents' Exile/Graveyard/Library/Hand under may-play grants and stack
     *   cards with stack-restricted abilities. This is a candidate source, not a
     *   legality oracle (contrast the heuristic timeout-based AvailableActions,
     *   which is never consulted).</li>
     * </ul>
     * <p>Zone matrix (pinned source): directly actionable — Hand, Battlefield,
     * Command, Graveyard, Exile, Library, own Sideboard, Stack (grant-indexed),
     * Flashback-virtual (grant-indexed view over graveyard/exile/library/command/
     * sideboard plus opponents/stack — covered by the union above). Resolution-only
     * selections (targets/modes) are never priority options. Variant-only zones
     * (Scheme/Planar/Attraction/Contraption decks, Junkyard, Subgame, ExtraHand)
     * and Ante remnants are swept via getAllCards but hold no Commander playables;
     * any canPlay-true surprise there fails the frame UNSUPPORTED via the
     * classifier rather than being dropped.
     */
    private List<SpellAbility> enumerateCandidates() {
        if (enumerationFaultForTests) {
            throw new BridgeNativeEnumerationException(null, "injected test fault", null);
        }
        final Set<Card> sources = Collections.newSetFromMap(new IdentityHashMap<Card, Boolean>());
        try {
            sources.addAll(player.getAllCards());
        } catch (Throwable t) {
            throw new BridgeNativeEnumerationException(null, "all-cards read failed", t);
        }
        try {
            sources.addAll(player.getCardsActivatableInExternalZones(true));
        } catch (Throwable t) {
            throw new BridgeNativeEnumerationException(null, "external-activatables read failed", t);
        }
        final List<SpellAbility> result = new ArrayList<>();
        for (Card card : sources) {
            if (card == null) {
                throw new BridgeNativeEnumerationException(null, "null card in source set", null);
            }
            try {
                result.addAll(card.getAllPossibleAbilities(player, true));
            } catch (Throwable t) {
                throw new BridgeNativeEnumerationException(safeZoneOf(card),
                        "card enumeration failed", t);
            }
        }
        return result;
    }

    private static ZoneType safeZoneOf(Card card) {
        try {
            final forge.game.zone.Zone zone = card.getZone();
            return zone == null ? null : zone.getZoneType();
        } catch (Throwable t) {
            return null;
        }
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

    // ---- mana/cost execution: pre-floated pool only, no heuristic tapping ----
    //
    // WS202 L1: the pilot floats mana beforehand via explicit fixed-output mana
    // ability activations (each a framed PRIORITY option). Payment consumes only
    // from the floating pool through the native ManaPool deduction, framing
    // chooseManaFromPool when multiple equally-weighted mana could pay a shard.
    // No weighted auto-tap, no mid-payment mana-ability activation, no AI.
    // Pool-insufficient payment declines and the engine rolls the play back.

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        if (toPay == null) {
            return false;
        }
        if (toPay.isZero() || toPay.isNoCost()) {
            return true;
        }
        try {
            // Offering/Emerge/Convoke/Delve need dedicated selections; fail closed
            // rather than paying incorrectly. Pilot pre-floats all other mana.
            if (sa != null && (sa.isOffering() || sa.isEmerge())) {
                return false;
            }
            final forge.game.mana.ManaCostBeingPaid beingPaid =
                    new forge.game.mana.ManaCostBeingPaid(toPay);
            // Mirror the engine's X binding (PlaySpellAbility authority): retain
            // pre-announced X; compute from SVar only when the cost demands it.
            if (sa != null && costPartMana != null && costPartMana.getAmountOfX() > 0) {
                final Integer announced = sa.getXManaCostPaid();
                if (announced != null) {
                    beingPaid.setXManaCostPaid(announced.intValue(), sa.getXColor());
                }
            } else if (sa != null && sa.getXManaCostPaid() != null) {
                beingPaid.setXManaCostPaid(sa.getXManaCostPaid(), sa.getXColor());
            }
            final forge.game.card.Card source = sa == null ? null : sa.getHostCard();
            final CardCollection delvePlaceholder = new CardCollection();
            forge.game.cost.CostAdjustment.adjust(beingPaid, sa, player, delvePlaceholder, false,
                    effect);
            if (!delvePlaceholder.isEmpty()) {
                return false;
            }
            ManaConversionMatrix useMatrix = matrix;
            if (useMatrix == null) {
                useMatrix = new ManaConversionMatrix();
                useMatrix.restoreColorReplacements();
                forge.game.staticability.StaticAbilityManaConvert.manaConvert(useMatrix, player,
                        source, null);
            }
            // Pool-only: attempt native deduction; ambiguity frames chooseManaFromPool.
            final java.util.List<Mana> spent = new java.util.ArrayList<>();
            final boolean ok = player.getManaPool().payManaCostFromPool(beingPaid, sa, false,
                    spent);
            if (ok && source != null) {
                try {
                    source.setXManaCostPaidByColor(beingPaid.getXManaCostPaidByColor());
                } catch (Throwable t) {
                    // Non-fatal; payment already deducted.
                }
            }
            if (!ok) {
                final Map<String, String> details = new LinkedHashMap<>();
                details.put("actor", actorId());
                details.put("reason", "insufficient floating mana; pre-float required");
                session.audit("mana_payment_declined", details);
            }
            return ok;
        } catch (BridgeUnsupportedDecision e) {
            session.setLastExecutionError(e.getMessage());
            throw e;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt,
            ManaConversionMatrix matrix, boolean effect) {
        if (toPay == null) {
            return false;
        }
        if (toPay.isPaid()) {
            return true;
        }
        try {
            final java.util.List<Mana> spent = new java.util.ArrayList<>();
            final boolean ok = player.getManaPool().payManaCostFromPool(toPay, ability, false,
                    spent);
            if (!ok) {
                final Map<String, String> details = new LinkedHashMap<>();
                details.put("actor", actorId());
                details.put("reason", "insufficient floating mana; pre-float required");
                session.audit("mana_payment_declined", details);
            }
            return ok;
        } catch (BridgeUnsupportedDecision e) {
            session.setLastExecutionError(e.getMessage());
            throw e;
        } catch (Throwable t) {
            return false;
        }
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
        if (manaChoices == null || manaChoices.isEmpty()) {
            throw unsupported("chooseManaFromPool", "empty mana set");
        }
        if (manaChoices.size() == 1) {
            return manaChoices.get(0);
        }
        return parkSingleChoice(DecisionFrame.Kind.MANA_PAYMENT, "mana_payment", manaChoices,
                item -> {
                    try {
                        return "Mana [" + item.toString() + "]";
                    } catch (Throwable t) {
                        return "Mana";
                    }
                }, "MANA");
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
        // WS202: authoritative trigger ordering. Complete permutation set when
        // small; fail closed when too large to offer completely (no partial).
        if (activePlayerSAs.size() > 4) {
            throw unsupported("orderSimultaneousSa", "too many simultaneous abilities to order");
        }
        final List<List<SpellAbility>> perms = permutations(activePlayerSAs);
        final List<DecisionFrame.Option> options = new ArrayList<>(perms.size());
        for (List<SpellAbility> perm : perms) {
            final StringBuilder label = new StringBuilder("Order:");
            for (SpellAbility sa : perm) {
                label.append(' ');
                try {
                    label.append(sa.getHostCard().getName());
                } catch (Throwable t) {
                    label.append('?');
                }
                label.append('#').append(indexOfIdentity(activePlayerSAs, sa)).append(';');
            }
            options.add(DecisionFrame.payloadOption("trigger_order", label.toString(), null,
                    new ArrayList<>(perm), "ORDER"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.TRIGGER_ORDER, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final List<SpellAbility> chosen = (List<SpellAbility>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("order option without native payload");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("count", Integer.toString(chosen.size()));
        session.audit("trigger_ordered", details);
        return new ArrayList<>(chosen);
    }

    private static <T> List<List<T>> permutations(List<T> input) {
        final List<List<T>> result = new ArrayList<>();
        permuteInto(new ArrayList<>(input), 0, result);
        return result;
    }

    private static <T> void permuteInto(List<T> working, int start, List<List<T>> result) {
        if (start >= working.size()) {
            result.add(new ArrayList<>(working));
            return;
        }
        for (int i = start; i < working.size(); i++) {
            final T tmp = working.get(start);
            working.set(start, working.get(i));
            working.set(i, tmp);
            permuteInto(working, start + 1, result);
            final T back = working.get(start);
            working.set(start, working.get(i));
            working.set(i, back);
        }
    }

    private static <T> int indexOfIdentity(List<T> list, T target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                return i;
            }
        }
        return -1;
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
        // alternative-cost choice (e.g. Force of Will pitch vs hard-cast) and park a
        // COST_SELECTION frame with the engine's complete variant set.
        if (abilities.size() == 1) {
            return abilities.get(0);
        }
        if (abilities.isEmpty()) {
            throw unsupported("getAbilityToPlay", "empty variant set");
        }
        return parkSingleChoice(DecisionFrame.Kind.COST_SELECTION, "cost_selection", abilities,
                item -> {
                    try {
                        return "Cost option [" + item.getHostCard().getName() + ": "
                                + item.getPayCosts().toString() + "]";
                    } catch (Throwable t) {
                        return "Cost option";
                    }
                }, "SPELL_ABILITY");
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
        // WS202: mandatory static triggers auto-play (no discretion). Optional
        // triggers park TRIGGER_PLAY; Yes plays via the native no-stack pipeline
        // (which may recursively frame targets/costs), No declines.
        if (isMandatory) {
            return forge.game.player.PlaySpellAbility.playSpellAbilityNoStack(
                    this, player, wrapperAbility, false);
        }
        final String prompt;
        try {
            prompt = "Play triggered ability of "
                    + (host == null ? "?" : host.getName());
        } catch (Throwable t) {
            throw unsupported("playTrigger", "prompt unreadable");
        }
        final boolean yes = parkBinary(DecisionFrame.Kind.TRIGGER_PLAY, "trigger_play", prompt);
        if (!yes) {
            return false;
        }
        return forge.game.player.PlaySpellAbility.playSpellAbilityNoStack(
                this, player, wrapperAbility, false);
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        throw unsupported("playSaFromPlayEffect", "play-from-effect is not represented");
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        throw unsupported("chooseCardsYouWonToAddToDeck", "ante card choice is not represented");
    }

    // WS191: aa5c Rules-Core replaced the raw-map assignCombatDamage boundary with
    // the Core-owned CombatDamageDecisionView/CombatDamageSelection choice. The
    // bridge does not represent combat damage assignment and fails closed here.
    @Override
    public CombatDamageSelection chooseCombatDamage(final CombatDamageDecisionView decision) {
        throw unsupported("chooseCombatDamage", "combat damage assignment is not represented");
    }

    // WS191: aa5c Rules-Core added the Core-owned noncombat amount-distribution
    // choice. The bridge does not represent it and fails closed here.
    @Override
    public AmountDistributionSelection chooseAmountDistribution(
            final AmountDistributionDecisionView decision) {
        throw unsupported("chooseAmountDistribution", "amount distribution is not represented");
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
        final List<T> legal = new ArrayList<>();
        if (optionList != null) {
            for (T item : optionList) {
                if (item != null) {
                    legal.add(item);
                }
            }
        }
        if (legal.isEmpty()) {
            if (isOptional) {
                return null;
            }
            throw unsupported("chooseSingleEntityForEffect", "empty entity set");
        }
        if (legal.size() == 1 && !isOptional) {
            return legal.get(0);
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(legal.size() + 1);
        for (T item : legal) {
            options.add(DecisionFrame.payloadOption("entity", entityLabel(item,
                    title), entitySourceName(item), item, "GAME_ENTITY"));
        }
        if (isOptional) {
            options.add(DecisionFrame.confirmOption("entity", "Decline selection", false));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.COPY_CHOICE, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        if (answer.selected.confirmValue != null && !answer.selected.confirmValue.booleanValue()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        final T chosen = (T) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("entity option without native payload");
        }
        return chosen;
    }

    private static String entityLabel(GameEntity entity, String title) {
        final String base = title == null || title.isEmpty() ? "Choose" : title;
        try {
            if (entity instanceof Card) {
                return base + " [" + ((Card) entity).getName() + "]";
            }
            if (entity instanceof Player) {
                return base + " [player]";
            }
            return base + " [" + entity.toString() + "]";
        } catch (Throwable t) {
            return base;
        }
    }

    private static String entitySourceName(GameEntity entity) {
        try {
            if (entity instanceof Card) {
                return ((Card) entity).getName();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min,
            int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer,
            Map<String, Object> params) {
        final List<T> legal = new ArrayList<>();
        if (optionList != null) {
            for (T item : optionList) {
                if (item != null) {
                    legal.add(item);
                }
            }
        }
        if (legal.isEmpty()) {
            if (min <= 0) {
                return new ArrayList<>();
            }
            throw unsupported("chooseEntitiesForEffect", "empty entity set");
        }
        // Bounded subset enumeration preserves the complete legal set.
        final List<List<T>> subsets = enumerateSubsets(legal, min, max);
        if (subsets.isEmpty()) {
            throw unsupported("chooseEntitiesForEffect", "no valid subset");
        }
        if (subsets.size() == 1) {
            return new ArrayList<>(subsets.get(0));
        }
        if (subsets.size() > 128) {
            throw unsupported("chooseEntitiesForEffect", "too many combinations");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size());
        for (List<T> subset : subsets) {
            final StringBuilder label = new StringBuilder(
                    title == null || title.isEmpty() ? "Choose" : title);
            label.append(" [");
            for (T item : subset) {
                label.append(entityLabel(item, "").replaceFirst("^Choose ?", ""));
                label.append(';');
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("entities", label.toString(), null,
                    new ArrayList<>(subset), "GAME_ENTITY_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.GENERIC_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final List<T> chosen = (List<T>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("entities option without native payload");
        }
        return new ArrayList<>(chosen);
    }

    private static <T> List<List<T>> enumerateSubsets(List<T> legal, int min, int max) {
        final List<List<T>> result = new ArrayList<>();
        final int n = legal.size();
        final int total = 1 << n;
        // Guard against explosion before enumerating.
        if (n > 12) {
            return result;
        }
        for (int mask = 0; mask < total; mask++) {
            final int bits = Integer.bitCount(mask);
            if (bits < min || bits > max) {
                continue;
            }
            final List<T> subset = new ArrayList<>(bits);
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(legal.get(i));
                }
            }
            result.add(subset);
        }
        return result;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa,
            String title, int num, Map<String, Object> params) {
        if (spells == null || spells.isEmpty()) {
            throw unsupported("chooseSpellAbilitiesForEffect", "empty spell set");
        }
        if (spells.size() == num && num == 1) {
            final List<SpellAbility> single = new ArrayList<>(1);
            single.add(spells.get(0));
            return single;
        }
        final List<List<SpellAbility>> subsets = enumerateSubsets(spells, num, num);
        if (subsets.isEmpty()) {
            throw unsupported("chooseSpellAbilitiesForEffect", "no valid subset");
        }
        if (subsets.size() == 1) {
            return new ArrayList<>(subsets.get(0));
        }
        if (subsets.size() > 128) {
            throw unsupported("chooseSpellAbilitiesForEffect", "too many combinations");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size());
        for (List<SpellAbility> subset : subsets) {
            final StringBuilder label = new StringBuilder(
                    title == null || title.isEmpty() ? "Choose spells" : title);
            label.append(" [");
            for (SpellAbility item : subset) {
                try {
                    label.append(item.getHostCard().getName()).append(';');
                } catch (Throwable t) {
                    label.append("?;");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("spells", label.toString(), null,
                    new ArrayList<>(subset), "SPELL_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.COPY_CHOICE, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final List<SpellAbility> chosen = (List<SpellAbility>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("spells option without native payload");
        }
        return new ArrayList<>(chosen);
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa,
            String title, Map<String, Object> params) {
        if (spells == null || spells.isEmpty()) {
            throw unsupported("chooseSingleSpellForEffect", "empty spell set");
        }
        if (spells.size() == 1) {
            return spells.get(0);
        }
        return parkSingleChoice(DecisionFrame.Kind.COPY_CHOICE, "copy_spell", spells, item -> {
            try {
                return (title == null ? "Choose spell" : title) + " [" + item.getHostCard().getName()
                        + "]";
            } catch (Throwable t) {
                return "Choose spell";
            }
        }, "SPELL_ABILITY");
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        // WS202: authoritative binary confirm. Commander 903.9a/903.9b movement
        // (ChangeZoneToAltDestination) parks COMMANDER_MOVE; all other modes park
        // GENERIC_CONFIRM. Custom button labels are not yet represented and fail
        // closed to avoid mapping ambiguity; empty options (Yes/No) are complete.
        if (options != null && !options.isEmpty()) {
            throw unsupported("confirmAction", "custom confirm options not represented");
        }
        final String prompt;
        try {
            prompt = (message == null || message.isEmpty() ? "Confirm action" : message)
                    + " [" + (mode == null ? "?" : mode.name()) + "]";
        } catch (Throwable t) {
            throw unsupported("confirmAction", "prompt unreadable");
        }
        final DecisionFrame.Kind kind;
        final String actionType;
        if (mode == PlayerActionConfirmMode.ChangeZoneToAltDestination) {
            kind = DecisionFrame.Kind.COMMANDER_MOVE;
            actionType = "commander_move";
        } else {
            kind = DecisionFrame.Kind.GENERIC_CONFIRM;
            actionType = "confirm";
        }
        return parkBinary(kind, actionType, prompt);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string,
            int bid, Player winner) {
        final String prompt;
        try {
            prompt = (string == null ? "Confirm bid" : string) + " [bid=" + bid + "]";
        } catch (Throwable t) {
            throw unsupported("confirmBidAction", "prompt unreadable");
        }
        return parkBinary(DecisionFrame.Kind.GENERIC_CONFIRM, "confirm_bid", prompt);
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
            GameEntity affected, String question) {
        final String prompt;
        try {
            prompt = question == null || question.isEmpty() ? "Apply replacement effect"
                    : question;
        } catch (Throwable t) {
            throw unsupported("confirmReplacementEffect", "prompt unreadable");
        }
        return parkBinary(DecisionFrame.Kind.REPLACEMENT_CONFIRM, "replacement_confirm", prompt);
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message,
            String logic) {
        final String prompt;
        try {
            prompt = message == null || message.isEmpty() ? "Apply static ability" : message;
        } catch (Throwable t) {
            throw unsupported("confirmStaticApplication", "prompt unreadable");
        }
        return parkBinary(DecisionFrame.Kind.STATIC_CHOICE, "static_confirm", prompt);
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        final String prompt;
        try {
            prompt = "Use triggered ability of "
                    + (sa == null || sa.getHostCard() == null ? "?" : sa.getHostCard().getName());
        } catch (Throwable t) {
            throw unsupported("confirmTrigger", "prompt unreadable");
        }
        return parkBinary(DecisionFrame.Kind.TRIGGER_PLAY, "trigger_play", prompt);
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
        if (possible == null || possible.isEmpty()) {
            throw unsupported("chooseModeForAbility", "empty mode set");
        }
        if (!allowRepeat && min == num && num == possible.size()) {
            return new ArrayList<>(possible);
        }
        // WS202: enumerate all valid mode subsets (bounded). Each subset is one
        // authoritative option; submission selects exactly one subset.
        final List<List<AbilitySub>> subsets = enumerateModeSubsets(possible, min, num,
                allowRepeat);
        if (subsets.isEmpty()) {
            throw unsupported("chooseModeForAbility", "no valid mode subset");
        }
        if (subsets.size() == 1) {
            return new ArrayList<>(subsets.get(0));
        }
        if (subsets.size() > 128) {
            throw unsupported("chooseModeForAbility", "too many mode combinations");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size());
        for (List<AbilitySub> subset : subsets) {
            final StringBuilder label = new StringBuilder("Modes:");
            for (AbilitySub sub : subset) {
                label.append(' ');
                try {
                    final String desc = sub.toUnsuppressedString();
                    label.append(desc == null || desc.isEmpty() ? "?" : desc);
                } catch (Throwable t) {
                    label.append('?');
                }
                label.append(';');
            }
            options.add(DecisionFrame.payloadOption("mode", label.toString(), null,
                    new ArrayList<>(subset), "MODE_SUBSET"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.MODE_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final List<AbilitySub> chosen = (List<AbilitySub>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("mode option without native payload");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("count", Integer.toString(chosen.size()));
        session.audit("modes_chosen", details);
        return new ArrayList<>(chosen);
    }

    private static List<List<AbilitySub>> enumerateModeSubsets(List<AbilitySub> possible, int min,
            int num, boolean allowRepeat) {
        final List<List<AbilitySub>> result = new ArrayList<>();
        if (!allowRepeat) {
            final int n = possible.size();
            final int total = 1 << n;
            for (int mask = 0; mask < total; mask++) {
                final int bits = Integer.bitCount(mask);
                if (bits < min || bits > num) {
                    continue;
                }
                final List<AbilitySub> subset = new ArrayList<>(bits);
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << i)) != 0) {
                        subset.add(possible.get(i));
                    }
                }
                result.add(subset);
            }
        } else {
            // With repeats: bounded enumeration by size then compositions.
            // Cards with repeatable modes are rare; bound total to avoid explosion.
            for (int size = min; size <= num; size++) {
                enumerateWithRepeats(possible, size, 0, new ArrayList<>(), result, 128);
                if (result.size() > 128) {
                    break;
                }
            }
        }
        return result;
    }

    private static void enumerateWithRepeats(List<AbilitySub> possible, int size, int start,
            List<AbilitySub> working, List<List<AbilitySub>> result, int cap) {
        if (result.size() >= cap) {
            return;
        }
        if (working.size() == size) {
            result.add(new ArrayList<>(working));
            return;
        }
        for (int i = start; i < possible.size(); i++) {
            working.add(possible.get(i));
            enumerateWithRepeats(possible, size, i, working, result, cap);
            working.remove(working.size() - 1);
            if (result.size() >= cap) {
                return;
            }
        }
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return parkIntRange(DecisionFrame.Kind.NUMBER_CHOICE, "number", "Choose number", min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword,
            String prompt, int max) {
        return parkIntRange(DecisionFrame.Kind.NUMBER_CHOICE, "number",
                prompt == null ? "Choose number" : prompt, 0, Math.max(0, max));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return parkIntRange(DecisionFrame.Kind.NUMBER_CHOICE, "number",
                title == null ? "Choose number" : title, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (values == null || values.isEmpty()) {
            throw unsupported("chooseNumber", "empty value set");
        }
        if (values.size() == 1) {
            return values.get(0).intValue();
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(values.size());
        for (Integer value : values) {
            options.add(DecisionFrame.intOption("number",
                    (title == null ? "Choose" : title) + " [" + value + "]", value.intValue()));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.NUMBER_CHOICE, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        if (answer.selected.intValue == null) {
            throw new IllegalStateException("number option without int value");
        }
        return answer.selected.intValue.intValue();
    }

    private int parkIntRange(DecisionFrame.Kind kind, String actionType, String title,
            int min, int max) {
        if (max < min) {
            throw unsupported("chooseNumber", "inverted range");
        }
        if (min == max) {
            return min;
        }
        final long size = (long) max - (long) min + 1L;
        if (size > 128L) {
            throw unsupported("chooseNumber", "numeric range too large to offer completely");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>((int) size);
        for (int value = min; value <= max; value++) {
            options.add(DecisionFrame.intOption(actionType, title + " [" + value + "]", value));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(kind, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        if (answer.selected.intValue == null) {
            throw new IllegalStateException("number option without int value");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("choice", answer.selected.intValue.toString());
        session.audit("number_chosen", details);
        return answer.selected.intValue.intValue();
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        final int value = parkIntRange(DecisionFrame.Kind.X_ANNOUNCE, "x_announce",
                "Announce " + (announce == null ? "X" : announce), min, max);
        return Integer.valueOf(value);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice,
            Boolean defaultChoice) {
        // WS202: no default authority. The defaultChoice hint is never used to
        // decide; both branches are always offered and the pilot selects.
        final String prompt;
        try {
            prompt = (question == null || question.isEmpty() ? "Choose" : question)
                    + " [" + (kindOfChoice == null ? "?" : kindOfChoice.name()) + "]";
        } catch (Throwable t) {
            throw unsupported("chooseBinary", "prompt unreadable");
        }
        return parkBinary(DecisionFrame.Kind.BINARY_CHOICE, "binary", prompt);
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        final String prompt;
        try {
            prompt = "Coin flip call [" + (call ? "heads" : "tails") + "]";
        } catch (Throwable t) {
            throw unsupported("chooseFlipResult", "prompt unreadable");
        }
        return parkBinary(DecisionFrame.Kind.BINARY_CHOICE, "flip", prompt);
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        if (colors == null) {
            throw unsupported("chooseColor", "null color set");
        }
        if (colors.countColors() == 0) {
            return 0;
        }
        if (colors.countColors() == 1) {
            return colors.getColor();
        }
        final List<forge.card.MagicColor.Color> legal = new ArrayList<>(colors.getOrderedColors());
        final forge.card.MagicColor.Color chosen = parkSingleChoice(
                DecisionFrame.Kind.COLOR_CHOICE, "color", legal,
                item -> (message == null ? "Choose color" : message) + " [" + item.getName() + "]",
                "COLOR");
        return chosen.getColorMask();
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card card, ColorSet colors) {
        if (colors == null) {
            throw unsupported("chooseColorAllowColorless", "null color set");
        }
        if (1 + colors.countColors() == 1) {
            return 0;
        }
        final List<forge.card.MagicColor.Color> legal = new ArrayList<>(colors.getOrderedColors());
        if (!colors.isColorless()) {
            legal.add(forge.card.MagicColor.Color.COLORLESS);
        }
        if (legal.size() == 1) {
            return legal.get(0).getColorMask();
        }
        final forge.card.MagicColor.Color chosen = parkSingleChoice(
                DecisionFrame.Kind.COLOR_CHOICE, "color", legal,
                item -> (message == null ? "Choose color" : message) + " [" + item.getName() + "]",
                "COLOR");
        return chosen.getColorMask();
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        if (options == null) {
            throw unsupported("chooseColors", "null color set");
        }
        final List<forge.card.MagicColor.Color> legal = new ArrayList<>(options.getOrderedColors());
        if (legal.isEmpty()) {
            if (min <= 0) {
                return ColorSet.fromMask(0);
            }
            throw unsupported("chooseColors", "empty color set");
        }
        final List<List<forge.card.MagicColor.Color>> subsets = enumerateSubsets(legal, min, max);
        if (subsets.isEmpty()) {
            throw unsupported("chooseColors", "no valid subset");
        }
        if (subsets.size() == 1) {
            byte mask = 0;
            for (forge.card.MagicColor.Color b : subsets.get(0)) {
                mask |= b.getColorMask();
            }
            return ColorSet.fromMask(mask);
        }
        if (subsets.size() > 128) {
            throw unsupported("chooseColors", "too many combinations");
        }
        final List<DecisionFrame.Option> frameOptions = new ArrayList<>(subsets.size());
        for (List<forge.card.MagicColor.Color> subset : subsets) {
            final StringBuilder label = new StringBuilder(
                    message == null ? "Choose colors" : message);
            label.append(" [");
            for (forge.card.MagicColor.Color b : subset) {
                label.append(b.getName()).append(';');
            }
            label.append(']');
            frameOptions.add(DecisionFrame.payloadOption("colors", label.toString(), null,
                    new ArrayList<>(subset), "COLOR_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.COLOR_CHOICE, player,
                DecisionFrame.Status.SUPPORTED, "", frameOptions);
        @SuppressWarnings("unchecked")
        final List<forge.card.MagicColor.Color> chosen =
                (List<forge.card.MagicColor.Color>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("colors option without native payload");
        }
        byte mask = 0;
        for (forge.card.MagicColor.Color b : chosen) {
            mask |= b.getColorMask();
        }
        return ColorSet.fromMask(mask);
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
        if (options == null || options.isEmpty()) {
            throw unsupported("chooseCounterType", "empty counter set");
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        return parkSingleChoice(DecisionFrame.Kind.GENERIC_SELECTION, "counter", options, item -> {
            try {
                return (prompt == null ? "Choose counter" : prompt) + " [" + item.getName() + "]";
            } catch (Throwable t) {
                return "Choose counter";
            }
        }, "COUNTER_TYPE");
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt,
            Card targetCard) {
        if (options == null || options.isEmpty()) {
            throw unsupported("chooseKeywordForPump", "empty keyword set");
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        return parkSingleChoice(DecisionFrame.Kind.GENERIC_SELECTION, "keyword", options,
                item -> (prompt == null ? "Choose keyword" : prompt) + " [" + item + "]", "STRING");
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers == null || possibleReplacers.isEmpty()) {
            throw unsupported("chooseSingleReplacementEffect", "empty replacer set");
        }
        if (possibleReplacers.size() == 1) {
            return possibleReplacers.get(0);
        }
        return parkSingleChoice(DecisionFrame.Kind.REPLACEMENT_ORDER, "replacement_order",
                possibleReplacers, re -> {
                    try {
                        final String desc = re.getDescription();
                        return desc == null || desc.isEmpty() ? re.toString() : desc;
                    } catch (Throwable t) {
                        return "replacement";
                    }
                }, "REPLACEMENT_EFFECT");
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        if (possibleReplacers == null || possibleReplacers.isEmpty()) {
            throw unsupported("chooseSingleStaticAbility", "empty static set");
        }
        if (possibleReplacers.size() == 1) {
            return possibleReplacers.get(0);
        }
        return parkSingleChoice(DecisionFrame.Kind.STATIC_CHOICE, "static_choice",
                possibleReplacers, st -> {
                    try {
                        return st.getHostCard().getName();
                    } catch (Throwable t) {
                        return "static";
                    }
                }, "STATIC_ABILITY");
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
        final List<Card> legal = new ArrayList<>();
        if (fetchList != null) {
            for (Card card : fetchList) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.isEmpty()) {
            if (isOptional) {
                return null;
            }
            throw unsupported("chooseSingleCardForZoneChange", "empty fetch set");
        }
        if (legal.size() == 1 && !isOptional) {
            return legal.get(0);
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(legal.size() + 1);
        for (Card card : legal) {
            final String label;
            try {
                label = (selectPrompt == null ? "Choose card" : selectPrompt) + " [" + card.getName()
                        + "]";
            } catch (Throwable t) {
                throw unsupported("chooseSingleCardForZoneChange", "label unreadable");
            }
            final String sourceName;
            try {
                sourceName = card.getName();
            } catch (Throwable t) {
                throw unsupported("chooseSingleCardForZoneChange", "name unreadable");
            }
            options.add(DecisionFrame.payloadOption("search", label, sourceName, card, "CARD"));
        }
        if (isOptional) {
            options.add(DecisionFrame.confirmOption("search", "Decline selection", false));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.SEARCH_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        if (answer.selected.confirmValue != null && !answer.selected.confirmValue.booleanValue()) {
            return null;
        }
        final Card chosen = (Card) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("search option without native payload");
        }
        return chosen;
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal,
            String selectPrompt, Player decider) {
        final List<Card> legal = new ArrayList<>();
        if (fetchList != null) {
            for (Card card : fetchList) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.isEmpty()) {
            if (min <= 0) {
                return new ArrayList<>();
            }
            throw unsupported("chooseCardsForZoneChange", "empty fetch set");
        }
        final List<List<Card>> subsets = enumerateSubsets(legal, min, max);
        if (subsets.isEmpty()) {
            throw unsupported("chooseCardsForZoneChange", "no valid subset");
        }
        if (subsets.size() == 1) {
            return new ArrayList<>(subsets.get(0));
        }
        if (subsets.size() > 128) {
            throw unsupported("chooseCardsForZoneChange", "too many combinations");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size());
        for (List<Card> subset : subsets) {
            final StringBuilder label = new StringBuilder(
                    selectPrompt == null ? "Choose cards" : selectPrompt);
            label.append(" [");
            for (Card card : subset) {
                try {
                    label.append(card.getName()).append(';');
                } catch (Throwable t) {
                    throw unsupported("chooseCardsForZoneChange", "label unreadable");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("search", label.toString(), null,
                    new ArrayList<>(subset), "CARD_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.SEARCH_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final List<Card> chosen = (List<Card>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("search option without native payload");
        }
        return new ArrayList<>(chosen);
    }
}
