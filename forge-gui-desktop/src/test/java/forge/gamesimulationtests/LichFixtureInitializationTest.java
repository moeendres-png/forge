package forge.gamesimulationtests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.d24.D24ExecutionGuardTest;

/** Construction-only control; no actual-card behavior qualification credit. */
public class LichFixtureInitializationTest {
    // This class deliberately does not extend CardMockTestCase (the probe must
    // start from a cold JVM), so it reports itself to the D24 execution guard
    // the same way CardMockTestCase does for every other guarded class.
    @BeforeMethod
    public void recordDiscovery() {
        D24ExecutionGuardTest.recordAffectedDiscovery(getClass());
    }

    @AfterMethod(alwaysRun = true)
    public void recordResult(final ITestResult result) {
        D24ExecutionGuardTest.recordAffectedResult(result);
    }

    @Test
    public void multiwordTypesAreLoadedBeforeCardParsing() throws Exception {
        // The parent may already have cached correct types/cards. Never reuse them.
        final String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        final Path output = Files.createTempFile("d22-cold-types-", ".log");
        Process child = null;
        try {
            child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, LichFixtureInitializationProbe.class.getName())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Assert.assertTrue(child.waitFor(45, TimeUnit.SECONDS), "cold fixture probe timed out");
            final String trace = Files.readString(output);
            System.out.print(trace);
            Assert.assertEquals(child.exitValue(), 0, trace);
            Assert.assertTrue(trace.contains("D22_COLD_TYPES=PASS"), "missing completed cold fixture receipt");
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }
}
