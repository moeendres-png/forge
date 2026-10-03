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

    def compile_candidate(self, sources: dict, extra_cp=()) -> Path:
        src = self.tmp / "candidate-src"
        out = self.tmp / "candidate-classes"
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

    def launch(self, classes, classpath, ledger_dir: Path, key: bytes, nonce: str, as_user=None, env=None):
        ledger = ledger_dir / "forge-game.witness.jsonl"
        cmd = ["java", "-cp", trusted_execution.assemble_classpath(self.witness, [str(j) for j in self.jars] + classpath),
               trusted_execution.DRIVER_CLASS, "--module", "forge-game", "--nonce", nonce,
               "--ledger", str(ledger), "--output-dir", str(ledger_dir / "testng-out")]
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


class SandboxRuntimeControls(RuntimeCase):
    def setUp(self) -> None:
        super().setUp()
        self.user = os.environ.get("D17_SANDBOX_USER", sandbox.CANDIDATE_USER)
        self.sandbox_dir = Path(os.environ.get("D17_SANDBOX_DIR", "/srv/d17-selftest-sandbox"))
        try:
            sandbox.prepare_sandbox(self.user, self.sandbox_dir)
        except (sandbox.SandboxError, OSError) as exc:
            if REQUIRE:
                self.fail("sandbox unavailable: {}".format(exc))
            self.skipTest("sandbox unavailable: {}".format(exc))

    def test_candidate_tests_cannot_write_trusted_state(self) -> None:
        trusted = self.tmp / "trusted"
        trusted.mkdir()
        os.chmod(trusted, 0o755)
        targets = [trusted / "qualify.py", trusted / "execution-manifest.json"]
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
            self.assertEqual(target.read_text(), "trusted\n", "{} was written by candidate code".format(target))
        self.assertEqual(sandbox.alive(self.user), [], "a candidate process survived")
        lines, problem = trusted_execution.verify_ledger(raw or b"", key, nonce)
        self.assertIsNone(problem, (problem, proc.stdout[-800:], proc.stderr[-800:]))
        self.assertFalse(sandbox.writable_by(self.user, [trusted, bundle]))


if __name__ == "__main__":
    unittest.main()
