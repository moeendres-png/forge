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
import org.testng.ISuite;
import org.testng.ISuiteResult;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.Test;

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
        Path testRoot = basedir.resolve("src/test/java");
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

        String pom = Files.readString(basedir.resolve("pom.xml"));
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
    public void verifyLiveAffectedExecution(final ITestContext context) {
        if (System.getProperty("test") != null) {
            return;
        }

        ISuite suite = context.getSuite();
        Map<String, Integer> discovered = zeroCounts();
        Map<String, Integer> invoked = zeroCounts();
        Map<String, Integer> skipped = zeroCounts();

        for (ITestNGMethod method : suite.getAllMethods()) {
            String className = method.getTestClass().getRealClass().getName();
            if (discovered.containsKey(className) && method.getEnabled()) {
                discovered.merge(className, 1, Integer::sum);
            }
        }

        for (ISuiteResult suiteResult : suite.getResults().values()) {
            ITestContext testContext = suiteResult.getTestContext();
            addResults(invoked, testContext.getPassedTests().getAllResults());
            addResults(invoked, testContext.getFailedTests().getAllResults());
            addSkipped(skipped, testContext.getSkippedTests().getAllResults());
        }

        validateExecution(discovered, invoked, skipped);

        int totalInvoked = invoked.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("D24_EXECUTION_GUARD=PASS enabled_runtime_methods=" + totalInvoked
                + " disabled_not_run=" + EXPECTED_DISABLED.size());
    }

    private static void addResults(Map<String, Integer> counts, Set<ITestResult> results) {
        for (ITestResult result : results) {
            String className = result.getInstance().getClass().getName();
            if (counts.containsKey(className)) {
                counts.merge(className, 1, Integer::sum);
            }
        }
    }

    private static void addSkipped(Map<String, Integer> skipped, Set<ITestResult> results) {
        for (ITestResult result : results) {
            String className = result.getInstance().getClass().getName();
            if (skipped.containsKey(className)) {
                skipped.merge(className, 1, Integer::sum);
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
