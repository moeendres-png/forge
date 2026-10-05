package forge.d17.witness;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.testng.IConfigurationListener;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.internal.RuntimeBehavior;

/**
 * Non-authoritative in-process execution counter.
 *
 * <p>This object holds no receipt key, nonce, trusted evidence handle or
 * credential.  It only helps the child JVM decide its OS exit status.  The
 * trusted parent process, running outside the candidate UID/JVM, owns the
 * qualification receipt and evidence files.
 */
public final class QualifiedExecutionCounter implements ITestListener, IInvokedMethodListener, IConfigurationListener {
    private final Containment.Guard containment;
    private final Map<String, Integer> passCounts = new TreeMap<String, Integer>();
    private final Map<String, Integer> failureCounts = new TreeMap<String, Integer>();
    private final Map<String, Integer> skipCounts = new TreeMap<String, Integer>();
    private final Map<String, Integer> methodCounts = new TreeMap<String, Integer>();
    private final Set<ITestResult> dispatched =
            Collections.newSetFromMap(new IdentityHashMap<ITestResult, Boolean>());
    private volatile Properties pinned;
    private long invocations;
    private long failures;
    private long skips;

    QualifiedExecutionCounter(Containment.Guard containment) {
        this.containment = containment;
    }

    void pinSystemProperties() {
        this.pinned = System.getProperties();
    }

    private String runtimeAlteredReason() {
        if (containment == null) {
            return "containment-null";
        }
        if (!containment.intact()) {
            return "containment-not-intact";
        }
        Properties current = System.getProperties();
        if (pinned == null) {
            return "properties-not-pinned";
        }
        if (current != pinned) {
            return "properties-object-replaced";
        }
        if (RuntimeBehavior.isDryRun()) {
            return "testng-dry-run";
        }
        for (String name : current.stringPropertyNames()) {
            if (name.startsWith("testng.")) {
                return "testng-property:" + name;
            }
        }
        return null;
    }

    private boolean runtimeAltered() {
        return runtimeAlteredReason() != null;
    }

    private static void bump(Map<String, Integer> counts, String key) {
        Integer current = counts.get(key);
        counts.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    private static String bounded(String value) {
        if (value == null) {
            return "<null>";
        }
        value = value.replace('\n', ' ').replace('\r', ' ');
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    private void diagnose(String kind, ITestResult result,
            boolean dispatchedObserved, String alteredReason) {
        String className = result == null || result.getTestClass() == null
                ? "<unknown-class>" : result.getTestClass().getName();
        String methodName = result == null || result.getMethod() == null
                ? "<unknown-method>" : result.getMethod().getMethodName();
        int status = result == null ? -1 : result.getStatus();
        Throwable throwable = result == null ? null : result.getThrowable();
        System.err.println("D17_TESTNG_DIAGNOSTIC kind=" + kind
                + " class=" + className + " method=" + methodName
                + " status=" + status
                + " dispatched=" + dispatchedObserved
                + " runtimeAltered=" + (alteredReason == null ? "none" : alteredReason)
                + " throwableClass=" + (throwable == null ? "<none>" : throwable.getClass().getName())
                + " throwableMessage=" + (throwable == null ? "<none>" : bounded(throwable.getMessage())));
    }

    private synchronized void record(ITestResult result) {
        if (result == null || result.getInstance() == null || result.getTestContext() == null) {
            failures++;
            return;
        }
        String className = result.getTestClass() == null
                ? result.getInstance().getClass().getName()
                : result.getTestClass().getName();
        String methodName = result.getMethod() == null
                ? "<unknown>" : result.getMethod().getMethodName();
        bump(methodCounts, className + "#" + methodName);
        boolean dispatchedObserved = dispatched.remove(result);
        String alteredReason = runtimeAlteredReason();
        boolean invoked = dispatchedObserved && alteredReason == null;
        int status = result.getStatus();
        invocations++;
        if (status == ITestResult.FAILURE || (status != ITestResult.SKIP && !invoked)) {
            diagnose("test-failure", result, dispatchedObserved, alteredReason);
            failures++;
            bump(failureCounts, className);
        } else if (status == ITestResult.SKIP) {
            skips++;
            bump(skipCounts, className);
        } else {
            bump(passCounts, className);
        }
    }

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult result) {
        if (method != null) {
            containment.enterInvocation();
        }
        if (method != null && method.isTestMethod() && result != null && !runtimeAltered()) {
            synchronized (this) {
                dispatched.add(result);
            }
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult result) {
        if (method != null) {
            containment.exitInvocation();
        }
    }
    @Override public void onTestSuccess(ITestResult result) { record(result); }
    @Override public void onTestFailure(ITestResult result) { record(result); }
    @Override public void onTestSkipped(ITestResult result) { record(result); }
    @Override public void onTestFailedButWithinSuccessPercentage(ITestResult result) { record(result); }
    @Override public void onTestFailedWithTimeout(ITestResult result) { record(result); }
    @Override public void onConfigurationFailure(ITestResult result) {
        diagnose("configuration-failure", result, false, runtimeAlteredReason());
    }

    long invocations() { return invocations; }
    long failures() { return failures; }
    long skips() { return skips; }

    Map<String, Integer> perClassTotal() {
        Map<String, Integer> total = new TreeMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : passCounts.entrySet()) {
            total.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
            total.put(entry.getKey(), Integer.valueOf(
                    total.getOrDefault(entry.getKey(), Integer.valueOf(0)).intValue()
                    + entry.getValue().intValue()));
        }
        for (Map.Entry<String, Integer> entry : skipCounts.entrySet()) {
            total.put(entry.getKey(), Integer.valueOf(
                    total.getOrDefault(entry.getKey(), Integer.valueOf(0)).intValue()
                    + entry.getValue().intValue()));
        }
        return total;
    }

    Map<String, Integer> perMethodTotal() {
        return Collections.unmodifiableMap(methodCounts);
    }

    Set<String> skipClasses() {
        return Collections.unmodifiableSet(skipCounts.keySet());
    }
}
