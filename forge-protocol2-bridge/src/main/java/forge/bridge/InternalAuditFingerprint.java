package forge.bridge;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Privileged INTERNAL audit fingerprint: same-process mutation evidence.
 *
 * <p>Scope truthfully bounded:
 * <ul>
 *   <li>MAY inspect hidden zones, engine timestamps and process-local object
 *       identity ({@code System.identityHashCode}) to detect that rejected
 *       submissions mutated nothing in THIS process.</li>
 *   <li>Is NOT cross-process deterministic (object identity, re-objecting
 *       across zone moves) and therefore NEVER a replay identity.</li>
 *   <li>Is NEVER serialized to a pilot: no Protocol-2 response may carry it.
 *       Any value crossing to the wire must match {@code [0-9a-f]{64}}; the
 *       {@code internal-*} sentinels below never satisfy that gate.</li>
 *   <li>Order-sensitive where semantics are ordered: {@code Library} and the
 *       stack preserve encounter order (no sorting). Unordered zones
 *       (hand/battlefield/graveyard/exile/command) are hashed as sorted
 *       multisets.</li>
 * </ul>
 *
 * <p>Read failures degrade to explicit {@code internal-*} sentinels for the
 * internal audit trail. A sentinel must never be treated as proof of a
 * particular state; qualification checks fail closed on them.
 */
public final class InternalAuditFingerprint {
    private InternalAuditFingerprint() { }

    public static String ofGame(Game game, BridgeSession session) {
        if (game == null) {
            return "internal-no-game";
        }
        try {
            final StringBuilder sb = new StringBuilder();
            sb.append("turn=").append(safeTurn(game)).append(';');
            sb.append("phase=").append(safePhase(game)).append(';');
            sb.append("priority=").append(idOf(game.getPhaseHandler().getPriorityPlayer(), session)).append(';');
            sb.append("active=").append(idOf(game.getPhaseHandler().getPlayerTurn(), session)).append(';');
            sb.append("timestamp=").append(game.getTimestamp()).append(';');
            sb.append("over=").append(game.isGameOver()).append(';');
            final List<Player> players = session == null
                    ? new ArrayList<>(game.getPlayers())
                    : session.registryPlayers();
            for (Player player : players) {
                sb.append("player=").append(session == null ? player.getName() : session.playerIdOf(player));
                sb.append(",life=").append(player.getLife());
                sb.append(",poison=").append(player.getPoisonCounters());
                sb.append(",lost=").append(player.hasLost());
                // Unordered zones: sorted multisets. Ordered zones (Library):
                // encounter order preserved so reordering is detected.
                for (ZoneType zone : new ZoneType[] { ZoneType.Hand, ZoneType.Battlefield,
                        ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command }) {
                    sb.append(',').append(zone.name()).append("=[");
                    final List<String> names = new ArrayList<>();
                    try {
                        for (Card card : player.getCardsIn(zone)) {
                            names.add(System.identityHashCode(card) + ":" + card.getName());
                        }
                    } catch (Throwable t) {
                        names.add("internal-unreadable:" + t.getClass().getSimpleName());
                    }
                    Collections.sort(names);
                    sb.append(String.join("|", names)).append(']');
                }
                sb.append(",Library=[");
                final List<String> library = new ArrayList<>();
                try {
                    for (Card card : player.getCardsIn(ZoneType.Library)) {
                        library.add(System.identityHashCode(card) + ":" + card.getName());
                    }
                } catch (Throwable t) {
                    library.add("internal-unreadable:" + t.getClass().getSimpleName());
                }
                sb.append(String.join("|", library)).append(']');
                sb.append(';');
            }
            sb.append("stack=[");
            final List<String> stack = new ArrayList<>();
            try {
                for (SpellAbilityStackInstance si : game.getStack()) {
                    stack.add(si.getSourceCard().getName() + "@" + si.getActivatingPlayer());
                }
            } catch (Throwable t) {
                stack.add("internal-unreadable:" + t.getClass().getSimpleName());
            }
            sb.append(String.join("|", stack)).append(']');
            return shaOrSentinel(sb.toString());
        } catch (Throwable e) {
            return "internal-hash-error";
        }
    }

    private static String safeTurn(Game game) {
        try {
            return Integer.toString(game.getPhaseHandler().getTurn());
        } catch (Throwable e) {
            return "?";
        }
    }

    private static String safePhase(Game game) {
        try {
            final PhaseType phase = game.getPhaseHandler().getPhase();
            return phase == null ? "null" : phase.name();
        } catch (Throwable e) {
            return "?";
        }
    }

    private static String idOf(Player player, BridgeSession session) {
        if (player == null) {
            return "none";
        }
        return session == null ? player.getName() : session.playerIdOf(player);
    }

    private static String shaOrSentinel(String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Throwable e) {
            return "internal-sha-unavailable";
        }
    }
}
