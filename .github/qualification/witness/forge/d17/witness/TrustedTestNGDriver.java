package forge.d17.witness;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.TestNG;

/**
 * Contained child-JVM entry point for D17.
 *
 * <p>This process is deliberately <strong>not</strong> a receipt authority. It
 * receives no HMAC key, receipt nonce or trusted evidence path. It runs trusted
 * comparison-base tests against candidate production bytecode behind the
 * mandatory containment boundary and communicates only through its OS process
 * exit status. The trusted parent process, running outside this UID/JVM, owns
 * all qualification receipts and evidence.
 */
public final class TrustedTestNGDriver {
    static final int COMPLETE_PASS = 0;
    static final int TEST_FAILURE = 10;
    static final int UNDECLARED_SKIP = 11;
    static final int EXECUTION_COUNT_MISMATCH = 12;
    static final int CONTAINMENT_VIOLATION = 13;
    static final int DECLARED_SKIP_BASE = 30;
    static final int MAX_ENCODED_DECLARED_SKIPS = 39;
    static final int CONTAINMENT_UNAVAILABLE = 78;
    static final int DRIVER_ERROR = 79;

    private TrustedTestNGDriver() {
    }

    private static URL[] urls(List<String> values) throws IOException {
        List<URL> out = new ArrayList<URL>();
        for (String value : values) {
            out.add(Paths.get(value).toUri().toURL());
        }
        return out.toArray(new URL[0]);
    }

    private static Map<String, Integer> expectedCounts(List<String> specs) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        for (String spec : specs) {
            int split = spec.lastIndexOf('=');
            if (split <= 0 || split == spec.length() - 1) {
                throw new IllegalArgumentException("bad --expected-count " + spec);
            }
            String name = spec.substring(0, split);
            int count = Integer.parseInt(spec.substring(split + 1));
            if (count <= 0 || out.put(name, Integer.valueOf(count)) != null) {
                throw new IllegalArgumentException("invalid/duplicate expected class " + spec);
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String module = null;
        Path protectedRoot = null;
        String outputDir = null;
        String trustedTests = null;
        List<URL> trustedSpi = new ArrayList<URL>();
        List<String> candidateCode = new ArrayList<String>();
        List<String> trustedDependencies = new ArrayList<String>();
        List<String> classes = new ArrayList<String>();
        List<String> expectedSpecs = new ArrayList<String>();
        List<String> expectedMethodSpecs = new ArrayList<String>();
        Set<String> allowedSkipClasses = new HashSet<String>();

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                System.err.println("TrustedTestNGDriver: " + flag + " needs a value");
                System.exit(DRIVER_ERROR);
            }
            String value = args[++i];
            if ("--module".equals(flag)) {
                module = value;
            } else if ("--protected-root".equals(flag)) {
                protectedRoot = Paths.get(value);
            } else if ("--output-dir".equals(flag)) {
                outputDir = value;
            } else if ("--trusted-test-root".equals(flag)) {
                trustedTests = value;
            } else if ("--candidate-code".equals(flag)) {
                candidateCode.add(value);
            } else if ("--trusted-dependency".equals(flag)) {
                trustedDependencies.add(value);
            } else if ("--trusted-jar".equals(flag)) {
                trustedSpi.add(Paths.get(value).toUri().toURL());
            } else if ("--class".equals(flag)) {
                classes.add(value);
            } else if ("--expected-count".equals(flag)) {
                expectedSpecs.add(value);
            } else if ("--expected-method-count".equals(flag)) {
                expectedMethodSpecs.add(value);
            } else if ("--allowed-skip-class".equals(flag)) {
                allowedSkipClasses.add(value);
            } else {
                System.err.println("TrustedTestNGDriver: unknown argument " + flag);
                System.exit(DRIVER_ERROR);
            }
        }

        final Map<String, Integer> expected;
        final Map<String, Integer> expectedMethods;
        try {
            expected = expectedCounts(expectedSpecs);
            expectedMethods = expectedCounts(expectedMethodSpecs);
        } catch (RuntimeException badExpected) {
            System.err.println("TrustedTestNGDriver: " + badExpected);
            System.exit(DRIVER_ERROR);
            return;
        }

        if (module == null || protectedRoot == null || outputDir == null
                || trustedTests == null || trustedSpi.isEmpty() || classes.isEmpty()
                || expected.isEmpty() || expectedMethods.isEmpty()
                || !new HashSet<String>(classes).equals(expected.keySet())) {
            System.err.println("TrustedTestNGDriver: incomplete or inconsistent trusted launch contract");
            System.exit(DRIVER_ERROR);
        }

        final Containment.Session containment;
        try {
            containment = Containment.install(
                    urls(trustedDependencies),
                    urls(candidateCode),
                    urls(java.util.List.of(trustedTests)),
                    protectedRoot);
        } catch (UnsupportedOperationException | SecurityException failure) {
            System.err.println("D17_CONTAINMENT_UNAVAILABLE: " + failure);
            System.exit(CONTAINMENT_UNAVAILABLE);
            return;
        }

        QualifiedExecutionCounter counter = new QualifiedExecutionCounter(containment.guard);
        List<Class<?>> selected = new ArrayList<Class<?>>();
        for (String name : classes) {
            try {
                selected.add(Class.forName(name, false, containment.tests));
            } catch (ClassNotFoundException | LinkageError exc) {
                System.err.println("TrustedTestNGDriver: required class not loadable: " + name + ": " + exc);
                System.exit(EXECUTION_COUNT_MISMATCH);
                return;
            }
        }

        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("testng.")) {
                System.clearProperty(name);
            }
        }
        counter.pinSystemProperties();

        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        testng.setServiceLoaderClassLoader(new URLClassLoader(trustedSpi.toArray(new URL[0]), null));
        testng.setOutputDirectory(outputDir);
        testng.setVerbose(0);
        testng.setTestClasses(selected.toArray(new Class<?>[0]));
        testng.addListener(counter);
        testng.run();

        if (!containment.intact()) {
            System.err.println("D17_CONTAINMENT_VIOLATION: " + containment.guard.violation());
            System.exit(CONTAINMENT_VIOLATION);
            return;
        }

        Map<String, Integer> observed = counter.perClassTotal();
        Map<String, Integer> observedMethods = counter.perMethodTotal();
        if (!observed.equals(expected) || !observedMethods.equals(expectedMethods)) {
            System.err.println("D17_EXECUTION_COUNT_MISMATCH expectedClasses=" + expected
                    + " observedClasses=" + observed + " expectedMethods=" + expectedMethods
                    + " observedMethods=" + observedMethods);
            System.exit(EXECUTION_COUNT_MISMATCH);
            return;
        }

        // TestNG also reports configuration failures in its status word;
        // those must not disappear merely because no test-method failure record
        // carried them. HAS_FAILURE is bit 1; HAS_SKIPPED is handled below by
        // the counter's explicit skip-class policy.
        if (counter.failures() > 0 || (testng.getStatus() & 1) != 0) {
            System.exit(TEST_FAILURE);
            return;
        }

        Set<String> undeclared = new HashSet<String>(counter.skipClasses());
        undeclared.removeAll(allowedSkipClasses);
        if (!undeclared.isEmpty()) {
            System.err.println("D17_UNDECLARED_SKIP_CLASSES=" + undeclared);
            System.exit(UNDECLARED_SKIP);
            return;
        }

        long skipped = counter.skips();
        if (skipped > 0) {
            if (allowedSkipClasses.size() != 1 || skipped > MAX_ENCODED_DECLARED_SKIPS) {
                System.err.println("D17_DECLARED_SKIP_ENCODING_UNAVAILABLE count=" + skipped
                        + " classes=" + allowedSkipClasses);
                System.exit(DRIVER_ERROR);
                return;
            }
            System.exit(DECLARED_SKIP_BASE + (int) skipped);
            return;
        }

        System.exit(COMPLETE_PASS);
    }
}
