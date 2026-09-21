package forge.bridge;

import forge.game.zone.ZoneType;

/**
 * Thrown when native legal-option enumeration cannot be completed for a required
 * zone or card. Never caught-and-continued: a partial candidate list must never
 * back a SUPPORTED frame (F5).
 */
public final class BridgeNativeEnumerationException extends RuntimeException {
    private final ZoneType zone;

    public BridgeNativeEnumerationException(ZoneType zone, String detail, Throwable cause) {
        super("native enumeration failed zone=" + (zone == null ? "?" : zone.name())
                + " " + detail, cause);
        this.zone = zone;
    }

    public ZoneType getZone() {
        return zone;
    }
}
