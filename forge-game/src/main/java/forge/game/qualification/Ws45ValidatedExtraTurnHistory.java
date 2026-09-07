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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Qualification-only historical extra-turn facts validated against native Forge card rules.
 *
 * <p>The caller must identify the historical source card and affected player because those facts
 * are not recoverable from the current snapshot after the resolving spell has left the stack.
 * They are not accepted blindly: the source must be a live native Forge Card whose parsed raw
 * rules expose an AddTurn ability. The stored history contains native card/player ids and a
 * resolution kind derived from the native source/controller relationship.</p>
 *
 * <p>This surface is observation/history only. It is never consulted by Forge to decide legality,
 * targets, priority, or turn order.</p>
 */
public final class Ws45ValidatedExtraTurnHistory {
    private Ws45ValidatedExtraTurnHistory() {
    }

    public enum ResolutionKind {
        TARGETED_EXTRA_TURN,
        SELF_EXTRA_TURN
    }

    public record Entry(int sequence, int playerId, int sourceCardId, ResolutionKind kind) {
    }

    private static final Map<Game, List<Entry>> STATE =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void clear(final Game game) {
        STATE.remove(game);
    }

    public static Entry validateAndRecord(
            final Game game,
            final int sequence,
            final Player player,
            final Card source) {
        if (game == null || player == null || source == null || sequence <= 0) {
            throw new IllegalArgumentException("WS45_EXTRA_TURN_HISTORY_BAD_ARGUMENT");
        }
        if (player.getGame() != game || source.getGame() != game) {
            throw new IllegalArgumentException("WS45_EXTRA_TURN_HISTORY_FOREIGN_GAME_OBJECT");
        }

        boolean nativeAddTurn = false;
        for (final String ability : source.getRules().getMainPart().getAbilities()) {
            if (ability != null && ability.contains("AddTurn")) {
                nativeAddTurn = true;
                break;
            }
        }
        if (!nativeAddTurn) {
            throw new IllegalArgumentException(
                    "WS45_EXTRA_TURN_SOURCE_HAS_NO_NATIVE_ADDTURN_RULE:" + source.getName());
        }

        final ResolutionKind kind = source.getController() == player
                ? ResolutionKind.SELF_EXTRA_TURN
                : ResolutionKind.TARGETED_EXTRA_TURN;
        final Entry entry = new Entry(sequence, player.getId(), source.getId(), kind);
        synchronized (STATE) {
            final List<Entry> entries = STATE.computeIfAbsent(game, ignored -> new ArrayList<>());
            for (final Entry old : entries) {
                if (old.sequence() == sequence) {
                    throw new IllegalArgumentException(
                            "WS45_EXTRA_TURN_DUPLICATE_SEQUENCE:" + sequence);
                }
            }
            entries.add(entry);
            entries.sort(java.util.Comparator.comparingInt(Entry::sequence));
        }
        return entry;
    }

    public static List<Entry> get(final Game game) {
        synchronized (STATE) {
            final List<Entry> entries = STATE.get(game);
            return entries == null ? List.of() : List.copyOf(entries);
        }
    }
}
