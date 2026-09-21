/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011 Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package forge.game.qualification;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Qualification-only native history for semantic facts that are part of a restored game
 * snapshot but are not otherwise retained by Forge after the originating event completes.
 *
 * This class does not decide Magic legality and is never consulted by normal game rules. It is
 * analogous to RestoredSpellCastHistory: a state-loader records already-completed native history
 * while restoring a serialized snapshot, and qualification observers later read that history
 * back from the isolated Forge process instead of reconstructing it from the request.
 *
 * WS-45 deliberately stores structured facts rather than canonical request JSON. The observer
 * must re-serialize these typed records; a request blob cannot be returned as evidence.
 */
public final class RestoredQualificationHistory {
    private RestoredQualificationHistory() {
    }

    public enum ExtraTurnResolutionKind {
        TARGETED_EXTRA_TURN_SPELL,
        SELF_EXTRA_TURN_SPELL_LATER_SAME_TURN
    }

    public enum EliminationReason {
        LIFE_TOTAL_ZERO
    }

    public enum CommanderMoveTiming {
        REPLACEMENT_EFFECT_BEFORE_MOVE,
        STATE_BASED_ACTION
    }

    public enum CommanderRelationKind {
        PARTNER
    }

    public enum KnowledgeFactKind {
        KNOWN_OBJECT_IDENTITY,
        KNOWN_LIBRARY_RANGE,
        FACE_DOWN_LOOK_PERMISSION,
        TEMPORARY_PERMISSION,
        CHANNEL_UNDER_TEST,
        HONEY_SENTINEL,
        INVALIDATION_CONDITION,
        OBLIGATION,
        ORDERED_KNOWN_INFORMATION,
        PERMITTED_PUBLIC_METADATA,
        PROHIBITED_METADATA
    }

    public record ExtraTurnCreation(
            int sequence,
            int playerId,
            int sourceCardId,
            ExtraTurnResolutionKind resolutionKind) {
    }

    public record Elimination(
            int playerId,
            EliminationReason reason) {
    }

    public record CommanderZoneMove(
            int commanderCardId,
            ZoneType from,
            ZoneType to,
            CommanderMoveTiming timing) {
    }

    public record CommanderRelation(
            int firstCommanderCardId,
            int secondCommanderCardId,
            CommanderRelationKind relationKind) {
        public CommanderRelation {
            if (firstCommanderCardId == secondCommanderCardId || relationKind == null) {
                throw new IllegalArgumentException("RESTORE_QUALIFICATION_BAD_COMMANDER_RELATION");
            }
        }
    }

    public record PredeterminedDraw(
            String channel,
            String operation,
            String result) {
        public PredeterminedDraw {
            if (channel == null || channel.isBlank() || operation == null || operation.isBlank()
                    || result == null || result.isBlank()) {
                throw new IllegalArgumentException("RESTORE_QUALIFICATION_BAD_PREDETERMINED_DRAW");
            }
        }
    }

    public record RulesRandomness(
            Long seed,
            String seedBinding,
            List<String> channels,
            List<PredeterminedDraw> predeterminedDraws,
            boolean pilotRandomnessProhibited,
            boolean providerNativeRngCallsRecorded) {
        public RulesRandomness {
            channels = List.copyOf(channels);
            predeterminedDraws = List.copyOf(predeterminedDraws);
            if (seed == null && (seedBinding == null || seedBinding.isBlank())) {
                throw new IllegalArgumentException("RESTORE_QUALIFICATION_RANDOMNESS_BINDING_REQUIRED");
            }
            if (!pilotRandomnessProhibited) {
                throw new IllegalArgumentException("RESTORE_QUALIFICATION_PILOT_RANDOMNESS_MUST_BE_PROHIBITED");
            }
        }
    }

    /**
     * A deliberately typed union record. Only fields meaningful to {@code kind} may be populated.
     * Stable semantic IDs are qualification identity metadata; they are not Forge legality inputs.
     */
    public record KnowledgeFact(
            KnowledgeFactKind kind,
            String viewer,
            String objectId,
            String playerId,
            String zone,
            String permission,
            String scope,
            Integer start,
            Integer count,
            Boolean ordered,
            Boolean persistsWhileSameObject,
            String beforeEvent,
            String controlledPlayer,
            String controller,
            String value,
            List<String> values) {
        public KnowledgeFact {
            if (kind == null) {
                throw new IllegalArgumentException("RESTORE_QUALIFICATION_KNOWLEDGE_KIND_REQUIRED");
            }
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record ViewerKnowledgeState(
            String viewer,
            List<KnowledgeFact> facts) {
        public ViewerKnowledgeState {
            if (viewer == null || viewer.isBlank()) {
                throw new IllegalArgumentException("RESTORE_QUALIFICATION_VIEWER_REQUIRED");
            }
            facts = List.copyOf(facts);
        }
    }

    public record KnowledgePolicy(
            String channelPolicy,
            List<ViewerKnowledgeState> viewers,
            boolean nativeReferentsValidated) {
        public KnowledgePolicy {
            if (channelPolicy == null || channelPolicy.isBlank() || !nativeReferentsValidated) {
                throw new IllegalArgumentException("RESTORE_QUALIFICATION_KNOWLEDGE_POLICY_NOT_VALIDATED");
            }
            viewers = List.copyOf(viewers);
        }
    }

    private static final class History {
        final List<ExtraTurnCreation> extraTurns = new ArrayList<>();
        final List<Elimination> eliminations = new ArrayList<>();
        final List<CommanderZoneMove> commanderMoves = new ArrayList<>();
        final List<CommanderRelation> commanderRelations = new ArrayList<>();
        RulesRandomness randomness;
        KnowledgePolicy knowledgePolicy;
    }

    private static final Map<Game, History> HISTORY =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static History history(final Game game) {
        if (game == null) {
            throw new IllegalArgumentException("RESTORE_QUALIFICATION_GAME_REQUIRED");
        }
        synchronized (HISTORY) {
            return HISTORY.computeIfAbsent(game, ignored -> new History());
        }
    }

    public static void clear(final Game game) {
        HISTORY.remove(game);
    }

    public static void restoreExtraTurnCreation(
            final Game game,
            final int sequence,
            final Player player,
            final Card source,
            final ExtraTurnResolutionKind resolutionKind) {
        if (sequence <= 0 || player == null || source == null || resolutionKind == null) {
            throw new IllegalArgumentException("RESTORE_QUALIFICATION_BAD_EXTRA_TURN");
        }
        final History h = history(game);
        final int expected = h.extraTurns.size() + 1;
        if (sequence != expected) {
            throw new IllegalArgumentException("RESTORE_QUALIFICATION_EXTRA_TURN_SEQUENCE:" + sequence + ":expected:" + expected);
        }
        h.extraTurns.add(new ExtraTurnCreation(sequence, player.getId(), source.getId(), resolutionKind));
    }

    public static void restoreElimination(
            final Game game,
            final Player player,
            final EliminationReason reason) {
        if (player == null || reason == null) {
            throw new IllegalArgumentException("RESTORE_QUALIFICATION_BAD_ELIMINATION");
        }
        history(game).eliminations.add(new Elimination(player.getId(), reason));
    }

    public static void restoreCommanderZoneMove(
            final Game game,
            final Card commander,
            final ZoneType from,
            final ZoneType to,
            final CommanderMoveTiming timing) {
        if (commander == null || from == null || to == null || timing == null) {
            throw new IllegalArgumentException("RESTORE_QUALIFICATION_BAD_COMMANDER_MOVE");
        }
        history(game).commanderMoves.add(new CommanderZoneMove(commander.getId(), from, to, timing));
    }

    public static void restoreCommanderRelation(
            final Game game,
            final Card first,
            final Card second,
            final CommanderRelationKind relationKind) {
        if (first == null || second == null || relationKind == null) {
            throw new IllegalArgumentException("RESTORE_QUALIFICATION_BAD_COMMANDER_RELATION");
        }
        history(game).commanderRelations.add(new CommanderRelation(first.getId(), second.getId(), relationKind));
    }

    public static void restoreRulesRandomness(
            final Game game,
            final Long seed,
            final String seedBinding,
            final List<String> channels,
            final List<PredeterminedDraw> predeterminedDraws,
            final boolean pilotRandomnessProhibited,
            final boolean providerNativeRngCallsRecorded) {
        final History h = history(game);
        if (h.randomness != null) {
            throw new IllegalStateException("RESTORE_QUALIFICATION_RANDOMNESS_ALREADY_SET");
        }
        h.randomness = new RulesRandomness(seed, seedBinding, channels, predeterminedDraws,
                pilotRandomnessProhibited, providerNativeRngCallsRecorded);
    }

    public static void restoreKnowledgePolicy(
            final Game game,
            final String channelPolicy,
            final List<ViewerKnowledgeState> viewers,
            final boolean nativeReferentsValidated) {
        final History h = history(game);
        if (h.knowledgePolicy != null) {
            throw new IllegalStateException("RESTORE_QUALIFICATION_KNOWLEDGE_POLICY_ALREADY_SET");
        }
        h.knowledgePolicy = new KnowledgePolicy(channelPolicy, viewers, nativeReferentsValidated);
    }

    public static List<ExtraTurnCreation> getExtraTurnCreations(final Game game) {
        return List.copyOf(history(game).extraTurns);
    }

    public static List<Elimination> getEliminations(final Game game) {
        return List.copyOf(history(game).eliminations);
    }

    public static List<CommanderZoneMove> getCommanderZoneMoves(final Game game) {
        return List.copyOf(history(game).commanderMoves);
    }

    public static List<CommanderRelation> getCommanderRelations(final Game game) {
        return List.copyOf(history(game).commanderRelations);
    }

    public static RulesRandomness getRulesRandomness(final Game game) {
        final RulesRandomness value = history(game).randomness;
        if (value == null) {
            throw new IllegalStateException("RESTORE_QUALIFICATION_RANDOMNESS_UNAVAILABLE");
        }
        return value;
    }

    public static KnowledgePolicy getKnowledgePolicy(final Game game) {
        final KnowledgePolicy value = history(game).knowledgePolicy;
        if (value == null) {
            throw new IllegalStateException("RESTORE_QUALIFICATION_KNOWLEDGE_POLICY_UNAVAILABLE");
        }
        return value;
    }
}
