package forge.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Truthful provider/engine/bridge identity (hard gate F3).
 *
 * <p>WSR24 (PB-05): the operator claim (system property, then environment,
 * then the legacy build-filtered {@code bridge.properties} value) keeps its
 * historical precedence for compatibility, but the version payload now also
 * carries the build-derived source identity ({@code engine.git_commit} /
 * {@code engine.git_tree} / {@code engine.git_dirty}, materialized from
 * native git at build time via maven-antrun, worktree-correct). Merely changing an environment variable can therefore no longer
 * silently forge the binding: any divergence between the claim and the built
 * source is visible in every {@code get_provider_version} response via
 * {@link #buildGitCommit()} and {@link #claimMatchesBuild()}. Tarball builds
 * without .git record no build identity (honest {@code unknown}, never
 * faked); the usability gate still fails closed on missing identity.
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
            value = buildGitCommit();
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
        value = buildGitCommit();
        if (value != null) {
            return "build:bridge.properties:git";
        }
        value = buildProperty("engine.commit", null);
        if (value != null) {
            return value.matches("[0-9a-f]{40}")
                    ? "build:bridge.properties" : "build:bridge.properties:invalid";
        }
        return "unavailable";
    }

    /**
     * Build-derived source commit, or null when the build recorded none
     * (tarball without .git, unfiltered placeholder, malformed value, or an
     * all-zero fixture). Shape-validated and never all zeros: this is the
     * value no runtime environment variable can alter.
     */
    public static String buildGitCommit() {
        final String value = buildProperty("engine.git_commit", null);
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            return null;
        }
        if (value.matches("0{40}")) {
            return null;
        }
        return value;
    }

    /**
     * Build dirtiness as recorded at build time: {@code "true"},
     * {@code "false"}, or {@code "unknown"} when the build recorded nothing.
     */
    public static String buildGitDirty() {
        final String value = buildProperty("engine.git_dirty", null);
        if ("true".equals(value) || "false".equals(value)) {
            return value;
        }
        return "unknown";
    }

    /** Where the build identity came from, for payload transparency. */
    public static String buildGitSource() {
        return buildGitCommit() == null
                        || buildGitTree() == null
                        || "unknown".equals(buildGitDirty())
                ? "unavailable" : "build:bridge.properties:git";
    }

    /**
     * Build-derived source tree, or null when the build recorded none. Binds
     * the exact built bytes (commit alone is ambiguous under dirtiness).
     */
    public static String buildGitTree() {
        final String value = buildProperty("engine.git_tree", null);
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            return null;
        }
        if (value.matches("0{40}")) {
            return null;
        }
        return value;
    }

    /**
     * True only when an operator claim is bound AND the build recorded an
     * exact clean source AND they agree. Consumers (Lab AF00) fail the
     * provenance binding on false; the bridge itself keeps serving the
     * claim with both values visible (compatibility: historical pins such
     * as the c4d67145 separate-process fixture keep working, visibly
     * unverified rather than silently trusted).
     */
    public static boolean claimMatchesBuild() {
        final String claim = engineCommitIfValid();
        final String built = buildGitCommit();
        final String tree = buildGitTree();
        if (claim == null || built == null || tree == null) {
            return false;
        }
        if (!claim.equals(built)) {
            return false;
        }
        return "false".equals(buildGitDirty());
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
