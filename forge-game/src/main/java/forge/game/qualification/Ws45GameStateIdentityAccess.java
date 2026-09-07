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
import forge.game.GameState;
import forge.game.card.Card;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Qualification-only read access to the exact card-identity map produced by Forge {@link GameState}.
 *
 * <p>{@code GameState} deliberately treats {@code |Id:N} as a state-file identity token used for
 * attachments, remembered objects and similar reconstruction; it does not overwrite the live
 * {@link Card#getId()} value. WS-45 needs the same already-native mapping to bind provider-neutral
 * semantic identities without falling back to card name, controller, zone order, or "first
 * candidate" heuristics.</p>
 *
 * <p>This class reads only the existing private {@code idToCard} map after native
 * {@link GameState#applyToGame(Game)} has completed. It cannot mutate the map and does not
 * participate in Magic legality or choices. The reflective lookup is deliberately fail-closed and
 * source-locked: any Forge refactor that removes or changes the field causes qualification failure
 * rather than heuristic fallback.</p>
 */
public final class Ws45GameStateIdentityAccess {
    private Ws45GameStateIdentityAccess() {
    }

    private static final Field ID_TO_CARD_FIELD = resolveIdentityField();

    private static Field resolveIdentityField() {
        try {
            final Field field = GameState.class.getDeclaredField("idToCard");
            if (!Map.class.isAssignableFrom(field.getType())) {
                throw new IllegalStateException("WS45_GAME_STATE_IDENTITY_FIELD_TYPE_CHANGED");
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    /** Return the exact live Card associated by native GameState with one explicit state-file ID. */
    public static Card cardForStateId(final GameState state, final Game game, final int stateId) {
        if (state == null || game == null || stateId < 0) {
            throw new IllegalArgumentException("WS45_GAME_STATE_IDENTITY_BAD_ARGUMENT");
        }
        try {
            final Object value = ID_TO_CARD_FIELD.get(state);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalStateException("WS45_GAME_STATE_IDENTITY_MAP_UNAVAILABLE");
            }
            final Object mapped = map.get(stateId);
            if (!(mapped instanceof Card card)) {
                throw new IllegalArgumentException("WS45_GAME_STATE_IDENTITY_MISSING:" + stateId);
            }
            if (card.getGame() != game) {
                throw new IllegalStateException("WS45_GAME_STATE_IDENTITY_FOREIGN_GAME:" + stateId);
            }
            return card;
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("WS45_GAME_STATE_IDENTITY_ACCESS_DENIED", ex);
        }
    }
}
