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

import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * WS-45 native qualification state.
 *
 * Unlike {@link RestoredQualificationHistory}, this class is not a passive request-history
 * container. Its Rules-state records are admitted only after validation against the live Forge
 * game object graph and native Rules Core APIs. The hidden-information ledger is part of the
 * actor-view authority used by the isolated provider, and the RNG configuration is installed at
 * Forge's real {@link MyRandom} boundary.
 *
 * This class is qualification-only and does not make discretionary pilot choices.
 */
public final class NativeQualificationObservation {
    private NativeQualificationObservation() {
    }

    public enum CommanderMoveTiming {
        REPLACEMENT_EFFECT_BEFORE_MOVE,
        STATE_BASED_ACTION
    }

    public enum KnowledgeKind {
        KNOWN_OBJECT_IDENTITY,
        KNOWN_LIBRARY_RANGE,
        FACE_DOWN_LOOK_PERMISSION,
        TEMPORARY_PERMISSION
    }

    public record PendingElimination(int playerId, String reason) {
    }

    public record CommanderMovePlan(
            int commanderCardId,
            ZoneType from,
            ZoneType to,
            CommanderMoveTiming timing) {
    }

    public record PartnerRelation(int firstCommanderCardId, int secondCommanderCardId) {
    }

    public record PredeterminedDraw(String channel, String operation, String result) {
        public PredeterminedDraw {
            if (channel == null || channel.isBlank() || operation == null || operation.isBlank()
                    || result == null || result.isBlank()) {
                throw new IllegalArgumentException("WS45_BAD_PREDETERMINED_DRAW");
            }
        }
    }

    public record NativeRngSnapshot(
            Long fixedSeed,
            String seedBinding,
            List<String> declaredChannels,
            List<PredeterminedDraw> predeterminedDraws,
            boolean pilotRandomnessProhibited,
            boolean providerNativeRngCallsRecorded,
            long nativePrimitiveCalls,
            List<String> consumedSemanticDraws) {
        public NativeRngSnapshot {
            declaredChannels = List.copyOf(declaredChannels);
            predeterminedDraws = List.copyOf(predeterminedDraws);
            consumedSemanticDraws = List.copyOf(consumedSemanticDraws);
        }
    }

    public record KnowledgeFact(
            KnowledgeKind kind,
            int viewerPlayerId,
            Integer objectCardId,
            Integer libraryPlayerId,
            String zone,
            String permission,
            String scope,
            Integer start,
            Integer count,
            Boolean ordered,
            Boolean persistsWhileSameObject) {
    }

    public record ViewerKnowledge(int viewerPlayerId, List<KnowledgeFact> facts) {
        public ViewerKnowledge {
            facts = List.copyOf(facts);
        }
    }

    private static final class GameState {
        final List<ViewerKnowledge> restoredKnowledge = new ArrayList<>();
        final List<CommanderMovePlan> commanderMovePlans = new ArrayList<>();
        final List<PartnerRelation> partnerRelations = new ArrayList<>();
    }

    private static final Map<Game, GameState> STATE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static GameState state(final Game game) {
        if (game == null) {
            throw new IllegalArgumentException("WS45_GAME_REQUIRED");
        }
        synchronized (STATE) {
            return STATE.computeIfAbsent(game, ignored -> new GameState());
        }
    }

    public static void clear(final Game game) {
        STATE.remove(game);
    }

    public static List<PendingElimination> pendingStateBasedEliminations(final Game game) {
        final List<PendingElimination> out = new ArrayList<>();
        for (final Player player : game.getPlayers()) {
            if (player.hasLost()) {
                continue;
            }
            if (player.getLife() <= 0 && !player.cantLoseForZeroOrLessLife()) {
                out.add(new PendingElimination(player.getId(), "life_total_0"));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Classify a declared Commander zone move from actual current Forge state and the currently
     * applied Commander rules variant. The caller supplies the destination as an operation input;
     * the timing value is derived here and must not be supplied by the qualification request.
     */
    public static CommanderMovePlan validateCommanderMovePlan(
            final Game game,
            final Card commander,
            final ZoneType destination) {
        if (commander == null || destination == null || !commander.isRealCommander()) {
            throw new IllegalArgumentException("WS45_COMMANDER_MOVE_REQUIRES_REAL_COMMANDER");
        }
        final Zone current = game.getZoneOf(commander);
        if (current == null) {
            throw new IllegalArgumentException("WS45_COMMANDER_MOVE_CURRENT_ZONE_REQUIRED");
        }
        if (!game.getRules().hasAppliedVariant(GameType.Commander)) {
            throw new IllegalArgumentException("WS45_COMMANDER_MOVE_REQUIRES_COMMANDER_RULES");
        }

        final CommanderMoveTiming timing;
        if (destination == ZoneType.Hand || destination == ZoneType.Library) {
            // CR 903.9b path implemented by Player's native CommanderMoveReplacement effect.
            timing = CommanderMoveTiming.REPLACEMENT_EFFECT_BEFORE_MOVE;
        } else if (destination == ZoneType.Graveyard || destination == ZoneType.Exile) {
            // CR 903.9a path implemented by GameAction.stateBasedAction_Commander.
            timing = CommanderMoveTiming.STATE_BASED_ACTION;
        } else {
            throw new IllegalArgumentException("WS45_UNSUPPORTED_COMMANDER_MOVE_DESTINATION:" + destination);
        }
        final CommanderMovePlan plan =
                new CommanderMovePlan(commander.getId(), current.getZoneType(), destination, timing);
        state(game).commanderMovePlans.add(plan);
        return plan;
    }

    public static List<CommanderMovePlan> getCommanderMovePlans(final Game game) {
        return List.copyOf(state(game).commanderMovePlans);
    }

    public static PartnerRelation validatePartnerRelation(
            final Game game,
            final Card first,
            final Card second) {
        if (first == null || second == null || first == second
                || !first.isRealCommander() || !second.isRealCommander()) {
            throw new IllegalArgumentException("WS45_BAD_PARTNER_COMMANDERS");
        }
        final CardRules a = first.getRules();
        final CardRules b = second.getRules();
        if (!a.canBePartnerCommanders(b)) {
            throw new IllegalArgumentException("WS45_NATIVE_CARD_RULES_REJECT_PARTNER_RELATION");
        }
        final PartnerRelation relation = new PartnerRelation(first.getId(), second.getId());
        state(game).partnerRelations.add(relation);
        return relation;
    }

    public static List<PartnerRelation> getPartnerRelations(final Game game) {
        return List.copyOf(state(game).partnerRelations);
    }

    public static void restoreKnowledge(
            final Game game,
            final List<ViewerKnowledge> viewers) {
        final List<ViewerKnowledge> validated = new ArrayList<>();
        final Set<Integer> gamePlayers = new LinkedHashSet<>();
        for (final Player p : game.getPlayers()) {
            gamePlayers.add(p.getId());
        }

        for (final ViewerKnowledge viewerState : viewers) {
            if (!gamePlayers.contains(viewerState.viewerPlayerId())) {
                throw new IllegalArgumentException(
                        "WS45_KNOWLEDGE_UNKNOWN_VIEWER:" + viewerState.viewerPlayerId());
            }
            final List<KnowledgeFact> facts = new ArrayList<>();
            for (final KnowledgeFact fact : viewerState.facts()) {
                if (fact.viewerPlayerId() != viewerState.viewerPlayerId()) {
                    throw new IllegalArgumentException("WS45_KNOWLEDGE_VIEWER_MISMATCH");
                }
                final Player viewer = playerById(game, fact.viewerPlayerId());
                switch (fact.kind()) {
                    case KNOWN_OBJECT_IDENTITY -> {
                        final Card card = cardById(game, required(fact.objectCardId(), "object"));
                        card.getView().canBeShownTo(viewer.getView());
                    }
                    case FACE_DOWN_LOOK_PERMISSION -> {
                        final Card card = cardById(game, required(fact.objectCardId(), "object"));
                        card.addMayLookFaceDownExile(viewer);
                        if (!card.mayPlayerLook(viewer)) {
                            throw new IllegalStateException("WS45_NATIVE_FACE_DOWN_LOOK_NOT_APPLIED");
                        }
                    }
                    case TEMPORARY_PERMISSION -> {
                        if (fact.objectCardId() != null) {
                            final Card card = cardById(game, fact.objectCardId());
                            if ("look_at_face_down_exile".equals(fact.permission())) {
                                card.addMayLookFaceDownExile(viewer);
                                if (!card.mayPlayerLook(viewer)) {
                                    throw new IllegalStateException("WS45_NATIVE_TEMP_LOOK_NOT_APPLIED");
                                }
                            }
                        } else if (fact.libraryPlayerId() != null) {
                            playerById(game, fact.libraryPlayerId());
                        }
                    }
                    case KNOWN_LIBRARY_RANGE -> {
                        final Player libraryPlayer =
                                playerById(game, required(fact.libraryPlayerId(), "libraryPlayer"));
                        final int start = required(fact.start(), "start");
                        final int count = required(fact.count(), "count");
                        if (start < 0 || count < 0
                                || libraryPlayer.getZone(ZoneType.Library).size() < start + count) {
                            throw new IllegalArgumentException("WS45_KNOWLEDGE_LIBRARY_RANGE_INVALID");
                        }
                    }
                }
                facts.add(fact);
            }
            validated.add(new ViewerKnowledge(viewerState.viewerPlayerId(), facts));
        }
        final GameState gs = state(game);
        gs.restoredKnowledge.clear();
        gs.restoredKnowledge.addAll(validated);
    }

    public static List<ViewerKnowledge> getKnowledge(final Game game) {
        return List.copyOf(state(game).restoredKnowledge);
    }

    /**
     * Actor-view authority for hidden identities. Native Forge visibility wins; restored historical
     * identity knowledge can additionally authorize the viewer until an invalidation removes it.
     */
    public static boolean canPilotSeeIdentity(
            final Game game,
            final Player viewer,
            final Card card) {
        if (card.getView().canBeShownTo(viewer.getView()) || card.mayPlayerLook(viewer)) {
            return true;
        }
        for (final ViewerKnowledge vk : state(game).restoredKnowledge) {
            if (vk.viewerPlayerId() != viewer.getId()) {
                continue;
            }
            for (final KnowledgeFact fact : vk.facts()) {
                if (fact.kind() == KnowledgeKind.KNOWN_OBJECT_IDENTITY
                        && fact.objectCardId() != null
                        && fact.objectCardId() == card.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void invalidateObjectKnowledge(final Game game, final Card card) {
        final GameState gs = state(game);
        final List<ViewerKnowledge> replacement = new ArrayList<>();
        for (final ViewerKnowledge vk : gs.restoredKnowledge) {
            final List<KnowledgeFact> keep = new ArrayList<>();
            for (final KnowledgeFact fact : vk.facts()) {
                if (fact.objectCardId() == null || fact.objectCardId() != card.getId()) {
                    keep.add(fact);
                }
            }
            replacement.add(new ViewerKnowledge(vk.viewerPlayerId(), keep));
        }
        gs.restoredKnowledge.clear();
        gs.restoredKnowledge.addAll(replacement);
    }

    public static void invalidateLibraryOrderKnowledge(final Game game, final Player libraryPlayer) {
        final GameState gs = state(game);
        final List<ViewerKnowledge> replacement = new ArrayList<>();
        for (final ViewerKnowledge vk : gs.restoredKnowledge) {
            final List<KnowledgeFact> keep = new ArrayList<>();
            for (final KnowledgeFact fact : vk.facts()) {
                if (fact.kind() != KnowledgeKind.KNOWN_LIBRARY_RANGE
                        || fact.libraryPlayerId() == null
                        || fact.libraryPlayerId() != libraryPlayer.getId()) {
                    keep.add(fact);
                }
            }
            replacement.add(new ViewerKnowledge(vk.viewerPlayerId(), keep));
        }
        gs.restoredKnowledge.clear();
        gs.restoredKnowledge.addAll(replacement);
    }

    private static final class QualificationRandom extends Random {
        private static final long serialVersionUID = 1L;

        private final Long fixedSeed;
        private final String seedBinding;
        private final List<String> declaredChannels;
        private final List<PredeterminedDraw> predeterminedDraws;
        private final boolean pilotRandomnessProhibited;
        private final boolean providerNativeRngCallsRecorded;
        private final Deque<PredeterminedDraw> pendingDraws;
        private final List<String> consumedSemanticDraws = new ArrayList<>();
        private long primitiveCalls = 0;

        QualificationRandom(
                final long effectiveSeed,
                final Long fixedSeed0,
                final String seedBinding0,
                final List<String> channels,
                final List<PredeterminedDraw> draws,
                final boolean pilotProhibited,
                final boolean nativeCallsRecorded) {
            super(effectiveSeed);
            fixedSeed = fixedSeed0;
            seedBinding = seedBinding0;
            declaredChannels = List.copyOf(channels);
            predeterminedDraws = List.copyOf(draws);
            pilotRandomnessProhibited = pilotProhibited;
            providerNativeRngCallsRecorded = nativeCallsRecorded;
            pendingDraws = new ArrayDeque<>(draws);
        }

        @Override
        protected int next(final int bits) {
            primitiveCalls++;
            return super.next(bits);
        }

        @Override
        public boolean nextBoolean() {
            final PredeterminedDraw next = pendingDraws.peekFirst();
            if (next != null && "coin_flip".equals(next.operation())) {
                pendingDraws.removeFirst();
                consumedSemanticDraws.add(
                        next.channel() + "|" + next.operation() + "|" + next.result());
                primitiveCalls++;
                if ("HEADS".equalsIgnoreCase(next.result())) {
                    return true;
                }
                if ("TAILS".equalsIgnoreCase(next.result())) {
                    return false;
                }
                throw new IllegalStateException("WS45_UNSUPPORTED_PREDETERMINED_COIN_RESULT");
            }
            return super.nextBoolean();
        }

        NativeRngSnapshot snapshot() {
            return new NativeRngSnapshot(
                    fixedSeed,
                    seedBinding,
                    declaredChannels,
                    predeterminedDraws,
                    pilotRandomnessProhibited,
                    providerNativeRngCallsRecorded,
                    primitiveCalls,
                    consumedSemanticDraws);
        }
    }

    private static QualificationRandom installedRandom;

    public static synchronized void installRulesRandomness(
            final Long fixedSeed,
            final String seedBinding,
            final long effectiveSeed,
            final List<String> channels,
            final List<PredeterminedDraw> draws,
            final boolean pilotRandomnessProhibited,
            final boolean providerNativeRngCallsRecorded) {
        if (fixedSeed == null && (seedBinding == null || seedBinding.isBlank())) {
            throw new IllegalArgumentException("WS45_RNG_BINDING_REQUIRED");
        }
        if (!pilotRandomnessProhibited) {
            throw new IllegalArgumentException("WS45_PILOT_RANDOMNESS_MUST_BE_PROHIBITED");
        }
        installedRandom = new QualificationRandom(
                effectiveSeed,
                fixedSeed,
                seedBinding,
                channels,
                draws,
                true,
                providerNativeRngCallsRecorded);
        MyRandom.setRandom(installedRandom);
        if (MyRandom.getRandom() != installedRandom) {
            throw new IllegalStateException("WS45_NATIVE_RNG_INSTALL_FAILED");
        }
    }

    public static synchronized NativeRngSnapshot getRulesRandomness() {
        if (installedRandom == null || MyRandom.getRandom() != installedRandom) {
            throw new IllegalStateException("WS45_NATIVE_RNG_NOT_INSTALLED");
        }
        return installedRandom.snapshot();
    }

    private static Player playerById(final Game game, final int id) {
        for (final Player p : game.getPlayers()) {
            if (p.getId() == id) {
                return p;
            }
        }
        throw new IllegalArgumentException("WS45_UNKNOWN_PLAYER_ID:" + id);
    }

    private static Card cardById(final Game game, final int id) {
        final Card card = game.findById(id);
        if (card == null) {
            throw new IllegalArgumentException("WS45_UNKNOWN_CARD_ID:" + id);
        }
        return card;
    }

    private static <T> T required(final T value, final String name) {
        if (value == null) {
            throw new IllegalArgumentException("WS45_REQUIRED_FIELD:" + name);
        }
        return value;
    }
}
