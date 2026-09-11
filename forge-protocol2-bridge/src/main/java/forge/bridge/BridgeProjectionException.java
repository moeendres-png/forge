package forge.bridge;

/**
 * Thrown when authoritative Forge state cannot be read for a REQUIRED observation
 * field. The bridge converts this into an explicit protocol error; it never
 * substitutes a plausible default (R4).
 */
public final class BridgeProjectionException extends RuntimeException {
    private final String field;

    public BridgeProjectionException(String field, String detail) {
        super("projection failed field=" + field + " " + detail);
        this.field = field;
    }

    public BridgeProjectionException(String field, Throwable cause) {
        super("projection failed field=" + field + ": " + cause, cause);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
