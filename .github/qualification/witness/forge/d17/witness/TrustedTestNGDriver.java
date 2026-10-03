package forge.d17.witness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.testng.TestNG;

/**
 * Trusted entry point of every D17 candidate test JVM.
 *
 * <p>Compiled from the trusted default branch against the pinned, digest-checked
 * TestNG, staged read-only, and placed first on the classpath. It runs as the
 * separate candidate account, so it holds no write authority over trusted state.
 *
 * <ul>
 *   <li>The ledger key arrives on stdin, as one hex line, before any candidate
 *       class is loaded. It is never a system property, argument, environment
 *       variable or file, and stdin is closed once it has been read.</li>
 *   <li>Only the keyed {@link QualifiedExecutionListener} is added. TestNG's
 *       default reporters are off, and its service-loader discovery of
 *       listeners sees only the trusted jars named on the command line, so a
 *       {@code META-INF/services/org.testng.ITestNGListener} in a candidate
 *       resource or dependency cannot register a listener that changes what
 *       runs or how it is counted.</li>
 *   <li>The required classes come from the trusted orchestrator. The driver
 *       prints TestNG-style totals from the keyed listener's own counts; those
 *       lines only show that the launch completed, and credit comes from the
 *       authenticated ledger alone.</li>
 * </ul>
 *
 * <p>Residual, stated: candidate test code runs in this JVM, so deliberate
 * in-process tampering (reflection on this process's memory) is not ruled out.
 * It must come from reviewable candidate source.
 */
public final class TrustedTestNGDriver {

    private TrustedTestNGDriver() {
    }

    private static byte[] readKey(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            line.write(c);
            if (line.size() > 256) {
                throw new IOException("witness key line is too long");
            }
        }
        in.close();
        String hex = new String(line.toByteArray(), StandardCharsets.US_ASCII).trim();
        if (hex.length() != 64 || !hex.matches("[0-9a-f]{64}")) {
            throw new IOException("witness key is not 32 hex-encoded bytes");
        }
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return key;
    }

    public static void main(String[] args) throws Exception {
        String module = null;
        String nonce = null;
        Path ledger = null;
        String outputDir = null;
        List<URL> trustedSpi = new ArrayList<URL>();
        List<String> classes = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                System.err.println("TrustedTestNGDriver: " + flag + " needs a value");
                System.exit(3);
            }
            String value = args[++i];
            if ("--module".equals(flag)) {
                module = value;
            } else if ("--nonce".equals(flag)) {
                nonce = value;
            } else if ("--ledger".equals(flag)) {
                ledger = Paths.get(value);
            } else if ("--output-dir".equals(flag)) {
                outputDir = value;
            } else if ("--trusted-jar".equals(flag)) {
                trustedSpi.add(Paths.get(value).toUri().toURL());
            } else if ("--class".equals(flag)) {
                classes.add(value);
            } else {
                System.err.println("TrustedTestNGDriver: unknown argument " + flag);
                System.exit(3);
            }
        }
        if (module == null || nonce == null || ledger == null || outputDir == null
                || trustedSpi.isEmpty() || classes.isEmpty()) {
            System.err.println("TrustedTestNGDriver: --module, --nonce, --ledger, --output-dir, "
                    + "--trusted-jar and at least one --class are required");
            System.exit(3);
        }

        byte[] key = readKey(System.in);
        QualifiedExecutionListener witness = new QualifiedExecutionListener(module, nonce, ledger, key);

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<Class<?>> selected = new ArrayList<Class<?>>();
        for (String name : classes) {
            // A required class that cannot load is still required: it is simply
            // never observed, and the trusted per-class floor fails.
            try {
                selected.add(Class.forName(name, false, loader));
            } catch (ClassNotFoundException | LinkageError exc) {
                System.err.println("TrustedTestNGDriver: required class not loadable: " + name + ": " + exc);
            }
        }

        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        testng.setServiceLoaderClassLoader(new URLClassLoader(trustedSpi.toArray(new URL[0]), null));
        testng.setOutputDirectory(outputDir);
        testng.setVerbose(0);
        testng.setTestClasses(selected.toArray(new Class<?>[0]));
        testng.addListener(witness);
        testng.run();

        long total = witness.invocations();
        long failed = witness.failures();
        long skipped = witness.skips();
        System.out.println("Total tests run: " + total + ", Passes: " + (total - failed - skipped)
                + ", Failures: " + failed + ", Skips: " + skipped);
        // Completed (0, or 2 when the suite completed with skips) versus crashed is
        // judged by the trusted side; failures are judged by the ledger.
        System.exit(testng.getStatus() == 0 ? 0 : (failed > 0 ? 1 : 2));
    }
}
