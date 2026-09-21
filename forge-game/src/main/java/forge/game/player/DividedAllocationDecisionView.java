package forge.game.player;

import forge.game.GameEntity;
import forge.game.card.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable Core-owned legal choice surface for a chooser-divided allocation (CR 601.2d). */
public final class DividedAllocationDecisionView {
    public static final class RecipientView {
        private final GameEntity recipient;
        private final int minAmount;
        private final int maxAmount;

        RecipientView(final GameEntity recipient, final int minAmount, final int maxAmount) {
            this.recipient = recipient;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
        }

        public GameEntity getRecipient() { return recipient; }
        public int getMinAmount() { return minAmount; }
        public int getMaxAmount() { return maxAmount; }
    }

    private final int totalAmount;
    private final boolean dividedUpTo;
    private final List<RecipientView> recipients;
    private final String allocationLabel;
    private final Card source;

    DividedAllocationDecisionView(final int totalAmount, final boolean dividedUpTo,
            final List<RecipientView> recipients, final String allocationLabel, final Card source) {
        this.totalAmount = totalAmount;
        this.dividedUpTo = dividedUpTo;
        this.recipients = Collections.unmodifiableList(new ArrayList<>(recipients));
        this.allocationLabel = allocationLabel == null ? "damage" : allocationLabel;
        this.source = source;
    }

    public int getTotalAmount() { return totalAmount; }
    public boolean isDividedUpTo() { return dividedUpTo; }
    public List<RecipientView> getRecipients() { return recipients; }
    public String getAllocationLabel() { return allocationLabel; }
    public Card getSource() { return source; }

    public boolean isEmpty() { return recipients.isEmpty(); }
}
