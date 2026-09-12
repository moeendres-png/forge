package forge.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Truthful provider/engine/bridge identity (hard gate F3).
 *
 * <p>The engine commit is NEVER hardcoded here and never hand-maintained. Resolution
 * uses the first present operator-supplied, non-authoritative source:
 * {@code forge.engine.sha} system property, then {@code FORGE_ENGINE_SHA} environment
 * variable, then the build-filtered {@code bridge.properties} value (itself sourced
 * from the same environment variable at build time). A present but malformed value
 * fails closed instead of falling through; anything else (including absent values)
 * resolves to {@code "unknown"} for reporting, while
 * {@link #engineCommitIfValid()} returns null so the engine gates startup.
 */
public final class VersionInfo {
    public static final String PROVIDER = "forge";
    public static final String RELEASE = "2.0.14";
    public static final String PROTOCOL_VERSION = BridgeProtocol.PROTOCOL_VERSION;
    public static final String BRIDGE_NAME = "forge-protocol2-bridge";
    public static final String BRIDGE_VERSION = "2.0.14-ws-a1d-h4f";

    private VersionInfo() { }

    public static String engineCommit() {
        final String valid = engineCommitIfValid();
        return valid == null ? "unknown" : valid;
    }

    /**
     * Returns the exact 40-hex engine SHA from operator-supplied sources, or null when
     * no valid identity is available. Shape-validated only: this method never asserts
     * which SHA is correct (that authority lives outside the bridge). A present but
     * malformed value fails closed (null) rather than silently falling through to a
     * weaker source.
     */
    public static String engineCommitIfValid() {
        String value = System.getProperty("forge.engine.sha");
        if (value == null) {
            value = System.getenv("FORGE_ENGINE_SHA");
        }
        if (value == null) {
            value = buildProperty("engine.commit", null);
        }
        return value != null && value.matches("[0-9a-f]{40}") ? value : null;
    }

    public static String engineCommitSource() {
        String value = System.getProperty("forge.engine.sha");
        if (value != null) {
            return value.matches("[0-9a-f]{40}")
                    ? "sysprop:forge.engine.sha" : "sysprop:forge.engine.sha:invalid";
        }
        value = System.getenv("FORGE_ENGINE_SHA");
        if (value != null) {
            return value.matches("[0-9a-f]{40}")
                    ? "env:FORGE_ENGINE_SHA" : "env:FORGE_ENGINE_SHA:invalid";
        }
        value = buildProperty("engine.commit", null);
        if (value != null) {
            return value.matches("[0-9a-f]{40}")
                    ? "build:bridge.properties" : "build:bridge.properties:invalid";
        }
        return "unavailable";
    }

    private static String buildProperty(String key, String fallback) {
        final Properties props = new Properties();
        try (InputStream in = VersionInfo.class.getResourceAsStream("/bridge.properties")) {
            if (in == null) {
                return fallback;
            }
            props.load(in);
        } catch (IOException e) {
            return fallback;
        }
        final String value = props.getProperty(key, fallback);
        if (value != null && value.contains("${")) {
            return fallback;
        }
        return value;
    }
}
