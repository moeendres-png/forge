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

    public record RulesRandomness(
            long seed,
            List<String> channels,
            boolean providerNativeRngCallsRecorded,
            List<String> predeterminedSemanticDraws) {
        public RulesRandomness {
            channels = List.copyOf(channels);
            predeterminedSemanticDraws = List.copyOf(predeterminedSemanticDraws);
        }
    }

    public record KnowledgePolicy(
            String canonicalPolicy,
            boolean nativeVisibilityValidated) {
    }

    public record SetupValidation(
            boolean constructInsideRulesProcess,
            boolean exposeNormalizedConstructedState,
            boolean nativeStructuralValidationRequired,
            boolean requestedVsNormalizedEqualityRequired,
            boolean failClosedOnMismatch) {
    }

    private static final class History {
        final List<ExtraTurnCreation> extraTurns = new ArrayList<>();
        final List<Elimination> eliminations = new ArrayList<>();
        final List<CommanderZoneMove> commanderMoves = new ArrayList<>();
        RulesRandomness randomness;
        KnowledgePolicy knowledgePolicy;
        SetupValidation setupValidation;
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

    public static void restoreRulesRandomness(
            final Game game,
            final long seed,
            final List<String> channels,
            final boolean providerNativeRngCallsRecorded,
            final List<String> predeterminedSemanticDraws) {
        final History h = history(game);
        if (h.randomness != null) {
            throw new IllegalStateException("RESTORE_QUALIFICATION_RANDOMNESS_ALREADY_SET");
        }
        h.randomness = new RulesRandomness(seed, channels, providerNativeRngCallsRecorded, predeterminedSemanticDraws);
    }

    public static void restoreKnowledgePolicy(
            final Game game,
            final String canonicalPolicy,
            final boolean nativeVisibilityValidated) {
        if (canonicalPolicy == null || canonicalPolicy.isEmpty() || !nativeVisibilityValidated) {
            throw new IllegalArgumentException("RESTORE_QUALIFICATION_KNOWLEDGE_POLICY_NOT_VALIDATED");
        }
        final History h = history(game);
        if (h.knowledgePolicy != null) {
            throw new IllegalStateException("RESTORE_QUALIFICATION_KNOWLEDGE_POLICY_ALREADY_SET");
        }
        h.knowledgePolicy = new KnowledgePolicy(canonicalPolicy, true);
    }

    public static void restoreSetupValidation(
            final Game game,
            final boolean constructInsideRulesProcess,
            final boolean exposeNormalizedConstructedState,
            final boolean nativeStructuralValidationRequired,
            final boolean requestedVsNormalizedEqualityRequired,
            final boolean failClosedOnMismatch) {
        final History h = history(game);
        if (h.setupValidation != null) {
            throw new IllegalStateException("RESTORE_QUALIFICATION_SETUP_VALIDATION_ALREADY_SET");
        }
        h.setupValidation = new SetupValidation(
                constructInsideRulesProcess,
                exposeNormalizedConstructedState,
                nativeStructuralValidationRequired,
                requestedVsNormalizedEqualityRequired,
                failClosedOnMismatch);
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

    public static SetupValidation getSetupValidation(final Game game) {
        final SetupValidation value = history(game).setupValidation;
        if (value == null) {
            throw new IllegalStateException("RESTORE_QUALIFICATION_SETUP_VALIDATION_UNAVAILABLE");
        }
        return value;
    }
}
