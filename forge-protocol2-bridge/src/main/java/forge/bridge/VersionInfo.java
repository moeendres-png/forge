package forge.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Truthful provider/engine/bridge identity (hard gate F3).
 *
 * <p>The engine commit is NEVER hand-maintained here. Resolution order:
 * {@code FORGE_ENGINE_SHA} environment variable, then the build-filtered
 * {@code bridge.properties} value (itself sourced from the same environment
 * variable at build time), then {@code "unknown"}.
 */
public final class VersionInfo {
    public static final String PROVIDER = "forge";
    public static final String RELEASE = "2.0.14";
    public static final String PROTOCOL_VERSION = BridgeProtocol.PROTOCOL_VERSION;
    public static final String BRIDGE_NAME = "forge-protocol2-bridge";
    public static final String BRIDGE_VERSION = "2.0.14-ws-a1d-h4f";

    private VersionInfo() { }

    public static String engineCommit() {
        final String env = System.getenv("FORGE_ENGINE_SHA");
        if (env != null && env.matches("[0-9a-f]{40}")) {
            return env;
        }
        final String built = buildProperty("engine.commit", "");
        if (built != null && built.matches("[0-9a-f]{40}")) {
            return built;
        }
        return "unknown";
    }

    public static String engineCommitSource() {
        final String env = System.getenv("FORGE_ENGINE_SHA");
        if (env != null && env.matches("[0-9a-f]{40}")) {
            return "env:FORGE_ENGINE_SHA";
        }
        final String built = buildProperty("engine.commit", "");
        if (built != null && built.matches("[0-9a-f]{40}")) {
            return "build:bridge.properties";
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
