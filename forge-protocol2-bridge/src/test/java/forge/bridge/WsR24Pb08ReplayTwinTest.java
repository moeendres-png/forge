package forge.bridge;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * WSR24 PB-08 Forge-side clean-process replay twin (second fixture).
 *
 * <p>Process A records the authoritative {@code scry-search} scenario
 * (Opt scry + Evolving Wilds search + engine shuffle) with decision tape,
 * Rules-RNG coordinates, semantic events and state hashes, then terminates.
 * Process B starts clean and replays from the bound seed/tape; the required
 * semantic sequence and terminal state must match, with the first divergence
 * recorded precisely on any mismatch. A tampered tape must fail closed.
 * No same-process pseudo-twin, no shared mutable state, no outcome injection.
 */
public class WsR24Pb08ReplayTwinTest {
    private static final long SEED = 240817L;

    private static String resolveCoreSha() {
        try {
            final Path repoRoot = Paths.get("").toAbsolutePath().getParent();
            final ProcessBuilder probe = new ProcessBuilder("git",
                    "-C", repoRoot.toString(), "rev-parse", "HEAD");
            probe.redirectErrorStream(true);
            final Process process = probe.start();
            final String output;
            try (java.io.InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            final boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (exited && process.exitValue() == 0 && output.matches("[0-9a-f]{40}")) {
                return output;
            }
        } catch (Exception e) {
            // Fall through to the shape-valid test binding.
        }
        return "0000000000000000000000000000000000000000";
    }

    private static final class ChildResult {
        final int exit;
        final String output;

        ChildResult(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }

    private static ChildResult runChild(String mode, long seed, String gameId, Path tape,
            String coreSha) throws Exception {
        final String javaBin = System.getProperty("java.home") + "/bin/java";
        final String classpath = System.getProperty("java.class.path");
        final ProcessBuilder builder = new ProcessBuilder(javaBin,
                "-Djava.awt.headless=true",
                "-Dforge.engine.sha=" + coreSha,
                "-cp", classpath, "forge.bridge.WsR24Pb08ReplayTwinChild",
                mode, Long.toString(seed), gameId, tape.toString(), coreSha);
        builder.environment().put("FORGE_ENGINE_SHA", coreSha);
        final Path repoRoot = Paths.get("").toAbsolutePath().getParent();
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
        System.err.println("[wsr24twin:" + mode + "] exit=" + process.exitValue()
                + "\n" + output);
        return new ChildResult(process.exitValue(), output);
    }

    @Test(timeOut = 900000)
    public void testScrySearchCleanProcessTwin() throws Exception {
        final String coreSha = resolveCoreSha();
        final Path tape = Files.createTempFile("wsr24-twin-", ".json");
        try {
            final ChildResult recorded =
                    runChild("record", SEED, "wsr24-pb08-record", tape, coreSha);
            Assert.assertEquals(recorded.exit, 0, "record must exit 0:\n" + recorded.output);
            Assert.assertTrue(recorded.output.contains("RECORD_PASS"));
            Assert.assertTrue(Files.size(tape) > 0, "tape must be written");
            final String raw = new String(Files.readAllBytes(tape), StandardCharsets.UTF_8);
            Assert.assertTrue(raw.contains("semantic-replay-tape/1.0.0"));
            Assert.assertTrue(raw.contains("\"fixture\":\"scry-search\""));
            Assert.assertTrue(raw.contains(coreSha), "tape must bind the core SHA");
            final ChildResult replayed =
                    runChild("replay", SEED, "wsr24-pb08-replay", tape, coreSha);
            Assert.assertEquals(replayed.exit, 0, "replay must exit 0:\n" + replayed.output);
            Assert.assertTrue(replayed.output.contains("REPLAY_PASS"));
        } finally {
            try {
                Files.deleteIfExists(tape);
            } catch (Exception e) {
                // Best effort.
            }
        }
    }

    @Test(timeOut = 900000)
    public void testTamperedTapeFailsClosedWithFirstDivergence() throws Exception {
        final String coreSha = resolveCoreSha();
        final Path tape = Files.createTempFile("wsr24-twin-", ".json");
        final Path tampered = Files.createTempFile("wsr24-twin-tampered-", ".json");
        try {
            final ChildResult recorded =
                    runChild("record", SEED, "wsr24-pb08-record2", tape, coreSha);
            Assert.assertEquals(recorded.exit, 0, "record must exit 0:\n" + recorded.output);
            String raw = new String(Files.readAllBytes(tape), StandardCharsets.UTF_8);
            // Tamper exactly one recorded semantic fingerprint: the replay must
            // name the first divergence instead of silently accepting the tape.
            final String marker = "\"selected_fingerprint\":\"";
            final int at = raw.indexOf(marker);
            Assert.assertTrue(at >= 0, "tape must carry selected fingerprints");
            final int valueStart = at + marker.length();
            final int valueEnd = raw.indexOf('"', valueStart);
            Assert.assertTrue(valueEnd > valueStart);
            final String tamperedRaw = raw.substring(0, valueStart) + "TAMPERED"
                    + raw.substring(valueEnd);
            Files.write(tampered, tamperedRaw.getBytes(StandardCharsets.UTF_8));
            final ChildResult replayed =
                    runChild("replay", SEED, "wsr24-pb08-tamper", tampered, coreSha);
            Assert.assertNotEquals(replayed.exit, 0,
                    "tampered tape must fail closed:\n" + replayed.output);
            Assert.assertTrue(replayed.output.contains("FIRST_DIVERGENCE"),
                    "first divergence must be recorded:\n" + replayed.output);
        } finally {
            try {
                Files.deleteIfExists(tape);
            } catch (Exception e) {
                // Best effort.
            }
            try {
                Files.deleteIfExists(tampered);
            } catch (Exception e) {
                // Best effort.
            }
        }
    }
}
