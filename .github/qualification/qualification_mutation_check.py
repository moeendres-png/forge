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
PRODUCT = ("source_lock.py", "qualify.py", "trusted_execution.py", "qualification_selftest.py")

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
        "ignore-nonzero-trusted-launch",
        "qualify.py",
        "    launches_ok = bool(launch_codes) and all(",
        "    launches_ok = True or all(",
        "a failed trusted test launch would qualify",
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
        "drop-the-trusted-witness-from-the-classpath-head",
        "trusted_execution.py",
        '    return ":".join([str(witness_classes)] + list(entries))',
        '    return ":".join(list(entries))',
        "a candidate class could shadow the trusted listener",
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