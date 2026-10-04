package forge.d17.witness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.internal.RuntimeBehavior;

/**
 * Trusted execution witness for the D17 exact-SHA candidate qualification.
 *
 * <p>Trust model. This class is compiled from the <em>trusted default branch</em>
 * and placed on the classpath ahead of every candidate class by the trusted
 * orchestrator. It records what TestNG actually dispatched, so qualification
 * credit derives from observed execution rather than from any artifact the
 * candidate's build produced. No Surefire or TestNG report file is read for
 * credit: a candidate that forges, copies, renames or lifecycle-generates
 * reports earns nothing.
 *
 * <p>Provenance properties.
 * <ul>
 *   <li>Records are emitted only from real TestNG callbacks carrying a non-null
 *       {@link ITestContext} and a live result instance, so a stray call cannot
 *       manufacture credit.</li>
 *   <li>Every record is bound to a per-run nonce chosen by the trusted
 *       orchestrator and to a gap-free monotonic sequence, so stale, partial or
 *       replayed evidence does not validate.</li>
 *   <li>Output is JSON Lines: a header record, one record per dispatched test
 *       method, and a closing summary. The trusted verifier recomputes every
 *       aggregate from the invocation records rather than trusting the summary.</li>
 *   <li>Every line is authenticated: it ends with {@code "mac"}, an HMAC-SHA256
 *       over the previous line's MAC and this line's body, keyed by a secret the
 *       trusted orchestrator hands to {@link TrustedTestNGDriver} on stdin. The
 *       key never appears in a system property, argument, environment variable
 *       or file. The ledger directory is writable by the candidate account, so
 *       candidate code can delete or truncate a ledger (which only removes
 *       credit) but cannot add, alter, reorder or replay a line the orchestrator
 *       accepts.</li>
 * </ul>
 *
 * <p>Scope. The witness proves that required comparison-base tests were
 * dispatched and how they finished. Candidate-owned test bodies are never
 * executed for credit. Candidate production code shares this process only
 * behind the mandatory Containment boundary; a denied authority operation
 * makes the run non-PASS even when the candidate catches the exception. Before launch, the orchestrator admits the classpath only if
 * no candidate-authored class references TestNG beyond test annotations and
 * assertions (so no @Listeners, hook, object factory, Reporter or test-result
 * access) or the witness package, and every dependency jar equals the trusted
 * repository's copy. Deliberate reflection with computed names remains a stated
 * residual, not a prevented threat.
 */
public final class QualifiedExecutionListener implements ITestListener, IInvokedMethodListener {

    /** Wire format version of the emitted evidence. */
    public static final String SCHEMA = "forge.d17.witness/1";

    private final String module;
    private final String nonce;
    private final Path output;
    private final Containment.Guard containment;

    private final Map<String, Integer> passCounts = new TreeMap<String, Integer>();
    private final Map<String, Integer> failureCounts = new TreeMap<String, Integer>();
    private final Map<String, Integer> skipCounts = new TreeMap<String, Integer>();

    private long sequence = 0L;
    private long failures = 0L;
    private long skips = 0L;

    private final Mac mac;
    private String chain;

    /**
     * Results TestNG actually dispatched to a test method. A success TestNG
     * reports without invoking the method (its dry-run mode, which candidate
     * code can switch on with one system property) is recorded as a failure.
     */
    /** The system properties object the driver installed; replacing it is tampering. */
    private volatile Properties pinned;

    private final Set<ITestResult> dispatched =
            Collections.newSetFromMap(new IdentityHashMap<ITestResult, Boolean>());

    /**
     * Constructed only by the trusted driver, never by TestNG reflection: the
     * listener has no public no-argument constructor, so a {@code -listener}
     * argument or service registration cannot instantiate an unkeyed copy.
     */
    QualifiedExecutionListener(String module, String nonce, Path output, byte[] key,
            Containment.Guard containment) {
        this.module = module;
        this.nonce = nonce;
        this.output = output;
        this.containment = containment;
        try {
            this.mac = Mac.getInstance("HmacSHA256");
            this.mac.init(new SecretKeySpec(key, "HmacSHA256"));
        } catch (GeneralSecurityException exc) {
            throw new IllegalStateException("witness cannot key its ledger", exc);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        this.chain = nonce;
        try {
            Files.createDirectories(output.getParent());
            // A stale witness must never be mistaken for this run's evidence.
            Files.deleteIfExists(this.output);
            write("{\"kind\":\"header\",\"schema\":\"" + SCHEMA + "\",\"module\":"
                    + quote(module) + ",\"nonce\":" + quote(nonce) + "}");
        } catch (IOException exc) {
            throw new IllegalStateException("witness cannot initialise", exc);
        }
    }

    private static String quote(String raw) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", Integer.valueOf(c)));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * TestNG reads its runtime knobs from {@code testng.*} system properties, and
     * candidate code can set one at any time. In dry-run mode TestNG reports a
     * success, and still fires this listener, without running the method body.
     * The driver clears every {@code testng.*} property and pins the properties
     * object before the run, so a property present now, or a replaced object,
     * was set by code under test.
     */
    boolean runtimeAltered() {
        // Hostile candidate bytecode runs behind the mandatory containment guard.
        // A denied operation is sticky even if candidate code catches the
        // SecurityException; it can never be converted back into PASS.
        if (containment == null || !containment.intact()) {
            return true;
        }
        // A replaced Properties object can answer TestNG and this check
        // differently, so the object itself must still be the pinned one.
        Properties current = System.getProperties();
        if (pinned == null || current != pinned) {
            return true;
        }
        if (RuntimeBehavior.isDryRun()) {
            return true;
        }
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("testng.")) {
                return true;
            }
        }
        return false;
    }

    private static void bump(Map<String, Integer> counts, String key) {
        Integer current = counts.get(key);
        counts.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    private synchronized void write(String body) {
        // body is one complete JSON object. The authenticated line is that
        // object with a final "mac" member: HMAC(previous mac + "\n" + body).
        byte[] digest = mac.doFinal((chain + "\n" + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", Integer.valueOf(b & 0xff)));
        }
        chain = hex.toString();
        String line = body.substring(0, body.length() - 1) + ",\"mac\":\"" + chain + "\"}";
        try {
            Files.write(this.output, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exc) {
            throw new IllegalStateException("witness cannot record", exc);
        }
    }

    /** Called by the driver once every testng.* property is cleared, just before the run. */
    void pinSystemProperties() {
        this.pinned = System.getProperties();
    }

    long invocations() {
        return sequence;
    }

    long failures() {
        return failures;
    }

    long skips() {
        return skips;
    }

    void noteContainmentViolation(String detail) {
        if (containment != null && containment.violation() == null) {
            try {
                containment.deny(detail);
            } catch (SecurityException expected) {
                // Sticky violation is the intended effect.
            }
        }
    }

    private synchronized void record(ITestResult result) {
        if (result == null) {
            return;
        }
        ITestContext context = result.getTestContext();
        Object instance = result.getInstance();
        if (context == null || instance == null) {
            return;
        }
        String className = result.getTestClass() == null
                ? instance.getClass().getName()
                : result.getTestClass().getName();
        String methodName = result.getMethod() == null
                ? "<unknown>" : result.getMethod().getMethodName();
        int status = result.getStatus();

        boolean invoked = dispatched.remove(result) && !runtimeAltered();

        String verdict;
        if (status == ITestResult.FAILURE || (status != ITestResult.SKIP && !invoked)) {
            verdict = "FAIL";
            bump(failureCounts, className);
            failures++;
        } else if (status == ITestResult.SKIP) {
            verdict = "SKIP";
            bump(skipCounts, className);
            skips++;
        } else {
            verdict = "PASS";
            bump(passCounts, className);
        }

        write("{\"kind\":\"invocation\",\"seq\":" + sequence
                + ",\"class\":" + quote(className)
                + ",\"method\":" + quote(methodName)
                + ",\"status\":" + quote(verdict)
                + ",\"invoked\":" + invoked
                + ",\"context\":" + quote(String.valueOf(context.getName()))
                + ",\"thread\":" + quote(String.valueOf(Thread.currentThread().getName()))
                + "}");
        sequence++;
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        record(result);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        record(result);
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        record(result);
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        record(result);
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        record(result);
    }

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult result) {
        // Execution provenance begins at dispatch: remember that this result's
        // test method is really about to be invoked; nothing is written
        // pre-flight. No candidate code runs between this callback and TestNG's
        // own dry-run decision for the method.
        if (method != null && method.isTestMethod() && result != null && !runtimeAltered()) {
            synchronized (this) {
                dispatched.add(result);
            }
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult result) {
        // Intentionally empty: onTest* already records each outcome exactly once.
    }

    @Override
    public void onStart(ITestContext context) {
        // Header written during construction.
    }

    @Override
    public void onFinish(ITestContext context) {
        Map<String, Integer> observed = new TreeMap<String, Integer>(passCounts);
        for (Map.Entry<String, Integer> entry : skipCounts.entrySet()) {
            Integer prior = observed.get(entry.getKey());
            observed.put(entry.getKey(), Integer.valueOf(
                    (prior == null ? 0 : prior.intValue()) + entry.getValue().intValue()));
        }
        for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
            Integer prior = observed.get(entry.getKey());
            observed.put(entry.getKey(), Integer.valueOf(
                    (prior == null ? 0 : prior.intValue()) + entry.getValue().intValue()));
        }
        String containmentState = containment != null && containment.intact()
                ? Containment.ENFORCED : Containment.VIOLATED;
        String containmentDetail = containment == null ? "guard missing"
                : String.valueOf(containment.violation());
        write("{\"kind\":\"summary\",\"tests\":" + sequence
                + ",\"failed\":" + failures
                + ",\"skipped\":" + skips
                + ",\"per_class_total\":" + render(observed)
                + ",\"skip_classes\":" + render(skipCounts)
                + ",\"fail_classes\":" + render(failureCounts)
                + ",\"containment\":" + quote(containmentState)
                + ",\"containment_violation\":" + quote(containmentDetail)
                + ",\"last_seq\":" + (sequence - 1L) + "}");
    }

    private static String render(Map<String, Integer> counts) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(quote(entry.getKey())).append(':').append(entry.getValue().intValue());
        }
        return out.append('}').toString();
    }
}