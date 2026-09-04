package forge.game.player;

import forge.game.GameEntity;

/** One incremental selection for a Core-constrained noncombat amount distribution. */
public final class AmountDistributionSelection {
    private final GameEntity recipient;
    private final int amount;

    public AmountDistributionSelection(final GameEntity recipient, final int amount) {
        this.recipient = recipient;
        this.amount = amount;
    }

    public GameEntity getRecipient() { return recipient; }
    public int getAmount() { return amount; }
}
