package forge.game.combat;

import forge.game.GameEntity;
import forge.game.card.Card;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/** Canonical fail-closed validation immediately before combat-damage state mutation. */
public final class CombatDamageAssignmentValidator {
    private CombatDamageAssignmentValidator() {}

    public static void validateAll(final Combat combat,
            final Collection<CombatDamageDecision> decisions,
            final Map<Card, Map<GameEntity, Integer>> staged) {
        if (combat == null) {
            throw new IllegalStateException("FORGE_COMBAT_DAMAGE_NO_LIVE_COMBAT");
        }
        for (CombatDamageDecision decision : decisions) {
            validateDecision(combat, decision, staged);
        }
    }

    private static void validateDecision(final Combat combat, final CombatDamageDecision decision,
            final Map<Card, Map<GameEntity, Integer>> staged) {
        if (!decision.isComplete()) {
            throw new IllegalStateException("FORGE_COMBAT_DAMAGE_UNDERASSIGNED_TRANSACTION");
        }
        for (CombatDamageDecision.SourceState source : decision.sourceStates()) {
            if (!source.source.isInPlay()
                    || (!combat.isAttacking(source.source) && !combat.isBlocking(source.source))) {
                throw new IllegalStateException("FORGE_COMBAT_DAMAGE_STALE_SOURCE");
            }
            if (source.source.getNetCombatDamage() != source.totalDamage) {
                throw new IllegalStateException("FORGE_COMBAT_DAMAGE_STALE_SOURCE_AMOUNT");
            }

            final Map<GameEntity, Integer> actual = staged.getOrDefault(source.source, Collections.emptyMap());
            int total = 0;
            for (Map.Entry<GameEntity, Integer> entry : actual.entrySet()) {
                final GameEntity recipient = entry.getKey();
                final int amount = entry.getValue();
                if (recipient == null || amount <= 0) {
                    throw new IllegalStateException("FORGE_COMBAT_DAMAGE_INVALID_ASSIGNMENT_VALUE");
                }
                total += amount;
                final boolean listedRecipient = source.recipients.contains(recipient);
                final boolean isDefender = source.allowDefender && recipient == source.defender;
                if (!listedRecipient && !isDefender) {
                    throw new IllegalStateException("FORGE_COMBAT_DAMAGE_RECIPIENT_NOT_AUTHORIZED");
                }
                if (recipient instanceof Card card && !card.isInPlay()) {
                    throw new IllegalStateException("FORGE_COMBAT_DAMAGE_STALE_RECIPIENT");
                }
                if (isDefender && source.blocked && !source.unrestrictedDivide && !source.trample) {
                    throw new IllegalStateException("FORGE_COMBAT_DAMAGE_BLOCKED_NONTRAMPLER_SPILL");
                }
            }
            if (total != source.totalDamage) {
                throw new IllegalStateException(total < source.totalDamage
                        ? "FORGE_COMBAT_DAMAGE_UNDERASSIGNMENT"
                        : "FORGE_COMBAT_DAMAGE_OVERASSIGNMENT");
            }

            if (source.allowDefender && source.blocked && source.trample
                    && actual.getOrDefault(source.defender, 0) > 0) {
                for (GameEntity recipient : source.recipients) {
                    if (recipient instanceof Card card && decision.lethalRemaining(card) > 0) {
                        throw new IllegalStateException("FORGE_COMBAT_DAMAGE_TRAMPLE_SPILL_BEFORE_LETHAL");
                    }
                }
            }

            if (source.legacyOrder && !source.unrestrictedDivide) {
                boolean priorNotLethal = false;
                for (GameEntity recipient : source.recipients) {
                    if (!(recipient instanceof Card card)) {
                        continue;
                    }
                    final int assignedHere = actual.getOrDefault(recipient, 0);
                    if (priorNotLethal && assignedHere > 0) {
                        throw new IllegalStateException("FORGE_COMBAT_DAMAGE_ILLEGAL_LEGACY_ORDER");
                    }
                    if (decision.lethalRemaining(card) > 0) {
                        priorNotLethal = true;
                    }
                }
            }
        }
    }
}
