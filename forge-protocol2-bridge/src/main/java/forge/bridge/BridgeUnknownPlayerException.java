package forge.bridge;

/**
 * Thrown when a native Player has no registered bridge principal. Bridge identity
 * never falls back to display names; unknown identity fails explicitly (R11).
 */
public final class BridgeUnknownPlayerException extends RuntimeException {
    BridgeUnknownPlayerException(String detail) {
        super("unregistered player identity: " + detail);
    }
}
