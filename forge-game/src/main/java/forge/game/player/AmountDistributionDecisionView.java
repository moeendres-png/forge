package forge.game.player;

import forge.game.GameEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable Core-owned legal choice surface for a noncombat amount distribution. */
public final class AmountDistributionDecisionView {
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

    private final int remainingAmount;
    private final List<RecipientView> recipients;

    AmountDistributionDecisionView(final int remainingAmount, final List<RecipientView> recipients) {
        this.remainingAmount = remainingAmount;
        this.recipients = Collections.unmodifiableList(new ArrayList<>(recipients));
    }

    public int getRemainingAmount() { return remainingAmount; }
    public List<RecipientView> getRecipients() { return recipients; }
}
