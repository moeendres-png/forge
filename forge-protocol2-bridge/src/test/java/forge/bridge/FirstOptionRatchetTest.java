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
 * whatever option the frame happens to list first ({@code options.get(0)},
 * {@code opts.get(0)}, {@code offered.get(0)}, ...).
 * A pick must say why it is sound: {@link BridgeTestSupport#equivalentPayment}
 * (OUTCOME_EQUIVALENT_PROVEN), {@link BridgeTestSupport#reachabilityOnlyChoice}
 * (REACHABILITY_ONLY_NO_BEHAVIOR_CREDIT), or an explicit label/predicate.
 *
 * <p>The 245 legacy picks in older evidence tests are frozen as a ratchet:
 * no file may gain one, a file not listed may have none, and a count that
 * drops must be lowered here so it cannot silently grow back. Adjudicating
 * the legacy picks is tracked separately; this gate only stops new ones.</p>
 */
public class FirstOptionRatchetTest {

    /*
     * Bounded claim (D23, commander-playtest-lab#504). Both gates below are
     * syntactic debt control. They stop new first-option picks in the bridge's
     * tests and keep every first-element pick in its production code either a
     * structurally guarded forced singleton or a pinned, reviewed exception.
     * They are NOT a runtime proof that no production-reachable decision falls
     * back to a first option; that proof belongs to the candidate's runtime
     * qualification (its unsupported paths fail closed and its decisions are
     * observed at runtime), never to these tests.
     */

    /** A test pick of the first element of a frame's option list, under any of the spellings used here. */
    private static final Pattern FIRST_OPTION = Pattern.compile(
            "\\b(options|opts|offered[A-Za-z]*|choices|candidates|legal[A-Za-z]*|getOptions\\s*\\(\\s*\\))"
                    + "\\s*\\.\\s*(get\\s*\\(\\s*0\\s*\\)|getFirst\\s*\\(\\s*\\)"
                    + "|iterator\\s*\\(\\s*\\)\\s*\\.\\s*next\\s*\\(\\s*\\)"
                    + "|stream\\s*\\(\\s*\\)\\s*\\.\\s*find(First|Any)\\s*\\(\\s*\\))");

    /** Legacy first-option picks per test source (path below forge/bridge); may only go down. */
    private static final Map<String, Integer> LEGACY = new TreeMap<>(Map.ofEntries(
            Map.entry("BridgeEngineTest", 7),
            Map.entry("BridgeProtocolProcessTest", 1),
            Map.entry("WS202ExecutableSurfaceTest", 48),
            Map.entry("WS202SeparateProcessTest", 12),
            Map.entry("WS216GapClosureTest", 30),
            Map.entry("WS216SeparateProcessTest", 17),
            Map.entry("WS217DividedAllocationTest", 10),
            Map.entry("WS217SeparateProcessTest", 1),
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
        final Path root = testSources();
        final Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                final String name = root.relativize(file).toString().replace('\\', '/').replaceAll("\\.java$", "");
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

    // ------------------------------------------------------------------ //
    // Production gate

    /**
     * Every first-element pick in production code, whatever the collection is
     * called: {@code get(0)}, {@code getFirst()}, {@code getLast()},
     * {@code iterator().next()}, {@code findFirst()}, {@code findAny()} and
     * {@code Iterables.get*}. It runs on source with comments and literals
     * blanked, so a pick split over lines is still found.
     */
    private static final Pattern PICK = Pattern.compile(
            "\\.\\s*(?:get\\s*\\(\\s*0\\s*\\)|getFirst\\s*\\(\\s*\\)|getLast\\s*\\(\\s*\\)"
                    + "|iterator\\s*\\(\\s*\\)\\s*\\.\\s*next\\s*\\(\\s*\\)"
                    + "|findFirst\\s*\\(\\s*\\)|findAny\\s*\\(\\s*\\))"
                    + "|\\bIterables\\s*\\.\\s*(?:getFirst|getLast|get)\\s*\\(");

    /** Structurally guarded picks: the pick sits directly in the block of {@code if (<receiver>.size() == 1 [&& flag]...)}. */
    private static final int PRODUCTION_FORCED_SINGLETONS = 33;

    /**
     * Every other production pick, pinned by file and exact (whitespace-collapsed)
     * line, with how often it occurs and why it is not a first-option choice.
     */
    private static final String[][] PRODUCTION_EXCEPTIONS = {
            {"BridgeCostDecisionMaker.java", "final Card first = picked.getFirst();", "2",
                    "reads back the pilot's own one-card frame answer (frameCostCards max 1), not an option list"},
            {"BridgeCostDecisionMaker.java", "&& payable.getZone() == player.getZone(cost.getFrom().get(0))", "1",
                    "the cost's source zone, not a decision option"},
            {"ExternalPlayerController.java", "targetOptions, decision.getTotalAmount(), decision.getRecipients().get(0)", "1",
                    "minimum amount carried by the engine's divided-allocation decision, not a choice"},
            {"ExternalPlayerController.java", "single.add(spells.get(0));", "1",
                    "forced singleton guarded by spells.size() == num && num == 1"},
            {"SemanticReplay.java", "return matches.get(0);", "1",
                    "replay match proven unique: throws when no option or more than one option matches"},
    };

    private static Path mainSources() {
        final Path tests = testSources();
        final Path main = tests.getParent().getParent().getParent().getParent().resolve("main").resolve("java");
        if (!Files.isDirectory(main)) {
            throw new AssertionError("bridge main sources not found (gate cannot run): " + main);
        }
        return main;
    }

    /** One first-element pick: where, on which receiver, and whether it is a structurally guarded singleton. */
    static final class Site {
        final int line;
        final String text;
        final boolean forced;

        Site(int line, String text, boolean forced) {
            this.line = line;
            this.text = text;
            this.forced = forced;
        }
    }

    /** Comments, string and char literals blanked to spaces; offsets and newlines kept. */
    static String blankCommentsAndLiterals(String source) {
        final char[] out = source.toCharArray();
        int i = 0;
        while (i < out.length) {
            final char c = source.charAt(i);
            final char next = i + 1 < out.length ? source.charAt(i + 1) : '\0';
            int end;
            if (c == '/' && next == '/') {
                end = source.indexOf('\n', i);
                end = end < 0 ? out.length : end;
            } else if (c == '/' && next == '*') {
                end = source.indexOf("*/", i + 2);
                end = end < 0 ? out.length : end + 2;
            } else if (c == '"' || c == '\'') {
                end = i + 1;
                while (end < out.length && source.charAt(end) != c && source.charAt(end) != '\n') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(out.length, end + 1);
            } else {
                i++;
                continue;
            }
            for (int k = i; k < end; k++) {
                if (out[k] != '\n') {
                    out[k] = ' ';
                }
            }
            i = end;
        }
        return new String(out);
    }

    static List<Site> sites(String source) {
        final String code = blankCommentsAndLiterals(source);
        final String[] lines = source.split("\n", -1);
        final List<Site> found = new ArrayList<>();
        final Matcher m = PICK.matcher(code);
        while (m.find()) {
            final int start = m.start();
            int line = 1;
            for (int k = 0; k < start; k++) {
                if (code.charAt(k) == '\n') {
                    line++;
                }
            }
            final String receiver = code.charAt(start) == '.' ? receiverBefore(code, start) : null;
            final boolean forced = receiver != null && guardedBy(code, start, receiver);
            found.add(new Site(line, lines[line - 1].trim().replaceAll("\\s+", " "), forced));
        }
        return found;
    }

    /** The plain (possibly dotted) name a pick is made on, or null when it is a call result or other expression. */
    private static String receiverBefore(String code, int dot) {
        int end = dot;
        while (end > 0 && Character.isWhitespace(code.charAt(end - 1))) {
            end--;
        }
        int begin = end;
        while (begin > 0 && (Character.isJavaIdentifierPart(code.charAt(begin - 1)) || code.charAt(begin - 1) == '.')) {
            begin--;
        }
        final String name = code.substring(begin, end);
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0)) || name.endsWith(".")) {
            return null;
        }
        return name;
    }

    /**
     * True only when the innermost block that contains the pick is opened by
     * {@code if (<receiver>.size() == 1)} or {@code else if (...)}, optionally
     * conjoined ({@code &&}) with plain flags. A disjunction, a negation, a
     * guard whose block closed before the pick, a comment, or a guard on a
     * different collection does not count.
     */
    private static boolean guardedBy(String code, int pick, String receiver) {
        int depth = 0;
        int open = -1;
        for (int k = pick - 1; k >= 0; k--) {
            final char c = code.charAt(k);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth == 0) {
                    open = k;
                    break;
                }
                depth--;
            }
        }
        if (open < 0) {
            return false;
        }
        int from = open - 1;
        while (from >= 0 && ";{}".indexOf(code.charAt(from)) < 0) {
            from--;
        }
        final String header = code.substring(from + 1, open).replaceAll("\\s+", "");
        return header.matches("(?:else)?if\\(" + Pattern.quote(receiver)
                + "\\.size\\(\\)==1(?:&&!?[A-Za-z_][A-Za-z0-9_.]*(?:\\(\\))?)*\\)");
    }

    /**
     * Every first-element pick in the bridge's production code is either a
     * structurally guarded forced singleton (counted and pinned) or a pinned,
     * reviewed exception. Anything else would answer a real decision with
     * whatever was listed first.
     */
    @Test
    public void productionPicksAreForcedSingletons() throws IOException {
        final List<String> problems = new ArrayList<>();
        final Map<String, Integer> exceptions = new TreeMap<>();
        int forced = 0;
        try (Stream<Path> files = Files.walk(mainSources())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                final String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (Site site : sites(source)) {
                    if (site.forced) {
                        forced++;
                    } else {
                        exceptions.merge(file.getFileName() + "|" + site.text, 1, Integer::sum);
                    }
                }
            }
        }
        final Map<String, Integer> pinned = new TreeMap<>();
        for (String[] exception : PRODUCTION_EXCEPTIONS) {
            pinned.put(exception[0] + "|" + exception[1], Integer.parseInt(exception[2]));
        }
        for (Map.Entry<String, Integer> entry : exceptions.entrySet()) {
            final int allowed = pinned.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() != allowed) {
                problems.add(entry.getKey() + ": " + entry.getValue() + " unguarded first-element pick(s), pinned "
                        + allowed + " (guard it with if (<collection>.size() == 1) or adjudicate it here)");
            }
        }
        for (Map.Entry<String, Integer> entry : pinned.entrySet()) {
            if (!exceptions.containsKey(entry.getKey())) {
                problems.add(entry.getKey() + ": pinned exception no longer present, remove it");
            }
        }
        if (forced != PRODUCTION_FORCED_SINGLETONS) {
            problems.add(forced + " guarded forced-singleton picks in production code, expected "
                    + PRODUCTION_FORCED_SINGLETONS + " (adjust deliberately, never silently)");
        }
        Assert.assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static Site only(String snippet) {
        final List<Site> found = sites(snippet);
        Assert.assertEquals(found.size(), 1, "expected one pick in: " + snippet);
        return found.get(0);
    }

    /** The gate must find every spelling of a pick and accept only a real guard (non-vacuity). */
    @Test
    public void productionGateIsNotVacuous() {
        Assert.assertTrue(only("if (options.size() == 1) {\n    return options.get(0);\n}").forced);
        Assert.assertTrue(only("if (legal.size() == 1 && !isOptional) {\n    return legal.get(0);\n}").forced);
        Assert.assertTrue(only("} else if (subsets.size() == 1) {\n    if (x) { y(); }\n"
                + "    return new ArrayList<>(subsets.get(0));\n}").forced);

        // Detected whatever the collection is called or however the pick is spelled; none guarded.
        for (String unguarded : new String[] {
                "return subsets.get(0);",
                "return legal\n        .get(0);",
                "return legal.stream().findFirst().get();",
                "return legal.stream().findAny().orElseThrow();",
                "return Iterables.getFirst(options, null);",
                "return frame.getOptions().get(0);",
                "return options.getLast();",
                "return spells.iterator().next();",
                "return new ArrayList<>(legal).get(0);"}) {
            Assert.assertFalse(only(unguarded).forced, unguarded);
        }

        // Guards that do not guard.
        for (String fake : new String[] {
                "if (legal.size() == 1 || !isOptional) {\n    return legal.get(0);\n}",
                "if (!(legal.size() == 1)) {\n    return legal.get(0);\n}",
                "if (legal.size() == 1) {\n    audit();\n}\nreturn legal.get(0);",
                "// legal.size() == 1\nreturn legal.get(0);",
                "if (illegal.size() == 1) {\n    return legal.get(0);\n}",
                "if (!subsets.isEmpty()) {\n    return subsets.get(0);\n}",
                "if (legal.size() == 10) {\n    return legal.get(0);\n}",
                "if (legal.size() >= 1) {\n    return legal.get(0);\n}",
                "if (legal.size() == 1) return legal.get(0);"}) {
            Assert.assertFalse(only(fake).forced, fake);
        }

        // Comments and literals are not code.
        Assert.assertTrue(sites("// return options.get(0);\nString s = \"options.get(0)\";").isEmpty());
    }
}
