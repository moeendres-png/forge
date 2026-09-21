package forge.game.player;

import forge.game.GameEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core-owned exact-total noncombat amount-distribution transaction. */
public final class AmountDistributionDecision {
    private final int totalAmount;
    private final List<GameEntity> recipients;
    private final Map<GameEntity, Integer> assigned = new LinkedHashMap<>();

    public AmountDistributionDecision(final int totalAmount,
            final Collection<? extends GameEntity> recipients) {
        if (totalAmount < 0 || recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("Invalid amount-distribution decision");
        }
        this.totalAmount = totalAmount;
        this.recipients = Collections.unmodifiableList(new ArrayList<>(recipients));
    }

    public int getTotalAmount() { return totalAmount; }

    public boolean isComplete() { return assignedTotal() == totalAmount; }

    public AmountDistributionDecisionView buildView() {
        final int remaining = totalAmount - assignedTotal();
        final List<AmountDistributionDecisionView.RecipientView> legal = new ArrayList<>();
        if (remaining <= 0) {
            return new AmountDistributionDecisionView(0, legal);
        }
        for (GameEntity recipient : recipients) {
            legal.add(new AmountDistributionDecisionView.RecipientView(recipient, 1, remaining));
        }
        if (legal.size() == 1) {
            final GameEntity recipient = legal.get(0).getRecipient();
            legal.set(0, new AmountDistributionDecisionView.RecipientView(recipient, remaining, remaining));
        }
        return new AmountDistributionDecisionView(remaining, legal);
    }

    public void resolve(final PlayerController controller) {
        while (!isComplete()) {
            final AmountDistributionDecisionView view = buildView();
            if (view.getRecipients().size() == 1) {
                final AmountDistributionDecisionView.RecipientView only = view.getRecipients().get(0);
                if (only.getMinAmount() == only.getMaxAmount()) {
                    apply(new AmountDistributionSelection(only.getRecipient(), only.getMaxAmount()));
                    continue;
                }
            }
            final AmountDistributionSelection selection = controller.chooseAmountDistribution(view);
            if (selection == null) {
                throw new IllegalStateException("FORGE_AMOUNT_DISTRIBUTION_NULL_SELECTION");
            }
            apply(selection);
        }
    }

    public void apply(final AmountDistributionSelection selection) {
        if (!recipients.contains(selection.getRecipient())) {
            throw new IllegalArgumentException("FORGE_AMOUNT_DISTRIBUTION_ILLEGAL_RECIPIENT");
        }
        final int remaining = totalAmount - assignedTotal();
        if (selection.getAmount() <= 0 || selection.getAmount() > remaining) {
            throw new IllegalArgumentException("FORGE_AMOUNT_DISTRIBUTION_ILLEGAL_AMOUNT");
        }
        assigned.merge(selection.getRecipient(), selection.getAmount(), Integer::sum);
    }

    public Map<GameEntity, Integer> validatedResult() {
        if (!isComplete()) {
            throw new IllegalStateException("FORGE_AMOUNT_DISTRIBUTION_UNDERASSIGNED");
        }
        int total = 0;
        for (Map.Entry<GameEntity, Integer> entry : assigned.entrySet()) {
            if (!recipients.contains(entry.getKey()) || entry.getValue() <= 0) {
                throw new IllegalStateException("FORGE_AMOUNT_DISTRIBUTION_INVALID_FINAL_ASSIGNMENT");
            }
            total += entry.getValue();
        }
        if (total != totalAmount) {
            throw new IllegalStateException("FORGE_AMOUNT_DISTRIBUTION_TOTAL_MISMATCH");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(assigned));
    }

    private int assignedTotal() {
        int total = 0;
        for (int amount : assigned.values()) {
            total += amount;
        }
        return total;
    }
}
