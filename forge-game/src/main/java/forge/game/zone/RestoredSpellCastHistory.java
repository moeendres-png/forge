/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package forge.game.zone;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Native history state for a spell restored onto the stack from a serialized game state.
 *
 * This is deliberately not a casting shortcut. It exists for state restoration where a
 * serialized state already represents a spell whose casting process finished and whose costs
 * were paid before the snapshot was taken. The caller supplies no cast-complete or costs-paid
 * booleans. Those facts are derived from successful use of this native restore operation.
 *
 * Target legality remains owned by Forge. Every supplied target is checked through the same
 * SpellAbility.canTarget rules surface before the spell is admitted to the native stack.
 */
public final class RestoredSpellCastHistory {
    private RestoredSpellCastHistory() {
    }

    private record History(boolean castComplete, boolean costsPaid) {
    }

    private static final Map<SpellAbility, History> HISTORY =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Restore an already-completed, already-paid spell cast onto the native Forge stack.
     *
     * @throws IllegalArgumentException when the requested stack object is not an ordinary spell
     *                                  or its targets are not legal in the restored game state.
     * @throws IllegalStateException when Forge does not actually admit the spell to the stack.
     */
    public static SpellAbilityStackInstance restoreCompletedPaidSpell(final Game game, final SpellAbility ability) {
        if (game == null || ability == null) {
            throw new IllegalArgumentException("RESTORE_STACK_NULL_INPUT");
        }
        if (!ability.isSpell() || ability.isCopied()) {
            throw new IllegalArgumentException("RESTORE_STACK_REQUIRES_NONCOPIED_SPELL");
        }
        if (ability.getActivatingPlayer() == null) {
            throw new IllegalArgumentException("RESTORE_STACK_ACTIVATOR_REQUIRED");
        }
        if (!hasLegalRestoredTargets(game, ability)) {
            throw new IllegalArgumentException("RESTORE_STACK_ILLEGAL_TARGET_STATE");
        }

        final int before = game.getStack().size();
        game.getStack().addAndUnfreeze(ability);
        final SpellAbilityStackInstance instance = game.getStack().getInstanceMatchingSpellAbilityID(ability);
        if (instance == null || game.getStack().size() != before + 1) {
            throw new IllegalStateException("RESTORE_STACK_NATIVE_ADMISSION_FAILED");
        }

        // The history booleans are not caller inputs. Successful admission through this method is
        // the native event establishing that the serialized state represents a completed paid cast.
        final History history = new History(true, true);
        HISTORY.put(ability, history);
        HISTORY.put(instance.getSpellAbility(), history);
        return instance;
    }

    public static boolean isCastComplete(final SpellAbility ability) {
        final History history = HISTORY.get(ability);
        return history != null && history.castComplete();
    }

    public static boolean areCostsPaid(final SpellAbility ability) {
        final History history = HISTORY.get(ability);
        return history != null && history.costsPaid();
    }

    public static boolean hasNativeHistory(final SpellAbility ability) {
        return HISTORY.containsKey(ability);
    }

    private static boolean hasLegalRestoredTargets(final Game game, final SpellAbility ability) {
        if (!game.getStack().hasLegalTargeting(ability)) {
            return false;
        }

        if (ability.usesTargeting()) {
            for (final GameObject target : ability.getTargets()) {
                if (target instanceof Card card) {
                    final Card current = game.getCardState(card);
                    if (current != null && !current.equalsWithGameTimestamp(card)) {
                        return false;
                    }
                    if (!ability.canTarget(card, true)) {
                        return false;
                    }
                } else if (target instanceof SpellAbility targetAbility) {
                    final SpellAbilityStackInstance targetInstance =
                            game.getStack().getInstanceMatchingSpellAbilityID(targetAbility);
                    if (targetInstance == null || !ability.canTarget(targetInstance.getSpellAbility(), true)) {
                        return false;
                    }
                } else if (!ability.canTarget(target, true)) {
                    return false;
                }
            }
        }

        return ability.getSubAbility() == null || hasLegalRestoredTargets(game, ability.getSubAbility());
    }
}
