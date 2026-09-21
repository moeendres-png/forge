package forge.game.combat;

import forge.game.GameEntity;
import forge.game.card.Card;

/**
 * One incremental selection against a Core-owned combat-damage decision.
 * The controller selects only a source, recipient and amount that were
 * exposed by {@link CombatDamageDecisionView}.
 */
public final class CombatDamageSelection {
    private final Card source;
    private final GameEntity recipient;
    private final int amount;

    public CombatDamageSelection(final Card source, final GameEntity recipient, final int amount) {
        this.source = source;
        this.recipient = recipient;
        this.amount = amount;
    }

    public Card getSource() {
        return source;
    }

    public GameEntity getRecipient() {
        return recipient;
    }

    public int getAmount() {
        return amount;
    }
}
