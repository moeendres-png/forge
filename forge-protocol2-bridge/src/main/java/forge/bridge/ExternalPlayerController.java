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
import forge.game.combat.CombatDamageDecisionView;
import forge.game.combat.CombatDamageSelection;
import forge.game.player.AmountDistributionDecisionView;
import forge.game.player.AmountDistributionSelection;
import forge.game.player.DividedAllocationDecision;
import forge.game.player.DividedAllocationDecisionView;
import forge.game.player.DividedAllocationSelection;
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
 * <p>Priority, mulligan, starting-player, mana/cost, target, mode, number,
 * combat, trigger, replacement, copy, search, commander-move and concession
 * callbacks rendezvous with the protocol thread through parked
 * {@link DecisionFrame}s. Forge rules/RNG selects which controller receives
 * the starting-player choice; that chooser is offered the complete native
 * player set with no preset seat and no default. Every discretionary option set
 * offered is the engine's own complete legal set for that callback; anything
 * that cannot be offered completely fails closed via
 * {@link BridgeUnsupportedDecision} so an unrepresented decision aborts loudly
 * instead of being answered by AI, defaults, first-option, randomness or
 * silent pass.
 *
 * <p>Execution uses the real {@link PlaySpellAbility} pipeline and the real
 * combat/cost pipelines; the engine keeps full legality, cost and timing
 * authority, including rollback on failure and re-prompt on invalid combat
 * declarations.
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
        return parkChoices(kind, actionType, legal, labeler, payloadKind);
    }

    /**
     * WS202: parks even a lone option. Used where the Core has already applied
     * forced progress, so anything reaching the bridge is discretionary and must
     * be externally selected with full revision/actor/option binding.
     */
    private <T> T parkChoices(DecisionFrame.Kind kind, String actionType,
            List<T> legal, java.util.function.Function<T, String> labeler, String payloadKind) {
        if (legal == null || legal.isEmpty()) {
            throw unsupported("parkChoices", "empty legal set cannot be framed");
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
                // Preserve a specific Rules/Core diagnostic (e.g., divided-allocation
                // native validation) when one is already bound; otherwise record the
                // generic rollback signal. Either way the pilot sees executionOk false
                // with no Rules mutation.
                if (session.getLastExecutionError() == null
                        || session.getLastExecutionError().isEmpty()) {
                    session.setLastExecutionError(
                            "engine declined the submitted option (rolled back)");
                }
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
     * alternative costs (COST_SELECTION frames), pre-floated pool mana payment
     * (MANA_PAYMENT frames), choice mana outputs (COLOR_CHOICE/MANA_PAYMENT
     * frames via the Core-owned ManaEffect paths), single- and multi-target
     * selection (TARGET_SELECTION frames) and sacrifice/discard/exile/pay-life
     * costs (COST_SELECTION frames) are now representable. WS217: chooser-divided
     * allocation travels through the native Core-owned divided-allocation seam
     * (DIVIDED_ALLOCATION frames, CR 601.2d) with native validation, so it no
     * longer blocks offering. AnnounceType, optional costs and the remaining
     * non-framed cost parts still fail closed.
     */
    static String classifyComplex(SpellAbility sa) {
        if (sa.usesTargeting() && !isSingleTargetRepresentable(sa)) {
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
                if (part instanceof forge.game.cost.CostSacrifice) {
                    continue;
                }
                if (part instanceof forge.game.cost.CostDiscard) {
                    continue;
                }
                if (part instanceof forge.game.cost.CostExile) {
                    continue;
                }
                if (part instanceof forge.game.cost.CostPayLife) {
                    continue;
                }
                return "COMPLEX_COST:" + part.getClass().getSimpleName();
            }
        }
        return null;
    }

    /**
     * WS217 target representability: any min..max combination is framed, including
     * chooser-divided allocation, whose exact vector now travels through the native
     * Core-owned divided-allocation seam (CR 601.2d) with native validation.
     * Any uncertainty resolves to not-representable.
     */
    static boolean isSingleTargetRepresentable(SpellAbility sa) {
        try {
            final int min = sa.getMinTargets();
            final int max = sa.getMaxTargets();
            return max >= min && min >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * WS202 mana boundary. Payment from the pre-floated pool is representable via
     * native ManaPool deduction with framed chooseManaFromPool on ambiguity; no
     * heuristic auto-tap is reachable. Choice mana outputs (any/combo/special,
     * including previously-chosen colors) resolve through the Core-owned
     * ManaEffect paths into framed chooseColor/specifyManaCombo decisions, so
     * they no longer block offering the ability. Only an unreadable mana
     * structure fails closed.
     */
    private static String classifyMana(SpellAbility sa) {
        // WS202: nonzero mana payment is representable via pre-floated pool
        // deduction (see payManaCost/applyManaToCost). No payment blocker here.
        if (sa.isManaAbility()) {
            SpellAbility tail = sa;
            while (tail != null) {
                try {
                    tail.getManaPart();
                } catch (Throwable t) {
                    return "MANA_OUTPUT_CHOICE";
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
            final String base = sa.getHostCard().getName() + " [" + actionTypeOf(sa) + "]";
            // WS202: alternative-cost variants (Force pitch vs hard cast) are
            // distinct native candidates with identical names; the engine-owned
            // pay-cost text keeps every offered route discriminable so the
            // pilot selects a real route, never an ambiguous duplicate. Any
            // unreadable cost falls back to the bare label; enumeration never
            // fails here.
            try {
                final Cost payCosts = sa.getPayCosts();
                if (payCosts != null) {
                    final String costText = payCosts.toSimpleString();
                    if (costText != null && !costText.isEmpty()) {
                        return base + " (" + costText + ")";
                    }
                }
            } catch (Throwable ignored) {
                // Fall through to the bare label.
            }
            return base;
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

    // ---- mana/cost execution: pool-first, framed mid-payment taps ----
    //
    // WS202 L1: the pilot floats mana beforehand via explicit fixed-output mana
    // ability activations (each a framed PRIORITY option) and/or taps mana
    // sources mid-payment through parked MANA_PAYMENT options (Propaganda-style
    // combat taxes and other costs collected outside priority). Payment consumes
    // from the floating pool through the native ManaPool deduction, framing
    // chooseManaFromPool on ambiguity; tapped sources resolve through the real
    // play pipeline (which itself frames choice-mana outputs). No weighted
    // auto-tap, no AI. Pool-and-taps-insufficient payment declines (explicit
    // Decline option) and the engine rolls the play back.

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
            // Pool-first, then framed mid-payment taps for any shortfall.
            if (payFromPoolWithTaps(beingPaid, sa)) {
                if (source != null) {
                    try {
                        source.setXManaCostPaidByColor(beingPaid.getXManaCostPaidByColor());
                    } catch (Throwable t) {
                        // Non-fatal; payment already deducted.
                    }
                }
                return true;
            }
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("actor", actorId());
            details.put("reason", "insufficient floating mana and no further taps taken");
            session.audit("mana_payment_declined", details);
            return false;
        } catch (BridgeUnsupportedDecision e) {
            session.setLastExecutionError(e.getMessage());
            throw e;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * WS202: native pool deduction, then authoritative mid-payment taps. Each
     * offered tap is a complete native mana ability (Core-filtered playable set)
     * the pilot activates through the real pipeline; Decline (or an empty/failed
     * tap set) returns false so the engine rolls back with no state fabricated.
     * Partial pool deductions persist across loop iterations exactly as the
     * engine leaves them (spent mana stays spent).
     */
    private boolean payFromPoolWithTaps(forge.game.mana.ManaCostBeingPaid beingPaid,
            SpellAbility sa) {
        final java.util.Set<SpellAbility> tried =
                Collections.newSetFromMap(new IdentityHashMap<SpellAbility, Boolean>());
        for (int guard = 0; guard < 24; guard++) {
            final java.util.List<Mana> spent = new java.util.ArrayList<>();
            try {
                if (player.getManaPool().payManaCostFromPool(beingPaid, sa, false, spent)
                        && beingPaid.isPaid()) {
                    return true;
                }
            } catch (BridgeUnsupportedDecision e) {
                session.setLastExecutionError(e.getMessage());
                throw e;
            } catch (Throwable t) {
                return false;
            }
            if (beingPaid.isPaid()) {
                return true;
            }
            final List<SpellAbility> taps = untappedManaAbilities(tried);
            if (taps.isEmpty()) {
                return false;
            }
            final List<DecisionFrame.Option> options = new ArrayList<>(taps.size() + 1);
            for (SpellAbility ability : taps) {
                final String hostName;
                try {
                    hostName = ability.getHostCard().getName();
                } catch (Throwable t) {
                    throw unsupported("payManaCost", "tap source unreadable");
                }
                options.add(DecisionFrame.payloadOption("tap_mana_source",
                        "Tap " + hostName + " for mana", hostName, ability, "SPELL_ABILITY"));
            }
            options.add(DecisionFrame.confirmOption("tap_mana_source",
                    "Decline to tap (leave cost unpaid)", false));
            final BridgeSession.FrameAnswer answer = session.parkFrame(
                    DecisionFrame.Kind.MANA_PAYMENT, player,
                    DecisionFrame.Status.SUPPORTED, "", options);
            if (answer.selected.confirmValue != null
                    && !answer.selected.confirmValue.booleanValue()) {
                final Map<String, String> details = new LinkedHashMap<>();
                details.put("actor", actorId());
                details.put("choice", "declined");
                session.audit("mana_tap_declined", details);
                return false;
            }
            final SpellAbility chosen = (SpellAbility) answer.selected.nativePayload;
            if (chosen == null) {
                throw new IllegalStateException("tap option without native payload");
            }
            tried.add(chosen);
            final boolean activated;
            try {
                activated = PlaySpellAbility.playSpellAbility(this, player, chosen);
            } catch (BridgeUnsupportedDecision e) {
                session.setLastExecutionError(e.getMessage());
                throw e;
            } catch (Throwable t) {
                return false;
            }
            if (!activated) {
                return false;
            }
        }
        return false;
    }

    /**
     * Core-filtered playable mana abilities on the payer's battlefield the pilot
     * may activate mid-payment. Enumeration failure degrades to no taps (the
     * payment then declines and rolls back) with an audit reason, never to a
     * partial or fabricated set.
     */
    private List<SpellAbility> untappedManaAbilities(java.util.Set<SpellAbility> tried) {
        final List<SpellAbility> result = new ArrayList<>();
        final List<Card> battlefield;
        try {
            battlefield = new ArrayList<>(player.getCardsIn(ZoneType.Battlefield));
        } catch (Throwable t) {
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("actor", actorId());
            details.put("reason", "battlefield unreadable");
            session.audit("mana_tap_unavailable", details);
            return result;
        }
        for (Card card : battlefield) {
            if (card == null) {
                continue;
            }
            final List<SpellAbility> abilities;
            try {
                abilities = card.getAllPossibleAbilities(player, true);
            } catch (Throwable t) {
                final Map<String, String> details = new LinkedHashMap<>();
                details.put("actor", actorId());
                details.put("reason", "ability enumeration failed");
                session.audit("mana_tap_unavailable", details);
                return new ArrayList<>();
            }
            for (SpellAbility ability : abilities) {
                if (ability == null || tried.contains(ability)) {
                    continue;
                }
                try {
                    if (ability.isManaAbility()) {
                        result.add(ability);
                    }
                } catch (Throwable t) {
                    final Map<String, String> details = new LinkedHashMap<>();
                    details.put("actor", actorId());
                    details.put("reason", "ability unreadable");
                    session.audit("mana_tap_unavailable", details);
                    return new ArrayList<>();
                }
            }
        }
        return result;
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
            if (payFromPoolWithTaps(toPay, ability)) {
                return true;
            }
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("actor", actorId());
            details.put("reason", "insufficient floating mana and no further taps taken");
            session.audit("mana_payment_declined", details);
            return false;
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
        // Optional cost-part confirms with real discretion (pay life/energy, exile
        // all/library, discard hand/random) are framed inline by the specific
        // BridgeCostDecisionMaker visits or cost branches, never here, so this
        // stay-closed gate only guards prompts with no represented shape.
        return false;
    }

    /**
     * WS202: exact-count cost-card selection shared by the casting pipeline
     * ({@code chooseCardsForCost}) and the cost-decision visits. Returns the
     * chosen cards, or null on pilot decline / insufficient legals / incompletely
     * offerable sets (engine rolls back; the decline/audit records the reason).
     */
    CardCollection frameCostCards(String actionType, String prompt, CardCollectionView legalView,
            int count, boolean cancelAllowed) {
        final List<Card> legal = new ArrayList<>();
        if (legalView != null) {
            for (Card card : legalView) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.size() < count) {
            return null;
        }
        final List<List<Card>> subsets = enumerateSubsets(legal, count, count);
        if (subsets.isEmpty() || subsets.size() > 128) {
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("actor", actorId());
            details.put("action", actionType);
            details.put("reason", subsets.isEmpty() ? "no valid set" : "too many sets");
            session.audit("cost_unrepresentable", details);
            return null;
        }
        if (subsets.size() == 1 && !cancelAllowed) {
            return new CardCollection(subsets.get(0));
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size() + 1);
        for (List<Card> subset : subsets) {
            final StringBuilder label = new StringBuilder(
                    prompt == null || prompt.isEmpty() ? "Choose" : prompt);
            label.append(" [");
            for (Card card : subset) {
                try {
                    label.append(card.getName()).append(';');
                } catch (Throwable t) {
                    label.append("?;");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption(actionType, label.toString(), null,
                    new CardCollection(subset), "CARD_LIST"));
        }
        if (cancelAllowed) {
            options.add(DecisionFrame.confirmOption(actionType, "Decline payment", false));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.COST_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        if (answer.selected.confirmValue != null && !answer.selected.confirmValue.booleanValue()) {
            final Map<String, String> details = new LinkedHashMap<>();
            details.put("actor", actorId());
            details.put("action", actionType);
            details.put("choice", "declined");
            session.audit("cost_declined", details);
            return null;
        }
        final CardCollection chosen = (CardCollection) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("cost option without native payload");
        }
        return chosen;
    }

    /**
     * WS202: binary cost confirm shared by cost-decision visits (pay life and
     * similar optional payments). No ambient default is consulted.
     */
    boolean frameCostConfirm(String actionType, String prompt) {
        return parkBinary(DecisionFrame.Kind.COST_SELECTION, actionType,
                prompt == null || prompt.isEmpty() ? "Pay cost" : prompt);
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        // WS202: route combat taxes through the real cost pipeline so mana
        // payments use pre-floated pool framing and card selections are parked.
        // Engine validates and removes unpaid attackers/blockers natively.
        try {
            return PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, prompt);
        } catch (BridgeUnsupportedDecision e) {
            session.setLastExecutionError(e.getMessage());
            throw e;
        }
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        // WS202: mirror the Human default without the ChooseCostOrder full-control
        // flag (which the bridge never sets): scripted order stands, no discretion.
        return costs;
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa,
            CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        // WS202: authoritative exact-count selection from the engine-supplied
        // legal list. Decline returns null so the engine rolls the play back.
        final CardCollection chosen = frameCostCards("cost_cards",
                prompt == null ? "Choose cards for cost" : prompt, optionList, amount,
                isOptional);
        if (chosen == null) {
            return null;
        }
        return chosen;
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
        // WS202: Core-owned combo-mana choice (ManaEffect). Every color-count map
        // over the engine-supplied options summing to the required amount (each at
        // most once when colors must differ) is one authoritative option, bounded
        // to avoid partial sets. Mirrors the AI's sequential chooseColor shape but
        // parks the complete combination instead of deciding tactically.
        if (colorSet == null || manaAmount <= 0) {
            throw unsupported("specifyManaCombo", "empty mana choice");
        }
        final List<forge.card.MagicColor.Color> colors =
                new ArrayList<>(colorSet.getOrderedColors());
        if (colors.isEmpty()) {
            throw unsupported("specifyManaCombo", "no colors offered by engine");
        }
        final List<Map<Byte, Integer>> combos = new ArrayList<>();
        // Sentinel cap: 129 means "more than offerable" and fails closed below,
        // so the frame never carries a truncated legal set.
        enumerateManaCombos(colors, 0, manaAmount, different, new LinkedHashMap<>(), combos, 129);
        if (combos.isEmpty()) {
            throw unsupported("specifyManaCombo", "no valid combination");
        }
        if (combos.size() > 128) {
            throw unsupported("specifyManaCombo", "too many combinations to offer completely");
        }
        if (combos.size() == 1) {
            return new LinkedHashMap<>(combos.get(0));
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(combos.size());
        for (Map<Byte, Integer> combo : combos) {
            final StringBuilder label = new StringBuilder("Mana [");
            for (Map.Entry<Byte, Integer> entry : combo.entrySet()) {
                label.append(forge.card.MagicColor.toShortString(entry.getKey()))
                        .append('x').append(entry.getValue()).append(';');
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("mana_combo", label.toString(), null,
                    new LinkedHashMap<>(combo), "MANA_COMBO"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.MANA_PAYMENT, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final Map<Byte, Integer> chosen = (Map<Byte, Integer>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("mana option without native payload");
        }
        return new LinkedHashMap<>(chosen);
    }

    private static void enumerateManaCombos(List<forge.card.MagicColor.Color> colors, int index,
            int remaining, boolean different, Map<Byte, Integer> working,
            List<Map<Byte, Integer>> result, int cap) {
        if (result.size() >= cap) {
            return;
        }
        if (index == colors.size()) {
            if (remaining == 0 && !working.isEmpty()) {
                result.add(new LinkedHashMap<>(working));
            }
            return;
        }
        final byte mask = colors.get(index).getColorMask();
        final int max = different ? Math.min(1, remaining) : remaining;
        for (int count = 0; count <= max; count++) {
            if (count > 0) {
                working.put(mask, count);
            }
            enumerateManaCombos(colors, index + 1, remaining - count, different, working,
                    result, cap);
            working.remove(mask);
            if (result.size() >= cap) {
                return;
            }
        }
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
        return frameCombatSubset(attackers, "exert", "Exert");
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return frameCombatSubset(attackers, "enlist", "Enlist");
    }

    /**
     * WS202: optional attack-cost creature selection. The engine supplies the
     * complete legal candidate list; every subset (including empty) is offered
     * as one authoritative option, bounded to avoid partial sets.
     */
    private List<Card> frameCombatSubset(List<Card> candidates, String actionType, String verb) {
        final List<Card> legal = new ArrayList<>();
        if (candidates != null) {
            for (Card card : candidates) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.isEmpty()) {
            return new ArrayList<>();
        }
        final List<List<Card>> subsets = enumerateSubsets(legal, 0, legal.size());
        if (subsets.isEmpty() || subsets.size() > 128) {
            throw unsupported(actionType + "Attackers", "cannot offer complete selection");
        }
        if (subsets.size() == 1) {
            return new ArrayList<>(subsets.get(0));
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size());
        for (List<Card> subset : subsets) {
            final StringBuilder label = new StringBuilder(verb).append(" [");
            for (Card card : subset) {
                try {
                    label.append(card.getName()).append(';');
                } catch (Throwable t) {
                    label.append("?;");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption(actionType, label.toString(), null,
                    new ArrayList<>(subset), "CARD_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final List<Card> chosen = (List<Card>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException(actionType + " option without native payload");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("action", actionType);
        details.put("count", Integer.toString(chosen.size()));
        session.audit("combat_subset_chosen", details);
        return new ArrayList<>(chosen);
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
        // WS202: mirror Human exactly — delegate to the real no-stack pipeline.
        // No decision of its own: targets/costs/modes inside route through the
        // already-framed controller callbacks (or fail closed there).
        PlaySpellAbility.playSpellAbilityNoStack(this, player, effectSA, !mayChoseNewTargets);
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        // WS202: mirror the Human order-then-play pipeline exactly, except the
        // order itself is always an authoritative parked decision when more than
        // one ability is present. The Human needPrompt skip (identical triggers
        // auto-order, remembered GUI orders) is a UI convenience default the
        // bridge must not replicate as external authority: the engine called
        // into the controller to order, and every complete order is offered.
        final List<SpellAbility> ordered;
        if (activePlayerSAs == null || activePlayerSAs.size() <= 1) {
            ordered = activePlayerSAs == null
                    ? new ArrayList<>() : new ArrayList<>(activePlayerSAs);
        } else {
            ordered = orderSimultaneousSa(activePlayerSAs);
        }
        for (int i = ordered.size() - 1; i >= 0; i--) {
            final SpellAbility next = ordered.get(i);
            if (next.isTrigger() && !next.isCopied()) {
                PlaySpellAbility.playSpellAbility(this, player, next);
            } else {
                if (next.isCopied()) {
                    if (next.isSpell()) {
                        if (!next.getHostCard().isInZone(ZoneType.Stack)) {
                            next.setHostCard(player.getGame().getAction()
                                    .moveToStack(next.getHostCard(), next));
                        } else {
                            player.getGame().getStackZone().add(next.getHostCard());
                        }
                    }
                    if (next.isMayChooseNewTargets()) {
                        next.setupNewTargets(player);
                    }
                }
                player.getGame().getStack().add(next);
            }
        }
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

    // WS191: aa5c Rules-Core owns the incremental combat-damage transaction
    // (CombatDamageDecision). Anything reaching this callback is discretionary:
    // forced progress was already applied by the Core, so every legal
    // (source, recipient, amount) triple from the Core-owned view is parked,
    // even a lone triple (the amount is still a choice). The Core revalidates
    // on apply; stale/foreign/illegal selections throw there, never silently.
    @Override
    public CombatDamageSelection chooseCombatDamage(final CombatDamageDecisionView decision) {
        if (decision == null || decision.isEmpty()) {
            throw unsupported("chooseCombatDamage", "empty damage decision");
        }
        final List<CombatDamageSelection> triples = new ArrayList<>();
        for (CombatDamageDecisionView.SourceView source : decision.getSources()) {
            if (source == null) {
                continue;
            }
            for (CombatDamageDecisionView.RecipientView recipient : source.getRecipients()) {
                if (recipient == null || recipient.getRecipient() == null) {
                    continue;
                }
                for (int amount = recipient.getMinDamage();
                        amount <= recipient.getMaxDamage(); amount++) {
                    if (amount <= 0) {
                        continue;
                    }
                    triples.add(new CombatDamageSelection(source.getSource(),
                            recipient.getRecipient(), amount));
                    if (triples.size() > 128) {
                        throw unsupported("chooseCombatDamage",
                                "too many damage assignments to offer completely");
                    }
                }
            }
        }
        if (triples.isEmpty()) {
            throw unsupported("chooseCombatDamage", "no legal damage assignment");
        }
        return parkChoices(DecisionFrame.Kind.COMBAT_DAMAGE, "combat_damage", triples,
                selection -> {
                    try {
                        final String from = selection.getSource() == null ? "?"
                                : selection.getSource().getName();
                        final GameEntity to = selection.getRecipient();
                        final String toName;
                        if (to instanceof Card) {
                            toName = ((Card) to).getName();
                        } else if (to instanceof Player) {
                            toName = "player " + session.playerIdOf((Player) to);
                        } else {
                            toName = to.toString();
                        }
                        return "Assign " + selection.getAmount() + " from " + from + " to "
                                + toName;
                    } catch (Throwable t) {
                        return "Assign combat damage";
                    }
                }, "COMBAT_DAMAGE_SELECTION");
    }

    // WS191: aa5c Rules-Core owns the exact-total noncombat amount transaction
    // (AmountDistributionDecision) with forced single-recipient collapse. As with
    // combat damage, anything reaching this callback is discretionary and every
    // legal (recipient, amount) pair from the Core-owned view is parked.
    @Override
    public AmountDistributionSelection chooseAmountDistribution(
            final AmountDistributionDecisionView decision) {
        if (decision == null || decision.getRecipients().isEmpty()) {
            throw unsupported("chooseAmountDistribution", "empty amount decision");
        }
        final List<AmountDistributionSelection> pairs = new ArrayList<>();
        for (AmountDistributionDecisionView.RecipientView recipient : decision.getRecipients()) {
            if (recipient == null || recipient.getRecipient() == null) {
                continue;
            }
            for (int amount = recipient.getMinAmount();
                    amount <= recipient.getMaxAmount(); amount++) {
                if (amount <= 0) {
                    continue;
                }
                pairs.add(new AmountDistributionSelection(recipient.getRecipient(), amount));
                if (pairs.size() > 128) {
                    throw unsupported("chooseAmountDistribution",
                            "too many amount assignments to offer completely");
                }
            }
        }
        if (pairs.isEmpty()) {
            throw unsupported("chooseAmountDistribution", "no legal amount assignment");
        }
        return parkChoices(DecisionFrame.Kind.AMOUNT_DISTRIBUTION, "amount_distribution",
                pairs, selection -> {
                    try {
                        final GameEntity to = selection.getRecipient();
                        final String toName;
                        if (to instanceof Card) {
                            toName = ((Card) to).getName();
                        } else if (to instanceof Player) {
                            toName = "player " + session.playerIdOf((Player) to);
                        } else {
                            toName = to.toString();
                        }
                        return "Assign " + selection.getAmount() + " to " + toName;
                    } catch (Throwable t) {
                        return "Assign amount";
                    }
                }, "AMOUNT_DISTRIBUTION_SELECTION");
    }

    // WS217: Rules/Core owns the chooser-divided vector (CR 601.2d). The view carries
    // authoritative targets plus Core-calculated total/min/UpTo constraints; the bridge
    // projects them and accepts an exact vector, never computing legality itself.
    // Native Forge validates before mutation; forced progress never parks.
    @Override
    public DividedAllocationSelection chooseDividedAllocation(
            final DividedAllocationDecisionView decision) {
        if (decision == null || decision.getRecipients().isEmpty()) {
            throw unsupported("chooseDividedAllocation", "empty divided decision");
        }
        if (decision.getTotalAmount() <= 0) {
            throw unsupported("chooseDividedAllocation", "empty divided total");
        }
        final List<DecisionFrame.Option> targetOptions = new ArrayList<>(
                decision.getRecipients().size());
        for (DividedAllocationDecisionView.RecipientView recipient : decision.getRecipients()) {
            if (recipient == null || recipient.getRecipient() == null) {
                throw unsupported("chooseDividedAllocation", "divided target unreadable");
            }
            final GameEntity entity = recipient.getRecipient();
            final String label;
            try {
                label = "Divide to [" + targetName(entity) + "] ("
                        + recipient.getMinAmount() + ".." + recipient.getMaxAmount()
                        + " of " + decision.getTotalAmount() + ")";
            } catch (Throwable t) {
                throw unsupported("chooseDividedAllocation", "label unreadable");
            }
            targetOptions.add(DecisionFrame.payloadOption("divided_allocation_target", label,
                    null, entity, "DIVIDED_TARGET"));
        }
        if (targetOptions.size() > 9) {
            throw unsupported("chooseDividedAllocation",
                    "too many divided targets to offer completely");
        }
        final BridgeSession.FrameAnswer answer = session.parkDividedAllocation(player,
                targetOptions, decision.getTotalAmount(), decision.getRecipients().get(0)
                        .getMinAmount(), decision.isDividedUpTo());
        if (answer.dividedAllocations == null || answer.dividedAllocations.isEmpty()) {
            throw new IllegalStateException("divided allocation without a vector");
        }
        final Map<GameEntity, Integer> allocations = new LinkedHashMap<>(
                answer.dividedAllocations);
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("total", Integer.toString(decision.getTotalAmount()));
        details.put("targets", Integer.toString(allocations.size()));
        session.audit("divided_allocation_chosen", details);
        return new DividedAllocationSelection(allocations);
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected,
            int shieldAmount) {
        // WS202: bounded shield division. Each affected target may absorb
        // 0..min(its damage, shield); every complete map spending at most the
        // shield is one authoritative option. Anything larger fails closed.
        if (affected == null || affected.isEmpty() || shieldAmount <= 0) {
            return new LinkedHashMap<>();
        }
        final List<GameEntity> targets = new ArrayList<>(affected.keySet());
        if (targets.size() > 4) {
            throw unsupported("divideShield", "too many shield targets to offer completely");
        }
        final List<Map<GameEntity, Integer>> maps = new ArrayList<>();
        maps.add(new LinkedHashMap<>());
        for (GameEntity target : targets) {
            if (target == null) {
                continue;
            }
            final int damage;
            try {
                damage = Math.max(0, affected.getOrDefault(target, 0));
            } catch (Throwable t) {
                throw unsupported("divideShield", "shield amount unreadable");
            }
            final int cap = Math.min(damage, shieldAmount);
            final List<Map<GameEntity, Integer>> next = new ArrayList<>();
            for (Map<GameEntity, Integer> base : maps) {
                int spent = 0;
                for (int value : base.values()) {
                    spent += value;
                }
                for (int amount = 0; amount <= cap && spent + amount <= shieldAmount; amount++) {
                    final Map<GameEntity, Integer> extended = new LinkedHashMap<>(base);
                    extended.put(target, amount);
                    next.add(extended);
                    if (next.size() > 128) {
                        throw unsupported("divideShield",
                                "too many shield divisions to offer completely");
                    }
                }
            }
            maps.clear();
            maps.addAll(next);
        }
        if (maps.size() == 1) {
            return new LinkedHashMap<>(maps.get(0));
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(maps.size());
        for (Map<GameEntity, Integer> map : maps) {
            final StringBuilder label = new StringBuilder("Shield [");
            for (Map.Entry<GameEntity, Integer> entry : map.entrySet()) {
                try {
                    if (entry.getKey() instanceof Card) {
                        label.append(((Card) entry.getKey()).getName());
                    } else {
                        label.append(entry.getKey().toString());
                    }
                } catch (Throwable t) {
                    label.append('?');
                }
                label.append(':').append(entry.getValue()).append(';');
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("divide_shield", label.toString(), null,
                    new LinkedHashMap<>(map), "SHIELD_MAP"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.GENERIC_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final Map<GameEntity, Integer> chosen =
                (Map<GameEntity, Integer>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("shield option without native payload");
        }
        return new LinkedHashMap<>(chosen);
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
            CardCollectionView validTargets, String message) {
        return frameEffectCards("sacrifice",
                message == null ? "Choose permanents to sacrifice" : message, validTargets,
                min, max);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
            CardCollectionView validTargets, String message) {
        return frameEffectCards("destroy",
                message == null ? "Choose permanents to destroy" : message, validTargets,
                min, max);
    }

    /**
     * WS202: effect-resolution card selection. Every min..max subset of the
     * engine-supplied valid list is one authoritative option, bounded to avoid
     * partial sets. Empty results pass through as empty (engine fizzles/shorts
     * downstream); oversized sets fail closed loudly.
     */
    private CardCollection frameEffectCards(String actionType, String prompt,
            CardCollectionView validTargets, int min, int max) {
        final List<Card> legal = new ArrayList<>();
        if (validTargets != null) {
            for (Card card : validTargets) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.isEmpty()) {
            return new CardCollection();
        }
        final List<List<Card>> subsets = enumerateSubsets(legal, Math.max(0, min),
                Math.min(Math.max(0, max), legal.size()));
        if (subsets.isEmpty()) {
            return new CardCollection();
        }
        if (subsets.size() == 1) {
            return new CardCollection(subsets.get(0));
        }
        if (subsets.size() > 128) {
            throw unsupported(actionType, "too many combinations to offer completely");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size());
        for (List<Card> subset : subsets) {
            final StringBuilder label = new StringBuilder(prompt).append(" [");
            for (Card card : subset) {
                try {
                    label.append(card.getName()).append(';');
                } catch (Throwable t) {
                    label.append("?;");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption(actionType, label.toString(), null,
                    new CardCollection(subset), "CARD_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.GENERIC_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        final CardCollection chosen = (CardCollection) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException(actionType + " option without native payload");
        }
        return chosen;
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter,
            boolean optional) {
        // WS202: mirror the Human redirect flow (unwrap, save/restore, exact-count
        // reselect) with the new targets parked as one authoritative TARGET_SELECTION
        // frame. Divided reselects have no native controller surface and fail closed.
        final SpellAbility sa = ability != null && ability.isWrapper()
                ? ((WrappedAbility) ability).getWrappedAbility() : ability;
        if (sa == null) {
            throw unsupported("chooseNewTargetsFor", "null ability");
        }
        if (!sa.usesTargeting()) {
            return null;
        }
        final TargetChoices oldTargets = sa.getTargets();
        final int count = oldTargets == null ? 0 : oldTargets.size();
        final boolean divided;
        try {
            divided = sa.isDividedAsYouChoose() || sa.hasParam("DividedUpTo");
        } catch (Throwable t) {
            throw unsupported("chooseNewTargetsFor", "target bounds unreadable");
        }
        if (divided && count > 0) {
            throw unsupported("chooseNewTargetsFor",
                    "divided allocation has no native controller surface");
        }
        final List<GameEntity> candidates;
        try {
            candidates = sa.getTargetRestrictions().getAllCandidates(sa);
        } catch (Throwable t) {
            throw unsupported("chooseNewTargetsFor", "candidate enumeration failed");
        }
        final List<GameEntity> legal = new ArrayList<>();
        if (candidates != null) {
            for (GameEntity entity : candidates) {
                if (entity == null) {
                    continue;
                }
                try {
                    if (filter != null && !filter.test(entity)) {
                        continue;
                    }
                    if (!sa.canTarget(entity)) {
                        continue;
                    }
                    legal.add(entity);
                } catch (Throwable t) {
                    throw unsupported("chooseNewTargetsFor", "target legality check failed");
                }
            }
        }
        if (legal.size() > 9) {
            throw unsupported("chooseNewTargetsFor", "too many candidates to offer completely");
        }
        final List<List<GameEntity>> validSets = new ArrayList<>();
        for (List<GameEntity> subset : enumerateSubsets(legal, count, count)) {
            try {
                sa.clearTargets();
                for (GameEntity entity : subset) {
                    sa.getTargets().add(toTargetObject(entity, "chooseNewTargetsFor"));
                }
                if (forge.game.staticability.StaticAbilityMustTarget.meetsMustTargetRestriction(
                        sa)) {
                    validSets.add(subset);
                }
            } catch (Throwable t) {
                try {
                    sa.setTargets(oldTargets);
                } catch (Throwable inner) {
                    // Ignore restore failure; report selection failure.
                }
                throw unsupported("chooseNewTargetsFor", "target combination check failed");
            }
            if (validSets.size() > 512) {
                try {
                    sa.setTargets(oldTargets);
                } catch (Throwable inner) {
                    // Ignore restore failure; report selection failure.
                }
                throw unsupported("chooseNewTargetsFor",
                        "too many target combinations to offer completely");
            }
        }
        try {
            sa.setTargets(oldTargets);
        } catch (Throwable t) {
            throw unsupported("chooseNewTargetsFor", "target restore failed");
        }
        if (validSets.isEmpty()) {
            return null;
        }
        final List<GameEntity> chosen;
        if (validSets.size() == 1 && !optional) {
            chosen = validSets.get(0);
        } else {
            final List<DecisionFrame.Option> options = new ArrayList<>(validSets.size() + 1);
            for (List<GameEntity> set : validSets) {
                final StringBuilder label = new StringBuilder("New targets [");
                for (GameEntity entity : set) {
                    label.append(targetName(entity)).append(';');
                }
                label.append(']');
                options.add(DecisionFrame.payloadOption("retarget", label.toString(), null,
                        new ArrayList<>(set), "GAME_ENTITY_LIST"));
            }
            if (optional) {
                options.add(DecisionFrame.confirmOption("retarget", "Keep existing targets", false));
            }
            final BridgeSession.FrameAnswer answer = session.parkFrame(
                    DecisionFrame.Kind.TARGET_SELECTION, player,
                    DecisionFrame.Status.SUPPORTED, "", options);
            if (answer.selected.confirmValue != null
                    && !answer.selected.confirmValue.booleanValue()) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final List<GameEntity> picked = (List<GameEntity>) answer.selected.nativePayload;
            if (picked == null) {
                throw new IllegalStateException("retarget option without native payload");
            }
            chosen = picked;
        }
        try {
            sa.clearTargets();
            for (GameEntity entity : chosen) {
                sa.getTargets().add(toTargetObject(entity, "chooseNewTargetsFor"));
            }
        } catch (Throwable t) {
            try {
                sa.setTargets(oldTargets);
            } catch (Throwable inner) {
                // Ignore restore failure; report assignment failure.
            }
            throw unsupported("chooseNewTargetsFor", "target assignment failed");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("count", Integer.toString(chosen.size()));
        session.audit("targets_redirected", details);
        return sa.getTargets();
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        // WS217: authoritative target selection via the engine's own
        // TargetRestrictions.getAllCandidates (Rules-Core legal set) filtered by
        // canTarget (fizzle/protection layers). Every MustTarget-valid combination
        // of min..max targets is one authoritative option, bounded to avoid partial
        // sets. Chooser-divided allocation (CR 601.2d) then travels through the
        // native Core-owned divided-allocation seam with native validation before
        // mutation; target choice and allocation stay separate lifecycle steps.
        if (currentAbility == null) {
            throw unsupported("chooseTargetsFor", "null ability");
        }
        final int min;
        final int max;
        final boolean divided;
        try {
            min = currentAbility.getMinTargets();
            max = currentAbility.getMaxTargets();
            divided = currentAbility.isDividedAsYouChoose()
                    || currentAbility.hasParam("DividedUpTo");
        } catch (Throwable t) {
            throw unsupported("chooseTargetsFor", "target bounds unreadable");
        }
        if (max < min) {
            throw unsupported("chooseTargetsFor", "inverted target bounds");
        }
        final List<GameEntity> candidates;
        try {
            candidates = currentAbility.getTargetRestrictions().getAllCandidates(currentAbility);
        } catch (Throwable t) {
            throw unsupported("chooseTargetsFor", "candidate enumeration failed");
        }
        final List<GameEntity> legal = new ArrayList<>();
        if (candidates != null) {
            for (GameEntity entity : candidates) {
                if (entity == null) {
                    continue;
                }
                try {
                    if (currentAbility.canTarget(entity)) {
                        legal.add(entity);
                    }
                } catch (Throwable t) {
                    throw unsupported("chooseTargetsFor", "target legality check failed");
                }
            }
        }
        if (legal.isEmpty()) {
            return min <= 0;
        }
        if (legal.size() > 9) {
            throw unsupported("chooseTargetsFor", "too many candidates to offer completely");
        }
        // Trial-assign each combination so MustTarget and sibling-target rules
        // prune the offered set exactly; the frame carries only valid sets.
        final List<List<GameEntity>> validSets = new ArrayList<>();
        for (List<GameEntity> subset : enumerateSubsets(legal, min, Math.min(max, legal.size()))) {
            try {
                for (GameEntity entity : subset) {
                    currentAbility.getTargets().add(toTargetObject(entity, "chooseTargetsFor"));
                }
                if (forge.game.staticability.StaticAbilityMustTarget.meetsMustTargetRestriction(
                        currentAbility)) {
                    validSets.add(subset);
                }
            } catch (Throwable t) {
                throw unsupported("chooseTargetsFor", "target combination check failed");
            } finally {
                try {
                    currentAbility.resetTargets();
                } catch (Throwable t) {
                    throw unsupported("chooseTargetsFor", "target reset failed");
                }
            }
            if (validSets.size() > 512) {
                throw unsupported("chooseTargetsFor",
                        "too many target combinations to offer completely");
            }
        }
        if (validSets.isEmpty()) {
            return false;
        }
        final List<GameEntity> chosen;
        if (validSets.size() == 1) {
            chosen = validSets.get(0);
        } else {
            final List<DecisionFrame.Option> options = new ArrayList<>(validSets.size());
            for (List<GameEntity> set : validSets) {
                final StringBuilder label = new StringBuilder("Target [");
                for (GameEntity entity : set) {
                    label.append(targetName(entity)).append(';');
                }
                label.append(']');
                options.add(DecisionFrame.payloadOption("target", label.toString(), null,
                        new ArrayList<>(set), "GAME_ENTITY_LIST"));
            }
            final BridgeSession.FrameAnswer answer = session.parkFrame(
                    DecisionFrame.Kind.TARGET_SELECTION, player,
                    DecisionFrame.Status.SUPPORTED, "", options);
            @SuppressWarnings("unchecked")
            final List<GameEntity> picked = (List<GameEntity>) answer.selected.nativePayload;
            if (picked == null) {
                throw new IllegalStateException("target option without native payload");
            }
            chosen = picked;
        }
        try {
            for (GameEntity entity : chosen) {
                currentAbility.getTargets().add(toTargetObject(entity, "chooseTargetsFor"));
            }
        } catch (Throwable t) {
            throw unsupported("chooseTargetsFor", "target assignment failed");
        }
        try {
            if (!forge.game.staticability.StaticAbilityMustTarget.meetsMustTargetRestriction(
                    currentAbility)) {
                currentAbility.resetTargets();
                return false;
            }
        } catch (Throwable t) {
            try {
                currentAbility.resetTargets();
            } catch (Throwable inner) {
                // Ignore reset failure; report assignment failure.
            }
            throw unsupported("chooseTargetsFor", "must-target check failed");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        final StringBuilder chosenNames = new StringBuilder();
        for (GameEntity entity : chosen) {
            chosenNames.append(targetName(entity)).append(';');
        }
        details.put("targets", chosenNames.toString());
        session.audit("targets_chosen", details);
        if (!divided) {
            return true;
        }
        // WS217 CR 601.2d announcement: targets are now authoritative; the exact
        // vector travels through the Core-owned seam with native validation before
        // mutation. DividedUpTo effective totals reuse the native number seam.
        final List<GameEntity> dividedTargets = new ArrayList<>();
        try {
            for (GameEntity entity : currentAbility.getTargets().getTargetEntities()) {
                dividedTargets.add(entity);
            }
        } catch (Throwable t) {
            throw unsupported("chooseTargetsFor", "divided targets unreadable");
        }
        if (dividedTargets.isEmpty()) {
            return true;
        }
        int total;
        try {
            total = currentAbility.getStillToDivide();
        } catch (Throwable t) {
            throw unsupported("chooseTargetsFor", "divided total unreadable");
        }
        if (total <= 0) {
            return true;
        }
        final boolean dividedUpTo;
        try {
            dividedUpTo = currentAbility.hasParam("DividedUpTo");
        } catch (Throwable t) {
            throw unsupported("chooseTargetsFor", "divided kind unreadable");
        }
        if (dividedUpTo) {
            try {
                total = chooseNumber(currentAbility, "How many", dividedTargets.size(), total);
            } catch (BridgeUnsupportedDecision e) {
                throw e;
            } catch (Throwable t) {
                try {
                    currentAbility.resetTargets();
                } catch (Throwable inner) {
                    // Ignore reset failure; report allocation failure.
                }
                session.setLastExecutionError("divided total choice failed: " + t.getMessage());
                return false;
            }
            if (total <= 0) {
                return true;
            }
        }
        final DividedAllocationDecision decision;
        try {
            decision = new DividedAllocationDecision(currentAbility, total, dividedTargets,
                    dividedUpTo);
        } catch (Throwable t) {
            throw unsupported("chooseTargetsFor", "divided decision unreadable");
        }
        try {
            decision.resolve(this);
        } catch (BridgeUnsupportedDecision e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException e) {
            try {
                currentAbility.resetTargets();
            } catch (Throwable inner) {
                // Ignore reset failure; report allocation failure.
            }
            session.setLastExecutionError(e.getMessage());
            return false;
        }
        final Map<String, String> dividedDetails = new LinkedHashMap<>();
        dividedDetails.put("actor", actorId());
        dividedDetails.put("total", Integer.toString(total));
        dividedDetails.put("targets", chosenNames.toString());
        session.audit("divided_targets_allocated", dividedDetails);
        return true;
    }

    private String targetName(GameEntity entity) {
        try {
            if (entity instanceof Card) {
                return ((Card) entity).getName();
            }
            if (entity instanceof Player) {
                return "player " + session.playerIdOf((Player) entity);
            }
            return entity.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * WS202: stack-spell target mapping, mirroring the Human
     * {@code TargetSelection} path exactly. Counter-style effects resolve
     * through {@code TargetChoices.getTargetSpells()}, which only sees
     * {@link SpellAbility} targets: a stack-zone Card stored raw would make
     * the effect silently do nothing at resolution. A chosen Card that hosts a
     * spell on the stack therefore stores that stack SpellAbility (first
     * spell, skipping cast triggers, exactly like the Human flow); anything
     * else stores unchanged. An unresolvable stack Card fails closed instead
     * of fabricating a no-op target.
     */
    private GameObject toTargetObject(GameEntity entity, String action) {
        if (entity instanceof Card) {
            final Card card = (Card) entity;
            boolean hostsStackSpell = false;
            try {
                for (SpellAbilityStackInstance si : getGame().getStack()) {
                    final SpellAbility onStack = si == null ? null : si.getSpellAbility();
                    if (onStack != null && onStack.isSpell() && onStack.getHostCard() == card) {
                        hostsStackSpell = true;
                        break;
                    }
                }
            } catch (Throwable t) {
                throw unsupported(action, "stack lookup failed");
            }
            if (hostsStackSpell) {
                try {
                    for (SpellAbilityStackInstance si : getGame().getStack()) {
                        final SpellAbility onStack = si == null ? null : si.getSpellAbility();
                        if (onStack != null && onStack.isSpell()
                                && onStack.getHostCard() == card) {
                            return onStack;
                        }
                    }
                } catch (Throwable t) {
                    throw unsupported(action, "stack spell read failed");
                }
                throw unsupported(action, "stack spell instance unresolvable");
            }
        }
        if (entity instanceof GameObject) {
            return (GameObject) entity;
        }
        throw unsupported(action, "target is not a game object");
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa,
            List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        if (allTargets == null || allTargets.isEmpty()) {
            throw unsupported("chooseTarget", "empty target set");
        }
        if (allTargets.size() == 1) {
            return allTargets.get(0);
        }
        return parkSingleChoice(DecisionFrame.Kind.TARGET_SELECTION, "target", allTargets, item -> {
            try {
                final GameObject target = item.getRight();
                if (target instanceof Card) {
                    return "Target [" + ((Card) target).getName() + "]";
                }
                return "Target [" + target.toString() + "]";
            } catch (Throwable t) {
                return "Target";
            }
        }, "TARGET_PAIR");
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
        // WS202: mirror the Human single-pick delegation, then subset framing.
        // Decline returns null so the engine treats the effect choice as refused.
        if (min == 1 && max == 1) {
            final Card single = chooseSingleEntityForEffect(sourceList, null, sa,
                    title == null ? "Choose card" : title, isOptional, null, params);
            if (single == null) {
                return CardCollection.EMPTY;
            }
            return new CardCollection(single);
        }
        final List<Card> legal = new ArrayList<>();
        if (sourceList != null) {
            for (Card card : sourceList) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.isEmpty()) {
            return new CardCollection();
        }
        final List<List<Card>> subsets = enumerateSubsets(legal, Math.max(0, min),
                Math.min(Math.max(0, max), legal.size()));
        if (subsets.isEmpty()) {
            return new CardCollection();
        }
        if (subsets.size() == 1 && !isOptional) {
            return new CardCollection(subsets.get(0));
        }
        if (subsets.size() > 128) {
            throw unsupported("chooseCardsForEffect", "too many combinations to offer completely");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size() + 1);
        for (List<Card> subset : subsets) {
            final StringBuilder label = new StringBuilder(
                    title == null || title.isEmpty() ? "Choose cards" : title);
            label.append(" [");
            for (Card card : subset) {
                try {
                    label.append(card.getName()).append(';');
                } catch (Throwable t) {
                    label.append("?;");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("effect_cards", label.toString(), null,
                    new CardCollection(subset), "CARD_LIST"));
        }
        if (isOptional) {
            options.add(DecisionFrame.confirmOption("effect_cards", "Decline selection", false));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.GENERIC_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        if (answer.selected.confirmValue != null && !answer.selected.confirmValue.booleanValue()) {
            return null;
        }
        final CardCollection chosen = (CardCollection) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("effect option without native payload");
        }
        return chosen;
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap,
            SpellAbility sa, String title, boolean isOptional) {
        // WS202: mirror the Human per-category 0..1 delegation exactly.
        if (validMap == null || validMap.isEmpty()) {
            throw unsupported("chooseCardsForEffectMultiple", "empty category set");
        }
        final CardCollection result = new CardCollection();
        for (Map.Entry<String, CardCollection> entry : validMap.entrySet()) {
            final CardCollectionView picked = chooseCardsForEffect(entry.getValue(), sa,
                    (title == null ? "Choose cards" : title) + " (" + entry.getKey() + ")",
                    0, 1, isOptional, null);
            if (picked == null) {
                return result;
            }
            result.addAll(picked);
        }
        return result;
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
        // WS202: Core-owned attacker declaration. Legal (attacker, defender) pairs
        // come from CombatUtil.getPossibleAttackers (primitive checks) crossed with
        // the combat's own defender set via CombatUtil.canAttack (restrictions).
        // Every complete declaration (each attacker striking at most one defender,
        // or sitting out, including the empty declaration) is one authoritative
        // option. The engine revalidates (must-attack/goad/propaganda) and
        // re-prompts on violation; propaganda taxes route through payCombatCost.
        if (attacker == null || combat == null) {
            throw unsupported("declareAttackers", "null combat state");
        }
        final List<Card> possible;
        final List<GameEntity> defenders;
        try {
            possible = new ArrayList<>(
                    forge.game.combat.CombatUtil.getPossibleAttackers(attacker));
            defenders = new ArrayList<>(combat.getDefenders());
        } catch (Throwable t) {
            throw unsupported("declareAttackers", "combat enumeration failed");
        }
        final List<Card> canAttack = new ArrayList<>();
        final Map<Card, List<GameEntity>> legalDefenders = new IdentityHashMap<>();
        for (Card card : possible) {
            if (card == null) {
                continue;
            }
            final List<GameEntity> legal = new ArrayList<>();
            for (GameEntity defender : defenders) {
                if (defender == null) {
                    continue;
                }
                try {
                    if (forge.game.combat.CombatUtil.canAttack(card, defender)) {
                        legal.add(defender);
                    }
                } catch (Throwable t) {
                    throw unsupported("declareAttackers", "attack legality check failed");
                }
            }
            if (!legal.isEmpty()) {
                canAttack.add(card);
                legalDefenders.put(card, legal);
            }
        }
        if (canAttack.isEmpty()) {
            return;
        }
        if (canAttack.size() > 4) {
            throw unsupported("declareAttackers", "too many attackers to offer completely");
        }
        List<Map<Card, GameEntity>> declarations = new ArrayList<>();
        declarations.add(new LinkedHashMap<>());
        for (Card card : canAttack) {
            final List<Map<Card, GameEntity>> next = new ArrayList<>();
            for (Map<Card, GameEntity> base : declarations) {
                next.add(new LinkedHashMap<>(base));
                for (GameEntity defender : legalDefenders.get(card)) {
                    final Map<Card, GameEntity> extended = new LinkedHashMap<>(base);
                    extended.put(card, defender);
                    next.add(extended);
                }
            }
            declarations = next;
            if (declarations.size() > 128) {
                throw unsupported("declareAttackers", "too many declarations to offer completely");
            }
        }
        if (declarations.size() == 1) {
            applyAttackDeclaration(attacker, combat, declarations.get(0));
            return;
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(declarations.size());
        for (Map<Card, GameEntity> declaration : declarations) {
            options.add(DecisionFrame.payloadOption("declare_attackers",
                    attackDeclarationLabel(declaration), null,
                    new LinkedHashMap<>(declaration), "ATTACK_DECLARATION"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final Map<Card, GameEntity> chosen =
                (Map<Card, GameEntity>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("attack declaration without native payload");
        }
        applyAttackDeclaration(attacker, combat, chosen);
    }

    private String attackDeclarationLabel(Map<Card, GameEntity> declaration) {
        if (declaration.isEmpty()) {
            return "No attacks";
        }
        final StringBuilder label = new StringBuilder("Attack");
        for (Map.Entry<Card, GameEntity> entry : declaration.entrySet()) {
            label.append(' ');
            try {
                label.append(entry.getKey().getName());
            } catch (Throwable t) {
                label.append('?');
            }
            label.append(" -> ");
            try {
                final GameEntity defender = entry.getValue();
                if (defender instanceof Player) {
                    label.append(session.playerIdOf((Player) defender));
                } else if (defender instanceof Card) {
                    label.append(((Card) defender).getName());
                } else {
                    label.append(defender.toString());
                }
            } catch (Throwable t) {
                label.append('?');
            }
            label.append(';');
        }
        return label.toString();
    }

    private void applyAttackDeclaration(Player attacker, Combat combat,
            Map<Card, GameEntity> declaration) {
        try {
            for (Card current : new ArrayList<>(combat.getAttackers())) {
                if (current != null && current.getController() == attacker) {
                    combat.removeFromCombat(current);
                }
            }
            for (Map.Entry<Card, GameEntity> entry : declaration.entrySet()) {
                combat.addAttacker(entry.getKey(), entry.getValue());
            }
        } catch (Throwable t) {
            throw unsupported("declareAttackers", "declaration assignment failed");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("count", Integer.toString(declaration.size()));
        session.audit("attackers_declared", details);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        // WS202: Core-owned blocker declaration. Candidates are the defender's
        // creatures passing CombatUtil.canBlock in this combat; per-pair legality
        // comes from CombatUtil.canBlock(attacker, blocker, combat). Every complete
        // assignment (each blocker on at most one attacker, or sitting out,
        // including the empty assignment) is one authoritative option. The engine
        // prunes group-block restrictions and unpaid block costs natively.
        if (defender == null || combat == null) {
            throw unsupported("declareBlockers", "null combat state");
        }
        final List<Card> attackers;
        try {
            attackers = new ArrayList<>(combat.getAttackers());
        } catch (Throwable t) {
            throw unsupported("declareBlockers", "combat enumeration failed");
        }
        if (attackers.isEmpty()) {
            return;
        }
        final List<Card> candidates = new ArrayList<>();
        final Map<Card, List<Card>> legalAttackers = new IdentityHashMap<>();
        for (Card blocker : defender.getCreaturesInPlay()) {
            if (blocker == null) {
                continue;
            }
            try {
                if (!forge.game.combat.CombatUtil.canBlock(blocker, combat)) {
                    continue;
                }
            } catch (Throwable t) {
                throw unsupported("declareBlockers", "block legality check failed");
            }
            final List<Card> legal = new ArrayList<>();
            for (Card attacker : attackers) {
                if (attacker == null) {
                    continue;
                }
                try {
                    if (forge.game.combat.CombatUtil.canBlock(attacker, blocker, combat)) {
                        legal.add(attacker);
                    }
                } catch (Throwable t) {
                    throw unsupported("declareBlockers", "block pairing check failed");
                }
            }
            if (!legal.isEmpty()) {
                candidates.add(blocker);
                legalAttackers.put(blocker, legal);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        if (candidates.size() > 4) {
            throw unsupported("declareBlockers", "too many blockers to offer completely");
        }
        List<Map<Card, Card>> assignments = new ArrayList<>();
        assignments.add(new LinkedHashMap<>());
        for (Card blocker : candidates) {
            final List<Map<Card, Card>> next = new ArrayList<>();
            for (Map<Card, Card> base : assignments) {
                next.add(new LinkedHashMap<>(base));
                for (Card attacker : legalAttackers.get(blocker)) {
                    final Map<Card, Card> extended = new LinkedHashMap<>(base);
                    extended.put(blocker, attacker);
                    next.add(extended);
                }
            }
            assignments = next;
            if (assignments.size() > 128) {
                throw unsupported("declareBlockers", "too many assignments to offer completely");
            }
        }
        if (assignments.size() == 1) {
            applyBlockDeclaration(defender, combat, assignments.get(0));
            return;
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(assignments.size());
        for (Map<Card, Card> assignment : assignments) {
            final StringBuilder label = new StringBuilder();
            if (assignment.isEmpty()) {
                label.append("No blocks");
            } else {
                label.append("Block");
                for (Map.Entry<Card, Card> entry : assignment.entrySet()) {
                    label.append(' ');
                    try {
                        label.append(entry.getKey().getName());
                    } catch (Throwable t) {
                        label.append('?');
                    }
                    label.append(" blocks ");
                    try {
                        label.append(entry.getValue().getName());
                    } catch (Throwable t) {
                        label.append('?');
                    }
                    label.append(';');
                }
            }
            options.add(DecisionFrame.payloadOption("declare_blockers", label.toString(), null,
                    new LinkedHashMap<>(assignment), "BLOCK_DECLARATION"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final Map<Card, Card> chosen = (Map<Card, Card>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("block declaration without native payload");
        }
        applyBlockDeclaration(defender, combat, chosen);
    }

    private void applyBlockDeclaration(Player defender, Combat combat,
            Map<Card, Card> assignment) {
        try {
            for (Card current : new ArrayList<>(combat.getAllBlockers())) {
                if (current != null && current.getController() == defender) {
                    for (Card attacker : new ArrayList<>(combat.getAttackersBlockedBy(current))) {
                        combat.removeBlockAssignment(attacker, current);
                    }
                }
            }
            for (Map.Entry<Card, Card> entry : assignment.entrySet()) {
                combat.addBlocker(entry.getValue(), entry.getKey());
            }
        } catch (Throwable t) {
            throw unsupported("declareBlockers", "block assignment failed");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("count", Integer.toString(assignment.size()));
        session.audit("blockers_declared", details);
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return frameCardOrder(DecisionFrame.Kind.COMBAT_ORDER, "order_blockers",
                "Order blockers for", attacker, blockers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        // WS202: insertion framing. Each position in the existing order (plus the
        // end) is one authoritative option carrying the complete resulting order.
        final List<Card> current = new ArrayList<>();
        if (oldBlockers != null) {
            for (Card card : oldBlockers) {
                if (card != null) {
                    current.add(card);
                }
            }
        }
        if (blocker == null) {
            throw unsupported("orderBlocker", "null blocker");
        }
        final List<List<Card>> orders = new ArrayList<>(current.size() + 1);
        for (int i = 0; i <= current.size(); i++) {
            final List<Card> order = new ArrayList<>(current);
            order.add(i, blocker);
            orders.add(order);
        }
        if (orders.size() == 1) {
            return new CardCollection(orders.get(0));
        }
        return frameOrderedCards(DecisionFrame.Kind.COMBAT_ORDER, "order_blocker",
                attacker, orders);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return frameCardOrder(DecisionFrame.Kind.COMBAT_ORDER, "order_attackers",
                "Order attackers for", blocker, attackers);
    }

    private CardCollection frameCardOrder(DecisionFrame.Kind kind, String actionType,
            String verb, Card subject, CardCollection cards) {
        final List<Card> legal = new ArrayList<>();
        if (cards != null) {
            for (Card card : cards) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.size() <= 1) {
            return cards == null ? new CardCollection() : new CardCollection(cards);
        }
        if (legal.size() > 4) {
            throw unsupported(actionType, "too many combatants to order completely");
        }
        return frameOrderedCards(kind, actionType, subject, permutations(legal));
    }

    private CardCollection frameOrderedCards(DecisionFrame.Kind kind, String actionType,
            Card subject, List<List<Card>> orders) {
        final String subjectName;
        try {
            subjectName = subject == null ? "?" : subject.getName();
        } catch (Throwable t) {
            throw unsupported(actionType, "subject unreadable");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(orders.size());
        for (List<Card> order : orders) {
            final StringBuilder label = new StringBuilder("Order for ").append(subjectName)
                    .append(" [");
            for (Card card : order) {
                try {
                    label.append(card.getName()).append(';');
                } catch (Throwable t) {
                    label.append("?;");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption(actionType, label.toString(), null,
                    new ArrayList<>(order), "CARD_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(kind, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        @SuppressWarnings("unchecked")
        final List<Card> chosen = (List<Card>) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException(actionType + " option without native payload");
        }
        return new CardCollection(chosen);
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
        // WS202: mirror the Human no-decision path exactly, frame the rest.
        // The Human controller performs no ordering for graveyard moves outside
        // ReorderZone effects in unordered-graveyard games (its default path):
        // no discretion exists there, so the input order returns untouched with
        // no frame. Anything else over 2+ cards is a genuine order decision and
        // parks ORDER_CHOICE over the complete permutation set (>4 fails closed).
        final List<Card> legal = new ArrayList<>();
        if (cards != null) {
            for (Card card : cards) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.size() <= 1) {
            return cards;
        }
        final boolean reorderEffect;
        try {
            reorderEffect = source != null && source.getApi() == ApiType.ReorderZone;
        } catch (Throwable t) {
            throw unsupported("orderMoveToZoneList", "source effect unreadable");
        }
        if (!reorderEffect && destinationZone == ZoneType.Graveyard) {
            final boolean ordered;
            try {
                ordered = getGame().isGraveyardOrdered(getPlayer());
            } catch (Throwable t) {
                throw unsupported("orderMoveToZoneList", "graveyard order state unreadable");
            }
            if (!ordered) {
                final Map<String, String> details = new LinkedHashMap<>();
                details.put("actor", actorId());
                details.put("destination", "Graveyard");
                details.put("count", Integer.toString(legal.size()));
                session.audit("zone_order_unneeded", details);
                return cards;
            }
        }
        if (legal.size() > 4) {
            throw unsupported("orderMoveToZoneList", "too many cards to order completely");
        }
        final Card subject;
        try {
            subject = source == null ? null : source.getHostCard();
        } catch (Throwable t) {
            throw unsupported("orderMoveToZoneList", "source card unreadable");
        }
        final CardCollection ordered = frameOrderedCards(DecisionFrame.Kind.ORDER_CHOICE,
                "order_zone", subject, permutations(legal));
        final Map<String, String> chosen = new LinkedHashMap<>();
        chosen.put("actor", actorId());
        chosen.put("destination", destinationZone == null ? "?" : destinationZone.name());
        chosen.put("count", Integer.toString(legal.size()));
        session.audit("zone_order_chosen", chosen);
        return ordered;
    }

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
            CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        // WS202: subset framing over the engine-supplied valid list. Labels show
        // only identities the chooser is entitled to (visibleToChooser); anything
        // else is an explicit hidden marker, never a name. The frame stays
        // actor-scoped end to end.
        final List<Card> legal = new ArrayList<>();
        if (validCards != null) {
            for (Card card : validCards) {
                if (card != null) {
                    legal.add(card);
                }
            }
        }
        if (legal.isEmpty()) {
            return new CardCollection();
        }
        final List<List<Card>> subsets = enumerateSubsets(legal, Math.max(0, min),
                Math.min(Math.max(0, max), legal.size()));
        if (subsets.isEmpty()) {
            return new CardCollection();
        }
        if (subsets.size() == 1) {
            return new CardCollection(subsets.get(0));
        }
        if (subsets.size() > 128) {
            throw unsupported("chooseCardsToDiscardFrom",
                    "too many combinations to offer completely");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size());
        for (List<Card> subset : subsets) {
            final StringBuilder label = new StringBuilder("Discard [");
            for (Card card : subset) {
                label.append(discardLabel(card, visibleToChooser)).append(';');
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("discard", label.toString(), null,
                    new CardCollection(subset), "CARD_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.HIDDEN_ZONE_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        final CardCollection chosen = (CardCollection) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("discard option without native payload");
        }
        return chosen;
    }

    private static String discardLabel(Card card, CardCollectionView visibleToChooser) {
        try {
            if (visibleToChooser != null && !visibleToChooser.contains(card)) {
                return "<hidden>";
            }
            return card.getName();
        } catch (Throwable t) {
            return "<hidden>";
        }
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand,
            String[] unlessTypes, SpellAbility sa) {
        // WS202: mirror the Human legality exactly — every subset that is either
        // the full count or a non-empty escape containing an unless-type card.
        final List<Card> cards = new ArrayList<>();
        if (hand != null) {
            for (Card card : hand) {
                if (card != null) {
                    cards.add(card);
                }
            }
        }
        if (cards.isEmpty()) {
            return new CardCollection();
        }
        final Player activating;
        final Card host;
        try {
            activating = sa == null ? null : sa.getActivatingPlayer();
            host = sa == null ? null : sa.getHostCard();
        } catch (Throwable t) {
            throw unsupported("chooseCardsToDiscardUnlessType", "ability unreadable");
        }
        final List<List<Card>> validSets = new ArrayList<>();
        for (List<Card> subset : enumerateSubsets(cards, 0, Math.min(min, cards.size()))) {
            if (subset.isEmpty()) {
                continue;
            }
            boolean escape = false;
            boolean full = subset.size() >= min;
            if (!full) {
                for (Card card : subset) {
                    try {
                        if (unlessTypes != null && card.isValid(unlessTypes, activating, host,
                                sa)) {
                            escape = true;
                            break;
                        }
                    } catch (Throwable t) {
                        throw unsupported("chooseCardsToDiscardUnlessType",
                                "unless-type check failed");
                    }
                }
            }
            if (full || escape) {
                validSets.add(subset);
            }
        }
        // Full-count sets (no escape needed) are always legal when available.
        for (List<Card> subset : enumerateSubsets(cards, min, Math.min(min, cards.size()))) {
            if (!validSets.contains(subset)) {
                validSets.add(subset);
            }
        }
        if (validSets.isEmpty()) {
            return new CardCollection();
        }
        if (validSets.size() == 1) {
            return new CardCollection(validSets.get(0));
        }
        if (validSets.size() > 128) {
            throw unsupported("chooseCardsToDiscardUnlessType",
                    "too many combinations to offer completely");
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(validSets.size());
        for (List<Card> subset : validSets) {
            final StringBuilder label = new StringBuilder("Discard [");
            for (Card card : subset) {
                try {
                    label.append(card.getName()).append(';');
                } catch (Throwable t) {
                    label.append("?;");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("discard", label.toString(), null,
                    new CardCollection(subset), "CARD_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.HIDDEN_ZONE_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        final CardCollection chosen = (CardCollection) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("discard option without native payload");
        }
        return chosen;
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        // WS202: exact-count subset of the actor's own hand, no cancel (mirror Human).
        final List<Card> hand = new ArrayList<>(player.getCardsIn(ZoneType.Hand));
        if (hand.size() < numDiscard) {
            throw unsupported("chooseCardsToDiscardToMaximumHandSize", "hand too small");
        }
        final List<List<Card>> subsets = enumerateSubsets(hand, numDiscard, numDiscard);
        if (subsets.isEmpty() || subsets.size() > 128) {
            throw unsupported("chooseCardsToDiscardToMaximumHandSize",
                    "cannot offer complete selection");
        }
        if (subsets.size() == 1) {
            return new CardCollection(subsets.get(0));
        }
        final List<DecisionFrame.Option> options = new ArrayList<>(subsets.size());
        for (List<Card> subset : subsets) {
            final StringBuilder label = new StringBuilder("Discard to hand size [");
            for (Card card : subset) {
                try {
                    label.append(card.getName()).append(';');
                } catch (Throwable t) {
                    label.append("?;");
                }
            }
            label.append(']');
            options.add(DecisionFrame.payloadOption("discard", label.toString(), null,
                    new CardCollection(subset), "CARD_LIST"));
        }
        final BridgeSession.FrameAnswer answer = session.parkFrame(
                DecisionFrame.Kind.HIDDEN_ZONE_SELECTION, player,
                DecisionFrame.Status.SUPPORTED, "", options);
        final CardCollection chosen = (CardCollection) answer.selected.nativePayload;
        if (chosen == null) {
            throw new IllegalStateException("discard option without native payload");
        }
        return chosen;
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        // WS202: mirror the Human two-step flow (count, then sequential picks)
        // with framed choices. Cancel aborts delving and yields empty, as Human
        // clears on cancel.
        final List<Card> yard = new ArrayList<>();
        if (grave != null) {
            for (Card card : grave) {
                if (card != null) {
                    yard.add(card);
                }
            }
        }
        final int maxDelve = Math.min(Math.max(0, genericAmount), yard.size());
        if (maxDelve == 0) {
            return CardCollection.EMPTY;
        }
        final int count = parkIntRange(DecisionFrame.Kind.COST_SELECTION, "delve_count",
                "Delve how many cards", 0, maxDelve);
        final CardCollection chosen = new CardCollection();
        final List<Card> remaining = new ArrayList<>(yard);
        for (int i = 0; i < count; i++) {
            if (remaining.isEmpty()) {
                chosen.clear();
                break;
            }
            if (remaining.size() == 1) {
                chosen.add(remaining.remove(0));
                continue;
            }
            final List<DecisionFrame.Option> options = new ArrayList<>(remaining.size() + 1);
            for (Card card : remaining) {
                final String label;
                try {
                    label = "Delve [" + card.getName() + "]";
                } catch (Throwable t) {
                    throw unsupported("chooseCardsToDelve", "label unreadable");
                }
                final String sourceName;
                try {
                    sourceName = card.getName();
                } catch (Throwable t) {
                    throw unsupported("chooseCardsToDelve", "name unreadable");
                }
                options.add(DecisionFrame.payloadOption("delve", label, sourceName, card,
                        "CARD"));
            }
            options.add(DecisionFrame.confirmOption("delve", "Stop delving", false));
            final BridgeSession.FrameAnswer answer = session.parkFrame(
                    DecisionFrame.Kind.COST_SELECTION, player,
                    DecisionFrame.Status.SUPPORTED, "", options);
            if (answer.selected.confirmValue != null
                    && !answer.selected.confirmValue.booleanValue()) {
                chosen.clear();
                break;
            }
            final Card picked = (Card) answer.selected.nativePayload;
            if (picked == null) {
                throw new IllegalStateException("delve option without native payload");
            }
            chosen.add(picked);
            remaining.remove(picked);
        }
        return chosen;
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
        return frameEffectCards("reveal",
                "Choose cards to reveal", valid, min, max);
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
        if (possible.size() > 10) {
            throw unsupported("chooseModeForAbility", "too many modes to offer completely");
        }
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
            // With repeats: bounded enumeration by size then compositions, with a
            // 129 sentinel so oversized spaces fail closed instead of truncating.
            for (int size = min; size <= num; size++) {
                enumerateWithRepeats(possible, size, 0, new ArrayList<>(), result, 129);
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
        if (size <= 128L) {
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
        // WS202: unbounded engine ranges (X defaults to 0..2^31-1) cannot be
        // enumerated without filtering the pilot's legal set, so they park a
        // validated free-integer frame instead: the pilot submits any integer
        // inside native [min,max] and the submit boundary range-checks it. No
        // affordable-cap heuristic, no truncation.
        final List<DecisionFrame.Option> sentinel = new ArrayList<>(1);
        sentinel.add(DecisionFrame.freeInputOption(actionType,
                title + " [integer " + min + ".." + max + "]"));
        final BridgeSession.FrameAnswer answer = session.parkFreeInput(kind, player,
                actionType, title, min, max, sentinel);
        if (answer.inputValue == null) {
            throw new IllegalStateException("free input without a value");
        }
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("actor", actorId());
        details.put("choice", answer.inputValue.toString());
        session.audit("number_chosen", details);
        final long picked = answer.inputValue.longValue();
        if (picked < Integer.MIN_VALUE || picked > Integer.MAX_VALUE) {
            throw new IllegalStateException("input outside int domain");
        }
        return (int) picked;
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
