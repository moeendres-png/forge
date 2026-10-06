package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Principal-scoped projection of authoritative Forge state into Lab GameState JSON.
 *
 * <p>Projection happens bridge-side from live engine objects. Only information the
 * observer principal may legally see is emitted: opponent hands become
 * {@code "<hidden>"} placeholders (count is public), libraries are always empty
 * (order hidden), and face-down cards stay {@code "<face-down>"} unless
 * {@link CardView#canBeShownTo(PlayerView)} grants the observer a look. A null
 * observer receives the public-only view (all hands redacted).
 */
public final class StateProjection {
    private StateProjection() { }

    /** Package-private test seam: forces every required read to fail (R4 regression). */
    static volatile boolean failRequiredReadsForTests;

    /** R10 seams: fail one authoritative reader family at a time. Test-only. */
    static volatile boolean failManaPoolForTests;
    static volatile boolean failCommanderDamageForTests;
    static volatile boolean failCommanderCastsForTests;

    /**
     * R17 seam: single-shot auto-clearing cause fault. When set, the next required
     * read throws it wrapped as the cause of a BridgeProjectionException, exercising
     * cause sanitization. Never written by production code.
     */
    static volatile Throwable projectionCauseForTests;

    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * Required-field reader: any failure (or an injected test fault) aborts the whole
     * projection with {@link BridgeProjectionException}. No plausible defaults.
     */
    /** Like privateField, but an unset field reads as null. */
    private static Object privateFieldOrNull(Object owner, String name) throws ReflectiveOperationException {
        try {
            return privateField(owner, name);
        } catch (NoSuchFieldException unset) {
            if (unset.getMessage() != null && unset.getMessage().endsWith(" is null")) {
                return null;
            }
            throw unset;
        }
    }

    /** Reads a private engine field that has no public reader (read only). */
    private static Object privateField(Object owner, String name) throws ReflectiveOperationException {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                final java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                final Object value = field.get(owner);
                if (value == null) {
                    throw new NoSuchFieldException(name + " is null");
                }
                return value;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static <T> T require(String field, ThrowingSupplier<T> reader) {
        if (failRequiredReadsForTests) {
            throw new BridgeProjectionException(field, "injected test fault");
        }
        try {
            if (projectionCauseForTests != null) {
                final Throwable fault = projectionCauseForTests;
                projectionCauseForTests = null;
                if (fault instanceof RuntimeException) {
                    throw (RuntimeException) fault;
                }
                throw new RuntimeException(fault);
            }
            return reader.get();
        } catch (BridgeProjectionException e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeProjectionException(field, t);
        }
    }

    public static JsonObject gameState(BridgeSession session, String observerPlayerId) {
        final Game game = session.getGame();
        if (game == null) {
            throw new BridgeProjectionException("game", "no game object");
        }
        final JsonObject state = new JsonObject();
        state.addProperty("game_id", session.getGameId());
        // WSR30 requester binding: the response names the principal it was
        // projected FOR, so a consumer can bind an observation to the requester
        // instead of inferring it from which seat happens to hold visible cards.
        // Null is the public (observer-less) view. The value is the validated
        // request principal, i.e. a seat token like "p1", never a private name.
        if (observerPlayerId == null) {
            state.add("observer_player_id", JsonNull.INSTANCE);
        } else {
            state.addProperty("observer_player_id", observerPlayerId);
        }
        final Long seedBinding = session.getSeedBinding();
        if (seedBinding == null) {
            state.add("seed", JsonNull.INSTANCE);
        } else {
            state.addProperty("seed", seedBinding.longValue());
        }
        state.add("rng_counter", JsonNull.INSTANCE);
        state.addProperty("status", statusOf(session));
        final PhaseHandler phases = require("phase_handler", () -> game.getPhaseHandler());
        final int turn = require("turn_number", () -> Math.max(0, phases.getTurn()));
        final String activeId =
                require("active_player", () -> idOrNull(session, phases.getPlayerTurn()));
        final String priorityId =
                require("priority_player", () -> idOrNull(session, phases.getPriorityPlayer()));
        final PhaseType phase = require("phase", () -> phases.getPhase());
        final String phaseName;
        final String step;
        if (phase == null) {
            // Legitimate pre-first-turn engine state (no phase has begun yet): documented
            // structural mapping, applied only when the read itself succeeded.
            phaseName = "beginning";
            step = null;
        } else {
            phaseName = mapPhase(phase);
            step = phase.name();
        }
        state.addProperty("turn_number", turn);
        if (activeId == null) {
            state.add("active_player_id", JsonNull.INSTANCE);
        } else {
            state.addProperty("active_player_id", activeId);
        }
        if (priorityId == null) {
            state.add("priority_player_id", JsonNull.INSTANCE);
        } else {
            state.addProperty("priority_player_id", priorityId);
        }
        state.addProperty("phase", phaseName);
        if (step == null) {
            state.add("step", JsonNull.INSTANCE);
        } else {
            state.addProperty("step", step);
        }
        final Player observer = observerPlayerId == null ? null : session.playerById(observerPlayerId);
        final PlayerView observerView = observer == null ? null : observer.getView();
        final List<Player> enginePlayers =
                require("players", () -> session.registryPlayers());
        final JsonArray players = new JsonArray();
        for (Player player : enginePlayers) {
            players.add(playerState(session, player, observer, observerView));
        }
        state.add("players", players);
        state.add("stack", stackState(game, observerView));
        final DecisionFrame frame = session.getCurrentFrame();
        final boolean actorScoped = observer != null && frame != null
                && observerPlayerId.equals(frame.actorPlayerId);
        state.add("legal_actions", actorScoped ? legalActions(session) : new JsonArray());
        state.add("winner_ids", winnersState(session, game));
        state.addProperty("event_sequence", (int) Math.min(Integer.MAX_VALUE, session.auditSize()));
        // WS227 additive semantic replay observability (Protocol 2.0.0 preserved:
        // all pre-existing fields unchanged; new fields are additive only).
        // rng_counter legacy null above is preserved; authoritative coordinates
        // live in rng_binding (Core-owned, regenerate-not-inject).
        try {
            state.add("rng_binding", SemanticReplay.rngBinding(session));
        } catch (Throwable t) {
            throw new BridgeProjectionException("rng_binding", t);
        }
        state.addProperty("event_offset",
                (int) Math.min(Integer.MAX_VALUE, SemanticReplay.eventOffset(session)));
        try {
            state.addProperty("public_state_digest",
                    SemanticReplay.publicStateDigest(session));
        } catch (Throwable t) {
            throw new BridgeProjectionException("public_state_digest", t);
        }
        if (observerPlayerId != null && session.playerById(observerPlayerId) != null) {
            try {
                state.addProperty("principal_observation_digest",
                        SemanticReplay.principalObservationDigest(session, observerPlayerId));
            } catch (Throwable t) {
                throw new BridgeProjectionException("principal_observation_digest", t);
            }
        } else {
            state.add("principal_observation_digest", JsonNull.INSTANCE);
        }
        try {
            state.add("terminal_outcomes", SemanticReplay.terminalOutcomes(session));
        } catch (Throwable t) {
            throw new BridgeProjectionException("terminal_outcomes", t);
        }
        state.addProperty("semantic_replay_version", SemanticReplay.SEMANTIC_REPLAY_VERSION);
        state.addProperty("decision_protocol_version", SemanticReplay.DECISION_PROTOCOL_VERSION);
        state.addProperty("tape_contract", SemanticReplay.TAPE_CONTRACT);
        return state;
    }

    /** Schema of {@link #constructedState(BridgeSession)}. */
    public static final String CONSTRUCTED_STATE_SCHEMA = "commander-lab.generic-constructed-state/4";

    /**
     * Commander-Lab #441 decision (c): the engine's normalized constructed state,
     * for the Lab's generic-lane construction proof. Served only through the
     * orchestration channel ({@link OrchestrationKey}); never a principal view.
     *
     * <p>Seats are named by their one-based seat number ({@code P1}..). Public
     * facts are plain: life, poison, loss, zone sizes, and each commander's
     * identity, zone and command-zone cast count. Each seat's library and hand
     * together (a name multiset, no order) leave only as an HMAC under the launch
     * key; no other card name of any zone leaves. The token layout is the one
     * the Lab computes from the record: schema, zone label, seat, then
     * {@code name<TAB>count} per distinct name in {@link String} order. Each
     * seat's {@code library_shuffles} counts the engine's own shuffles of that
     * library so far (its GameEventShuffle).</p>
     */
    public static JsonObject constructedState(BridgeSession session) {
        final Game game = session.getGame();
        if (game == null) {
            throw new BridgeProjectionException("game", "no game object");
        }
        final JsonObject state = new JsonObject();
        state.addProperty("schema", CONSTRUCTED_STATE_SCHEMA);
        state.addProperty("observation_scope", "orchestration_keyed_digests");
        state.addProperty("lifecycle", statusOf(session));
        final PhaseHandler phases = require("phase_handler", () -> game.getPhaseHandler());
        state.addProperty("turn_number", require("turn_number", () -> Math.max(0, phases.getTurn())));
        final PhaseType phase = require("phase", () -> phases.getPhase());
        if (phase == null) {
            state.add("phase", JsonNull.INSTANCE);
        } else {
            state.addProperty("phase", mapPhase(phase));
        }
        state.add("active_player", seatName(session, require("active_player", () -> phases.getPlayerTurn())));
        state.add("priority_player",
                seatName(session, require("priority_player", () -> phases.getPriorityPlayer())));
        state.addProperty("stack_size", require("stack", () -> game.getStack().size()));
        // Native rules state (schema /4), read from the engine, never inferred
        // by the Lab: the combat in progress, queued extra turns, triggered
        // abilities waiting to be put on the stack, and the static (continuous)
        // effects in force. The extra-turn stack and the waiting trigger lists
        // have no public reader in forge-game, so they are read reflectively;
        // a failed read fails the whole projection closed.
        final JsonObject rulesState = new JsonObject();
        final forge.game.combat.Combat combat = phases.getCombat();
        rulesState.addProperty("combat_groups",
                require("combat.bands", () -> combat == null ? 0 : combat.getAttackingBands().size()));
        rulesState.addProperty("combat_attackers",
                require("combat.attackers", () -> combat == null ? 0 : combat.getAttackers().size()));
        // PhaseHandler.addExtraTurn keeps one entry at the bottom of its stack
        // that restores the normal turn order; every entry above it is an
        // extra turn.
        rulesState.addProperty("extra_turns", require("extra_turns", () ->
                Math.max(0, ((java.util.Collection<?>) privateField(phases, "extraTurns")).size() - 1)));
        // A waiting trigger event counts only by the triggered abilities it
        // actually triggers: those already collected for it plus every active
        // trigger that can run for it (TriggerHandler.getActiveTrigger). Opening
        // draws leave ChangesZone events that trigger nothing.
        rulesState.addProperty("pending_triggers", require("pending_triggers", () -> {
            int pending = ((java.util.Collection<?>) privateField(game.getStack(),
                    "simultaneousStackEntryList")).size();
            for (Object waiting : (java.util.Collection<?>) privateField(game.getTriggerHandler(),
                    "waitingTriggers")) {
                final forge.game.trigger.TriggerWaiting event = (forge.game.trigger.TriggerWaiting) waiting;
                if (event.getTriggers() != null) {
                    for (Object ignored : event.getTriggers()) {
                        pending++;
                    }
                }
                pending += game.getTriggerHandler().getActiveTrigger(event.getMode(), event.getParams()).size();
            }
            return pending;
        }));
        // Static (continuous) effects in force, apart from the Commander format's
        // own rule effect each player's command zone carries (CR 903.8: the
        // commander may be cast from the command zone), which is reported
        // separately as a format rule, never as a scenario effect.
        final java.util.Set<Object> formatRuleSources = require("format_rule_sources", () -> {
            final java.util.Set<Object> sources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Player player : session.registryPlayers()) {
                final Object effect = privateFieldOrNull(player, "commanderEffect");
                if (effect != null) {
                    sources.add(effect);
                }
            }
            return sources;
        });
        final int[] effectCounts = require("continuous_effects", () -> {
            final int[] counts = new int[2];
            for (forge.game.StaticEffect effect : game.getStaticEffects().getEffects()) {
                counts[formatRuleSources.contains(effect.getSource()) ? 1 : 0]++;
            }
            return counts;
        });
        rulesState.addProperty("continuous_effects", effectCounts[0]);
        rulesState.addProperty("format_rule_effects", effectCounts[1]);
        state.add("rules_state", rulesState);
        final List<Player> enginePlayers = require("players", () -> session.registryPlayers());
        final JsonArray players = new JsonArray();
        for (Player player : enginePlayers) {
            final int seat = session.seatOf(player) + 1;
            final String seatId = "P" + seat;
            final JsonObject entry = new JsonObject();
            entry.addProperty("player_id", seatId);
            entry.addProperty("seat", seat);
            entry.addProperty("life", require("life:" + seatId, () -> player.getLife()));
            entry.addProperty("poison",
                    require("poison:" + seatId, () -> Math.max(0, player.getPoisonCounters())));
            entry.addProperty("lost", require("has_lost:" + seatId, () -> player.hasLost()));
            entry.addProperty("left",
                    require("in_game:" + seatId, () -> !game.getPlayers().contains(player)));
            final List<String> libraryAndHand = new ArrayList<>(zoneNames(player, ZoneType.Library));
            entry.addProperty("library_size", libraryAndHand.size());
            final List<String> hand = zoneNames(player, ZoneType.Hand);
            entry.addProperty("hand_size", hand.size());
            libraryAndHand.addAll(hand);
            entry.addProperty("library_and_hand_digest",
                    zoneDigest(seatId, "library_and_hand", libraryAndHand));
            entry.addProperty("graveyard_size",
                    require("zone.Graveyard", () -> player.getCardsIn(ZoneType.Graveyard).size()));
            entry.addProperty("exile_size",
                    require("zone.Exile", () -> player.getCardsIn(ZoneType.Exile).size()));
            entry.addProperty("library_shuffles", session.libraryShuffles(player));
            entry.addProperty("battlefield_size",
                    require("zone.Battlefield", () -> player.getCardsIn(ZoneType.Battlefield).size()));
            // Native knowledge (schema /4): hidden cards this seat may see beyond
            // its own hand, i.e. every library card and every other seat's hand
            // card the engine lets it look at (Card.mayPlayerLook).
            final JsonObject knowledge = new JsonObject();
            knowledge.addProperty("visible_hidden_cards", require("knowledge:" + seatId, () -> {
                int visible = 0;
                for (Player holder : enginePlayers) {
                    for (Card card : holder.getCardsIn(ZoneType.Library)) {
                        if (card.mayPlayerLook(player)) {
                            visible++;
                        }
                    }
                    if (holder != player) {
                        for (Card card : holder.getCardsIn(ZoneType.Hand)) {
                            if (card.mayPlayerLook(player)) {
                                visible++;
                            }
                        }
                    }
                }
                return visible;
            }));
            entry.add("knowledge", knowledge);
            // Commander damage this seat has taken (CR 903.10a).
            entry.addProperty("commander_damage_taken", require("commander_damage:" + seatId, () -> {
                int taken = 0;
                for (java.util.Map.Entry<Card, Integer> damage : player.getCommanderDamage()) {
                    taken += damage.getValue();
                }
                return taken;
            }));
            final JsonArray commanders = new JsonArray();
            for (Card commander : require("commanders:" + seatId, () -> player.getCommanders())) {
                final Card live = game.getCardState(commander, commander);
                final Card card = live == null ? commander : live;
                final JsonObject entryCommander = new JsonObject();
                entryCommander.addProperty("card_identity",
                        require("commander.name", () -> card.getName()));
                entryCommander.add("owner", seatName(session, require("commander.owner", () -> card.getOwner())));
                final ZoneType zone = require("commander.zone", () -> {
                    final forge.game.zone.Zone at = game.getZoneOf(card);
                    return at == null ? null : at.getZoneType();
                });
                if (zone == null) {
                    entryCommander.add("zone", JsonNull.INSTANCE);
                } else {
                    entryCommander.addProperty("zone", zone.name().toLowerCase(java.util.Locale.ROOT));
                }
                entryCommander.addProperty("prior_command_zone_cast_count",
                        require("commander.casts", () -> Math.max(0, player.getCommanderCast(card))));
                // Native object attributes (schema /3), read from the engine's
                // card: its controller, counters (the engine's own counter name,
                // CounterType.getName() as the battlefield projection uses it,
                // lower-cased: "+1/+1", "charge", "acquired taste"; never the
                // enum constant P1P1 / ACQUIREDTASTE), face-down status, tapped state and attached
                // cards. Nothing is inferred from the request.
                entryCommander.add("controller",
                        seatName(session, require("commander.controller", () -> card.getController())));
                final JsonObject counters = new JsonObject();
                for (com.google.common.collect.Multiset.Entry<forge.game.card.CounterType> counter
                        : require("commander.counters", () -> card.getCounters()).entrySet()) {
                    if (counter.getCount() > 0) {
                        counters.addProperty(counter.getElement().getName().toLowerCase(java.util.Locale.ROOT),
                                counter.getCount());
                    }
                }
                entryCommander.add("counters", counters);
                entryCommander.addProperty("face_down", require("commander.face_down", () -> card.isFaceDown()));
                entryCommander.addProperty("tapped", require("commander.tapped", () -> card.isTapped()));
                entryCommander.addProperty("attachments",
                        require("commander.attachments", () -> card.getAttachedCards().size()));
                commanders.add(entryCommander);
            }
            entry.add("commanders", commanders);
            players.add(entry);
        }
        state.add("players", players);
        // No seed value: Rules seed control is acknowledged on game creation.
        return state;
    }

    private static JsonElement seatName(BridgeSession session, Player player) {
        if (player == null) {
            return JsonNull.INSTANCE;
        }
        return new JsonPrimitive("P" + (session.seatOf(player) + 1));
    }

    /** HMAC under the launch's orchestration key over a seat's zone content as a name multiset. */
    static String zoneDigest(String seatId, String zone, List<String> names) {
        final java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>();
        for (String name : names) {
            counts.merge(name, 1, Integer::sum);
        }
        final List<String> tokens = new ArrayList<>();
        tokens.add(CONSTRUCTED_STATE_SCHEMA);
        tokens.add(zone);
        tokens.add(seatId);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            tokens.add(entry.getKey() + "\t" + entry.getValue());
        }
        return OrchestrationKey.digest(tokens);
    }

    /**
     * R9/R14B principal-scoped bridge metadata. Frame-bound fields (revision, pending
     * decision incl. kind/status/actor/count/reason, state hash) are exposed ONLY to
     * the frame's actor; anyone else receives null/-1. Raw internal failure strings
     * (fail_reason, last_execution_error) are NEVER exposed here: non-actors get
     * nothing, and the actor gets its execution diagnostic only when bound to its
     * current frame. Terminal status ("failed"/"aborted") is itself the public signal.
     */
    public static JsonObject bridgeMeta(BridgeSession session, String observerPlayerId) {
        final JsonObject meta = new JsonObject();
        meta.addProperty("session_status", session.getStatus().name());
        final DecisionFrame frame = session.getCurrentFrame();
        final boolean actorScoped = observerPlayerId != null && frame != null
                && observerPlayerId.equals(frame.actorPlayerId);
        if (actorScoped) {
            meta.addProperty("revision", frame.revision);
            meta.add("pending_decision", decisionSummary(session, frame));
        } else {
            meta.addProperty("revision", -1);
            meta.add("pending_decision", JsonNull.INSTANCE);
        }
        if (actorScoped) {
            try {
                meta.addProperty("state_hash", StateHash.ofGame(session.getGame(), session));
            } catch (Throwable t) {
                meta.addProperty("state_hash", "unavailable");
            }
        } else {
            // The digest covers private zones; only the actor may hold it.
            meta.add("state_hash", JsonNull.INSTANCE);
        }
        if (actorScoped && session.isExecutionErrorBoundTo(observerPlayerId, frame.revision)) {
            meta.addProperty("last_execution_error", session.getLastExecutionError());
        }
        return meta;
    }

    /** Backwards-compatible entry point: public observer, fully redacted metadata. */
    public static JsonObject bridgeMeta(BridgeSession session) {
        return bridgeMeta(session, null);
    }

    public static JsonObject decisionSummary(DecisionFrame frame) {
        final JsonObject summary = new JsonObject();
        summary.addProperty("revision", frame.revision);
        summary.addProperty("kind", frame.kind.name());
        summary.addProperty("status", frame.status.name());
        summary.addProperty("actor", frame.actorPlayerId);
        summary.addProperty("options", frame.options.size());
        if (frame.freeInput) {
            summary.addProperty("free_input", true);
            summary.addProperty("input_min", frame.inputMin);
            summary.addProperty("input_max", frame.inputMax);
        }
        if (frame.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
            summary.addProperty("divided_total", frame.dividedTotal);
            summary.addProperty("divided_min_per_target", frame.dividedMinPerTarget);
            summary.addProperty("divided_up_to", frame.dividedUpTo);
        }
        if (!frame.reason.isEmpty()) {
            summary.addProperty("reason", frame.reason);
        }
        return summary;
    }

    /**
     * WS227 additive semantic decision summary (Protocol 2.0.0 preserved:
     * all legacy fields unchanged). Adds neutral decision class, legal-set
     * multiset semantics and RNG/event coordinates. Actor-scoped by the caller.
     */
    public static JsonObject decisionSummary(BridgeSession session, DecisionFrame frame) {
        final JsonObject summary = decisionSummary(frame);
        try {
            summary.addProperty("decision_class", SemanticReplay.decisionClass(frame.kind));
        } catch (Throwable t) {
            throw new BridgeProjectionException("decision_class", t);
        }
        summary.addProperty("decision_protocol_version",
                SemanticReplay.DECISION_PROTOCOL_VERSION);
        summary.addProperty("lifecycle", SemanticReplay.isLifecycle(frame.kind));
        try {
            summary.addProperty("legal_set_digest",
                    SemanticReplay.legalSetDigest(frame, session));
        } catch (Throwable t) {
            throw new BridgeProjectionException("legal_set_digest", t);
        }
        summary.addProperty("legal_set_size", SemanticReplay.legalSetSize(frame));
        try {
            summary.add("rng_binding", SemanticReplay.rngBinding(session));
        } catch (Throwable t) {
            throw new BridgeProjectionException("rng_binding", t);
        }
        summary.addProperty("event_offset",
                (int) Math.min(Integer.MAX_VALUE, SemanticReplay.eventOffset(session)));
        try {
            summary.addProperty("public_state_digest",
                    SemanticReplay.publicStateDigest(session));
        } catch (Throwable t) {
            throw new BridgeProjectionException("public_state_digest", t);
        }
        try {
            summary.addProperty("principal_observation_digest",
                    SemanticReplay.principalObservationDigest(session, frame.actorPlayerId));
        } catch (Throwable t) {
            throw new BridgeProjectionException("principal_observation_digest", t);
        }
        summary.addProperty("semantic_replay_version",
                SemanticReplay.SEMANTIC_REPLAY_VERSION);
        return summary;
    }

    public static JsonArray legalActions(BridgeSession session) {
        final JsonArray actions = new JsonArray();
        final DecisionFrame frame = session.getCurrentFrame();
        if (frame == null || frame.status != DecisionFrame.Status.SUPPORTED) {
            return actions;
        }
        for (DecisionFrame.Option option : frame.options) {
            if (option.isConsumed()) {
                continue;
            }
            actions.add(legalAction(session, frame, option));
        }
        return actions;
    }

    public static JsonObject legalAction(DecisionFrame frame, DecisionFrame.Option option) {
        final JsonObject action = new JsonObject();
        action.addProperty("action_id", option.optionId);
        action.addProperty("actor_id", frame.actorPlayerId);
        action.addProperty("action_type", option.actionType);
        if (option.sourceCardName == null) {
            action.add("source_object_id", JsonNull.INSTANCE);
        } else {
            action.addProperty("source_object_id", option.sourceCardName);
        }
        action.add("target_ids", new JsonArray());
        action.add("allowed_target_ids", new JsonArray());
        action.add("modes", new JsonArray());
        if (frame.freeInput && DecisionFrame.FREE_INPUT_ID.equals(option.optionId)) {
            final JsonObject schema = new JsonObject();
            schema.addProperty("type", "integer");
            schema.addProperty("min", frame.inputMin);
            schema.addProperty("max", frame.inputMax);
            action.add("choices_schema", schema);
        } else if (frame.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION) {
            final JsonObject schema = new JsonObject();
            schema.addProperty("type", "divided_allocation_target");
            schema.addProperty("divided_total", frame.dividedTotal);
            schema.addProperty("divided_min_per_target", frame.dividedMinPerTarget);
            schema.addProperty("divided_up_to", frame.dividedUpTo);
            action.add("choices_schema", schema);
        } else {
            action.add("choices_schema", new JsonObject());
        }
        action.add("cost", new JsonObject());
        final JsonObject metadata = new JsonObject();
        metadata.addProperty("revision", frame.revision);
        metadata.addProperty("frame_kind", frame.kind.name());
        metadata.addProperty("label", option.label);
        if (!option.costOrderIndices.isEmpty()) {
            final String decisionSubtype;
            if ("COST_ORDER".equals(option.payloadKind)) {
                decisionSubtype = "cost_order";
            } else if ("COST_PART".equals(option.payloadKind)) {
                decisionSubtype = "cost_order_next";
            } else {
                throw new BridgeProjectionException("cost_order_indices",
                        "cost-order metadata has unexpected payload kind");
            }
            metadata.addProperty("decision_subtype", decisionSubtype);
            final JsonArray costOrderIndices = new JsonArray();
            for (Integer index : option.costOrderIndices) {
                costOrderIndices.add(index);
            }
            metadata.add("cost_order_indices", costOrderIndices);
        }
        action.add("metadata", metadata);
        return action;
    }

    /**
     * WS227 additive semantic legal action (Protocol 2.0.0 preserved: all
     * legacy fields unchanged). Adds neutral decision class plus stable
     * semantic fingerprint/key. The fingerprint is the replay identity;
     * action_id remains the process-local submission handle (never the replay
     * identity). Actor-scoped by the caller.
     */
    public static JsonObject legalAction(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final JsonObject action = legalAction(frame, option);
        try {
            action.addProperty("decision_class", SemanticReplay.decisionClass(frame.kind));
        } catch (Throwable t) {
            throw new BridgeProjectionException("decision_class", t);
        }
        try {
            action.addProperty("semantic_fingerprint",
                    SemanticReplay.optionFingerprint(frame, option, session));
        } catch (Throwable t) {
            throw new BridgeProjectionException("semantic_fingerprint", t);
        }
        try {
            action.addProperty("semantic_key",
                    SemanticReplay.semanticKey(frame, option, session));
        } catch (Throwable t) {
            throw new BridgeProjectionException("semantic_key", t);
        }
        // Additive identity references for the option's payload. Labels carry
        // names only, so on a multiplayer board two players' Sol Rings (or two
        // opponents) were indistinguishable to the pilot. Not part of the
        // semantic fingerprint; actor-scoped like the rest of the action.
        final JsonArray refs = objectRefs(session, option.nativePayload,
                session.playerById(frame.actorPlayerId));
        if (refs.size() > 0) {
            action.getAsJsonObject("metadata").add("object_refs", refs);
        }
        return action;
    }

    static JsonArray objectRefs(BridgeSession session, Object payload, Player actor) {
        final JsonArray refs = new JsonArray();
        if (payload instanceof Iterable) {
            for (Object item : (Iterable<?>) payload) {
                final JsonObject ref = objectRef(session, item, actor);
                if (ref != null) {
                    refs.add(ref);
                }
            }
        } else {
            final JsonObject ref = objectRef(session, payload, actor);
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * A card's identity is referenced only when the deciding player may see
     * it: a face-up card in a public zone, or a card the actor owns (their
     * own hand or library). Anything else (an opponent's unrevealed hand
     * card, a face-down permanent) is marked hidden, never named or numbered,
     * matching the "&lt;hidden&gt;" labels of those frames.
     */
    private static boolean identityVisible(Card card, Player actor) {
        if (card.isFaceDown()) {
            return actor != null && actor.equals(card.getController());
        }
        final forge.game.zone.Zone zone = card.getZone();
        if (zone == null) {
            return false;
        }
        switch (zone.getZoneType()) {
            case Battlefield:
            case Graveyard:
            case Exile:
            case Stack:
            case Command:
                return true;
            default:
                return actor != null && actor.equals(card.getOwner());
        }
    }

    private static JsonObject objectRef(BridgeSession session, Object item, Player actor) {
        try {
            if (item instanceof Card) {
                final Card card = (Card) item;
                final JsonObject ref = new JsonObject();
                ref.addProperty("kind", "card");
                if (!identityVisible(card, actor)) {
                    ref.addProperty("hidden", true);
                    return ref;
                }
                ref.addProperty("card_id", card.getId());
                ref.addProperty("name", card.getName());
                if (card.getController() != null) {
                    ref.addProperty("controller", session.playerIdOf(card.getController()));
                }
                if (card.getZone() != null) {
                    ref.addProperty("zone", card.getZone().getZoneType().name());
                }
                return ref;
            }
            if (item instanceof Player) {
                final JsonObject ref = new JsonObject();
                ref.addProperty("kind", "player");
                ref.addProperty("player_id", session.playerIdOf((Player) item));
                return ref;
            }
        } catch (Throwable t) {
            throw new BridgeProjectionException("object_refs", t);
        }
        return null;
    }

    private static JsonObject playerState(BridgeSession session, Player player, Player observer,
            PlayerView observerView) {
        final JsonObject state = new JsonObject();
        state.addProperty("player_id", session.playerIdOf(player));
        state.addProperty("seat", session.seatOf(player));
        // WSR30 requester binding: mark the principal this observation is scoped
        // to. Derived from the validated observer request context, never from
        // which seat happens to hold visible cards, and never reconstructed from
        // redaction output. In a principal observation "actor" is the observing
        // principal; the decision frame's actor is a separate concept reported
        // under `decision`. Exactly one player is marked when the caller named a
        // valid observer; none are marked for the public view.
        state.addProperty("is_actor", observer != null && observer.equals(player));
        final int life = require("life:" + state.get("player_id").getAsString(), () -> player.getLife());
        final int poison = require("poison:" + state.get("player_id").getAsString(),
                () -> Math.max(0, player.getPoisonCounters()));
        state.addProperty("life", life);
        state.addProperty("poison_counters", poison);
        state.add("commander_damage_received", commanderDamage(session, player));
        state.add("commander_cast_count", commanderCasts(session, player));
        state.add("mana_pool", manaPool(player));
        state.add("zones", zones(player, observer, observerView));
        final int landRemaining = require("land_plays:" + state.get("player_id").getAsString(),
                () -> Math.max(0, player.getMaxLandPlays() - player.getLandsPlayedThisTurn()));
        state.addProperty("land_plays_remaining", landRemaining);
        final boolean lost =
                require("has_lost:" + state.get("player_id").getAsString(), () -> player.hasLost());
        state.addProperty("has_lost", lost);
        String lossReason = null;
        try {
            if (lost && player.getOutcome() != null && player.getOutcome().lossState != null) {
                lossReason = player.getOutcome().lossState.name();
            }
        } catch (Throwable t) {
            lossReason = null;
        }
        if (lossReason == null) {
            state.add("loss_reason", JsonNull.INSTANCE);
        } else {
            state.addProperty("loss_reason", lossReason);
        }
        return state;
    }

    /**
     * R10: commander damage is an advertised visible field, so its read is required.
     * A legitimately empty map (no damage dealt) still succeeds; only read failure
     * throws. Never a plausible {} on exception.
     */
    private static JsonObject commanderDamage(BridgeSession session, Player player) {
        return require("commander_damage", () -> {
            if (failCommanderDamageForTests) {
                throw new BridgeProjectionException("commander_damage", "injected test fault");
            }
            final JsonObject damage = new JsonObject();
            for (Map.Entry<Card, Integer> entry : player.getCommanderDamage()) {
                damage.addProperty(entry.getKey().getName(), entry.getValue());
            }
            return damage;
        });
    }

    /**
     * R15: commander cast counts from Forge's native Commander identity. Iterates the
     * player's authoritative Commander Card collection (zone-independent: commanders
     * on the Stack, battlefield or anywhere else are the same persistent identity),
     * resolves the live card via {@code Game.getCardState} (Forge re-objects cards
     * across zone changes), and reads {@code Player.getCommanderCast} directly. No
     * zone-name scanning, no bridge tax rules. A legitimate Forge zero stays zero;
     * read failure throws.
     */
    private static JsonObject commanderCasts(BridgeSession session, Player player) {
        return require("commander_casts", () -> {
            if (failCommanderCastsForTests) {
                throw new BridgeProjectionException("commander_casts", "injected test fault");
            }
            final Game game = session.getGame();
            if (game == null) {
                throw new BridgeProjectionException("commander_casts", "no game");
            }
            final JsonObject casts = new JsonObject();
            for (Card commander : player.getCommanders()) {
                final Card live = game.getCardState(commander, commander);
                final Card target = live == null ? commander : live;
                final String name;
                try {
                    name = target.getName();
                } catch (Throwable t) {
                    throw new BridgeProjectionException("commander_casts.name", t);
                }
                casts.addProperty(name, Math.max(0, player.getCommanderCast(target)));
            }
            return casts;
        });
    }

    /**
     * R10: the mana pool is authoritative engine state; its read is required.
     * A legitimately empty pool still succeeds. Never a plausible {} on exception.
     */
    private static JsonObject manaPool(Player player) {
        return require("mana_pool", () -> {
            if (failManaPoolForTests) {
                throw new BridgeProjectionException("mana_pool", "injected test fault");
            }
            final JsonObject pool = new JsonObject();
            final PlayerView view = player.getView();
            pool.addProperty("W", Math.max(0, view.getMana(MagicColor.WHITE)));
            pool.addProperty("U", Math.max(0, view.getMana(MagicColor.BLUE)));
            pool.addProperty("B", Math.max(0, view.getMana(MagicColor.BLACK)));
            pool.addProperty("R", Math.max(0, view.getMana(MagicColor.RED)));
            pool.addProperty("G", Math.max(0, view.getMana(MagicColor.GREEN)));
            pool.addProperty("C", Math.max(0, view.getMana(MagicColor.COLORLESS)));
            return pool;
        });
    }

    private static JsonObject zones(Player player, Player observer, PlayerView observerView) {
        final JsonObject zones = new JsonObject();
        zones.add("library", new JsonArray());
        zones.add("library_size", require("zones.library_size",
                () -> {
                    final com.google.gson.JsonPrimitive size =
                            new com.google.gson.JsonPrimitive(player.getCardsIn(ZoneType.Library).size());
                    return size;
                }));
        zones.add("hand", require("zones.hand", () -> handZone(player, observer)));
        zones.add("battlefield", require("zones.battlefield", () -> battlefieldZone(player, observerView)));
        zones.add("battlefield_details", require("zones.battlefield_details",
                () -> battlefieldDetails(player, observerView)));
        zones.add("graveyard", require("zones.graveyard", () -> namesZone(player, ZoneType.Graveyard)));
        zones.add("exile", require("zones.exile", () -> exileZone(player, observerView)));
        zones.add("command", require("zones.command", () -> namesZone(player, ZoneType.Command)));
        return zones;
    }

    /**
     * WSR24 (HIDDEN_12): hand visibility follows the engine's own authority —
     * the owner, plus the engine-declared controlling player (Mindslaver
     * shape, mirroring the {@code mindSlaveMaster} branch of
     * {@link CardView#canBeShownTo}). Anyone else receives count-preserving
     * {@code "<hidden>"} placeholders. The bridge exposes engine truth; it
     * invents no visibility of its own.
     */
    private static JsonArray handZone(Player player, Player observer) {
        final JsonArray hand = new JsonArray();
        final List<String> names = zoneNames(player, ZoneType.Hand);
        if (observer != null && (observer.equals(player) || isControlling(observer, player))) {
            for (String name : names) {
                hand.add(name);
            }
        } else {
            for (int i = 0; i < names.size(); i++) {
                hand.add("<hidden>");
            }
        }
        return hand;
    }

    private static boolean isControlling(Player observer, Player player) {
        return require("hand.control", () -> {
            final Player master;
            try {
                master = player.getControllingPlayer();
            } catch (Throwable t) {
                throw new BridgeProjectionException("hand.control", t);
            }
            return master != null && observer.equals(master);
        });
    }

    private static JsonArray battlefieldZone(Player player, PlayerView observerView) {
        final JsonArray zone = new JsonArray();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            zone.add(shownName(card, observerView));
        }
        return zone;
    }

    /**
     * WS216 additive battlefield detail projection for pipe semantic outcomes.
     *
     * <p>Exposes only public battlefield truth alongside the existing name array:
     * tapped status (public), counters and power/toughness for cards the observer
     * is entitled to see (same {@code shownName} gates). Hidden/face-down cards
     * keep redacted markers with empty counters and null PT, never names. Library
     * order stays hidden; only {@code library_size} (public count) is exposed.
     *
     * <p>G1-R1 (#561 C3): the engine-native first-turn-control flag is exposed as
     * {@code controlled_since_turn_began} — the negation of
     * {@link Card#isFirstTurnControlled()} (raw summoning-sickness, CR 302.6), not
     * {@code hasSickness()} (which folds in haste). The Lab checkpoint compares
     * this field instead of assuming attack eligibility. Control history is
     * public game state, so it is emitted for every battlefield entry, including
     * redacted ones.
     */
    private static JsonArray battlefieldDetails(Player player, PlayerView observerView) {
        final JsonArray details = new JsonArray();
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            final JsonObject entry = new JsonObject();
            final String shown = shownName(card, observerView);
            entry.addProperty("name", shown);
            final boolean tapped;
            try {
                tapped = card.isTapped();
            } catch (Throwable t) {
                throw new BridgeProjectionException("card.tapped", t);
            }
            entry.addProperty("tapped", tapped);
            final boolean firstTurnControlled;
            try {
                firstTurnControlled = card.isFirstTurnControlled();
            } catch (Throwable t) {
                throw new BridgeProjectionException("card.first_turn_controlled", t);
            }
            entry.addProperty("controlled_since_turn_began", !firstTurnControlled);
            final boolean redacted =
                    "<hidden>".equals(shown) || "<face-down>".equals(shown);
            if (redacted) {
                entry.add("counters", new JsonObject());
                entry.add("power", JsonNull.INSTANCE);
                entry.add("toughness", JsonNull.INSTANCE);
            } else {
                final JsonObject counters = new JsonObject();
                try {
                    for (forge.game.card.CounterType type : card.getCounters().elementSet()) {
                        if (type == null) {
                            continue;
                        }
                        counters.addProperty(type.getName(), card.getCounters(type));
                    }
                } catch (Throwable t) {
                    throw new BridgeProjectionException("card.counters", t);
                }
                entry.add("counters", counters);
                try {
                    entry.addProperty("power", card.getNetPower());
                } catch (Throwable t) {
                    throw new BridgeProjectionException("card.power", t);
                }
                try {
                    entry.addProperty("toughness", card.getNetToughness());
                } catch (Throwable t) {
                    throw new BridgeProjectionException("card.toughness", t);
                }
            }
            details.add(entry);
        }
        return details;
    }

    private static JsonArray exileZone(Player player, PlayerView observerView) {
        final JsonArray zone = new JsonArray();
        for (Card card : player.getCardsIn(ZoneType.Exile)) {
            zone.add(shownName(card, observerView));
        }
        return zone;
    }

    /**
     * R3 literal visibility: Forge's view authorization decides, using both native
     * gates exactly as the engine defines them — {@code canBeShownTo} for the zone
     * gate and {@code canFaceDownBeShownTo} for the face gate (battlefield cards are
     * zone-visible to all, but face-down identity additionally requires the face
     * gate, e.g. controller or an explicit may-look grant). Anything not shown to
     * the observer is a redacted marker, never a name.
     */
    private static String shownName(Card card, PlayerView observerView) {
        final boolean faceDown;
        try {
            faceDown = card.isFaceDown();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.face_down", t);
        }
        if (observerView == null) {
            if (faceDown) {
                return "<face-down>";
            }
            try {
                return card.getName();
            } catch (Throwable t) {
                throw new BridgeProjectionException("card.name", t);
            }
        }
        final CardView view;
        try {
            view = card.getView();
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.view", t);
        }
        final boolean shown;
        try {
            shown = view != null && view.canBeShownTo(observerView)
                    && view.canFaceDownBeShownTo(observerView);
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.visibility", t);
        }
        if (!shown) {
            return faceDown ? "<face-down>" : "<hidden>";
        }
        if (!faceDown) {
            try {
                return card.getName();
            } catch (Throwable t) {
                throw new BridgeProjectionException("card.name", t);
            }
        }
        // Face-down but shown to this observer (controller or explicit may-look grant):
        // the observer is entitled to the true identity. The engine's current-state
        // name is blank while face-down, so use the native alternate (true) state,
        // falling back to the immutable paper identity. Never reached for unauthorized
        // observers (they received a marker above).
        try {
            final CardView.CardStateView alternate = view.getAlternateState();
            if (alternate != null && alternate.getName() != null
                    && !alternate.getName().isEmpty()) {
                return alternate.getName();
            }
            if (card.getPaperCard() != null && card.getPaperCard().getName() != null) {
                return card.getPaperCard().getName();
            }
        } catch (Throwable t) {
            throw new BridgeProjectionException("card.truename", t);
        }
        throw new BridgeProjectionException("card.truename", "no true identity available");
    }

    private static JsonArray namesZone(Player player, ZoneType zone) {
        final JsonArray result = new JsonArray();
        for (String name : zoneNames(player, zone)) {
            result.add(name);
        }
        return result;
    }

    private static List<String> zoneNames(Player player, ZoneType zone) {
        final List<String> names = new ArrayList<>();
        for (Card card : player.getCardsIn(zone)) {
            try {
                names.add(card.getName());
            } catch (Throwable t) {
                throw new BridgeProjectionException("zone." + zone.name(), t);
            }
        }
        return names;
    }

    private static JsonArray stackState(Game game, PlayerView observerView) {
        return require("stack", () -> {
            final JsonArray stack = new JsonArray();
            for (SpellAbilityStackInstance si : game.getStack()) {
                stack.add(stackText(si, observerView));
            }
            return stack;
        });
    }

    private static JsonArray winnersState(BridgeSession session, Game game) {
        return require("winners", () -> {
            final JsonArray winners = new JsonArray();
            if (game.isGameOver()) {
                final GameOutcome outcome = game.getOutcome();
                if (outcome == null || !outcome.isDraw()) {
                    for (Player player : game.getPlayers()) {
                        if (!player.hasLost()) {
                            winners.add(session.playerIdOf(player));
                        }
                    }
                }
            }
            return winners;
        });
    }

    /**
     * R13: stack sources obey BOTH native gates. ZoneType.Stack is zone-visible to
     * everyone, so the face gate decides face-down identity: only the controller or
     * an explicit may-look authority sees it. Anything else (including null/public
     * observers) receives only the redacted marker — the hidden name and the
     * source-specific description are never read for them. Gate-read failures throw
     * rather than defaulting to visible.
     */
    private static String stackText(SpellAbilityStackInstance si, PlayerView observerView) {
        final Card source = si.getSourceCard();
        if (source != null) {
            final boolean faceDown;
            try {
                faceDown = source.isFaceDown();
            } catch (Throwable t) {
                throw new BridgeProjectionException("stack.facedown", t);
            }
            if (faceDown) {
                boolean shown = false;
                try {
                    final CardView view = source.getView();
                    shown = observerView != null && view != null
                            && view.canBeShownTo(observerView)
                            && view.canFaceDownBeShownTo(observerView);
                } catch (Throwable t) {
                    throw new BridgeProjectionException("stack.visibility", t);
                }
                if (!shown) {
                    return "<face-down spell>";
                }
            }
        }
        final String text = si.getStackDescription();
        return text == null || text.isEmpty() ? "<spell>" : text;
    }

    private static String idOrNull(BridgeSession session, Player player) {
        return player == null ? null : session.playerIdOf(player);
    }

    private static String statusOf(BridgeSession session) {
        switch (session.getStatus()) {
            case CREATED:
                return "not_started";
            case RUNNING:
                return "in_progress";
            case OVER:
                return "completed";
            case FAILED:
            case CLOSED:
            default:
                return "aborted";
        }
    }

    private static String mapPhase(PhaseType phase) {
        switch (phase) {
            case UNTAP:
            case UPKEEP:
            case DRAW:
                return "beginning";
            case MAIN1:
                return "precombat_main";
            case COMBAT_BEGIN:
            case COMBAT_DECLARE_ATTACKERS:
            case COMBAT_DECLARE_BLOCKERS:
            case COMBAT_FIRST_STRIKE_DAMAGE:
            case COMBAT_DAMAGE:
            case COMBAT_END:
                return "combat";
            case MAIN2:
                return "postcombat_main";
            case END_OF_TURN:
            case CLEANUP:
            default:
                return "ending";
        }
    }
}
