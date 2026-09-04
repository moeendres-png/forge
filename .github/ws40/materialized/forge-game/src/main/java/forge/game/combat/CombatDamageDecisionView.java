package forge.game.combat;

import forge.game.GameEntity;
import forge.game.card.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable controller-facing view of the next Core-authorized combat-damage choices. */
public final class CombatDamageDecisionView {
    public static final class RecipientView {
        private final GameEntity recipient;
        private final int minDamage;
        private final int maxDamage;
        private final int lethalDamageRemaining;
        private final boolean defender;

        RecipientView(final GameEntity recipient, final int minDamage, final int maxDamage,
                final int lethalDamageRemaining, final boolean defender) {
            this.recipient = recipient;
            this.minDamage = minDamage;
            this.maxDamage = maxDamage;
            this.lethalDamageRemaining = lethalDamageRemaining;
            this.defender = defender;
        }

        public GameEntity getRecipient() { return recipient; }
        public int getMinDamage() { return minDamage; }
        public int getMaxDamage() { return maxDamage; }
        public int getLethalDamageRemaining() { return lethalDamageRemaining; }
        public boolean isDefender() { return defender; }
    }

    public static final class SourceView {
        private final Card source;
        private final int remainingDamage;
        private final List<RecipientView> recipients;

        SourceView(final Card source, final int remainingDamage, final List<RecipientView> recipients) {
            this.source = source;
            this.remainingDamage = remainingDamage;
            this.recipients = Collections.unmodifiableList(new ArrayList<>(recipients));
        }

        public Card getSource() { return source; }
        public int getRemainingDamage() { return remainingDamage; }
        public List<RecipientView> getRecipients() { return recipients; }
    }

    private final boolean firstStrikeDamage;
    private final List<SourceView> sources;

    CombatDamageDecisionView(final boolean firstStrikeDamage, final List<SourceView> sources) {
        this.firstStrikeDamage = firstStrikeDamage;
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
    }

    public boolean isFirstStrikeDamage() { return firstStrikeDamage; }
    public List<SourceView> getSources() { return sources; }

    public boolean isEmpty() { return sources.isEmpty(); }
}
