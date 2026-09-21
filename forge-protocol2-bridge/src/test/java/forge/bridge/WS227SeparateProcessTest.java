package forge.bridge;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * WS227 fresh-JVM record/replay proof: Process A records the Arc Lightning
 * 2+1 semantic tape in a fresh child JVM bound to the exact Core-authority
 * SHA; Process B replays it in a second fresh child JVM from the same
 * manifest/seed with exactly-once semantic resolution and coordinate
 * comparison. No state injection, no outcome injection, one game per process.
 */
public class WS227SeparateProcessTest {
    private static final String CORE_SHA = "d52e890538dc0380d1b26d781349312e310b3a2b";
    private static final long SEED = 2277717L;

    private static int runChild(String mode, long seed, String gameId, Path recordPath)
            throws Exception {
        final String javaBin = System.getProperty("java.home") + "/bin/java";
        final String classpath = System.getProperty("java.class.path");
        final ProcessBuilder builder = new ProcessBuilder(javaBin,
                "-Djava.awt.headless=true",
                "-Dforge.engine.sha=" + CORE_SHA,
                "-cp", classpath, "forge.bridge.WS227ReplayChild",
                mode, Long.toString(seed), gameId, recordPath.toString(), CORE_SHA);
        builder.environment().put("FORGE_ENGINE_SHA", CORE_SHA);
        final Path repoRoot = java.nio.file.Paths.get("").toAbsolutePath().getParent();
        builder.environment().put("FORGE_ASSETS_DIR",
                repoRoot.resolve("forge-gui").toString());
        builder.environment().remove("DISPLAY");
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        final String output;
        try (java.io.InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final boolean exited = process.waitFor(300, TimeUnit.SECONDS);
        Assert.assertTrue(exited, "child " + mode + " timed out; output:\n" + output);
        final int code = process.exitValue();
        System.err.println("[ws227pipe:" + mode + "] exit=" + code + "\n" + output);
        Assert.assertTrue(output.contains(mode.equals("record")
                ? "RECORD_PASS" : "REPLAY_PASS"),
                "child " + mode + " must report PASS; exit=" + code + " output:\n" + output);
        return code;
    }

    @Test(timeOut = 900000)
    public void testFreshJvmRecordReplay() throws Exception {
        final Path recordPath =
                Files.createTempFile("ws227-tape-", ".json");
        try {
            final int recordCode = runChild("record", SEED, "ws227-fresh-record",
                    recordPath);
            Assert.assertEquals(recordCode, 0, "record child must exit 0");
            Assert.assertTrue(Files.size(recordPath) > 0, "tape must be written");
            final String tape = new String(Files.readAllBytes(recordPath),
                    StandardCharsets.UTF_8);
            Assert.assertTrue(tape.contains("semantic-replay-tape/1.0.0")
                    || tape.contains("forge-semantic-replay"),
                    "tape must carry the neutral contract identity");
            Assert.assertTrue(tape.contains(CORE_SHA), "tape must bind the core SHA");
            // Replay runs in a second fresh JVM: same manifest/seed, no state
            // injection, exactly-once resolution, coordinate comparison.
            final int replayCode = runChild("replay", SEED, "ws227-fresh-replay",
                    recordPath);
            Assert.assertEquals(replayCode, 0, "replay child must exit 0");
        } finally {
            try {
                Files.deleteIfExists(recordPath);
            } catch (Exception e) {
                // Best effort.
            }
        }
    }

    @Test(timeOut = 900000)
    public void testFreshJvmIsolationBindsCoreSha() throws Exception {
        // Each child binds the exact provider/core SHA; a wrong SHA fails
        // closed before any game is constructed (process identity gate).
        final Path recordPath =
                Files.createTempFile("ws227-tape-iso-", ".json");
        try {
            final String javaBin = System.getProperty("java.home") + "/bin/java";
            final String classpath = System.getProperty("java.class.path");
            final ProcessBuilder builder = new ProcessBuilder(javaBin,
                    "-Djava.awt.headless=true",
                    "-Dforge.engine.sha=0000000000000000000000000000000000000000",
                    "-cp", classpath, "forge.bridge.WS227ReplayChild",
                    "record", Long.toString(SEED), "ws227-iso",
                    recordPath.toString(), CORE_SHA);
            builder.environment().put("FORGE_ENGINE_SHA",
                    "0000000000000000000000000000000000000000");
            builder.redirectErrorStream(true);
            final Process process = builder.start();
            final String output;
            try (java.io.InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final boolean exited = process.waitFor(300, TimeUnit.SECONDS);
            Assert.assertTrue(exited, "isolation child timed out");
            Assert.assertNotEquals(process.exitValue(), 0,
                    "wrong core SHA must fail closed; output:\n" + output);
        } finally {
            try {
                Files.deleteIfExists(recordPath);
            } catch (Exception e) {
                // Best effort.
            }
        }
    }
}
