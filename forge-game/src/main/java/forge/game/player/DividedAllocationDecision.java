package forge.game.player;

import forge.game.GameEntity;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core-owned chooser-divided allocation transaction (CR 601.2d, 602.2b).
 *
 * <p>Rules/Core alone owns legality: whether division is required, the total amount,
 * the legal target set, the per-target minimum (CR 601.2d: each target must receive
 * at least one), DividedAsYouChoose / DividedUpTo semantics, and validation of the
 * final vector. Controllers only project the authoritative view and submit an exact
 * vector; the Core validates before any mutation of {@link SpellAbility} targets.
 */
public final class DividedAllocationDecision {
    private final SpellAbility ability;
    private final int totalAmount;
    private final List<GameEntity> targets;
    private final boolean dividedUpTo;
    private final int minPerTarget;

    public DividedAllocationDecision(final SpellAbility ability, final int totalAmount,
            final List<? extends GameEntity> targets, final boolean dividedUpTo) {
        if (ability == null || targets == null) {
            throw new IllegalArgumentException("FORGE_DIVIDED_NULL_DECISION");
        }
        if (totalAmount < 0) {
            throw new IllegalArgumentException("FORGE_DIVIDED_ILLEGAL_TOTAL");
        }
        this.ability = ability;
        this.totalAmount = totalAmount;
        this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
        this.dividedUpTo = dividedUpTo;
        this.minPerTarget = this.targets.isEmpty() ? 0 : 1;
    }

    public int getTotalAmount() { return totalAmount; }
    public List<GameEntity> getTargets() { return targets; }
    public boolean isDividedUpTo() { return dividedUpTo; }
    public int getMinPerTarget() { return minPerTarget; }

    /** Core-calculated per-target bounds: each target at least one, at most total minus minimums for others. */
    public DividedAllocationDecisionView buildView() {
        final List<DividedAllocationDecisionView.RecipientView> legal = new ArrayList<>();
        if (!targets.isEmpty() && totalAmount > 0) {
            final int maxPerTarget = Math.max(minPerTarget, totalAmount - (targets.size() - 1) * minPerTarget);
            for (GameEntity target : targets) {
                legal.add(new DividedAllocationDecisionView.RecipientView(target, minPerTarget, maxPerTarget));
            }
        }
        return new DividedAllocationDecisionView(totalAmount, dividedUpTo, legal, allocationLabel(),
                ability.getHostCard());
    }

    private String allocationLabel() {
        try {
            if (ability.getApi() == forge.game.ability.ApiType.PreventDamage) {
                return "shield";
            }
            if (ability.getApi() == forge.game.ability.ApiType.PutCounter) {
                return "counters";
            }
        } catch (Throwable ignored) {
            // Fall through to the generic label; legality never depends on it.
        }
        return "damage";
    }

    /**
     * Resolves the allocation: applies forced progress without consulting the controller,
     * otherwise asks the entitled controller for an exact vector and validates it.
     * Any validation failure throws before mutation; callers translate that into a
     * cancelled cast (return false) with no Rules mutation.
     */
    public void resolve(final PlayerController controller) {
        if (targets.isEmpty() || totalAmount <= 0) {
            return;
        }
        if (targets.size() > totalAmount) {
            throw new IllegalArgumentException("FORGE_DIVIDED_TOO_MANY_TARGETS");
        }
        if (targets.size() == 1) {
            apply(new DividedAllocationSelection(Collections.singletonMap(targets.get(0), totalAmount)));
            return;
        }
        if (targets.size() == totalAmount) {
            final Map<GameEntity, Integer> ones = new java.util.LinkedHashMap<>();
            for (GameEntity target : targets) {
                ones.put(target, 1);
            }
            apply(new DividedAllocationSelection(ones));
            return;
        }
        if (controller == null) {
            throw new IllegalArgumentException("FORGE_DIVIDED_NULL_CONTROLLER");
        }
        final DividedAllocationDecisionView view = buildView();
        if (view.isEmpty()) {
            throw new IllegalStateException("FORGE_DIVIDED_NO_LEGAL_CORE_PROGRESS");
        }
        final DividedAllocationSelection selection = controller.chooseDividedAllocation(view);
        if (selection == null) {
            throw new IllegalStateException("FORGE_DIVIDED_NULL_SELECTION");
        }
        apply(selection);
    }

    /** Validates fully before mutating any target state. */
    public void apply(final DividedAllocationSelection selection) {
        validate(selection);
        for (Map.Entry<GameEntity, Integer> entry : selection.getAllocations().entrySet()) {
            ability.addDividedAllocation(entry.getKey(), entry.getValue());
        }
    }

    private void validate(final DividedAllocationSelection selection) {
        if (selection == null || selection.getAllocations() == null) {
            throw new IllegalArgumentException("FORGE_DIVIDED_NULL_SELECTION");
        }
        final Map<GameEntity, Integer> allocations = selection.getAllocations();
        if (targets.isEmpty()) {
            if (!allocations.isEmpty()) {
                throw new IllegalArgumentException("FORGE_DIVIDED_COUNT_MISMATCH");
            }
            return;
        }
        if (allocations.size() != targets.size()) {
            throw new IllegalArgumentException("FORGE_DIVIDED_COUNT_MISMATCH");
        }
        final Set<GameEntity> identity = Collections.newSetFromMap(new IdentityHashMap<>());
        identity.addAll(targets);
        int sum = 0;
        for (Map.Entry<GameEntity, Integer> entry : allocations.entrySet()) {
            final GameEntity key = entry.getKey();
            final Integer value = entry.getValue();
            if (key == null || value == null) {
                throw new IllegalArgumentException("FORGE_DIVIDED_NULL_ENTRY");
            }
            if (!identity.contains(key)) {
                throw new IllegalArgumentException("FORGE_DIVIDED_ILLEGAL_TARGET");
            }
            if (value.intValue() < minPerTarget) {
                throw new IllegalArgumentException("FORGE_DIVIDED_BELOW_MINIMUM");
            }
            if (value.intValue() > totalAmount) {
                throw new IllegalArgumentException("FORGE_DIVIDED_ABOVE_MAXIMUM");
            }
            sum += value.intValue();
        }
        if (sum < totalAmount) {
            throw new IllegalArgumentException("FORGE_DIVIDED_TOTAL_TOO_LOW");
        }
        if (sum > totalAmount) {
            throw new IllegalArgumentException("FORGE_DIVIDED_TOTAL_TOO_HIGH");
        }
    }
}
