#!/usr/bin/env python3
"""Prove the exact-SHA qualification controls are non-vacuous by mutation.

A red/positive control suite that cannot detect a broken implementation proves
nothing. This harness takes each safety property of the qualification, mutates the
product code to violate exactly that property in a scratch copy, and requires the
control suite to go RED.

Design constraints
------------------
* Nothing in the repository checkout is modified. Each mutation is applied to a
  throwaway copy under a temporary directory.
* A mutation counts as *detected* only when the control suite fails. A surviving
  mutation exits nonzero, because a survivor is a hole in the controls.
* Mutants are textual and minimal so a failure is attributable to one property.

Usage: ``python3 qualification_mutation_check.py``
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PRODUCT = ("source_lock.py", "qualify.py", "trusted_execution.py", "sandbox.py", "qualification_selftest.py")

#: Workflow files the workflow-contract controls read. They are staged next to the
#: product copy so those controls are exercised rather than erroring out.
WORKFLOWS = ("forge-candidate-qualification.yml", "test-build.yaml")


class Mutation:
    """One deliberate violation of one qualification safety property."""

    def __init__(self, name: str, filename: str, old: str, new: str, expected: str) -> None:
        self.name = name
        self.filename = filename
        self.old = old
        self.new = new
        self.expected = expected

    def apply(self, root: Path) -> None:
        path = root / self.filename
        text = path.read_text()
        if self.old not in text:
            raise AssertionError(
                "mutation {!r} does not match {}; the product code changed".format(
                    self.name, self.filename
                )
            )
        path.write_text(text.replace(self.old, self.new, 1))


MUTATIONS = [
    # --- candidate identity ------------------------------------------------ #
    Mutation(
        "accept-github-synthetic-merge-ref-as-candidate",
        "source_lock.py",
        'FETCH_REF = re.compile(r"^refs/(pull/[1-9][0-9]*/head|heads/[A-Za-z0-9._/-]+)$")',
        'FETCH_REF = re.compile(r"^refs/pull/[1-9][0-9]*/(head|merge)$")',
        "a synthetic merge ref would become an acceptable candidate identity",
    ),
    Mutation(
        "do-not-verify-fetched-head-equals-declared-candidate",
        "source_lock.py",
        "        if fetched != candidate_sha:",
        "        if False:",
        "a ref resolving to a different SHA would be silently accepted",
    ),
    Mutation(
        "accept-abbreviated-candidate-sha",
        "source_lock.py",
        'HEX40 = re.compile(r"^[0-9a-f]{40}$")',
        'HEX40 = re.compile(r"^[0-9a-f]{7,40}$")',
        "a non-exact candidate identity would resolve",
    ),
    Mutation(
        "allow-qualification-to-run-off-the-default-branch",
        "source_lock.py",
        '    if branch != TRUSTED_DEFAULT_BRANCH:',
        "    if False:",
        "the candidate's own copy of the definition could become authority",
    ),
    Mutation(
        "stop-asserting-verdict-is-not-read-from-candidate",
        "source_lock.py",
        "        if lock.get(assertion) is not False:",
        "        if False:",
        "a lock claiming a candidate-derived verdict would validate",
    ),
    Mutation(
        "stop-asserting-synthetic-merge-was-not-consulted",
        "source_lock.py",
        '    if lock["comparison_base"]["synthetic_merge_computed"] is not False:',
        "    if False:",
        "a lock claiming synthetic-merge consultation would validate",
    ),
    # --- trusted execution provenance -------------------------------------- #
    Mutation(
        "ignore-candidate-identity-binding-in-verifier",
        "qualify.py",
        'identity_ok = bound_sha == lock["candidate"]["sha"] and bound_tree == lock["candidate"]["tree"]',
        "identity_ok = True",
        "evidence for a different candidate would qualify the locked one",
    ),
    Mutation(
        "ignore-required-surface-comparison-base-binding",
        "qualify.py",
        'surface_base_ok = surface.get("comparison_base_sha") == lock["comparison_base"]["sha"]',
        "surface_base_ok = True",
        "a required surface from an unrelated base would be trusted",
    ),
    Mutation(
        "ignore-an-incomplete-trusted-launch",
        "qualify.py",
        "    launches_ok = bool(launch_codes) and not incomplete",
        "    launches_ok = True",
        "a crashed or truncated trusted test launch would qualify",
    ),
    Mutation(
        "treat-a-testng-skip-exit-code-as-a-broken-launch",
        "qualify.py",
        "TESTNG_LAUNCH_COMPLETED_CODES = (0, 2)",
        "TESTNG_LAUNCH_COMPLETED_CODES = (0,)",
        "a suite completing with declared skips would be rejected as broken",
    ),
    Mutation(
        "ignore-the-trusted-per-class-denominator",
        "qualify.py",
        "            if alive.get(name, 0) < max(1, int(floors.get(name, 1)))",
        "            if alive.get(name, 0) < 1",
        "a candidate could partially suppress a required test class",
    ),
    Mutation(
        "ignore-required-class-liveness",
        "qualify.py",
        "        not dead_classes,",
        "        True,",
        "required test classes never observed executing would qualify",
    ),
    Mutation(
        "ignore-trusted-invocation-floor",
        "qualify.py",
        "        observed_total >= required_total,",
        "        True,",
        "a candidate could cut the qualified test volume and still qualify",
    ),
    Mutation(
        "ignore-observed-failures",
        "qualify.py",
        '    signal("no_failed_cases", failures == 0, "{} failure(s)".format(failures))',
        '    signal("no_failed_cases", True, "{} failure(s)".format(failures))',
        "failing candidate tests would qualify",
    ),
    Mutation(
        "ignore-undeclared-skips",
        "qualify.py",
        "        not undeclared,",
        "        True,",
        "skipped coverage inside the qualified surface would qualify",
    ),
    Mutation(
        "treat-absent-and-malformed-witness-ledgers-the-same",
        "qualify.py",
        "        sorted(present_modules) == expected_modules,",
        "        True,",
        "a missing witness ledger would read as ambiguous rather than as never-run",
    ),
    Mutation(
        "accept-witness-without-the-trusted-run-nonce",
        "qualify.py",
        '    if header.get("nonce") != nonce:',
        "    if False:",
        "stale or replayed witness evidence would validate",
    ),
    Mutation(
        "accept-a-witness-with-gaps-in-its-sequence",
        "qualify.py",
        "        if seq != index:",
        "        if False:",
        "a truncated or spliced witness ledger would validate",
    ),
    Mutation(
        "accept-a-witness-summary-that-contradicts-its-records",
        "qualify.py",
        "    if summary.get(\"tests\") != recomputed_total:",
        "    if False:",
        "a hand-edited witness summary would validate",
    ),
    Mutation(
        "accept-invocations-without-a-testng-context",
        "qualify.py",
        '        if not isinstance(record.get("context"), str) or not record["context"]:',
        "        if False:",
        "records that were not genuine TestNG dispatches would count",
    ),
    # --- evidence-class separation and exit codes ------------------------- #
    Mutation(
        "expose-a-nonzero-verdict-class-as-success",
        "qualify.py",
        "EXIT_CODES = {PASS: 0, FAIL: 1, PARTIAL: 2, NOT_RUN: 3, UNKNOWN: 4}",
        "EXIT_CODES = {PASS: 0, FAIL: 0, PARTIAL: 0, NOT_RUN: 0, UNKNOWN: 0}",
        "a non-PASS class would exit 0 and read as a green check",
    ),
    Mutation(
        "promote-rules-qualification-credit-from-ci-green",
        "qualify.py",
        '            "status": _NOT_CLAIMED,',
        '            "status": "PASS",',
        "a green CI qualification would be reported as Rules qualification",
    ),
    Mutation(
        "attribute-pr-mergeability-as-qualification-evidence",
        "qualify.py",
        '            "status": _NOT_APPLICABLE,',
        '            "status": "MERGEABLE",',
        "PR mergeability would be reported as qualification evidence",
    ),
    Mutation(
        "understate-the-trusted-required-surface-by-pattern",
        "trusted_execution.py",
        'TEST_INCLUDE_PATTERNS = ("Test*.java", "*Test.java", "*Tests.java", "*TestCase.java")',
        'TEST_INCLUDE_PATTERNS = ("*Test.java",)',
        "the trusted denominator would silently shrink and let tests be deleted",
    ),
    Mutation(
        "ignore-module-surefire-explicit-includes",
        "trusted_execution.py",
        "    if explicit:\n        return explicit\n",
        "    if explicit:\n        return list(TEST_INCLUDE_PATTERNS)\n",
        "D24-restored non-default test classes would be omitted from execution",
    ),
    Mutation(
        "use-candidate-test-source-as-qualification-authority",
        "trusted_execution.py",
        "    return comparison_base\n\n\ndef cmd_execute",
        "    return candidate_sha\n\n\ndef cmd_execute",
        "candidate-owned test deletion or weakening would become qualification authority",
    ),
    Mutation(
        "ignore-hostile-bytecode-containment",
        "qualify.py",
        '    elif not by_name["hostile_candidate_bytecode_contained"]["satisfied"]:',
        '    elif False:',
        "hostile candidate bytecode could violate the witness authority and still qualify",
    ),
    Mutation(
        "ignore-generic-source-not-run-obligations",
        "qualify.py",
        "        and not source_obligations_not_run\n",
        "        and True\n",
        "a newly undiscovered trusted-source @Test class could disappear behind the executable denominator",
    ),
    Mutation(
        "ignore-method-level-disabled-test-debt",
        "qualify.py",
        "        and not other_disabled_methods\n",
        "        and True\n",
        "explicitly disabled test methods could disappear behind class-level execution",
    ),
    Mutation(
        "promote-known-not-run-coverage-to-pass",
        "qualify.py",
        '    elif not by_name["whole_reactor_coverage_complete"]["satisfied"]:',
        '    elif False:',
        "D24/disabled NOT_RUN obligations would be hidden behind executable-surface PASS",
    ),
    Mutation(
        "ignore-an-integrity-violation",
        "qualify.py",
        '    if integrity_status == "VIOLATION":',
        '    if False:',
        "candidate code that rewrote trusted state could still reach PASS",
    ),
    Mutation(
        "credit-an-unauthenticated-ledger",
        "qualify.py",
        '        if entry.get("ledger_authentication") != LEDGER_AUTHENTICATED:',
        '        if False:',
        "a ledger the orchestrator never authenticated would be credit",
    ),
    Mutation(
        "ignore-external-parent-receipt-authority",
        "qualify.py",
        '    elif not by_name["external_parent_receipt_authority"]["satisfied"]:',
        '    elif False:',
        "a same-JVM/candidate-owned receipt authority could qualify",
    ),
    Mutation(
        "ignore-module-parent-receipt-binding",
        "qualify.py",
        '    elif not by_name["module_receipts_bound_to_external_parent"]["satisfied"]:',
        '    elif False:',
        "a replayed or foreign module receipt could qualify",
    ),
    Mutation(
        "pass-receipt-nonce-into-candidate-jvm",
        "trusted_execution.py",
        '        "--module", module,\n        "--protected-root", str(launch_dir),',
        '        "--module", module, "--nonce", receipt_run_id,\n        "--protected-root", str(launch_dir),',
        "the candidate JVM would receive parent receipt authentication state",
    ),
    Mutation(
        "write-credited-receipt-inside-candidate-output",
        "trusted_execution.py",
        '    trusted_copy = evidence_witness / (module + ".witness.jsonl")',
        '    trusted_copy = launch_dir / (module + ".witness.jsonl")',
        "candidate-execution UID could overwrite credited receipt evidence",
    ),
    Mutation(
        "accept-candidate-code-run-as-a-trusted-identity",
        "qualify.py",
        'TRUSTED_IDENTITIES = ("root", "runner")',
        'TRUSTED_IDENTITIES = ()',
        "candidate build/execution code run as a trusted runner identity would be credit",
    ),
    Mutation(
        "ignore-build-execution-identity-separation",
        "qualify.py",
        '    elif not by_name["build_and_execution_identities_separated"]["satisfied"]:',
        '    elif False:',
        "a delayed candidate build process could share the witness JVM UID and attack it out of process",
    ),
    Mutation(
        "accept-an-unpinned-trusted-testng",
        "trusted_execution.py",
        '        if sha256_file(path) != TRUSTED_TESTNG_PINS[path.name]:',
        '        if False:',
        "a substituted TestNG would compile and run the trusted witness",
    ),
    Mutation(
        "drop-the-trusted-witness-from-the-classpath-head",
        "trusted_execution.py",
        '    return ":".join([str(witness_classes)] + list(entries))',
        '    return ":".join(list(entries))',
        "a candidate class could shadow the trusted listener",
    ),
    # --- export integrity and trusted interpreter (ported from C12) --------- #
    Mutation(
        "export-without-blob-verification",
        "sandbox.py",
        "    verify_export(repo, sha, dest)\n",
        "    pass\n",
        "a .gitattributes export-subst/export-ignore would change the bytes built and recompiled",
    ),
    Mutation(
        "trusted-python-imports-site",
        "../workflows/forge-candidate-qualification.yml",
        "/usr/bin/python3 -I -S -B .github/qualification/sandbox.py prepare",
        "/usr/bin/python3 -I -B .github/qualification/sandbox.py prepare",
        "a .pth file on the runner would run inside trusted prepare",
    ),
    Mutation(
        "prepare-probes-inherited-path",
        "../workflows/forge-candidate-qualification.yml",
        '          export PATH="$D17_TRUSTED_PATH"\n          /usr/bin/python3 -I -S -B .github/qualification/sandbox.py prepare',
        '          /usr/bin/python3 -I -S -B .github/qualification/sandbox.py prepare',
        "prepare would probe and resolve tools on the inherited PATH",
    ),
    Mutation(
        "compile-trusted-tests-against-candidate-writable-classpath",
        "trusted_execution.py",
        '                + scans[module]["trusted_dependency_compile_entries"]\n'
        '                + scans[module]["candidate_compile_entries"]\n',
        '                + entries\n',
        "trusted javac would parse candidate-build-UID-writable inputs before the freeze boundary",
    ),
    Mutation(
        "limit-candidate-production-to-test-bearing-modules",
        "trusted_execution.py",
        "    reactor_modules = trusted_reactor_modules(trusted_repo, args.comparison_base)\n",
        "    reactor_modules = modules\n",
        "candidate changes in upstream Forge reactor modules would disappear from the executed classpath",
    ),
    Mutation(
        "read-d20-known-not-run-from-working-tree",
        "trusted_execution.py",
        '        raw = _git_blob_bytes(repo, "{}:{}".format(base, D20_KNOWN_NOT_RUN_PATH))\n',
        '        raw = (repo / D20_KNOWN_NOT_RUN_PATH).read_bytes()\n',
        "candidate working-tree data could redefine named NOT_RUN coverage categories",
    ),
    # --- trusted Maven/build-definition authority ------------------------- #
    Mutation(
        "ignore-candidate-build-definition-divergence",
        "trusted_execution.py",
        "        divergence = build_definition_divergence(\n            trusted_repo, args.comparison_base, args.candidate_sha)\n",
        "        divergence = []\n",
        "candidate-controlled POM/plugin changes would be allowed to define the bytecode under test",
    ),
    Mutation(
        "use-candidate-writable-maven-repository",
        "trusted_execution.py",
        '"-Dmaven.repo.local=" + str(trusted_maven_repo),',
        '"-Dmaven.repo.local=" + str(home / ".m2" / "repository"),',
        "candidate code could replace Maven plugin/dependency jars before later build phases",
    ),
    Mutation(
        "allow-candidate-annotation-processors",
        "trusted_execution.py",
        '             "-Dmaven.compiler.proc=none",\n',
        '',
        "candidate source could execute as an annotation processor and synthesize downstream bytecode",
    ),
    Mutation(
        "allow-network-resolution-in-candidate-build",
        "trusted_execution.py",
        '            [args.mvn, "-o", "-B", "-q",',
        '            [args.mvn, "-B", "-q",',
        "candidate Maven execution could resolve untrusted code instead of the pre-resolved trusted repository",
    ),
    # --- launch classpath admission and verdict bindings (review 63731d9f) - #
    Mutation(
        "ignore-rejected-launch-classpath",
        "qualify.py",
        "    elif rejected:\n",
        "    elif False:\n",
        "a @Listeners class or a rewritten dependency jar would no longer block PASS",
    ),
    Mutation(
        "admit-testng-listener-annotation",
        "trusted_execution.py",
        "NoInjection|Ignore)|Assert|",
        "NoInjection|Ignore|Listeners)|Assert|",
        "@Listeners would be admitted next to the witness",
    ),
    Mutation(
        "accept-tampered-dependency-jar",
        "trusted_execution.py",
        "                result[\"tampered_jars\"].append(str(rel_path))",
        "                pass",
        "a dependency jar the candidate account rewrote would be admitted",
    ),
    Mutation(
        "ignore-failed-trusted-compile",
        "qualify.py",
        "        elif isinstance(code, bool) or code != 0:",
        "        elif False:",
        "a required class trusted javac did not produce could load from candidate bytecode",
    ),
    Mutation(
        "integrity-record-without-account",
        "sandbox.py",
        "        \"user\": user,\n        \"trusted_sha\": sha,",
        "        \"trusted_sha\": sha,",
        "every real run would be FAIL: qualify could never PASS",
    ),
    Mutation(
        "verify-against-own-head",
        "../workflows/forge-candidate-qualification.yml",
        '--trusted-sha "$AUTHORITY_SHA"',
        '--trusted-sha "$(git -C "$GITHUB_WORKSPACE" rev-parse HEAD)"',
        "trusted-file re-derivation would compare the checkout with itself",
    ),
    Mutation(
        "allow-testng-listener-in-candidate-loader",
        "witness/forge/d17/witness/Containment.java",
        '            if (name.startsWith("org.testng.")) {\n',
        '            if (name.startsWith("org.testng.") && !name.equals("org.testng.annotations.Listeners")) {\n',
        "computed-name candidate bytecode could resolve @Listeners authority",
    ),
    Mutation(
        "expose-trusted-dependency-bridge-as-candidate-parent",
        "witness/forge/d17/witness/Containment.java",
        "            super(urls, ClassLoader.getPlatformClassLoader());\n            this.dependencies = dependencies;\n",
        "            super(urls, dependencies);\n            this.dependencies = dependencies;\n",
        "candidate code could obtain its parent loader and load trusted TestNG authority through it",
    ),
    # --- dispatch and frozen launches (review e05e6f17) -------------------- #
    Mutation(
        "credit-pass-without-dispatch",
        "qualify.py",
        '        if status == "PASS" and record.get("invoked") is not True:',
        "        if False:",
        "a TestNG dry-run success would be credited as an executed PASS",
    ),
    Mutation(
        "put-candidate-bytecode-on-system-classpath",
        "trusted_execution.py",
        '    full_cp = assemble_classpath(bundle / "witness", staged_testng)\n',
        '    full_cp = assemble_classpath(bundle / "witness", staged_testng + list(candidate_code_entries))\n',
        "candidate production bytecode would share the trusted system-loader authority domain",
    ),
]


def stage(root: Path) -> Path:
    """Materialize a runnable copy of the real qualification layout under ``root``."""
    qualification = root / ".github" / "qualification"
    workflows = root / ".github" / "workflows"
    qualification.mkdir(parents=True, exist_ok=True)
    workflows.mkdir(parents=True, exist_ok=True)
    for name in PRODUCT:
        shutil.copy2(HERE / name, qualification / name)
    shutil.copytree(HERE / "witness", qualification / "witness", dirs_exist_ok=True)
    for name in WORKFLOWS:
        shutil.copy2(HERE.parent / "workflows" / name, workflows / name)
    return qualification


def run_controls(root: Path) -> "tuple[int, str]":
    proc = subprocess.run(
        [sys.executable, str(root / "qualification_selftest.py")],
        cwd=str(root), capture_output=True, text=True, check=False,
    )
    return proc.returncode, proc.stdout + proc.stderr


def failing_tests(output: str) -> "set[str]":
    names = set()
    for line in output.splitlines():
        line = line.strip()
        for marker in ("FAIL: ", "ERROR: "):
            if line.startswith(marker):
                names.add(line[len(marker):].split(" ")[0])
    return names


def main() -> int:
    scratch = Path(tempfile.mkdtemp(prefix="forge-d17-mutation-"))
    survived = []
    undetected = []
    try:
        baseline_root = stage(scratch / "baseline")
        code, output = run_controls(baseline_root)
        if code != 0:
            sys.stderr.write("baseline control suite is not green:\n{}\n".format(output[-4000:]))
            return 1
        print("baseline           : GREEN ({} controls)".format(output.count(" ... ")))

        for index, mutation in enumerate(MUTATIONS):
            root = stage(scratch / "mutant-{}".format(index))
            mutation.apply(root)
            code, output = run_controls(root)
            if code == 0:
                survived.append(mutation.name)
                print("SURVIVED           : {}".format(mutation.name))
            else:
                detected = failing_tests(output)
                print("detected ({:>2} red) : {}  [{}]".format(
                    len(detected), mutation.name, mutation.expected))
                if not detected:
                    undetected.append(mutation.name)
    finally:
        shutil.rmtree(scratch, ignore_errors=True)

    print("")
    print("mutations          : {}".format(len(MUTATIONS)))
    print("detected           : {}".format(len(MUTATIONS) - len(survived)))
    print("survived           : {}".format(len(survived)))
    if survived or undetected:
        for name in survived:
            print("  SURVIVED  {}".format(name))
        for name in undetected:
            print("  NO-RED    {}".format(name))
        return 1
    print("")
    print("all mutations detected: the controls are non-vacuous")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())