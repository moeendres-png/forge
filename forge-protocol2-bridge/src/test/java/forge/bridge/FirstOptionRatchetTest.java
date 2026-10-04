package forge.bridge;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static evidence-harness gate: a test may not answer a decision frame with
 * whatever option the frame happens to list first ({@code options.get(0)}).
 * A pick must say why it is sound: {@link BridgeTestSupport#equivalentPayment}
 * (OUTCOME_EQUIVALENT_PROVEN), {@link BridgeTestSupport#reachabilityOnlyChoice}
 * (REACHABILITY_ONLY_NO_BEHAVIOR_CREDIT), or an explicit label/predicate.
 *
 * <p>The 214 legacy picks in older evidence tests on this branch are frozen as a ratchet:
 * no file may gain one, a file not listed may have none, and a count that
 * drops must be lowered here so it cannot silently grow back. Adjudicating
 * the legacy picks is tracked separately; this gate only stops new ones.</p>
 */
public class FirstOptionRatchetTest {

    /*
     * Bounded claim (D23, commander-playtest-lab#504). Both gates below are
     * syntactic debt control. They stop new first-option picks in the bridge's
     * tests and keep every first-element pick in its production code a forced
     * singleton. They are NOT a runtime proof that no production-reachable
     * decision falls back to a first option; that proof belongs to the
     * candidate's runtime qualification (its unsupported paths fail closed and
     * its decisions are observed at runtime), never to these tests.
     */

    private static final Pattern FIRST_OPTION = Pattern.compile("options\\.get\\(0\\)");

    /** Legacy first-option picks per test class; may only go down. */
    private static final Map<String, Integer> LEGACY = new TreeMap<>(Map.ofEntries(
            Map.entry("BridgeEngineTest", 7),
            Map.entry("WS202ExecutableSurfaceTest", 48),
            Map.entry("WS216GapClosureTest", 30),
            Map.entry("WS217DividedAllocationTest", 10),
            Map.entry("WS227ReplayChild", 5),
            Map.entry("WS227SemanticReplayTest", 14),
            Map.entry("WS234S3BridgeTest", 21),
            Map.entry("WS236F4BridgeTest", 6),
            Map.entry("WsR10KedissBridgeFamilyTest", 4),
            Map.entry("WsR10MagmaBridgeFamilyTest", 9),
            Map.entry("WsR11BoseijuBridgeFamilyTest", 6),
            Map.entry("WsR11FuseBridgeFamilyTest", 5),
            Map.entry("WsR11JeskaBridgeFamilyTest", 4),
            Map.entry("WsR13PathBridgeFamilyTest", 10),
            Map.entry("WsR15ConcessionFamilyTest", 2),
            Map.entry("WsR15MulticountCombatTest", 2),
            Map.entry("WsR15MulticountTriggerTest", 2),
            Map.entry("WsR16SixPlayerFamilyTest", 2),
            Map.entry("WsR9FinaleX10BridgeFamilyTest", 6),
            Map.entry("WsR9RetargetBridgeFamilyTest", 21)));

    private static Path testSources() {
        final List<Path> candidates = new ArrayList<>();
        final String basedir = System.getProperty("basedir");
        if (basedir != null) {
            candidates.add(Paths.get(basedir, "src", "test", "java", "forge", "bridge"));
        }
        candidates.add(Paths.get("src", "test", "java", "forge", "bridge"));
        candidates.add(Paths.get("forge-protocol2-bridge", "src", "test", "java", "forge", "bridge"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("bridge test sources not found (gate cannot run): " + candidates);
    }

    @Test
    public void noTestGainsAFirstOptionPick() throws IOException {
        final Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.list(testSources())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                final String name = file.getFileName().toString().replace(".java", "");
                if (name.equals("FirstOptionRatchetTest")) {
                    continue;
                }
                final Matcher m = FIRST_OPTION.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                int count = 0;
                while (m.find()) {
                    count++;
                }
                if (count > 0) {
                    found.put(name, count);
                }
            }
        }
        Assert.assertFalse(found.isEmpty() && LEGACY.isEmpty(), "gate saw no sources");
        final List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : found.entrySet()) {
            final int allowed = LEGACY.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > allowed) {
                problems.add(entry.getKey() + ": " + entry.getValue() + " first-option picks, allowed "
                        + allowed + " (choose explicitly or use a documented disposition)");
            } else if (entry.getValue() < allowed) {
                problems.add(entry.getKey() + ": now " + entry.getValue() + ", lower its LEGACY entry from "
                        + allowed + " so it cannot grow back");
            }
        }
        for (Map.Entry<String, Integer> entry : LEGACY.entrySet()) {
            if (!found.containsKey(entry.getKey())) {
                problems.add(entry.getKey() + ": no picks left (or file gone), remove its LEGACY entry");
            }
        }
        Assert.assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** First-element picks over a decision's options, as spelled in production code. */
    private static final Pattern FIRST_ELEMENT = Pattern.compile(
            "\\b(options|choices|candidates|legal[A-Za-z]*|valid[A-Za-z]*|offered[A-Za-z]*)"
                    + "\\s*\\.\\s*(get\\(0\\)|getFirst\\(\\)|iterator\\(\\)\\s*\\.\\s*next\\(\\))");

    /** Production sites, each a forced singleton; a new one must be added here deliberately. */
    private static final int PRODUCTION_FORCED_SINGLETONS = 9;

    private static Path mainSources() {
        final Path tests = testSources();
        final Path main = tests.getParent().getParent().getParent().getParent().resolve("main").resolve("java");
        if (!Files.isDirectory(main)) {
            throw new AssertionError("bridge main sources not found (gate cannot run): " + main);
        }
        return main;
    }

    /**
     * Every first-element pick over a decision's options in the bridge's
     * production code must be the only offered option: the line before it (or
     * the line itself) guards {@code <same collection>.size() == 1}. Anything
     * else would answer a real decision with whatever was listed first.
     */
    @Test
    public void productionPicksAreForcedSingletons() throws IOException {
        final List<String> problems = new ArrayList<>();
        int sites = 0;
        try (Stream<Path> files = Files.walk(mainSources())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    final Matcher m = FIRST_ELEMENT.matcher(lines.get(i));
                    while (m.find()) {
                        sites++;
                        final String guard = Pattern.quote(m.group(1)) + "\\s*\\.\\s*size\\(\\)\\s*==\\s*1\\b";
                        final String window = (i > 0 ? lines.get(i - 1) : "") + "\n" + lines.get(i);
                        if (!Pattern.compile(guard).matcher(window).find()) {
                            problems.add(file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).trim()
                                    + " is not guarded by " + m.group(1) + ".size() == 1");
                        }
                    }
                }
            }
        }
        if (sites != PRODUCTION_FORCED_SINGLETONS) {
            problems.add(sites + " first-element picks in production code, expected "
                    + PRODUCTION_FORCED_SINGLETONS + " (adjust deliberately, never silently)");
        }
        Assert.assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** The gate must flag an unguarded pick and accept a guarded one (non-vacuity). */
    @Test
    public void productionGateIsNotVacuous() {
        final Matcher unguarded = FIRST_ELEMENT.matcher("return options.get(0);");
        Assert.assertTrue(unguarded.find());
        final String guard = Pattern.quote("options") + "\\s*\\.\\s*size\\(\\)\\s*==\\s*1\\b";
        Assert.assertTrue(Pattern.compile(guard).matcher("if (options.size() == 1) {\nreturn options.get(0);").find());
        Assert.assertFalse(Pattern.compile(guard).matcher("if (options.size() == 10) {\nreturn options.get(0);").find());
        Assert.assertFalse(Pattern.compile(guard).matcher("if (legal.size() == 1) {\nreturn options.get(0);").find());
    }
}
