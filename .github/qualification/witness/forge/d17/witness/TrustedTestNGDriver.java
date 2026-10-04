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
 * <p>The process system classpath contains only trusted witness bytecode and the
 * digest-pinned TestNG closure.  Candidate production bytecode is loaded through
 * {@link Containment.CandidateCodeLoader}; trusted comparison-base tests are
 * loaded through a separate {@link Containment.TrustedTestLoader}.  A mandatory
 * SecurityManager containment guard is installed before either domain is
 * initialized.  There is no uncontained fallback.
 *
 * <p>The HMAC key remains defense-in-depth for ledger truncation/replay.  It is
 * read before candidate classes are loaded and is inaccessible to candidate
 * bytecode through the enforced loader/security boundary.  Candidate code is
 * not permitted to obtain the system loader, access the witness package, use
 * suppress-access-check reflection, replace the security manager, load native
 * code, execute a process, or mutate the protected witness directory.
 */
public final class TrustedTestNGDriver {

    private static final int CONTAINMENT_UNAVAILABLE = 78;

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

    private static URL[] urls(List<String> values) throws IOException {
        List<URL> out = new ArrayList<URL>();
        for (String value : values) {
            out.add(Paths.get(value).toUri().toURL());
        }
        return out.toArray(new URL[0]);
    }

    public static void main(String[] args) throws Exception {
        String module = null;
        String nonce = null;
        Path ledger = null;
        Path protectedRoot = null;
        String outputDir = null;
        String trustedTests = null;
        List<URL> trustedSpi = new ArrayList<URL>();
        List<String> candidateCode = new ArrayList<String>();
        List<String> trustedDependencies = new ArrayList<String>();
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
            } else {
                System.err.println("TrustedTestNGDriver: unknown argument " + flag);
                System.exit(3);
            }
        }

        if (module == null || nonce == null || ledger == null || protectedRoot == null
                || outputDir == null || trustedTests == null || trustedSpi.isEmpty()
                || classes.isEmpty()) {
            System.err.println("TrustedTestNGDriver: --module, --nonce, --ledger, "
                    + "--protected-root, --output-dir, --trusted-test-root, --trusted-jar "
                    + "and at least one --class are required");
            System.exit(3);
        }

        byte[] key = readKey(System.in);

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

        QualifiedExecutionListener witness =
                new QualifiedExecutionListener(module, nonce, ledger, key, containment.guard);

        List<Class<?>> selected = new ArrayList<Class<?>>();
        for (String name : classes) {
            try {
                selected.add(Class.forName(name, false, containment.tests));
            } catch (ClassNotFoundException | LinkageError exc) {
                System.err.println("TrustedTestNGDriver: required class not loadable: " + name + ": " + exc);
            }
        }

        // TestNG runtime knobs are process global.  The properties object itself
        // is guarded; trusted-base tests may use normal test APIs while hostile
        // candidate production frames cannot mutate testng.* state.
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("testng.")) {
                System.clearProperty(name);
            }
        }
        witness.pinSystemProperties();

        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        testng.setServiceLoaderClassLoader(new URLClassLoader(trustedSpi.toArray(new URL[0]), null));
        testng.setOutputDirectory(outputDir);
        testng.setVerbose(0);
        testng.setTestClasses(selected.toArray(new Class<?>[0]));
        testng.addListener(witness);
        testng.run();

        if (!containment.intact()) {
            witness.noteContainmentViolation("post-run containment integrity check failed");
        }

        long total = witness.invocations();
        long failed = witness.failures();
        long skipped = witness.skips();
        System.out.println("Total tests run: " + total + ", Passes: " + (total - failed - skipped)
                + ", Failures: " + failed + ", Skips: " + skipped);
        System.exit(testng.getStatus() == 0 && containment.intact()
                ? 0 : (failed > 0 || !containment.intact() ? 1 : 2));
    }
}
