package forge.d17.witness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

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
 * </ul>
 *
 * <p>Scope. The witness proves that required tests were dispatched and how they
 * finished. It cannot prove that an individual candidate-owned test method is
 * semantically strong; that is review, not CI. Because candidate test code runs
 * in this same JVM, a candidate could in principle append records directly. That
 * is a strictly harder threat than forging build artifacts, is surfaced by the
 * baseline test-source-change signal, and is stated as a bounded residual rather
 * than claimed as prevented.
 */
public final class QualifiedExecutionListener implements ITestListener, IInvokedMethodListener {

    /** Wire format version of the emitted evidence. */
    public static final String SCHEMA = "forge.d17.witness/1";

    private final String module;
    private final String nonce;
    private final Path output;

    private final Map<String, Integer> passCounts = new TreeMap<String, Integer>();
    private final Map<String, Integer> failureCounts = new TreeMap<String, Integer>();
    private final Map<String, Integer> skipCounts = new TreeMap<String, Integer>();

    private long sequence = 0L;
    private long failures = 0L;
    private long skips = 0L;

    public QualifiedExecutionListener() {
        this.module = required("forge.d17.module");
        this.nonce = required("forge.d17.nonce");
        Path dir = Paths.get(required("forge.d17.witness.dir"));
        try {
            Files.createDirectories(dir);
            this.output = dir.resolve(module + ".witness.jsonl");
            // A stale witness must never be mistaken for this run's evidence.
            Files.deleteIfExists(this.output);
            write("{\"kind\":\"header\",\"schema\":\"" + SCHEMA + "\",\"module\":"
                    + quote(module) + ",\"nonce\":" + quote(nonce) + "}");
        } catch (IOException exc) {
            throw new IllegalStateException("witness cannot initialise", exc);
        }
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("witness requires system property " + key);
        }
        return value;
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

    private static void bump(Map<String, Integer> counts, String key) {
        Integer current = counts.get(key);
        counts.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    private void write(String line) {
        try {
            Files.write(this.output, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        } catch (IOException exc) {
            throw new IllegalStateException("witness cannot record", exc);
        }
    }

    /**
     * Records one dispatched test method. Only genuine TestNG dispatches
     * carrying a live context and instance are counted.
     */
    private void record(ITestResult result) {
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

        String verdict;
        if (status == ITestResult.FAILURE) {
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
        // Execution provenance begins at dispatch; nothing is recorded pre-flight.
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
        write("{\"kind\":\"summary\",\"tests\":" + sequence
                + ",\"failed\":" + failures
                + ",\"skipped\":" + skips
                + ",\"per_class_total\":" + render(observed)
                + ",\"skip_classes\":" + render(skipCounts)
                + ",\"fail_classes\":" + render(failureCounts)
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