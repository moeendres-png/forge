package forge.d21;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * D21 shadow-only red control.
 *
 * Runs the real compatibility action in an isolated child JVM so the JDK 17
 * SecurityManager installation cannot alter the Surefire process itself.
 */
public final class D21SecurityManagerCompatibilityRedControlTest {
    @Test
    public void dynamicSecurityManagerInstallationRemainsAvailable() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String testClasses = Path.of(System.getProperty("basedir"), "target", "test-classes").toString();

        Process process = new ProcessBuilder(
                java,
                "-cp",
                testClasses,
                "forge.d21.D21SecurityManagerChild")
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        Assert.assertEquals(exitCode, 0,
                "child JVM rejected dynamic SecurityManager installation; output:\n" + output);
    }
}
