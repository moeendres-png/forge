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
 *       Any value crossing to the wire must match {@code [0-9a-f]{64}};
 *       invalid fingerprints carry a null digest and an
 *       {@code UNAVAILABLE:*} audit string that never satisfies that gate.</li>
 *   <li>Order-sensitive where semantics are ordered: {@code Library} and the
 *       stack preserve encounter order (no sorting). Unordered zones
 *       (hand/battlefield/graveyard/exile/command) are hashed as sorted
 *       multisets.</li>
 * </ul>
 *
 * <p>WS87 fail-closed contract: every field in the fingerprint preimage is
 * required. If any required input cannot be read, this API returns an INVALID
 * {@link Fingerprint} (null digest + failure reason) and NEVER hashes an
 * error marker into a valid-looking 64-hex digest. Equality/no-mutation proof
 * requires BOTH fingerprints valid (see
 * {@link Fingerprint#sameValidIdentity(Fingerprint, Fingerprint)}). Callers
 * must treat invalid as UNKNOWN/UNAVAILABLE, never PASS. Failures never block
 * gameplay: park/submit/settle record UNKNOWN and proceed; only the audit
 * gate may fail.
 */
public final class InternalAuditFingerprint {
    private InternalAuditFingerprint() { }

    /** Explicit validity model: valid carries a real digest; invalid never does. */
    public static final class Fingerprint {
        /** True only when every required input was read and hashed. */
        public final boolean valid;
        /** 64-hex digest when valid; null when invalid (never a sentinel). */
        public final String digest;
        /** Failure reason when invalid; null when valid (never wire). */
        public final String failure;

        private Fingerprint(boolean valid, String digest, String failure) {
            this.valid = valid;
            this.digest = digest;
            this.failure = failure;
        }

        public static Fingerprint valid(String digest) {
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("valid fingerprint requires 64-hex digest");
            }
            return new Fingerprint(true, digest, null);
        }

        public static Fingerprint invalid(String failure) {
            if (failure == null || failure.isEmpty()) {
                throw new IllegalArgumentException("invalid fingerprint requires a failure reason");
            }
            return new Fingerprint(false, null, failure);
        }

        public boolean isValid() {
            return valid;
        }

        /**
         * Audit-safe rendering: the digest when valid, otherwise an explicit
         * non-hex marker that can never be confused with a valid digest.
         */
        public String toAuditString() {
            return valid ? digest : "UNAVAILABLE:" + failure;
        }

        /**
         * Bounded same-process no-mutation proof: true only when BOTH are
         * valid and digests are equal. Any invalid input yields false (the
         * caller must report UNKNOWN, never PASS).
         */
        public static boolean sameValidIdentity(Fingerprint left, Fingerprint right) {
            return left != null && right != null && left.valid && right.valid
                    && left.digest != null && left.digest.equals(right.digest);
        }

        @Override
        public String toString() {
            return toAuditString();
        }
    }

    /** Test-only fault seams: force one required reader family to fail. */
    static volatile boolean failTurnForTests;
    static volatile boolean failPhaseForTests;
    static volatile boolean failPriorityForTests;
    static volatile boolean failActiveForTests;
    static volatile boolean failTimestampForTests;
    static volatile boolean failGameOverForTests;
    static volatile boolean failRegistryForTests;
    static volatile boolean failPlayerStatsForTests;
    static volatile boolean failHandForTests;
    static volatile boolean failBattlefieldForTests;
    static volatile boolean failGraveyardForTests;
    static volatile boolean failExileForTests;
    static volatile boolean failCommandForTests;
    static volatile boolean failLibraryForTests;
    static volatile boolean failStackForTests;
    static volatile boolean failShaForTests;

    /** Clears every test seam (tests must call in finally). */
    public static void clearTestSeams() {
        failTurnForTests = false;
        failPhaseForTests = false;
        failPriorityForTests = false;
        failActiveForTests = false;
        failTimestampForTests = false;
        failGameOverForTests = false;
        failRegistryForTests = false;
        failPlayerStatsForTests = false;
        failHandForTests = false;
        failBattlefieldForTests = false;
        failGraveyardForTests = false;
        failExileForTests = false;
        failCommandForTests = false;
        failLibraryForTests = false;
        failStackForTests = false;
        failShaForTests = false;
    }

    public static Fingerprint ofGame(Game game, BridgeSession session) {
        if (game == null) {
            return Fingerprint.invalid("no-game");
        }
        try {
            final StringBuilder sb = new StringBuilder();
            final String turn;
            try {
                if (failTurnForTests) {
                    throw new RuntimeException("injected turn fault");
                }
                turn = Integer.toString(game.getPhaseHandler().getTurn());
            } catch (Throwable t) {
                return Fingerprint.invalid("turn-unreadable:" + t.getClass().getSimpleName());
            }
            sb.append("turn=").append(turn).append(';');
            final String phase;
            try {
                if (failPhaseForTests) {
                    throw new RuntimeException("injected phase fault");
                }
                final PhaseType phaseType = game.getPhaseHandler().getPhase();
                phase = phaseType == null ? "null" : phaseType.name();
            } catch (Throwable t) {
                return Fingerprint.invalid("phase-unreadable:" + t.getClass().getSimpleName());
            }
            sb.append("phase=").append(phase).append(';');
            final String priority;
            try {
                if (failPriorityForTests) {
                    throw new RuntimeException("injected priority fault");
                }
                priority = idOf(game.getPhaseHandler().getPriorityPlayer(), session);
            } catch (Throwable t) {
                return Fingerprint.invalid("priority-unreadable:" + t.getClass().getSimpleName());
            }
            sb.append("priority=").append(priority).append(';');
            final String active;
            try {
                if (failActiveForTests) {
                    throw new RuntimeException("injected active fault");
                }
                active = idOf(game.getPhaseHandler().getPlayerTurn(), session);
            } catch (Throwable t) {
                return Fingerprint.invalid("active-unreadable:" + t.getClass().getSimpleName());
            }
            sb.append("active=").append(active).append(';');
            final String timestamp;
            try {
                if (failTimestampForTests) {
                    throw new RuntimeException("injected timestamp fault");
                }
                timestamp = Long.toString(game.getTimestamp());
            } catch (Throwable t) {
                return Fingerprint.invalid("timestamp-unreadable:" + t.getClass().getSimpleName());
            }
            sb.append("timestamp=").append(timestamp).append(';');
            final String over;
            try {
                if (failGameOverForTests) {
                    throw new RuntimeException("injected game-over fault");
                }
                over = Boolean.toString(game.isGameOver());
            } catch (Throwable t) {
                return Fingerprint.invalid("game-over-unreadable:" + t.getClass().getSimpleName());
            }
            sb.append("over=").append(over).append(';');
            final List<Player> players;
            try {
                if (failRegistryForTests) {
                    throw new RuntimeException("injected registry fault");
                }
                players = session == null
                        ? new ArrayList<>(game.getPlayers())
                        : session.registryPlayers();
                if (players == null) {
                    throw new NullPointerException("null registry");
                }
            } catch (Throwable t) {
                return Fingerprint.invalid("registry-unreadable:" + t.getClass().getSimpleName());
            }
            for (Player player : players) {
                final String pid;
                final int life;
                final int poison;
                final boolean lost;
                try {
                    if (failPlayerStatsForTests) {
                        throw new RuntimeException("injected player-stats fault");
                    }
                    pid = session == null ? player.getName() : session.playerIdOf(player);
                    life = player.getLife();
                    poison = player.getPoisonCounters();
                    lost = player.hasLost();
                } catch (Throwable t) {
                    return Fingerprint.invalid(
                            "player-stats-unreadable:" + t.getClass().getSimpleName());
                }
                sb.append("player=").append(pid);
                sb.append(",life=").append(life);
                sb.append(",poison=").append(poison);
                sb.append(",lost=").append(lost);
                // Unordered zones: sorted multisets. Ordered zones (Library):
                // encounter order preserved so reordering is detected.
                for (ZoneType zone : new ZoneType[] { ZoneType.Hand, ZoneType.Battlefield,
                        ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command }) {
                    final boolean seam;
                    switch (zone) {
                        case Hand:
                            seam = failHandForTests;
                            break;
                        case Battlefield:
                            seam = failBattlefieldForTests;
                            break;
                        case Graveyard:
                            seam = failGraveyardForTests;
                            break;
                        case Exile:
                            seam = failExileForTests;
                            break;
                        case Command:
                            seam = failCommandForTests;
                            break;
                        default:
                            seam = false;
                            break;
                    }
                    final List<String> names = new ArrayList<>();
                    try {
                        if (seam) {
                            throw new RuntimeException("injected " + zone.name() + " fault");
                        }
                        for (Card card : player.getCardsIn(zone)) {
                            names.add(System.identityHashCode(card) + ":" + card.getName());
                        }
                    } catch (Throwable t) {
                        return Fingerprint.invalid("zone-unreadable:" + zone.name() + ":"
                                + t.getClass().getSimpleName());
                    }
                    Collections.sort(names);
                    sb.append(',').append(zone.name()).append("=[");
                    sb.append(String.join("|", names)).append(']');
                }
                final List<String> library = new ArrayList<>();
                try {
                    if (failLibraryForTests) {
                        throw new RuntimeException("injected Library fault");
                    }
                    for (Card card : player.getCardsIn(ZoneType.Library)) {
                        library.add(System.identityHashCode(card) + ":" + card.getName());
                    }
                } catch (Throwable t) {
                    return Fingerprint.invalid("library-unreadable:" + t.getClass().getSimpleName());
                }
                sb.append(",Library=[");
                sb.append(String.join("|", library)).append(']');
                sb.append(';');
            }
            final List<String> stack = new ArrayList<>();
            try {
                if (failStackForTests) {
                    throw new RuntimeException("injected stack fault");
                }
                for (SpellAbilityStackInstance si : game.getStack()) {
                    stack.add(si.getSourceCard().getName() + "@" + si.getActivatingPlayer());
                }
            } catch (Throwable t) {
                return Fingerprint.invalid("stack-unreadable:" + t.getClass().getSimpleName());
            }
            sb.append("stack=[");
            sb.append(String.join("|", stack)).append(']');
            try {
                if (failShaForTests) {
                    throw new RuntimeException("injected sha fault");
                }
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                final byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                final StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                return Fingerprint.valid(hex.toString());
            } catch (Throwable t) {
                return Fingerprint.invalid("sha-unavailable:" + t.getClass().getSimpleName());
            }
        } catch (Throwable e) {
            return Fingerprint.invalid("hash-error:" + e.getClass().getSimpleName());
        }
    }

    private static String idOf(Player player, BridgeSession session) {
        if (player == null) {
            return "none";
        }
        return session == null ? player.getName() : session.playerIdOf(player);
    }
}
