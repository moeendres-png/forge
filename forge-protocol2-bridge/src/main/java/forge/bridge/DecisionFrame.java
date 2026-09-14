package forge.bridge;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One parked native decision: the complete authoritative option set for a single
 * engine callback, bound to game/session/actor/revision with retained native bindings.
 *
 * <p>Option IDs are random opaque UUIDs: they encode nothing and cannot be used to
 * fabricate a native action. Native bindings never leave the bridge process.
 *
 * <p>WS202: general authoritative surface. Every discretionary decision originates
 * from native Forge Rules execution and is parked as a complete frame binding
 * game/actor/revision/kind plus opaque option identity. Submission selects only an
 * offered option from the exact actor/revision frame. The bridge never decides.
 */
public final class DecisionFrame {
    public enum Kind {
        PRIORITY,
        MULLIGAN,
        STARTING_PLAYER,
        MANA_PAYMENT,
        COST_SELECTION,
        TARGET_SELECTION,
        MODE_SELECTION,
        X_ANNOUNCE,
        NUMBER_CHOICE,
        COLOR_CHOICE,
        COMBAT_DECLARE_ATTACKERS,
        COMBAT_DECLARE_BLOCKERS,
        COMBAT_ORDER,
        COMBAT_DAMAGE,
        TRIGGER_ORDER,
        TRIGGER_PLAY,
        REPLACEMENT_ORDER,
        REPLACEMENT_CONFIRM,
        STATIC_CHOICE,
        COPY_CHOICE,
        SEARCH_SELECTION,
        HIDDEN_ZONE_SELECTION,
        COMMANDER_MOVE,
        CONCESSION,
        AMOUNT_DISTRIBUTION,
        GENERIC_CONFIRM,
        GENERIC_SELECTION,
        ORDER_CHOICE,
        BINARY_CHOICE
    }

    public enum Status {
        SUPPORTED,
        UNSUPPORTED
    }

    /** One externally selectable option. */
    public static final class Option {
        public final String optionId;
        public final String actionType;
        public final String label;
        public final String sourceCardName;
        public final SpellAbility nativeBinding;
        public final Player nativePlayer;
        public final boolean isPass;
        public final boolean isKeep;
        public final boolean isConcede;
        public final Boolean confirmValue;
        public final Integer intValue;
        public final String stringValue;
        public final Object nativePayload;
        public final String payloadKind;
        private boolean consumed;

        Option(String actionType, String label, String sourceCardName,
                SpellAbility nativeBinding, Player nativePlayer, boolean isPass, boolean isKeep) {
            this(actionType, label, sourceCardName, nativeBinding, nativePlayer, isPass, isKeep,
                    false, null, null, null, null, null);
        }

        Option(String actionType, String label, String sourceCardName,
                SpellAbility nativeBinding, Player nativePlayer, boolean isPass, boolean isKeep,
                boolean isConcede, Boolean confirmValue, Integer intValue, String stringValue,
                Object nativePayload, String payloadKind) {
            this.optionId = "opt-" + UUID.randomUUID();
            this.actionType = actionType;
            this.label = label;
            this.sourceCardName = sourceCardName;
            this.nativeBinding = nativeBinding;
            this.nativePlayer = nativePlayer;
            this.isPass = isPass;
            this.isKeep = isKeep;
            this.isConcede = isConcede;
            this.confirmValue = confirmValue;
            this.intValue = intValue;
            this.stringValue = stringValue;
            this.nativePayload = nativePayload;
            this.payloadKind = payloadKind == null ? "" : payloadKind;
            this.consumed = false;
        }

        synchronized boolean consume() {
            if (consumed) {
                return false;
            }
            consumed = true;
            return true;
        }

        public synchronized boolean isConsumed() {
            return consumed;
        }
    }

    public final long revision;
    public final Kind kind;
    public final Status status;
    public final String reason;
    public final String actorPlayerId;
    public final int actorSeat;
    public final List<Option> options;
    public final String preStateHash;
    public final long createdAtNanos;

    private final Map<String, Option> byId;

    DecisionFrame(long revision, Kind kind, Status status, String reason, String actorPlayerId,
            int actorSeat, List<Option> options, String preStateHash) {
        this.revision = revision;
        this.kind = kind;
        this.status = status;
        this.reason = reason == null ? "" : reason;
        this.actorPlayerId = actorPlayerId;
        this.actorSeat = actorSeat;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.preStateHash = preStateHash;
        this.createdAtNanos = System.nanoTime();
        final Map<String, Option> map = new LinkedHashMap<>();
        for (Option option : options) {
            map.put(option.optionId, option);
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    public static Option passOption() {
        return new Option("pass_priority", "Pass priority", null, null, null, true, false);
    }

    public static Option keepOption() {
        return new Option("mulligan", "Keep hand", null, null, null, false, true);
    }

    public static Option shipOption() {
        return new Option("mulligan", "Mulligan (take another hand)", null, null, null, false, false);
    }

    public static Option spellOption(String actionType, String label, String sourceCardName,
            SpellAbility nativeBinding) {
        return new Option(actionType, label, sourceCardName, nativeBinding, null, false, false);
    }

    public static Option startingPlayerOption(String playerId, String label, Player nativePlayer) {
        return new Option("structural_decision", label, playerId, null, nativePlayer, false, false);
    }

    public static Option concedeOption() {
        return new Option("concede", "Concede the game", null, null, null, false, false,
                true, null, null, null, null, "CONCEDE");
    }

    public static Option confirmOption(String actionType, String label, boolean yes) {
        return new Option(actionType, label, null, null, null, false, false,
                false, Boolean.valueOf(yes), null, null, null, "CONFIRM");
    }

    public static Option intOption(String actionType, String label, int value) {
        return new Option(actionType, label, null, null, null, false, false,
                false, null, Integer.valueOf(value), null, null, "INT");
    }

    public static Option stringOption(String actionType, String label, String value) {
        return new Option(actionType, label, value, null, null, false, false,
                false, null, null, value, null, "STRING");
    }

    public static Option payloadOption(String actionType, String label, String sourceCardName,
            Object payload, String payloadKind) {
        return new Option(actionType, label, sourceCardName, null, null, false, false,
                false, null, null, null, payload, payloadKind);
    }

    public Option find(String optionId) {
        return byId.get(optionId);
    }
}
