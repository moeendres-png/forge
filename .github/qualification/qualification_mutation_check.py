#!/usr/bin/env python3
"""Prove the exact-SHA qualification controls are non-vacuous by mutation.

A red/positive control suite that cannot detect a broken implementation proves
nothing.  This harness takes each safety property of the qualification, mutates
the product code to violate exactly that property in a scratch copy, and
requires the control suite to go RED for the right reason.

Design constraints
------------------
* Nothing in the repository checkout is modified.  Each mutation is applied to a
  throwaway copy under a temporary directory.
* A mutation counts as *detected* only when the control suite fails.  A mutation
  that leaves the suite green is reported as ``SURVIVED`` and the harness exits
  nonzero, because a surviving mutation is a hole in the controls.
* Mutants are textual and minimal, so a failure can be attributed to the single
  property under test.

Usage: ``python3 qualification_mutation_check.py``
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PRODUCT = ("source_lock.py", "qualify.py", "qualification_selftest.py")


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
    Mutation(
        name="accept-github-synthetic-merge-ref-as-candidate",
        filename="source_lock.py",
        old='FETCH_REF = re.compile(r"^refs/(pull/[1-9][0-9]*/head|heads/[A-Za-z0-9._/-]+)$")',
        new='FETCH_REF = re.compile(r"^refs/pull/[1-9][0-9]*/(head|merge)$")',
        expected="a synthetic merge ref would become an acceptable candidate identity",
    ),
    Mutation(
        name="do-not-verify-fetched-head-equals-declared-candidate",
        filename="source_lock.py",
        old="        if fetched != candidate_sha:",
        new="        if False:",
        expected="a ref resolving to a different SHA would be silently accepted",
    ),
    Mutation(
        name="accept-abbreviated-candidate-sha",
        filename="source_lock.py",
        old='HEX40 = re.compile(r"^[0-9a-f]{40}$")',
        new='HEX40 = re.compile(r"^[0-9a-f]{7,40}$")',
        expected="a non-exact candidate identity would resolve",
    ),
    Mutation(
        name="allow-qualification-to-run-off-the-default-branch",
        filename="source_lock.py",
        old='    if branch != TRUSTED_DEFAULT_BRANCH:',
        new="    if False:",
        expected="the candidate's own copy of the definition could become authority",
    ),
    Mutation(
        name="stop-asserting-verdict-is-not-read-from-candidate",
        filename="source_lock.py",
        old="        if lock.get(assertion) is not False:",
        new="        if False:",
        expected="a lock claiming a candidate-derived verdict would validate",
    ),
    Mutation(
        name="stop-asserting-synthetic-merge-was-not-consulted",
        filename="source_lock.py",
        old="    if lock[\"comparison_base\"][\"synthetic_merge_computed\"] is not False:",
        new="    if False:",
        expected="a lock claiming synthetic-merge consultation would validate",
    ),
    Mutation(
        name="ignore-candidate-identity-binding",
        filename="qualify.py",
        old="    identity_ok = bound_sha == lock[\"candidate\"][\"sha\"] and bound_tree == lock[\"candidate\"][\"tree\"]",
        new="    identity_ok = True",
        expected="evidence for a different candidate would qualify the locked one",
    ),
    Mutation(
        name="treat-malformed-reports-as-parsed",
        filename="qualify.py",
        old="        not report_problems,",
        new="        True,",
        expected="malformed candidate evidence would be read as executed coverage",
    ),
    Mutation(
        name="treat-absent-reports-as-executed",
        filename="qualify.py",
        old="        report_count > 0,",
        new="        True,",
        expected="a green command with no test evidence would reach PASS",
    ),
    Mutation(
        name="ignore-missing-expected-modules",
        filename="qualify.py",
        old="        not missing_modules,",
        new="        True,",
        expected="an unreported qualification module would qualify",
    ),
    Mutation(
        name="ignore-undeclared-skips",
        filename="qualify.py",
        old="        not undeclared,",
        new="        True,",
        expected="skipped coverage inside the qualified surface would qualify",
    ),
    Mutation(
        name="ignore-failed-and-errored-cases",
        filename="qualify.py",
        old="        failures == 0 and errors == 0,",
        new="        True,",
        expected="failing candidate tests would qualify",
    ),
    Mutation(
        name="ignore-nonzero-trusted-step-outcome",
        filename="qualify.py",
        old="    step_codes_ok = (\n        isinstance(step_codes, dict)",
        new="    step_codes_ok = (\n        True or isinstance(step_codes, dict)",
        expected="a failed qualification step would qualify",
    ),
    Mutation(
        name="stop-discarding-candidate-authored-verdict-attributes",
        filename="qualify.py",
        old='VERDICT_KEYS = ("verdict", "status", "result", "outcome", "passed", "success")',
        new="VERDICT_KEYS = ()",
        expected="candidate-authored verdict attributes would stop being provably discarded",
    ),
    Mutation(
        name="expose-a-nonzero-verdict-class-as-success",
        filename="qualify.py",
        old="EXIT_CODES = {PASS: 0, FAIL: 1, PARTIAL: 2, NOT_RUN: 3, UNKNOWN: 4}",
        new="EXIT_CODES = {PASS: 0, FAIL: 0, PARTIAL: 0, NOT_RUN: 0, UNKNOWN: 0}",
        expected="a non-PASS class would exit 0 and read as a green check",
    ),
    Mutation(
        name="promote-rules-qualification-credit-from-ci-green",
        filename="qualify.py",
        old='            "status": _NOT_CLAIMED,',
        new='            "status": "PASS",',
        expected="a green CI qualification would be reported as Rules qualification",
    ),
    Mutation(
        name="attribute-pr-mergeability-as-qualification-evidence",
        filename="qualify.py",
        old='            "status": _NOT_APPLICABLE,',
        new='            "status": "MERGEABLE",',
        expected="PR mergeability would be reported as qualification evidence",
    ),
    Mutation(
        name="accept-reports-from-anywhere-in-the-candidate-tree",
        filename="qualify.py",
        old="        if len(parts) != 4:\n            continue\n        if parts[1] != REPORT_TARGET_DIR or parts[2] != REPORT_DIR_NAME:\n            continue",
        new="        if False:\n            continue",
        expected="arbitrary candidate XML could pose as qualification evidence",
    ),
    Mutation(
        name="accept-an-unrun-surface-as-executed",
        filename="qualify.py",
        old='    signal("tests_executed", executed > 0,',
        new='    signal("tests_executed", True,',
        expected="reports containing no executed test would qualify",
    ),
]


def run_controls(root: Path) -> "tuple[int, str]":
    proc = subprocess.run(
        [sys.executable, str(root / "qualification_selftest.py")],
        cwd=str(root),
        capture_output=True,
        text=True,
        check=False,
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
    survived: "list[str]" = []
    undetected: "list[str]" = []
    try:
        # Baseline: the unmutated suite must be green, otherwise "detected" is
        # meaningless.
        baseline_root = scratch / "baseline"
        baseline_root.mkdir()
        for name in PRODUCT:
            shutil.copy2(HERE / name, baseline_root / name)
        code, output = run_controls(baseline_root)
        if code != 0:
            sys.stderr.write("baseline control suite is not green:\n{}\n".format(output[-4000:]))
            return 1
        print("baseline           : GREEN ({} controls)".format(output.count(" ... ")))

        for index, mutation in enumerate(MUTATIONS):
            root = scratch / "mutant-{}".format(index)
            root.mkdir()
            for name in PRODUCT:
                shutil.copy2(HERE / name, root / name)
            mutation.apply(root)
            code, output = run_controls(root)
            if code == 0:
                survived.append(mutation.name)
                print("SURVIVED           : {}".format(mutation.name))
            else:
                detected = failing_tests(output)
                print("detected ({:>2} red) : {}  [{}]".format(len(detected), mutation.name, mutation.expected))
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