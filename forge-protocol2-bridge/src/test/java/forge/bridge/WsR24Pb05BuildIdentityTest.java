package forge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WSR24 PB-05 build-identity hardening (Forge side).
 *
 * <p>Fail-before baseline: the bridge reported the operator-supplied
 * {@code FORGE_ENGINE_SHA} claim with no build-derived counterweight, so a
 * forged environment variable was silent (WSR22 PB-05, BOUNDED_NON_BLOCKING).
 * After the hardening, every {@code get_provider_version} payload carries the
 * build-recorded commit/dirty/source plus {@code engine_commit_verified}, so
 * divergence is visible, never silent; precedence stays compatible.
 *
 * <p>In-JVM tests manipulate only the {@code forge.engine.sha} system
 * property (same operator-claim tier as the environment) with save/restore.
 * Shadow-resource child tests prove malformed/missing provenance behavior in
 * a clean process.
 */
public class WsR24Pb05BuildIdentityTest {

    private static String savedSysprop() {
        return System.getProperty("forge.engine.sha");
    }

    private static void restoreSysprop(String saved) {
        if (saved == null) {
            System.clearProperty("forge.engine.sha");
        } else {
            System.setProperty("forge.engine.sha", saved);
        }
    }

    private static JsonObject dispatch(String json) {
        final BridgeEngine engine = new BridgeEngine();
        try {
            return JsonParser.parseString(engine.dispatch(BridgeProtocol.parse(json)))
                    .getAsJsonObject();
        } catch (BridgeProtocol.MalformedRequestException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonObject versionPayload() {
        final JsonObject response = dispatch("{\"protocol_version\":\"2.0.0\","
                + "\"request_id\":\"pb05-v\",\"message_type\":\"get_provider_version\"}");
        Assert.assertTrue(response.get("success").getAsBoolean(), "version must stay served");
        return response.get("payload").getAsJsonObject();
    }

    @Test(timeOut = 120000)
    public void testBuildIdentityEmbedded() {
        // The real build (from git) records its source; tarballs honestly
        // record nothing. Either way the value is never hand-maintained here.
        final String built = VersionInfo.buildGitCommit();
        final String dirty = VersionInfo.buildGitDirty();
        if (built != null) {
            Assert.assertTrue(built.matches("[0-9a-f]{40}"), "build commit shape");
            Assert.assertFalse(built.matches("0{40}"), "build commit must not be zeros");
            Assert.assertTrue(dirty.equals("true") || dirty.equals("false"),
                    "dirty flag: " + dirty);
            Assert.assertEquals(VersionInfo.buildGitSource(), "build:bridge.properties:git");
            final String tree = VersionInfo.buildGitTree();
            Assert.assertNotNull(tree, "clean git build must record the source tree");
            Assert.assertTrue(tree.matches("[0-9a-f]{40}"), "build tree shape");
        } else {
            Assert.assertEquals(dirty, "unknown");
            Assert.assertEquals(VersionInfo.buildGitSource(), "unavailable");
        }
    }

    @Test(timeOut = 120000)
    public void testCleanCheckoutNeedsNoOperatorClaim() {
        final String saved = savedSysprop();
        System.clearProperty("forge.engine.sha");
        try {
            if (System.getenv("FORGE_ENGINE_SHA") != null) {
                return;
            }
            final String built = VersionInfo.buildGitCommit();
            if (built == null) {
                return;
            }
            Assert.assertEquals(VersionInfo.engineCommitIfValid(), built,
                    "build identity binds gameplay with no env/var claim");
            Assert.assertEquals(VersionInfo.engineCommitSource(), "build:bridge.properties:git");
            Assert.assertEquals(VersionInfo.claimMatchesBuild(), "false".equals(
                    VersionInfo.buildGitDirty()));
        } finally {
            restoreSysprop(saved);
        }
    }

    @Test(timeOut = 120000)
    public void testDivergentClaimStaysVisibleNotSilent() {
        final String saved = savedSysprop();
        try {
            final String built = VersionInfo.buildGitCommit();
            if (built == null) {
                return;
            }
            // A forged claim keeps serving (compatibility) but is flagged.
            final String forged = built.startsWith("a") ? "b" + built.substring(1)
                    : "a" + built.substring(1);
            System.setProperty("forge.engine.sha", forged);
            Assert.assertEquals(VersionInfo.engineCommit(), forged);
            Assert.assertEquals(VersionInfo.engineCommitSource(), "sysprop:forge.engine.sha");
            Assert.assertEquals(VersionInfo.buildGitCommit(), built,
                    "build truth must ride along, unaltered by the claim");
            Assert.assertFalse(VersionInfo.claimMatchesBuild(),
                    "divergence must be reported, never silent");
            final JsonObject payload = versionPayload();
            Assert.assertEquals(payload.get("engine_commit").getAsString(), forged);
            Assert.assertEquals(payload.get("engine_build_commit").getAsString(), built);
            Assert.assertFalse(payload.get("engine_commit_verified").getAsBoolean());
        } finally {
            restoreSysprop(saved);
        }
    }

    @Test(timeOut = 120000)
    public void testExactClaimVerifiesOnCleanBuild() {
        final String saved = savedSysprop();
        try {
            final String built = VersionInfo.buildGitCommit();
            if (built == null) {
                return;
            }
            System.setProperty("forge.engine.sha", built);
            Assert.assertEquals(VersionInfo.engineCommit(), built);
            Assert.assertEquals(VersionInfo.claimMatchesBuild(),
                    "false".equals(VersionInfo.buildGitDirty()),
                    "exact claim verifies only on a clean build");
            final JsonObject payload = versionPayload();
            Assert.assertEquals(payload.get("engine_commit_verified").getAsBoolean(),
                    "false".equals(VersionInfo.buildGitDirty()));
        } finally {
            restoreSysprop(saved);
        }
    }

    // ------------------------------------------------------------------
    // Clean-process shadow fixtures: malformed/missing provenance.
    // ------------------------------------------------------------------

    private static final class Child {
        final Process process;
        final BufferedWriter stdin;
        final BlockingQueue<String> stdout = new ArrayBlockingQueue<>(1024);

        Child(Path shadow) throws Exception {
            final String javaHome = System.getProperty("java.home");
            String classpath = System.getProperty("java.class.path");
            if (shadow != null) {
                classpath = shadow.toString() + java.io.File.pathSeparator + classpath;
            }
            final Path repoRoot = java.nio.file.Paths.get("").toAbsolutePath().getParent();
            final ProcessBuilder builder = new ProcessBuilder(javaHome + "/bin/java",
                    "-Djava.awt.headless=true", "-cp", classpath, "forge.bridge.BridgeMain");
            builder.environment().remove("FORGE_ENGINE_SHA");
            builder.environment().remove("DISPLAY");
            builder.environment().put("FORGE_ASSETS_DIR",
                    repoRoot.resolve("forge-gui").toString());
            process = builder.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
                    StandardCharsets.UTF_8));
            final BufferedReader out = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            final Thread pump = new Thread(() -> {
                try {
                    String line;
                    while ((line = out.readLine()) != null) {
                        stdout.offer(line);
                    }
                } catch (Exception e) {
                    // Pump ends with the process.
                }
            });
            pump.setDaemon(true);
            pump.start();
        }

        JsonObject request(String json) throws Exception {
            final String requestId = JsonParser.parseString(json).getAsJsonObject()
                    .get("request_id").getAsString();
            stdin.write(json);
            stdin.write("\n");
            stdin.flush();
            final long deadline = System.currentTimeMillis() + 120000;
            while (System.currentTimeMillis() < deadline) {
                final String line = stdout.poll(
                        Math.max(100, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                final JsonObject response = JsonParser.parseString(line).getAsJsonObject();
                if (response.get("request_id").getAsString().equals(requestId)) {
                    return response;
                }
            }
            throw new AssertionError("no response for " + requestId);
        }

        void close() {
            try {
                stdin.close();
            } catch (Exception e) {
                // Already exiting.
            }
            try {
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
        }
    }

    private static Path shadowProps(String engineCommit, String gitCommit, String gitDirty)
            throws Exception {
        return shadowProps(engineCommit, gitCommit, null, gitDirty);
    }

    private static Path shadowProps(String engineCommit, String gitCommit, String gitTree,
                                    String gitDirty) throws Exception {
        final Path shadow = Files.createTempDirectory("pb05-shadow");
        final StringBuilder body = new StringBuilder("bridge.artifact=forge-protocol2-bridge\n"
                + "bridge.version=test\n");
        if (engineCommit != null) {
            body.append("engine.commit=").append(engineCommit).append('\n');
        }
        if (gitCommit != null) {
            body.append("engine.git_commit=").append(gitCommit).append('\n');
        }
        if (gitTree != null) {
            body.append("engine.git_tree=").append(gitTree).append('\n');
        }
        if (gitDirty != null) {
            body.append("engine.git_dirty=").append(gitDirty).append('\n');
        }
        Files.writeString(shadow.resolve("bridge.properties"), body.toString(),
                StandardCharsets.UTF_8);
        return shadow;
    }

    @Test(timeOut = 600000)
    public void testMalformedBuildIdentityFallsBackVisibly() throws Exception {
        // Malformed git provenance must not crash binding: the legacy claim
        // still serves, the build reads unknown, verification is false.
        final String legacy = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        final Path shadow = shadowProps(legacy, "not-a-sha", "maybe");
        final Child child = new Child(shadow);
        try {
            final JsonObject version = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"pb05-malformed\",\"message_type\":"
                    + "\"get_provider_version\"}");
            Assert.assertTrue(version.get("success").getAsBoolean());
            final JsonObject payload = version.get("payload").getAsJsonObject();
            Assert.assertEquals(payload.get("engine_commit").getAsString(), legacy);
            Assert.assertEquals(payload.get("engine_commit_source").getAsString(),
                    "build:bridge.properties");
            Assert.assertEquals(payload.get("engine_build_commit").getAsString(), "unknown");
            Assert.assertEquals(payload.get("engine_build_dirty").getAsString(), "unknown");
            Assert.assertFalse(payload.get("engine_commit_verified").getAsBoolean());
            final JsonObject started = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"pb05-malformed-start\",\"message_type\":"
                    + "\"start_engine\"}");
            Assert.assertTrue(started.get("success").getAsBoolean(),
                    "valid legacy claim still binds gameplay");
        } finally {
            child.close();
        }
    }

    @Test(timeOut = 600000)
    public void testMissingProvenanceFailsClosed() throws Exception {
        // No claim anywhere and no build identity: gameplay must fail closed,
        // while version/capabilities stay observable (documented gate).
        final Path shadow = shadowProps(null, null, null);
        final Child child = new Child(shadow);
        try {
            final JsonObject version = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"pb05-missing\",\"message_type\":"
                    + "\"get_provider_version\"}");
            Assert.assertTrue(version.get("success").getAsBoolean());
            final JsonObject payload = version.get("payload").getAsJsonObject();
            Assert.assertEquals(payload.get("engine_commit").getAsString(), "unknown");
            Assert.assertEquals(payload.get("engine_build_commit").getAsString(), "unknown");
            Assert.assertFalse(payload.get("engine_commit_verified").getAsBoolean());
            final JsonObject started = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"pb05-missing-start\",\"message_type\":"
                    + "\"start_engine\"}");
            Assert.assertFalse(started.get("success").getAsBoolean(),
                    "missing identity must fail closed where required");
        } finally {
            child.close();
        }
    }

    @Test(timeOut = 600000)
    public void testMissingBuildTreeNeverVerifies() throws Exception {
        final String sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        final Path shadow = shadowProps(sha, sha, null, "false");
        final Child child = new Child(shadow);
        try {
            final JsonObject version = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"pb05-missing-tree\",\"message_type\":"
                    + "\"get_provider_version\"}");
            Assert.assertTrue(version.get("success").getAsBoolean());
            final JsonObject payload = version.get("payload").getAsJsonObject();
            Assert.assertEquals(payload.get("engine_build_commit").getAsString(), sha);
            Assert.assertEquals(payload.get("engine_build_tree").getAsString(), "unknown");
            Assert.assertEquals(payload.get("engine_build_source").getAsString(), "unavailable");
            Assert.assertFalse(payload.get("engine_commit_verified").getAsBoolean(),
                    "commit+clean without a valid build tree must never verify");
        } finally {
            child.close();
        }
    }

    @Test(timeOut = 600000)
    public void testMalformedBuildTreeNeverVerifies() throws Exception {
        final String sha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        final Path shadow = shadowProps(sha, sha, "not-a-tree", "false");
        final Child child = new Child(shadow);
        try {
            final JsonObject version = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"pb05-malformed-tree\",\"message_type\":"
                    + "\"get_provider_version\"}");
            Assert.assertTrue(version.get("success").getAsBoolean());
            final JsonObject payload = version.get("payload").getAsJsonObject();
            Assert.assertEquals(payload.get("engine_build_tree").getAsString(), "unknown");
            Assert.assertFalse(payload.get("engine_commit_verified").getAsBoolean(),
                    "malformed build tree must fail closed");
        } finally {
            child.close();
        }
    }

    @Test(timeOut = 600000)
    public void testCompleteCleanCommitAndTreeCanVerify() throws Exception {
        final String sha = "cccccccccccccccccccccccccccccccccccccccc";
        final String tree = "dddddddddddddddddddddddddddddddddddddddd";
        final Path shadow = shadowProps(sha, sha, tree, "false");
        final Child child = new Child(shadow);
        try {
            final JsonObject version = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"pb05-complete\",\"message_type\":"
                    + "\"get_provider_version\"}");
            Assert.assertTrue(version.get("success").getAsBoolean());
            final JsonObject payload = version.get("payload").getAsJsonObject();
            Assert.assertEquals(payload.get("engine_build_commit").getAsString(), sha);
            Assert.assertEquals(payload.get("engine_build_tree").getAsString(), tree);
            Assert.assertEquals(payload.get("engine_build_dirty").getAsString(), "false");
            Assert.assertEquals(payload.get("engine_build_source").getAsString(),
                    "build:bridge.properties:git");
            Assert.assertTrue(payload.get("engine_commit_verified").getAsBoolean(),
                    "complete clean commit+tree provenance may verify");
        } finally {
            child.close();
        }
    }

    @Test(timeOut = 600000)
    public void testLegacyFallbackSurvivesWithoutGitKeys() throws Exception {
        // Pre-hardening artifacts (engine.commit only) behave exactly as before.
        final String legacy = "ffffffffffffffffffffffffffffffffffffffff";
        final List<String> lines = new ArrayList<>();
        lines.add("bridge.artifact=forge-protocol2-bridge");
        lines.add("bridge.version=test");
        lines.add("engine.commit=" + legacy);
        final Path shadow = Files.createTempDirectory("pb05-legacy");
        Files.write(shadow.resolve("bridge.properties"), lines, StandardCharsets.UTF_8);
        final Child child = new Child(shadow);
        try {
            final JsonObject version = child.request("{\"protocol_version\":\"2.0.0\","
                    + "\"request_id\":\"pb05-legacy\",\"message_type\":"
                    + "\"get_provider_version\"}");
            Assert.assertTrue(version.get("success").getAsBoolean());
            final JsonObject payload = version.get("payload").getAsJsonObject();
            Assert.assertEquals(payload.get("engine_commit").getAsString(), legacy);
            Assert.assertEquals(payload.get("engine_commit_source").getAsString(),
                    "build:bridge.properties");
            Assert.assertEquals(payload.get("engine_build_commit").getAsString(), "unknown");
        } finally {
            child.close();
        }
    }
}
