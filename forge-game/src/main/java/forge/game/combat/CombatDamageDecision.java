package forge.game.combat;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.player.PlayerController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core-owned incremental combat-damage transaction for one assigning player
 * in one combat-damage step. All decisions in a step share the same staged
 * assignment map so trample can account for damage assigned by other sources
 * during that same step without mutating live game state early.
 */
public final class CombatDamageDecision {
    static final class SourceState {
        final Card source;
        final List<GameEntity> recipients;
        final GameEntity defender;
        final int totalDamage;
        final boolean attacking;
        final boolean blocked;
        final boolean trample;
        final boolean allowDefender;
        final boolean unrestrictedDivide;
        final boolean legacyOrder;

        SourceState(final Card source, final Collection<? extends GameEntity> recipients,
                final GameEntity defender, final int totalDamage, final boolean attacking,
                final boolean blocked, final boolean trample, final boolean allowDefender,
                final boolean unrestrictedDivide, final boolean legacyOrder) {
            this.source = source;
            this.recipients = Collections.unmodifiableList(new ArrayList<>(recipients));
            this.defender = defender;
            this.totalDamage = totalDamage;
            this.attacking = attacking;
            this.blocked = blocked;
            this.trample = trample;
            this.allowDefender = allowDefender;
            this.unrestrictedDivide = unrestrictedDivide;
            this.legacyOrder = legacyOrder;
        }
    }

    private final Player assigningPlayer;
    private final boolean firstStrikeDamage;
    private final Map<Card, Map<GameEntity, Integer>> staged;
    private final Map<Card, SourceState> sources = new LinkedHashMap<>();

    public CombatDamageDecision(final Player assigningPlayer, final boolean firstStrikeDamage,
            final Map<Card, Map<GameEntity, Integer>> sharedStagedAssignments) {
        this.assigningPlayer = assigningPlayer;
        this.firstStrikeDamage = firstStrikeDamage;
        this.staged = sharedStagedAssignments;
    }

    public Player getAssigningPlayer() { return assigningPlayer; }
    public boolean isFirstStrikeDamage() { return firstStrikeDamage; }

    public void addSource(final Card source, final Collection<? extends GameEntity> recipients,
            final GameEntity defender, final int totalDamage, final boolean attacking,
            final boolean blocked, final boolean trample, final boolean allowDefender,
            final boolean unrestrictedDivide, final boolean legacyOrder) {
        if (source == null || totalDamage < 0) {
            throw new IllegalArgumentException("Invalid combat-damage source");
        }
        if (sources.containsKey(source)) {
            throw new IllegalStateException("Combat-damage source already registered: " + source);
        }
        sources.put(source, new SourceState(source, recipients, defender, totalDamage, attacking,
                blocked, trample, allowDefender, unrestrictedDivide, legacyOrder));
        staged.computeIfAbsent(source, ignored -> new LinkedHashMap<>());
    }

    public boolean hasSources() { return !sources.isEmpty(); }

    Collection<SourceState> sourceStates() { return sources.values(); }
    Map<Card, Map<GameEntity, Integer>> stagedAssignments() { return staged; }

    public boolean isComplete() {
        for (SourceState source : sources.values()) {
            if (remaining(source) != 0) {
                return false;
            }
        }
        return true;
    }

    public void resolve(final PlayerController controller) {
        while (!isComplete()) {
            if (applyForcedProgress()) {
                continue;
            }
            final CombatDamageDecisionView view = buildView();
            if (view.isEmpty()) {
                throw new IllegalStateException("FORGE_COMBAT_DAMAGE_NO_LEGAL_CORE_PROGRESS");
            }
            final CombatDamageSelection selection = controller.chooseCombatDamage(view);
            if (selection == null) {
                throw new IllegalStateException("FORGE_COMBAT_DAMAGE_NULL_SELECTION");
            }
            apply(selection);
        }
    }

    private boolean applyForcedProgress() {
        for (SourceState source : sources.values()) {
            final int remaining = remaining(source);
            if (remaining <= 0) {
                continue;
            }
            final List<CombatDamageDecisionView.RecipientView> legal = legalRecipients(source, remaining);
            if (legal.size() == 1) {
                final CombatDamageDecisionView.RecipientView only = legal.get(0);
                if (only.getMinDamage() == remaining && only.getMaxDamage() == remaining) {
                    stage(source.source, only.getRecipient(), remaining);
                    return true;
                }
            }
        }
        return false;
    }

    public CombatDamageDecisionView buildView() {
        final List<CombatDamageDecisionView.SourceView> result = new ArrayList<>();
        for (SourceState source : sources.values()) {
            final int remaining = remaining(source);
            if (remaining <= 0) {
                continue;
            }
            final List<CombatDamageDecisionView.RecipientView> legal = legalRecipients(source, remaining);
            if (!legal.isEmpty()) {
                result.add(new CombatDamageDecisionView.SourceView(source.source, remaining, legal));
            }
        }
        return new CombatDamageDecisionView(firstStrikeDamage, result);
    }

    public void apply(final CombatDamageSelection selection) {
        final SourceState source = sources.get(selection.getSource());
        if (source == null) {
            throw new IllegalArgumentException("FORGE_COMBAT_DAMAGE_STALE_OR_FOREIGN_SOURCE");
        }
        final int remaining = remaining(source);
        if (remaining <= 0) {
            throw new IllegalArgumentException("FORGE_COMBAT_DAMAGE_SOURCE_ALREADY_COMPLETE");
        }
        CombatDamageDecisionView.RecipientView matched = null;
        for (CombatDamageDecisionView.RecipientView recipient : legalRecipients(source, remaining)) {
            if (recipient.getRecipient() == selection.getRecipient()) {
                matched = recipient;
                break;
            }
        }
        if (matched == null) {
            throw new IllegalArgumentException("FORGE_COMBAT_DAMAGE_ILLEGAL_RECIPIENT");
        }
        if (selection.getAmount() < matched.getMinDamage() || selection.getAmount() > matched.getMaxDamage()) {
            throw new IllegalArgumentException("FORGE_COMBAT_DAMAGE_ILLEGAL_AMOUNT");
        }
        stage(source.source, selection.getRecipient(), selection.getAmount());
    }

    private void stage(final Card source, final GameEntity recipient, final int amount) {
        if (amount <= 0 || recipient == null) {
            throw new IllegalArgumentException("FORGE_COMBAT_DAMAGE_INVALID_STAGED_SELECTION");
        }
        staged.computeIfAbsent(source, ignored -> new LinkedHashMap<>())
                .merge(recipient, amount, Integer::sum);
    }

    private int remaining(final SourceState source) {
        int assigned = 0;
        for (Integer value : staged.getOrDefault(source.source, Collections.emptyMap()).values()) {
            assigned += value;
        }
        return source.totalDamage - assigned;
    }

    private List<CombatDamageDecisionView.RecipientView> legalRecipients(final SourceState source,
            final int remaining) {
        final List<CombatDamageDecisionView.RecipientView> legal = new ArrayList<>();
        final List<GameEntity> allowedCards = source.recipients;

        int legacyLastAllowed = allowedCards.size() - 1;
        if (source.legacyOrder && !source.unrestrictedDivide) {
            legacyLastAllowed = -1;
            for (int i = 0; i < allowedCards.size(); i++) {
                legacyLastAllowed = i;
                final GameEntity entity = allowedCards.get(i);
                if (entity instanceof Card card && lethalRemaining(card) > 0) {
                    break;
                }
            }
        }

        for (int i = 0; i < allowedCards.size(); i++) {
            if (source.legacyOrder && !source.unrestrictedDivide && i > legacyLastAllowed) {
                break;
            }
            final GameEntity recipient = allowedCards.get(i);
            if (recipient instanceof Card card && !card.isInPlay()) {
                continue;
            }
            final int lethalRemaining = recipient instanceof Card card ? lethalRemaining(card) : 0;
            legal.add(new CombatDamageDecisionView.RecipientView(recipient, 1, remaining,
                    lethalRemaining, false));
        }

        if (source.allowDefender && source.defender != null && canAssignToDefender(source)) {
            legal.add(new CombatDamageDecisionView.RecipientView(source.defender, 1, remaining, 0, true));
        }

        if (legal.size() == 1) {
            final CombatDamageDecisionView.RecipientView only = legal.get(0);
            legal.set(0, new CombatDamageDecisionView.RecipientView(only.getRecipient(), remaining,
                    remaining, only.getLethalDamageRemaining(), only.isDefender()));
        }
        return legal;
    }

    private boolean canAssignToDefender(final SourceState source) {
        if (source.unrestrictedDivide || !source.blocked) {
            return true;
        }
        if (!source.trample) {
            return false;
        }
        for (GameEntity recipient : source.recipients) {
            if (recipient instanceof Card card && lethalRemaining(card) > 0) {
                return false;
            }
        }
        return true;
    }

    int lethalRemaining(final Card target) {
        if (target.isPlaneswalker()) {
            return Math.max(0, target.getCurrentLoyalty() - stagedTo(target));
        }
        if (!target.isCreature()) {
            return Math.max(0, target.getExcessDamageValue(false) - stagedTo(target));
        }
        if (hasDeathtouchDamageAssigned(target)) {
            return 0;
        }
        return Math.max(0, target.getExcessDamageValue(false) - stagedTo(target));
    }

    private int stagedTo(final GameEntity target) {
        int total = 0;
        for (Map<GameEntity, Integer> byTarget : staged.values()) {
            total += byTarget.getOrDefault(target, 0);
        }
        return total;
    }

    private boolean hasDeathtouchDamageAssigned(final Card target) {
        for (Map.Entry<Card, Map<GameEntity, Integer>> source : staged.entrySet()) {
            if (source.getValue().getOrDefault(target, 0) > 0
                    && source.getKey().hasKeyword(Keyword.DEATHTOUCH)) {
                return true;
            }
        }
        return false;
    }

    SourceState sourceState(final Card source) { return sources.get(source); }
}
