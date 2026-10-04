package forge.d24;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.testng.Assert;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

@Listeners(D24ExecutionGuardTest.ExecutionListener.class)
public class D24ExecutionGuardTest {
    private static final Map<String, Integer> DECLARED_TEST_METHODS = Map.ofEntries(
            Map.entry("forge.deck.DeckRecognizerTest", 84),
            Map.entry("forge.card.CardDbCardMockTestCase", 54),
            Map.entry("forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection104", 12),
            Map.entry("forge.card.CardDbLazyCardLoadingCardMockTestCase", 4),
            Map.entry("forge.card.CardDbPerformanceTests", 4),
            Map.entry("forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection103", 2),
            Map.entry("forge.card.CardEditionCollectionCardMockTestCase", 2),
            Map.entry("forge.card.CardDbWithNoImageCardDbMockTestCase", 1));

    private static final Map<String, Integer> ENABLED_RUNTIME_METHODS = Map.ofEntries(
            Map.entry("forge.deck.DeckRecognizerTest", 84),
            Map.entry("forge.card.CardDbCardMockTestCase", 54),
            Map.entry("forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection104", 11),
            Map.entry("forge.card.CardDbLazyCardLoadingCardMockTestCase", 4),
            Map.entry("forge.card.CardDbPerformanceTests", 56),
            Map.entry("forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection103", 2),
            Map.entry("forge.card.CardEditionCollectionCardMockTestCase", 2),
            Map.entry("forge.card.CardDbWithNoImageCardDbMockTestCase", 55));

    private static final List<String> BASE_CLASSES = List.of(
            "forge.card.CardMockTestCase",
            "forge.gamesimulationtests.BaseGameSimulationTest");

    private static final Set<String> EXPECTED_DISABLED = Set.of(
            "forge.card.CardDbPerformanceTests#testBenchmarkFullDbGetCardLegacyImplementation",
            "forge.card.CardDbPerformanceTests#testBenchmarkFullDbGetCardNewDbImplementation",
            "forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection104"
                    + "#test_104_3f_if_a_player_would_win_and_lose_simultaneously_he_loses");

    @Test
    public void affectedSourceShapeAndDisabledDebtArePinned() throws ReflectiveOperationException {
        int declared = 0;
        int enabled = 0;
        Set<String> disabled = new HashSet<>();

        for (Map.Entry<String, Integer> expected : DECLARED_TEST_METHODS.entrySet()) {
            Class<?> type = Class.forName(expected.getKey());
            int classDeclared = 0;
            for (Method method : type.getDeclaredMethods()) {
                Test annotation = method.getAnnotation(Test.class);
                if (annotation == null) {
                    continue;
                }
                classDeclared++;
                if (annotation.enabled()) {
                    enabled++;
                } else {
                    disabled.add(type.getName() + "#" + method.getName());
                }
            }
            declared += classDeclared;
            Assert.assertEquals(classDeclared, expected.getValue().intValue(),
                    "declared @Test count drifted for " + expected.getKey());
            assertNoPowerMockSuperclass(type);
        }

        for (String baseName : BASE_CLASSES) {
            Class<?> base = Class.forName(baseName);
            long baseTests = Stream.of(base.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(Test.class) != null)
                    .count();
            Assert.assertEquals(baseTests, 0L, "base class unexpectedly declares @Test methods: " + baseName);
            assertNoPowerMockSuperclass(base);
        }

        Assert.assertEquals(declared, 163, "D24 declared-method denominator drifted");
        Assert.assertEquals(enabled, 160, "D24 enabled declared-method denominator drifted");
        Assert.assertEquals(disabled, EXPECTED_DISABLED, "disabled NOT_RUN obligations drifted");
    }

    @Test
    public void unsupportedPowerMockSurfaceCannotReturn() throws IOException {
        String packageToken = "org." + "powermock";
        String apiToken = "Power" + "Mockito";
        String baseToken = "Power" + "MockTestCase";
        String prepareToken = "@" + "PrepareForTest";
        String suppressToken = "@" + "SuppressStaticInitializationFor";
        String ignoreToken = "@" + "PowerMockIgnore";

        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path moduleRoot = basedir;
        if (!Files.isDirectory(moduleRoot.resolve("src/test/java"))
                && Files.isDirectory(basedir.resolve("forge-gui-desktop/src/test/java"))) {
            moduleRoot = basedir.resolve("forge-gui-desktop");
        }
        Path testRoot = moduleRoot.resolve("src/test/java");
        Assert.assertTrue(Files.isDirectory(testRoot), "D24 test source root not found: " + testRoot);
        try (Stream<Path> paths = Files.walk(testRoot)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path);
                    Assert.assertFalse(source.contains(packageToken), "unsupported package import in " + path);
                    Assert.assertFalse(source.contains(apiToken), "unsupported API usage in " + path);
                    Assert.assertFalse(source.contains(baseToken), "unsupported inheritance in " + path);
                    Assert.assertFalse(source.contains(prepareToken), "unsupported annotation in " + path);
                    Assert.assertFalse(source.contains(suppressToken), "unsupported annotation in " + path);
                    Assert.assertFalse(source.contains(ignoreToken), "unsupported annotation in " + path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        String pom = Files.readString(moduleRoot.resolve("pom.xml"));
        Assert.assertFalse(pom.contains("<groupId>org." + "powermock</groupId>"),
                "PowerMock dependency returned to forge-gui-desktop");
    }

    @Test
    public void missingAffectedClassCannotPass() {
        Map<String, Integer> discovered = new HashMap<>(ENABLED_RUNTIME_METHODS);
        Map<String, Integer> invoked = new HashMap<>(ENABLED_RUNTIME_METHODS);
        Map<String, Integer> skipped = zeroCounts();
        discovered.remove("forge.deck.DeckRecognizerTest");

        Assert.expectThrows(AssertionError.class, () -> validateExecution(discovered, invoked, skipped));
    }

    @Test
    public void zeroMethodExecutionCannotPass() {
        Map<String, Integer> discovered = new HashMap<>(ENABLED_RUNTIME_METHODS);
        Map<String, Integer> invoked = new HashMap<>(ENABLED_RUNTIME_METHODS);
        Map<String, Integer> skipped = zeroCounts();
        invoked.put("forge.card.CardDbCardMockTestCase", 0);

        Assert.expectThrows(AssertionError.class, () -> validateExecution(discovered, invoked, skipped));
    }

    @Test
    public void discoveredButNotInvokedCannotPass() {
        Map<String, Integer> discovered = new HashMap<>(ENABLED_RUNTIME_METHODS);
        Map<String, Integer> invoked = new HashMap<>(ENABLED_RUNTIME_METHODS);
        Map<String, Integer> skipped = zeroCounts();
        discovered.put("forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection103", 2);
        invoked.put("forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection103", 0);

        Assert.expectThrows(AssertionError.class, () -> validateExecution(discovered, invoked, skipped));
    }

    @AfterSuite(alwaysRun = true)
    public void verifyLiveAffectedExecution() {
        if (System.getProperty("test") != null) {
            return;
        }

        Map<String, Integer> discovered = zeroCounts();
        ISuite suite = ExecutionListener.suite;
        Assert.assertNotNull(suite, "D24 execution listener did not receive the TestNG suite");
        for (ITestNGMethod method : suite.getAllMethods()) {
            String className = method.getTestClass().getRealClass().getName();
            if (discovered.containsKey(className) && method.getEnabled()) {
                discovered.merge(className, 1, Integer::sum);
            }
        }

        Map<String, Integer> passed = new HashMap<>(ExecutionListener.passed);
        Map<String, Integer> failed = new HashMap<>(ExecutionListener.failed);
        Map<String, Integer> skipped = new HashMap<>(ExecutionListener.skipped);

        Map<String, Integer> invoked = zeroCounts();
        for (String className : invoked.keySet()) {
            invoked.put(className, passed.get(className) + failed.get(className));
        }
        validateExecution(discovered, invoked, skipped);

        for (Map.Entry<String, Integer> expected : ENABLED_RUNTIME_METHODS.entrySet()) {
            String className = expected.getKey();
            System.out.println("D24_CLASS_EXECUTION class=" + className
                    + " expected_enabled=" + expected.getValue()
                    + " discovered=" + discovered.get(className)
                    + " pass=" + passed.get(className)
                    + " fail=" + failed.get(className)
                    + " skip=" + skipped.get(className));
        }

        int totalPassed = passed.values().stream().mapToInt(Integer::intValue).sum();
        int totalFailed = failed.values().stream().mapToInt(Integer::intValue).sum();
        int totalSkipped = skipped.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("D24_EXECUTION_GUARD=PASS enabled_runtime_methods="
                + (totalPassed + totalFailed) + " pass=" + totalPassed + " fail=" + totalFailed
                + " skip=" + totalSkipped + " disabled_not_run=" + EXPECTED_DISABLED.size());
    }

    public static final class ExecutionListener implements ISuiteListener, IInvokedMethodListener {
        private static ISuite suite;
        private static Map<String, Integer> passed = zeroCounts();
        private static Map<String, Integer> failed = zeroCounts();
        private static Map<String, Integer> skipped = zeroCounts();

        @Override
        public void onStart(ISuite suite) {
            ExecutionListener.suite = suite;
            passed = zeroCounts();
            failed = zeroCounts();
            skipped = zeroCounts();
        }

        @Override
        public void afterInvocation(IInvokedMethod method, ITestResult result) {
            if (!method.isTestMethod()) {
                return;
            }
            String className = result.getTestClass().getRealClass().getName();
            if (!passed.containsKey(className)) {
                return;
            }

            switch (result.getStatus()) {
            case ITestResult.SUCCESS:
                passed.merge(className, 1, Integer::sum);
                break;
            case ITestResult.FAILURE:
            case ITestResult.SUCCESS_PERCENTAGE_FAILURE:
                failed.merge(className, 1, Integer::sum);
                break;
            case ITestResult.SKIP:
                skipped.merge(className, 1, Integer::sum);
                break;
            default:
                throw new AssertionError("unexpected TestNG status for " + className + ": " + result.getStatus());
            }
        }
    }

    private static Map<String, Integer> zeroCounts() {
        Map<String, Integer> result = new HashMap<>();
        for (String className : ENABLED_RUNTIME_METHODS.keySet()) {
            result.put(className, 0);
        }
        return result;
    }

    private static void validateExecution(Map<String, Integer> discovered, Map<String, Integer> invoked,
            Map<String, Integer> skipped) {
        for (Map.Entry<String, Integer> expected : ENABLED_RUNTIME_METHODS.entrySet()) {
            String className = expected.getKey();
            int expectedCount = expected.getValue();
            int discoveredCount = discovered.getOrDefault(className, 0);
            int invokedCount = invoked.getOrDefault(className, 0);
            int skippedCount = skipped.getOrDefault(className, 0);

            Assert.assertTrue(discoveredCount > 0, "affected class disappeared from discovery: " + className);
            Assert.assertEquals(discoveredCount, expectedCount,
                    "enabled discovery count drifted for " + className);
            Assert.assertEquals(invokedCount, expectedCount,
                    "enabled test bodies did not all execute for " + className);
            Assert.assertEquals(skippedCount, 0, "unexpected enabled-test skips for " + className);
        }
    }

    private static void assertNoPowerMockSuperclass(Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null) {
            Assert.assertFalse(cursor.getName().startsWith("org." + "powermock"),
                    "PowerMock inheritance returned for " + type.getName());
            cursor = cursor.getSuperclass();
        }
    }
}
