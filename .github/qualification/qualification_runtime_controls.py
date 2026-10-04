#!/usr/bin/env python3
"""Runtime controls for the D17 trust domain, with the real driver and real TestNG.

``qualification_selftest.py`` proves the verdict rules on synthetic evidence.
These controls prove the properties the rules rely on, by running the trusted
driver and listener compiled exactly as the orchestrator compiles them:

* a candidate test that appends a forged PASS line to its own ledger mid-run
  breaks the HMAC chain, so the orchestrator rejects the whole ledger;
* a candidate ``META-INF/services/org.testng.ITestNGListener`` that would flip
  a failure to success is never loaded, so the ledger still records FAIL;
* the ledger key is not visible to candidate code as a system property;
* run as the separate sandbox account, candidate test code cannot write the
  trusted qualification directory or the trusted evidence directory.

Requirements: ``javac``/``java`` and the pinned TestNG closure (``D17_TRUSTED_TESTNG_DIR``,
or the local Maven repository). The sandbox control also needs passwordless
``sudo`` and an unprivileged account (``D17_SANDBOX_USER``, default ``d17cand``,
created by ``sandbox.py`` when absent). With ``D17_REQUIRE_TOOLCHAIN=1`` a missing
prerequisite is a failure, never a skip.
"""

from __future__ import annotations

import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import sandbox  # noqa: E402
import trusted_execution  # noqa: E402

REQUIRE = os.environ.get("D17_REQUIRE_TOOLCHAIN") == "1"
M2 = Path(os.environ.get("HOME", "/root")) / ".m2" / "repository"
PIN_PATHS = {
    "testng-7.10.2.jar": "org/testng/testng/7.10.2",
    "jcommander-1.82.jar": "com/beust/jcommander/1.82",
    "jquery-3.7.1.jar": "org/webjars/jquery/3.7.1",
    "slf4j-api-1.7.36.jar": "org/slf4j/slf4j-api/1.7.36",
}

HONEST = """package probe;
import org.testng.annotations.Test;
public class HonestTest {
    @Test public void one() { }
    @Test public void two() { }
}
"""

FORGER = """package probe;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.testng.annotations.Test;
public class ForgerTest {
    @Test public void forge() throws Exception {
        List<String> args = ProcessHandle.current().info().arguments().map(Arrays::asList).orElse(List.of());
        String ledger = args.get(args.indexOf("--ledger") + 1);
        String line = "{\\"kind\\":\\"invocation\\",\\"seq\\":99,\\"class\\":\\"probe.HonestTest\\","
            + "\\"method\\":\\"forged\\",\\"status\\":\\"PASS\\",\\"context\\":\\"x\\",\\"thread\\":\\"x\\","
            + "\\"mac\\":\\"" + "0".repeat(64) + "\\"}\\n";
        Files.write(Paths.get(ledger), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }
    @Test public void keyIsNotAProperty() {
        for (String name : System.getProperties().stringPropertyNames()) {
            String value = System.getProperty(name);
            if (name.toLowerCase().contains("key") && value != null && value.matches("[0-9a-f]{64}")) {
                throw new AssertionError("a 32-byte hex key is exposed as system property " + name);
            }
        }
    }
}
"""

FAILING = """package probe;
import org.testng.annotations.Test;
public class FailingTest {
    @Test public void fails() { throw new AssertionError("real failure"); }
}
"""

FLIPPER = """package probe;
import org.testng.ITestListener;
import org.testng.ITestResult;
public class FlipFailures implements ITestListener {
    @Override public void onTestFailure(ITestResult result) { result.setStatus(ITestResult.SUCCESS); }
}
"""

TRESPASS = """package probe;
import java.nio.file.*;
import org.testng.annotations.Test;
public class TrespassTest {
    @Test public void writeTrustedState() throws Exception {
        for (String target : System.getenv().getOrDefault("D17_TRUSTED_TARGETS", "").split(":")) {
            if (target.isEmpty()) continue;
            try {
                Files.write(Paths.get(target), "candidate was here".getBytes());
            } catch (Exception denied) {
                // expected: the sandbox account cannot write trusted state
            }
        }
    }
}
"""


def testng_closure() -> "list[Path] | None":
    root = os.environ.get("D17_TRUSTED_TESTNG_DIR")
    jars = []
    for name, rel in PIN_PATHS.items():
        path = Path(root) / name if root else M2 / rel / name
        if not path.is_file():
            return None
        jars.append(path)
    return jars


def javac() -> "str | None":
    return shutil.which("javac")


class RuntimeCase(unittest.TestCase):
    def setUp(self) -> None:
        jars = testng_closure()
        if jars is None or javac() is None:
            if REQUIRE:
                self.fail("toolchain missing: pinned TestNG closure or javac")
            self.skipTest("toolchain missing: pinned TestNG closure or javac")
        self.jars = trusted_execution.verify_trusted_testng(jars)
        self.testng = str(next(j for j in self.jars if j.name.startswith("testng-")))
        self.tmp = Path(tempfile.mkdtemp(prefix="forge-d17-runtime-"))
        os.chmod(self.tmp, 0o755)
        self.addCleanup(shutil.rmtree, str(self.tmp), True)
        self.witness = trusted_execution.compile_witness(HERE.parents[1], self.tmp / "witness", "java", self.testng)

    def compile_source_set(self, sources: dict, out_name: str, extra_cp=()) -> Path:
        src = self.tmp / (out_name + "-src")
        out = self.tmp / out_name
        for name, text in sources.items():
            path = src / "probe" / (name + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        out.mkdir(exist_ok=True)
        files = [str(p) for p in src.rglob("*.java")]
        cp = ":".join([self.testng, *extra_cp])
        proc = subprocess.run([javac(), "-proc:none", "-nowarn", "-cp", cp, "-d", str(out), *files],
                              capture_output=True, text=True, check=False)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return out

    def compile_candidate(self, sources: dict, extra_cp=()) -> Path:
        # Historical helper: these are comparison-base test fixtures, not
        # candidate-owned tests in the production D17 architecture.
        return self.compile_source_set(sources, "trusted-test-classes", extra_cp)

    def launch(self, classes, classpath, ledger_dir: Path, key: bytes, nonce: str, as_user=None, env=None,
               candidate_code=(), trusted_dependencies=()):
        ledger = ledger_dir / "forge-game.witness.jsonl"
        self.assertTrue(classpath, "a trusted test root is required")
        system_cp = trusted_execution.assemble_classpath(self.witness, [str(j) for j in self.jars])
        cmd = ["java", "-Djava.security.manager=allow", "-cp", system_cp,
               trusted_execution.DRIVER_CLASS, "--module", "forge-game", "--nonce", nonce,
               "--ledger", str(ledger), "--protected-root", str(ledger_dir),
               "--output-dir", str(ledger_dir / "testng-out"),
               "--trusted-test-root", str(classpath[0])]
        for entry in candidate_code:
            cmd += ["--candidate-code", str(entry)]
        for entry in trusted_dependencies:
            cmd += ["--trusted-dependency", str(entry)]
        for jar in self.jars:
            cmd += ["--trusted-jar", str(jar)]
        for name in classes:
            cmd += ["--class", name]
        stdin = key.hex().encode("ascii") + b"\n"
        if as_user:
            home = self.sandbox_dir / "home"
            proc = sandbox.run_candidate(as_user, home, ledger_dir, [shutil.which("java")] + cmd[1:],
                                         timeout=600, stdin_bytes=stdin, extra_env=env)
        else:
            proc = subprocess.run(cmd, input=stdin, capture_output=True, check=False, env={**os.environ, **(env or {})})
        raw = trusted_execution.read_candidate_bytes(ledger)
        return proc, raw


class LedgerRuntimeControls(RuntimeCase):
    def test_honest_run_yields_an_authenticated_ledger(self) -> None:
        classes = self.compile_candidate({"HonestTest": HONEST})
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "honest"
        out.mkdir()
        proc, raw = self.launch(["probe.HonestTest"], [str(classes)], out, key, nonce)
        self.assertIsNotNone(raw, proc.stderr)
        lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
        self.assertIsNone(problem)
        records = [json.loads(line) for line in lines]
        invocations = [r for r in records if r["kind"] == "invocation"]
        self.assertEqual(sorted(r["method"] for r in invocations), ["one", "two"])
        self.assertTrue(all(r["status"] == "PASS" for r in invocations))
        self.assertEqual(records[0]["nonce"], nonce)
        # The key never travels as an argument.
        self.assertNotIn(key.hex(), " ".join(map(str, proc.args)))

    def test_a_forged_line_appended_by_candidate_code_rejects_the_ledger(self) -> None:
        classes = self.compile_candidate({"HonestTest": HONEST, "ForgerTest": FORGER})
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "forger"
        out.mkdir()
        _, raw = self.launch(["probe.HonestTest", "probe.ForgerTest"], [str(classes)], out, key, nonce)
        self.assertIsNotNone(raw)
        self.assertIn(b'"method":"forged"', raw, "the forger must actually have written its line")
        lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
        self.assertIsNone(lines)
        self.assertRegex(problem, r"ledger_line_\d+_(unauthenticated|mac_mismatch)")

    def test_the_key_is_not_a_system_property(self) -> None:
        source = FORGER.replace("@Test public void forge()", "public void forge()")
        classes = self.compile_candidate({"ForgerTest": source})
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "props"
        out.mkdir()
        _, raw = self.launch(["probe.ForgerTest"], [str(classes)], out, key, nonce)
        lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
        self.assertIsNone(problem)
        statuses = {json.loads(l)["method"]: json.loads(l)["status"] for l in lines
                    if json.loads(l)["kind"] == "invocation"}
        self.assertEqual(statuses, {"keyIsNotAProperty": "PASS"})

    def test_a_candidate_service_listener_cannot_flip_a_failure(self) -> None:
        classes = self.compile_candidate({"FailingTest": FAILING, "FlipFailures": FLIPPER})
        services = classes / "META-INF" / "services"
        services.mkdir(parents=True)
        (services / "org.testng.ITestNGListener").write_text("probe.FlipFailures\n")
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "spi"
        out.mkdir()
        _, raw = self.launch(["probe.FailingTest"], [str(classes)], out, key, nonce)
        lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
        self.assertIsNone(problem)
        statuses = [json.loads(l)["status"] for l in lines if json.loads(l)["kind"] == "invocation"]
        self.assertEqual(statuses, ["FAIL"])


ANNOTATED = """package probe;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
@Listeners(FlipFailures.class)
public class AnnotatedTest {
    @Test public void harmless() { }
}
"""

REPORTER = """package probe;
import org.testng.Reporter;
public class MainCodeReach {
    public static void flip() { Reporter.getCurrentTestResult().setStatus(1); }
}
"""

NUMERIC = """package probe;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;
public class NumericTest {
    static final long BIG = 1234567890123L;
    static final double PI = 3.141592653589793;
    @Test public void constants() { assertEquals(BIG + (long) PI, 1234567890126L); }
}
"""


DRYRUN_UNCHANGED = """package probe;
import org.testng.Assert;
import org.testng.annotations.Test;
public class AUnchangedTest {
    @Test public void broken() { Assert.fail("a real regression in unchanged code"); }
}
"""

DRYRUN_SWITCH = """package probe;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;
public class ZCandidateTest {
    @BeforeSuite public void quiet() { System.setProperty("testng.mode.dryrun", "true"); }
    @Test public void harmless() { }
}
"""


PROPERTIES_SWAP = """package probe;
import java.util.Properties;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;
public class ZSwapTest {
    static final class Lying extends Properties {
        Lying(Properties base) { super(); putAll(base); }
        @Override public String getProperty(String key) {
            return "testng.mode.dryrun".equals(key) ? "true" : super.getProperty(key);
        }
        @Override public String getProperty(String key, String def) {
            return "testng.mode.dryrun".equals(key) ? "true" : super.getProperty(key, def);
        }
    }
    @BeforeSuite public void swap() { System.setProperties(new Lying(System.getProperties())); }
    @Test public void harmless() { }
}
"""


class DispatchRuntimeControls(RuntimeCase):
    """Review P1-A at e05e6f17: TestNG's dry-run mode reports success without invoking the method."""

    def test_a_dry_run_switched_on_by_candidate_code_is_never_a_pass(self) -> None:
        classes = self.compile_candidate({"AUnchangedTest": DRYRUN_UNCHANGED, "ZCandidateTest": DRYRUN_SWITCH})
        # Admitted: the switch is a plain system property, not TestNG API.
        self.assertEqual(AdmissionRuntimeControls.scan(self, classes)["findings"], [])
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "dryrun"
        out.mkdir()
        _, raw = self.launch(["probe.AUnchangedTest", "probe.ZCandidateTest"], [str(classes)], out, key, nonce)
        lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
        self.assertIsNone(problem)
        records = [json.loads(l) for l in lines]
        statuses = {r["method"]: (r["status"], r["invoked"]) for r in records if r["kind"] == "invocation"}
        self.assertEqual(statuses["broken"][0], "FAIL", statuses)
        self.assertNotIn(("PASS", False), statuses.values())

    def test_a_replaced_properties_object_is_never_a_pass(self) -> None:
        """Review at d0773d76: plain JDK code swapping System.getProperties() hid the dry-run switch."""
        classes = self.compile_candidate({"AUnchangedTest": DRYRUN_UNCHANGED, "ZSwapTest": PROPERTIES_SWAP})
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "swap"
        out.mkdir()
        _, raw = self.launch(["probe.AUnchangedTest", "probe.ZSwapTest"], [str(classes)], out, key, nonce)
        lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
        self.assertIsNone(problem)
        statuses = {json.loads(l)["method"]: json.loads(l)["status"] for l in lines
                    if json.loads(l)["kind"] == "invocation"}
        self.assertEqual(statuses.get("broken"), "FAIL", statuses)
        self.assertNotIn("PASS", statuses.values())

    def test_honest_passes_are_dispatched(self) -> None:
        classes = self.compile_candidate({"HonestTest": HONEST})
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "dispatched"
        out.mkdir()
        _, raw = self.launch(["probe.HonestTest"], [str(classes)], out, key, nonce)
        lines, _ = trusted_execution.verify_ledger(raw, key, nonce)
        invocations = [json.loads(l) for l in lines if json.loads(l)["kind"] == "invocation"]
        self.assertTrue(invocations)
        self.assertTrue(all(r["status"] == "PASS" and r["invoked"] is True for r in invocations))


class AdmissionRuntimeControls(RuntimeCase):
    """Review P1 at 63731d9f, on real javac output: the admission scan sees what TestNG would honour."""

    def scan(self, classes: Path) -> dict:
        root = self.tmp / "candidate-root"
        root.mkdir(exist_ok=True)
        return trusted_execution.scan_launch_classpath(classes, [], root, self.tmp / "m2", None)

    def test_declarative_and_programmatic_listener_registration_is_refused(self) -> None:
        classes = self.compile_candidate({"FailingTest": FAILING, "FlipFailures": FLIPPER,
                                          "AnnotatedTest": ANNOTATED, "MainCodeReach": REPORTER})
        problems = {f["class"]: f["problems"] for f in self.scan(classes)["findings"]}
        self.assertIn("references org/testng/annotations/Listeners", problems["probe/AnnotatedTest.class"])
        self.assertIn("references org/testng/ITestListener", problems["probe/FlipFailures.class"])
        self.assertIn("references org/testng/Reporter", problems["probe/MainCodeReach.class"])
        self.assertNotIn("probe/FailingTest.class", problems)

    def test_ordinary_test_bytecode_is_admitted(self) -> None:
        classes = self.compile_candidate({"HonestTest": HONEST, "NumericTest": NUMERIC, "FailingTest": FAILING})
        scan = self.scan(classes)
        self.assertEqual(scan["findings"], [])
        self.assertEqual(scan["scanned_classes"], 3)


HOSTILE_MAIN = """package probe;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public class HostileMain {
    public static int add(int a, int b) { return a + b; }

    private static byte[] payloadBytes() throws Exception {
        try (InputStream in = HostileMain.class.getResourceAsStream("/probe/Payload.class")) {
            if (in == null) throw new IllegalStateException("payload bytes missing");
            return in.readAllBytes();
        }
    }

    public static int attack(String route, String ledger) {
        try {
            switch (route) {
                case "system-loader":
                    ClassLoader.getSystemClassLoader();
                    break;
                case "context-loader": {
                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    Class.forName("org.testng.Reporter", false, loader);
                    break;
                }
                case "properties-object":
                    System.getProperties();
                    break;
                case "set-properties":
                    System.setProperties(new Properties());
                    break;
                case "new-classloader":
                    new URLClassLoader(new URL[0]);
                    break;
                case "exit":
                    System.exit(0);
                    break;
                case "native":
                    System.loadLibrary("d17_nonexistent_native");
                    break;
                case "ledger-write":
                    Files.writeString(Path.of(ledger), "{\\\"kind\\\":\\\"forged\\\"}\\n",
                            StandardOpenOption.APPEND);
                    break;
                case "heap-dump":
                    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                            .dumpHeap(ledger + ".hprof", true);
                    break;
                case "proc-mem":
                    Files.readAllBytes(Path.of("/proc/self/mem"));
                    break;
                case "witness-reflection":
                    Class.forName("forge.d17.witness.QualifiedExecutionListener")
                            .getDeclaredFields();
                    break;
                case "define-class": {
                    Class<?> payload = MethodHandles.lookup().defineClass(payloadBytes());
                    payload.getMethod("attack").invoke(null);
                    break;
                }
                case "thread-race": {
                    Thread racer = new Thread(() ->
                            System.setProperty("testng.mode.dryrun", "true"));
                    racer.start();
                    racer.join();
                    break;
                }
                default:
                    throw new IllegalArgumentException(route);
            }
            return 0;
        } catch (SecurityException expected) {
            return 1;
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof SecurityException) return 1;
            throw new RuntimeException(wrapped.getCause());
        } catch (ClassNotFoundException expected) {
            // A loader-level authority denial is also an acceptable refusal.
            return 1;
        } catch (Exception other) {
            throw new RuntimeException(other);
        }
    }
}
"""

PAYLOAD = """package probe;
public class Payload {
    public static void attack() {
        System.setProperty("testng.mode.dryrun", "true");
    }
}
"""

TRUSTED_CALLER = """package probe;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;
public class TrustedCallerTest {
    @Test public void honestCandidateCodeRuns() {
        assertEquals(HostileMain.add(2, 3), 5);
    }
}
"""

def trusted_attack_caller(route: str, ledger: Path) -> str:
    return """package probe;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;
public class TrustedAttackCallerTest {
    @Test public void hostileAuthorityAttemptCannotBeHidden() {
        assertEquals(HostileMain.attack(%s, %s), 1);
    }
}
""" % (json.dumps(route), json.dumps(str(ledger)))


TRUSTED_DEPENDENCY = """package shadow;
public class Shared {
    public static int value() { return 7; }
}
"""

CANDIDATE_SHADOW = """package shadow;
public class Shared {
    public static int value() { return 99; }
}
"""

TRUSTED_SHADOW_CALLER = """package probe;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;
import shadow.Shared;
public class TrustedShadowCallerTest {
    @Test public void trustedDependencyWins() {
        assertEquals(Shared.value(), 7);
    }
}
"""


class HostileBytecodeContainmentRuntimeControls(RuntimeCase):
    ROUTES = (
        "system-loader",
        "context-loader",
        "properties-object",
        "set-properties",
        "new-classloader",
        "exit",
        "native",
        "ledger-write",
        "heap-dump",
        "proc-mem",
        "witness-reflection",
        "define-class",
        "thread-race",
    )

    def _candidate_domain(self) -> Path:
        return self.compile_source_set(
            {"HostileMain": HOSTILE_MAIN, "Payload": PAYLOAD}, "candidate-main")

    def test_honest_candidate_production_bytecode_runs_inside_containment(self) -> None:
        candidate = self._candidate_domain()
        tests = self.compile_source_set(
            {"TrustedCallerTest": TRUSTED_CALLER}, "comparison-base-tests-honest",
            extra_cp=[str(candidate)])
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "contained-honest"
        out.mkdir()
        proc, raw = self.launch(["probe.TrustedCallerTest"], [str(tests)], out, key, nonce,
                                candidate_code=[candidate])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
        self.assertIsNone(problem)
        records = [json.loads(line) for line in lines]
        summary = records[-1]
        self.assertEqual(summary.get("containment"), trusted_execution.CONTAINMENT_ENFORCED)
        invocations = [r for r in records if r.get("kind") == "invocation"]
        self.assertEqual([r["status"] for r in invocations], ["PASS"])

    def test_candidate_cannot_shadow_a_trusted_dependency_class(self) -> None:
        dependency = self.compile_source_set(
            {"Shared": TRUSTED_DEPENDENCY}, "trusted-dependency")
        candidate = self.compile_source_set(
            {"Shared": CANDIDATE_SHADOW}, "candidate-shadow")
        tests = self.compile_source_set(
            {"TrustedShadowCallerTest": TRUSTED_SHADOW_CALLER},
            "comparison-base-tests-shadow", extra_cp=[str(dependency)])
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        out = self.tmp / "contained-shadow"
        out.mkdir()
        proc, raw = self.launch(
            ["probe.TrustedShadowCallerTest"], [str(tests)], out, key, nonce,
            candidate_code=[candidate], trusted_dependencies=[dependency])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
        self.assertIsNone(problem)
        records = [json.loads(line) for line in lines]
        self.assertEqual(records[-1].get("containment"), trusted_execution.CONTAINMENT_ENFORCED)
        invocations = [r for r in records if r.get("kind") == "invocation"]
        self.assertEqual([r["status"] for r in invocations], ["PASS"])

    def test_each_hostile_authority_route_is_independently_contained(self) -> None:
        candidate = self._candidate_domain()
        for index, route in enumerate(self.ROUTES):
            with self.subTest(route=route):
                out = self.tmp / ("contained-hostile-" + route)
                out.mkdir()
                ledger = out / "forge-game.witness.jsonl"
                tests = self.compile_source_set(
                    {"TrustedAttackCallerTest": trusted_attack_caller(route, ledger)},
                    "comparison-base-tests-hostile-{}".format(index),
                    extra_cp=[str(candidate)])
                key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
                proc, raw = self.launch(
                    ["probe.TrustedAttackCallerTest"], [str(tests)], out, key, nonce,
                    candidate_code=[candidate])
                self.assertIsNotNone(raw, "{}: {}".format(route, proc.stderr))
                lines, problem = trusted_execution.verify_ledger(raw, key, nonce)
                self.assertIsNone(problem, "{}: {}".format(route, problem))
                records = [json.loads(line) for line in lines]
                summary = records[-1]
                # A route that merely makes the trusted assertion fail without
                # tripping containment is a control failure: it would show that
                # the attempted authority operation was not actually refused.
                self.assertEqual(
                    summary.get("containment"), "SECURITY_MANAGER_VIOLATED",
                    "{}: stdout={} stderr={}".format(route, proc.stdout, proc.stderr))
                self.assertTrue(summary.get("containment_violation"), route)
                invocations = [r for r in records if r.get("kind") == "invocation"]
                self.assertEqual([r["status"] for r in invocations], ["FAIL"], route)
                self.assertNotEqual(proc.returncode, 0, route)


class SandboxRuntimeControls(RuntimeCase):
    def setUp(self) -> None:
        super().setUp()
        self.build_user = os.environ.get("D17_BUILD_USER", "d17build")
        self.user = os.environ.get("D17_EXEC_USER",
                                   os.environ.get("D17_SANDBOX_USER", "d17exec"))
        self.build_sandbox = Path(os.environ.get(
            "D17_BUILD_SANDBOX_DIR", "/srv/d17-selftest-build"))
        self.sandbox_dir = Path(os.environ.get(
            "D17_EXEC_SANDBOX_DIR", "/srv/d17-selftest-exec"))
        try:
            sandbox.prepare_sandbox(self.build_user, self.build_sandbox)
            sandbox.prepare_sandbox(self.user, self.sandbox_dir)
        except (sandbox.SandboxError, OSError) as exc:
            if REQUIRE:
                self.fail("sandbox unavailable: {}".format(exc))
            self.skipTest("sandbox unavailable: {}".format(exc))

    def test_build_plugin_and_execution_users_cannot_write_trusted_state(self) -> None:
        trusted = self.tmp / "trusted"
        trusted.mkdir()
        os.chmod(trusted, 0o755)
        targets = [
            trusted / "qualify.py",
            trusted / "required-surface.json",
            trusted / "execution-manifest.json",
        ]
        for target in targets:
            target.write_text("trusted\n")
        bundle = Path("/var/lib/d17-selftest/bundle")
        staging = self.tmp / "bundle"
        staging.mkdir()
        classes = self.compile_candidate({"TrespassTest": TRESPASS})
        shutil.copytree(classes, staging / "classes")
        shutil.copytree(self.witness, staging / "witness")
        (staging / "testng").mkdir()
        for jar in self.jars:
            shutil.copy2(jar, staging / "testng" / jar.name)
        sandbox.stage_readonly(staging, bundle)
        self.addCleanup(lambda: subprocess.run(["sudo", "-n", "rm", "-rf", str(bundle)], check=False))
        self.witness = bundle / "witness"
        self.jars = [bundle / "testng" / j.name for j in self.jars]
        out = self.sandbox_dir / "ledger"
        sandbox.run_candidate(self.user, self.sandbox_dir / "home", self.sandbox_dir,
                              ["/bin/sh", "-c", 'rm -rf "$1" && mkdir -p "$1"', "d17", str(out)])
        key, nonce = secrets.token_bytes(32), secrets.token_hex(16)
        proc, raw = self.launch(["probe.TrespassTest"], [str(bundle / "classes")], out, key, nonce,
                                as_user=self.user, env={"D17_TRUSTED_TARGETS": ":".join(map(str, targets))})
        for target in targets:
            self.assertEqual(target.read_text(), "trusted\n", "{} was written by execution code".format(target))
        self.assertEqual(sandbox.alive(self.user), [], "an execution candidate process survived")

        # Model a Maven/plugin process under the build UID.  It must not be able
        # to overwrite validator/denominator/evidence or the execution UID's
        # private ledger directory.
        private_ledger = self.sandbox_dir / "private-ledger"
        sandbox.run_candidate(
            self.user, self.sandbox_dir / "home", self.sandbox_dir,
            ["/bin/sh", "-c", 'rm -rf "$1" && mkdir -p "$1" && chmod 0700 "$1"',
             "d17", str(private_ledger)])
        build_home = self.build_sandbox / "home"
        command = (
            'set +e; '
            + 'printf pwn > "$1"; a=$?; '
            + 'printf pwn > "$2"; b=$?; '
            + 'printf pwn > "$3"; c=$?; '
            + 'printf pwn > "$4/forged"; d=$?; '
            + 'test "$a" -ne 0 -a "$b" -ne 0 -a "$c" -ne 0 -a "$d" -ne 0'
        )
        plugin = sandbox.run_candidate(
            self.build_user, build_home, self.build_sandbox,
            ["/bin/sh", "-c", command, "d17",
             str(targets[0]), str(targets[1]), str(targets[2]), str(private_ledger)],
            timeout=60)
        self.assertEqual(plugin.returncode, 0, plugin.stderr)
        for target in targets:
            self.assertEqual(target.read_text(), "trusted\n",
                             "{} was written by build/plugin code".format(target))
        self.assertFalse((private_ledger / "forged").exists())
        self.assertEqual(sandbox.alive(self.build_user), [], "a build candidate process survived")
        lines, problem = trusted_execution.verify_ledger(raw or b"", key, nonce)
        self.assertIsNone(problem, (problem, proc.stdout[-800:], proc.stderr[-800:]))
        self.assertFalse(sandbox.writable_by(self.user, [trusted, bundle]))
        self.assertFalse(sandbox.writable_by(self.build_user, [trusted, bundle, private_ledger]))


if __name__ == "__main__":
    unittest.main()
