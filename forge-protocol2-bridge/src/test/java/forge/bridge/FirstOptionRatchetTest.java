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
 * <p>The 243 legacy picks in older evidence tests are frozen as a ratchet:
 * no file may gain one, a file not listed may have none, and a count that
 * drops must be lowered here so it cannot silently grow back. Adjudicating
 * the legacy picks is tracked separately; this gate only stops new ones.</p>
 */
public class FirstOptionRatchetTest {

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
            Map.entry("WsR20Full107DenominatorTest", 6),
            Map.entry("WsR24Pb06HiddenChannelTest", 6),
            Map.entry("WsR24Pb07ActualCardPreparationTest", 2),
            Map.entry("WsR24Pb07MechanicProbesTest", 10),
            Map.entry("WsR24Pb08ReplayTwinChild", 5),
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
}
