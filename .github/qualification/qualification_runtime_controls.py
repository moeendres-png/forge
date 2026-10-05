#!/usr/bin/env python3
"""Real-JVM runtime controls for the D17 external-parent trust boundary."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import sandbox
import trusted_execution

HERE = Path(__file__).resolve().parent
REQUIRE = os.environ.get("D17_REQUIRE_TOOLCHAIN") == "1"


def chmod_tree_readable(root: Path) -> None:
    for path in [root] + list(root.rglob("*")):
        try:
            os.chmod(path, 0o755 if path.is_dir() else 0o644)
        except OSError:
            pass


class RuntimeCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.java = shutil.which("java")
        cls.javac = shutil.which("javac")
        cls.testng_dir = Path(os.environ.get("D17_TRUSTED_TESTNG_DIR", ""))
        jars = sorted(cls.testng_dir.glob("*.jar")) if cls.testng_dir.is_dir() else []
        try:
            cls.testng = trusted_execution.verify_trusted_testng(jars)
        except Exception as exc:
            if REQUIRE:
                raise
            raise unittest.SkipTest("pinned TestNG closure unavailable: {}".format(exc))
        if not cls.java or not cls.javac:
            if REQUIRE:
                raise AssertionError("java/javac unavailable")
            raise unittest.SkipTest("java/javac unavailable")

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="d17-runtime-", dir="/tmp"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.exec_user = os.environ.get("D17_EXEC_USER", "d17exec")
        self.build_user = os.environ.get("D17_BUILD_USER", "d17build")
        self.exec_sandbox = Path(os.environ.get(
            "D17_EXEC_SANDBOX_DIR", "/srv/d17-selftest-exec"))
        self.build_sandbox = Path(os.environ.get(
            "D17_BUILD_SANDBOX_DIR", "/srv/d17-selftest-build"))
        try:
            self.exec_home = sandbox.prepare_sandbox(self.exec_user, self.exec_sandbox)
            self.build_home = sandbox.prepare_sandbox(self.build_user, self.build_sandbox)
        except (sandbox.SandboxError, OSError) as exc:
            if REQUIRE:
                self.fail("sandbox unavailable: {}".format(exc))
            self.skipTest("sandbox unavailable: {}".format(exc))

        self.harness = self.tmp / "harness"
        trusted_execution.compile_witness(
            HERE, self.harness, self.java,
            str(self.testng_dir / "testng-7.10.2.jar"))

        # The hosted runner's $RUNNER_TEMP ancestry is not guaranteed traversable
        # by the separate d17exec OS identity. Copy the already digest-verified
        # pinned closure into this per-test /tmp tree, then make only that exact
        # copy readable to the execution UID.
        runtime_testng_dir = self.tmp / "trusted-testng"
        runtime_testng_dir.mkdir()
        self.runtime_testng = []
        for jar in self.testng:
            target = runtime_testng_dir / jar.name
            shutil.copy2(jar, target)
            self.assertEqual(
                trusted_execution.sha256_file(target),
                trusted_execution.TRUSTED_TESTNG_PINS[jar.name],
            )
            self.runtime_testng.append(target)
        chmod_tree_readable(self.tmp)

    @property
    def testng_cp(self) -> str:
        return ":".join(str(p) for p in self.testng)

    def compile_sources(self, sources: dict[str, str], name: str, extra_cp=()) -> Path:
        src = self.tmp / (name + "-src")
        out = self.tmp / (name + "-classes")
        src.mkdir()
        out.mkdir()
        paths = []
        for fqcn, text in sources.items():
            parts = fqcn.split(".")
            target = src.joinpath(*parts[:-1], parts[-1] + ".java")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
            paths.append(str(target))
        cp = [self.testng_cp] + [str(p) for p in extra_cp]
        proc = subprocess.run(
            [self.javac, "-proc:none", "-nowarn", "-cp", ":".join(cp),
             "-d", str(out)] + paths,
            text=True, capture_output=True, check=False)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        chmod_tree_readable(src)
        chmod_tree_readable(out)
        return out

    def launch(self, test_root: Path, expected: dict[str, int],
               expected_methods: dict[str, int],
               candidate_code=(), trusted_dependencies=(), allowed_skips=()):
        output = self.exec_sandbox / "runtime-control-output"
        sandbox.run_candidate(
            self.exec_user, self.exec_home, self.exec_sandbox,
            ["/bin/sh", "-c",
             'rm -rf "$1" && mkdir -p "$1" && chmod 0700 "$1"',
             "d17", str(output)])
        cp = ":".join([str(self.harness)] + [str(p) for p in self.runtime_testng])
        cmd = [
            self.java, "-Djava.security.manager=allow", "-XX:+DisableAttachMechanism",
            "-cp", cp, trusted_execution.DRIVER_CLASS,
            "--module", "runtime-control",
            "--protected-root", str(output),
            "--output-dir", str(output / "testng"),
            "--trusted-test-root", str(test_root),
        ]
        for path in candidate_code:
            cmd += ["--candidate-code", str(path)]
        for path in trusted_dependencies:
            cmd += ["--trusted-dependency", str(path)]
        for jar in self.runtime_testng:
            cmd += ["--trusted-jar", str(jar)]
        for klass, count in sorted(expected.items()):
            cmd += ["--class", klass, "--expected-count", "{}={}".format(klass, count)]
        for key, count in sorted(expected_methods.items()):
            cmd += ["--expected-method-count", "{}={}".format(key, count)]
        for klass in allowed_skips:
            cmd += ["--allowed-skip-class", klass]
        return sandbox.run_candidate(
            self.exec_user, self.exec_home, self.exec_sandbox, cmd, timeout=180)

    def candidate_with_attack(self, body: str, name: str = "AttackProduct") -> Path:
        body = body.replace("AttackProduct.class", name + ".class")
        source = """package probe;
public class %s {
    private static void checkedBoundary() throws ClassNotFoundException { }
    public static int attack() {
        try {
            checkedBoundary();
            %s
            return 0;
        } catch (SecurityException | ClassNotFoundException expected) {
            return 1;
        } catch (Throwable other) {
            return 2;
        }
    }
}
""" % (name, body)
        return self.compile_sources({"probe." + name: source}, name.lower())

    def trusted_attack_test(self, product_name: str = "AttackProduct",
                            expected_value: int = 1, class_name: str = "AttackTest",
                            extra_cp=()) -> Path:
        source = """package probe;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;
public class %s {
    @Test public void attackIsRefused() {
        assertEquals(%s.attack(), %d);
    }
}
""" % (class_name, product_name, expected_value)
        return self.compile_sources(
            {"probe." + class_name: source}, class_name.lower(), extra_cp=extra_cp)


class ParentReceiptRuntimeControls(RuntimeCase):
    def test_honest_candidate_code_completes_without_receipt_credentials(self) -> None:
        candidate = self.compile_sources({
            "probe.HonestProduct":
                "package probe; public class HonestProduct { public static int add(int a,int b){return a+b;} }"
        }, "honest-product")
        tests = self.compile_sources({
            "probe.HonestTest": """package probe;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;
public class HonestTest {
 @Test public void adds(){ assertEquals(HonestProduct.add(2,3),5); }
}"""
        }, "honest-test", extra_cp=[candidate])
        proc = self.launch(tests, {"probe.HonestTest": 1}, {"probe.HonestTest#adds": 1}, candidate_code=[candidate])
        self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_declared_skip_is_encoded_in_os_exit_status(self) -> None:
        tests = self.compile_sources({
            "probe.DeclaredSkipTest": """package probe;
import org.testng.SkipException;
import org.testng.annotations.Test;
public class DeclaredSkipTest {
 @Test public void skipped(){ throw new SkipException("declared"); }
}"""
        }, "declared-skip")
        proc = self.launch(
            tests, {"probe.DeclaredSkipTest": 1}, {"probe.DeclaredSkipTest#skipped": 1},
            allowed_skips=["probe.DeclaredSkipTest"])
        self.assertEqual(proc.returncode, 31, proc.stderr)

    def test_testng_configuration_failure_is_red(self) -> None:
        tests = self.compile_sources({
            "probe.ConfigFailureTest": """package probe;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.Test;
public class ConfigFailureTest {
 @Test public void body(){}
 @AfterSuite public void teardown(){ throw new RuntimeException("configuration failure"); }
}"""
        }, "config-failure")
        proc = self.launch(tests, {"probe.ConfigFailureTest": 1}, {"probe.ConfigFailureTest#body": 1})
        self.assertEqual(proc.returncode, 10, proc.stderr)

    def test_undeclared_skip_is_red(self) -> None:
        tests = self.compile_sources({
            "probe.SkipTest": """package probe;
import org.testng.SkipException;
import org.testng.annotations.Test;
public class SkipTest {
 @Test public void skipped(){ throw new SkipException("no"); }
}"""
        }, "undeclared-skip")
        proc = self.launch(tests, {"probe.SkipTest": 1}, {"probe.SkipTest#skipped": 1})
        self.assertEqual(proc.returncode, 11, proc.stderr)

    def test_suppressed_invocation_count_is_red(self) -> None:
        tests = self.compile_sources({
            "probe.OneTest": """package probe;
import org.testng.annotations.Test;
public class OneTest { @Test public void one(){} }
"""
        }, "count-mismatch")
        proc = self.launch(tests, {"probe.OneTest": 2}, {"probe.OneTest#one": 2})
        self.assertEqual(proc.returncode, 12, proc.stderr)


class HostileBytecodeContainmentRuntimeControls(RuntimeCase):
    ROUTES = {
        "system-loader": 'ClassLoader.getSystemClassLoader();',
        "context-loader":
            'Thread.currentThread().getContextClassLoader().loadClass("org.testng.Reporter");',
        "context-loader-replacement":
            'Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());',
        "forbidden-testng":
            'Class.forName("org.testng.annotations.Listeners");',
        "witness-reflection":
            'Class.forName("forge.d17.witness.QualifiedExecutionCounter");',
        "sun-reflection-internals":
            'System.getSecurityManager().checkPackageAccess("sun.reflect");',
        "jdk-internal-unsafe":
            'System.getSecurityManager().checkPackageAccess("jdk.internal.misc");',
        "suppress-access-reflection":
            'AttackProduct.class.getDeclaredMethod("attack").setAccessible(true);',
        "set-properties":
            'System.setProperties(new java.util.Properties());',
        "direct-properties-mutation":
            'System.getProperties().put("d17.hostile.property", "candidate");',
        "properties-entryset-mutation":
            'for (java.util.Map.Entry<Object,Object> e : System.getProperties().entrySet()) '
            '{ e.setValue("candidate"); break; }',
        "properties-keyset-mutation":
            'System.getProperties().keySet().remove("user.dir");',
        "properties-values-mutation":
            'System.getProperties().values().clear();',
        "jdk-async-properties-deputy":
            'Runnable deputy=java.beans.EventHandler.create(Runnable.class, '
            'System.getProperties(), "clear"); '
            'java.util.concurrent.CompletableFuture.runAsync(deputy).join();',
        "security-manager-replacement":
            'System.setSecurityManager(null);',
        "new-classloader":
            'new java.net.URLClassLoader(new java.net.URL[0]);',
        "exit":
            'System.exit(0);',
        "shutdown-hook":
            'Runtime.getRuntime().addShutdownHook(new Thread(() -> {}));',
        "native":
            'System.loadLibrary("d17_nonexistent_native");',
        "process":
            'new ProcessBuilder("/bin/true").start();',
        "jmx-heap":
            'java.lang.management.ManagementFactory.getPlatformMBeanServer().getMBeanInfo('
            'new javax.management.ObjectName("com.sun.management:type=HotSpotDiagnostic"));',
        "proc-self-mem":
            'java.nio.file.Files.newByteChannel(java.nio.file.Path.of("/proc/self/mem")).close();',
        "proc-thread-self-mem":
            'java.nio.file.Files.newByteChannel(java.nio.file.Path.of("/proc/thread-self/mem")).close();',
        "proc-root-alias-mem":
            'java.nio.file.Files.newByteChannel(java.nio.file.Path.of("/proc/self/root/proc/self/mem")).close();',
        "proc-self-mem-write":
            'java.nio.file.Files.newByteChannel(java.nio.file.Path.of("/proc/self/mem"), '
            'java.nio.file.StandardOpenOption.WRITE).close();',
        "fd-discovery":
            'java.nio.file.Files.list(java.nio.file.Path.of("/proc/self/fd")).close();',
        "proc-thread-self-fd":
            'java.nio.file.Files.list(java.nio.file.Path.of("/proc/thread-self/fd")).close();',
        "symlink-proc-alias":
            'java.nio.file.Path d=java.nio.file.Files.createTempDirectory("d17-proc-link-"); '
            'java.nio.file.Path l=d.resolve("mem"); '
            'java.nio.file.Files.createSymbolicLink(l, java.nio.file.Path.of("/proc/self/mem")); '
            'java.nio.file.Files.newByteChannel(l).close();',
        "thread-race":
            'Thread t=new Thread(() -> System.setProperty("testng.mode.dryrun","true")); t.start(); t.join();',
    }

    def test_each_hostile_authority_route_sets_containment_violation(self) -> None:
        for index, (route, body) in enumerate(self.ROUTES.items()):
            with self.subTest(route=route):
                candidate = self.candidate_with_attack(body, "Attack{}".format(index))
                tests = self.trusted_attack_test(
                    "Attack{}".format(index), expected_value=1,
                    class_name="AttackTest{}".format(index), extra_cp=[candidate])
                proc = self.launch(
                    tests, {"probe.AttackTest{}".format(index): 1},
                    {"probe.AttackTest{}#attackIsRefused".format(index): 1},
                    candidate_code=[candidate])
                self.assertEqual(
                    proc.returncode, 13,
                    "{} stdout={} stderr={}".format(route, proc.stdout, proc.stderr))

    def test_preexisting_symlink_alias_to_procfs_is_denied(self) -> None:
        alias_dir = self.tmp / "preexisting-proc-alias"
        alias_dir.mkdir()
        alias = alias_dir / "mem"
        os.symlink("/proc/self/mem", alias)
        chmod_tree_readable(alias_dir)
        candidate = self.candidate_with_attack(
            'java.nio.file.Files.newByteChannel(java.nio.file.Path.of(%s)).close();'
            % json.dumps(str(alias)),
            "PreexistingLinkAttack",
        )
        tests = self.trusted_attack_test(
            "PreexistingLinkAttack", expected_value=1,
            class_name="PreexistingLinkAttackTest", extra_cp=[candidate])
        proc = self.launch(
            tests, {"probe.PreexistingLinkAttackTest": 1},
            {"probe.PreexistingLinkAttackTest#attackIsRefused": 1},
            candidate_code=[candidate])
        self.assertEqual(proc.returncode, 13, proc.stderr)

    def test_runtime_defined_bytecode_remains_in_hostile_candidate_domain(self) -> None:
        payload = self.compile_sources({
            "probe.RuntimePayload": """package probe;
public class RuntimePayload {
  public static int attack() {
    try {
      System.setProperty("d17.runtime.defined", "pwn");
      return 0;
    } catch (SecurityException expected) {
      return 1;
    }
  }
}
"""
        }, "runtime-payload")
        payload_class = payload / "probe" / "RuntimePayload.class"
        candidate = self.compile_sources({
            "probe.RuntimeDefineProduct": """package probe;
public class RuntimeDefineProduct {
  public static int attack() {
    try {
      byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(%s));
      Class<?> defined = java.lang.invoke.MethodHandles.lookup().defineClass(bytes);
      return ((Integer) defined.getMethod("attack").invoke(null)).intValue();
    } catch (SecurityException expected) {
      return 1;
    } catch (Throwable other) {
      return 2;
    }
  }
}
""" % json.dumps(str(payload_class))
        }, "runtime-define-product")
        tests = self.trusted_attack_test(
            "RuntimeDefineProduct", expected_value=1,
            class_name="RuntimeDefineTest", extra_cp=[candidate])
        proc = self.launch(
            tests, {"probe.RuntimeDefineTest": 1},
            {"probe.RuntimeDefineTest#attackIsRefused": 1},
            candidate_code=[candidate])
        self.assertEqual(proc.returncode, 13, proc.stderr)

    def test_parent_loader_is_structurally_cut_off_from_testng(self) -> None:
        candidate = self.candidate_with_attack(
            'ClassLoader p=AttackProduct.class.getClassLoader().getParent(); '
            'p.loadClass("org.testng.Reporter");')
        tests = self.trusted_attack_test(extra_cp=[candidate])
        proc = self.launch(tests, {"probe.AttackTest": 1}, {"probe.AttackTest#attackIsRefused": 1},
                           candidate_code=[candidate])
        self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_candidate_cannot_write_protected_child_output(self) -> None:
        output = self.exec_sandbox / "runtime-control-output"
        body = 'java.nio.file.Files.writeString(java.nio.file.Path.of(%s), "forged");' % (
            json.dumps(str(output / "forged")),)
        candidate = self.candidate_with_attack(body)
        tests = self.trusted_attack_test(extra_cp=[candidate])
        proc = self.launch(tests, {"probe.AttackTest": 1}, {"probe.AttackTest#attackIsRefused": 1},
                           candidate_code=[candidate])
        self.assertEqual(proc.returncode, 13, proc.stderr)

    def test_candidate_cannot_shadow_trusted_dependency(self) -> None:
        trusted = self.compile_sources({
            "shadow.Shared":
                "package shadow; public class Shared { public static int value(){return 7;} }"
        }, "trusted-shadow")
        candidate = self.compile_sources({
            "shadow.Shared":
                "package shadow; public class Shared { public static int value(){return 99;} }"
        }, "candidate-shadow")
        tests = self.compile_sources({
            "probe.ShadowTest": """package probe;
import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;
import shadow.Shared;
public class ShadowTest { @Test public void trustedWins(){ assertEquals(Shared.value(),7); } }
"""
        }, "shadow-test", extra_cp=[trusted])
        proc = self.launch(
            tests, {"probe.ShadowTest": 1}, {"probe.ShadowTest#trustedWins": 1},
            candidate_code=[candidate], trusted_dependencies=[trusted])
        self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_dependency_domain_cannot_bridge_testng_authority(self) -> None:
        trusted = self.compile_sources({
            "deputy.LoaderDeputy": """package deputy;
public class LoaderDeputy {
  public static int attack() {
    try {
      Class.forName("org.testng.TestNG", true, LoaderDeputy.class.getClassLoader());
      return 0;
    } catch (ClassNotFoundException | SecurityException expected) {
      return 1;
    } catch (Throwable other) {
      return 2;
    }
  }
}
"""
        }, "loader-deputy")
        candidate = self.compile_sources({
            "probe.DependencyLoaderAttackProduct": """package probe;
public class DependencyLoaderAttackProduct {
  public static int attack() {
    return deputy.LoaderDeputy.attack();
  }
}
"""
        }, "dependency-loader-attack", extra_cp=[trusted])
        tests = self.trusted_attack_test(
            "DependencyLoaderAttackProduct", expected_value=1,
            class_name="DependencyLoaderAttackTest", extra_cp=[candidate, trusted])
        proc = self.launch(
            tests, {"probe.DependencyLoaderAttackTest": 1},
            {"probe.DependencyLoaderAttackTest#attackIsRefused": 1},
            candidate_code=[candidate], trusted_dependencies=[trusted])
        self.assertEqual(proc.returncode, 13, proc.stderr)

    def test_candidate_supplied_synchronous_jdk_deputy_is_denied_under_testng(self) -> None:
        candidate = self.compile_sources({
            "probe.SyncDeputyProduct": """package probe;
public class SyncDeputyProduct {
  public static Runnable makeDeputy() {
    return java.beans.EventHandler.create(
        Runnable.class, System.getProperties(), "clear");
  }
}
"""
        }, "sync-deputy-product")
        tests = self.compile_sources({
            "probe.SyncDeputyTest": """package probe;
import org.testng.annotations.Test;
public class SyncDeputyTest {
  @Test public void returnedJdkDeputyCannotBorrowTestNgAuthority() {
    SyncDeputyProduct.makeDeputy().run();
  }
}
"""
        }, "sync-deputy-test", extra_cp=[candidate])
        proc = self.launch(
            tests, {"probe.SyncDeputyTest": 1},
            {"probe.SyncDeputyTest#returnedJdkDeputyCannotBorrowTestNgAuthority": 1},
            candidate_code=[candidate])
        self.assertEqual(proc.returncode, 13, proc.stderr)

    def test_trusted_dependency_cannot_be_used_as_async_confused_deputy(self) -> None:
        trusted = self.compile_sources({
            "deputy.AsyncDeputy": """package deputy;
public class AsyncDeputy {
  public static int attack() throws Exception {
    final int[] result = new int[] {0};
    Thread thread = new Thread(() -> {
      try {
        System.setProperty("d17.async.deputy", "pwn");
        result[0] = 0;
      } catch (SecurityException expected) {
        result[0] = 1;
      } catch (Throwable other) {
        result[0] = 2;
      }
    });
    thread.start();
    thread.join();
    return result[0];
  }
}
"""
        }, "async-deputy")
        candidate = self.compile_sources({
            "probe.DeputyAttackProduct": """package probe;
public class DeputyAttackProduct {
  public static int attack() {
    try {
      return deputy.AsyncDeputy.attack();
    } catch (Throwable other) {
      return 2;
    }
  }
}
"""
        }, "deputy-attack-product", extra_cp=[trusted])
        tests = self.trusted_attack_test(
            "DeputyAttackProduct", expected_value=1,
            class_name="DeputyAttackTest", extra_cp=[candidate, trusted])
        proc = self.launch(
            tests, {"probe.DeputyAttackTest": 1},
            {"probe.DeputyAttackTest#attackIsRefused": 1},
            candidate_code=[candidate], trusted_dependencies=[trusted])
        # The deputy thread has no candidate frame. The dependency loader itself
        # must therefore be tainted, producing the sticky containment exit.
        self.assertEqual(proc.returncode, 13, proc.stderr)


class FilesystemAuthorityRuntimeControls(RuntimeCase):
    def test_build_and_execution_uids_cannot_overwrite_trusted_evidence(self) -> None:
        trusted = self.tmp / "trusted"
        trusted.mkdir(mode=0o755)
        targets = [
            trusted / "qualify.py",
            trusted / "required-surface.json",
            trusted / "execution-manifest.json",
        ]
        for target in targets:
            target.write_text("trusted\n")
            os.chmod(target, 0o444)
        chmod_tree_readable(trusted)

        for user, home, cwd in (
            (self.build_user, self.build_home, self.build_sandbox),
            (self.exec_user, self.exec_home, self.exec_sandbox),
        ):
            for target in targets:
                proc = sandbox.run_candidate(
                    user, home, cwd,
                    ["/bin/sh", "-c", 'printf pwn > "$1"', "d17", str(target)],
                    timeout=30)
                self.assertNotEqual(proc.returncode, 0, (user, target, proc.stderr))
                self.assertEqual(target.read_text(), "trusted\n")

    def test_build_uid_cannot_write_execution_private_directory(self) -> None:
        private = self.exec_sandbox / "private-receipt-boundary"
        sandbox.run_candidate(
            self.exec_user, self.exec_home, self.exec_sandbox,
            ["/bin/sh", "-c", 'rm -rf "$1" && mkdir -p "$1" && chmod 0700 "$1"',
             "d17", str(private)])
        proc = sandbox.run_candidate(
            self.build_user, self.build_home, self.build_sandbox,
            ["/bin/sh", "-c", 'printf pwn > "$1/x"', "d17", str(private)],
            timeout=30)
        self.assertNotEqual(proc.returncode, 0)
        verify = sandbox.run_candidate(
            self.exec_user, self.exec_home, self.exec_sandbox,
            ["/bin/sh", "-c", 'test ! -e "$1"', "d17", str(private / "x")],
            timeout=30)
        self.assertEqual(verify.returncode, 0, verify.stderr)


if __name__ == "__main__":
    unittest.main()
