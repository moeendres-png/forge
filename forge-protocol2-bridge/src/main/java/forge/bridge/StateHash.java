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
 * Canonical, bridge-local state identity for staleness and audit binding.
 *
 * <p>Not a Forge revision (none exists); a deterministic digest over turn, phase,
 * priority, life totals, zone membership, the stack and terminal state. Used for
 * pre/post evidence and to prove rejected submissions mutate nothing.
 */
public final class StateHash {
    private StateHash() { }

    public static String ofGame(Game game, BridgeSession session) {
        if (game == null) {
            return "no-game";
        }
        try {
            final StringBuilder sb = new StringBuilder();
            sb.append("turn=").append(safeTurn(game)).append(';');
            sb.append("phase=").append(safePhase(game)).append(';');
            sb.append("priority=").append(idOf(game.getPhaseHandler().getPriorityPlayer(), session)).append(';');
            sb.append("active=").append(idOf(game.getPhaseHandler().getPlayerTurn(), session)).append(';');
            sb.append("timestamp=").append(game.getTimestamp()).append(';');
            sb.append("over=").append(game.isGameOver()).append(';');
            final List<Player> players = new ArrayList<>(game.getPlayers());
            for (Player player : players) {
                sb.append("player=").append(session == null ? player.getName() : session.playerIdOf(player));
                sb.append(",life=").append(player.getLife());
                sb.append(",poison=").append(player.getPoisonCounters());
                sb.append(",lost=").append(player.hasLost());
                for (ZoneType zone : new ZoneType[] { ZoneType.Hand, ZoneType.Battlefield,
                        ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Library }) {
                    sb.append(',').append(zone.name()).append("=[");
                    final List<String> names = new ArrayList<>();
                    try {
                        for (Card card : player.getCardsIn(zone)) {
                            names.add(System.identityHashCode(card) + ":" + card.getName());
                        }
                    } catch (Throwable t) {
                        names.add("unreadable");
                    }
                    Collections.sort(names);
                    sb.append(String.join("|", names)).append(']');
                }
                sb.append(';');
            }
            sb.append("stack=[");
            final List<String> stack = new ArrayList<>();
            try {
                for (SpellAbilityStackInstance si : game.getStack()) {
                    stack.add(si.getSourceCard().getName() + "@" + si.getActivatingPlayer());
                }
            } catch (Throwable t) {
                stack.add("unreadable");
            }
            Collections.sort(stack);
            sb.append(String.join("|", stack)).append(']');
            return sha256(sb.toString());
        } catch (Throwable e) {
            return "hash-error";
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

    public static String sha256(String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Throwable e) {
            return "sha-unavailable";
        }
    }
}
