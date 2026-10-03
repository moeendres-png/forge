#!/usr/bin/env python3
"""Red and positive controls for the Forge exact-SHA candidate qualification.

This suite is the qualification's own proof that it cannot be trivialized.  It
is stdlib-only (``unittest``) because Forge has no Python test infrastructure,
and it builds real Git repositories in temporary directories so the controls
exercise the same ``git`` calls the workflow makes rather than a mock.

Control classes
---------------
POSITIVE  the exact-SHA path resolves, binds and yields PASS.
RED       a wrong or trivialized candidate state must fail closed.  Every RED
          control asserts a *specific non-PASS class*, never merely "not PASS",
          so a control cannot pass for the wrong reason.

The controls deliberately include candidate-authored attempts to self-qualify:
fabricated surefire reports, a candidate-supplied run report, a candidate-
supplied verdict document and mergeability/synthetic-merge claims.  Each must
leave the trusted verdict non-PASS or provably ignored.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import qualify  # noqa: E402
import source_lock  # noqa: E402

QUALIFY_WORKFLOW = ".github/workflows/forge-candidate-qualification.yml"
EXPECTED_MODULES = ["forge-game", "forge-gui-desktop"]

WORKFLOW_STUB = """name: stub
on: [push]
jobs:
  stub:
    runs-on: ubuntu-latest
    steps:
      - run: echo trusted
"""


# --------------------------------------------------------------------------- #
# Git fixtures
# --------------------------------------------------------------------------- #


class Fixture:
    """A throwaway origin repository plus a trusted clone of it."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.origin = root / "origin"
        self.trusted = root / "trusted"
        self.origin.mkdir(parents=True)
        self._git(self.origin, "init", "-q", "-b", "master", ".")
        self._git(self.origin, "config", "user.email", "d17@example.invalid")
        self._git(self.origin, "config", "user.name", "D17 Fixture")

        # Trusted master carries the qualification definition and verifier.
        self.write_origin(QUALIFY_WORKFLOW, WORKFLOW_STUB)
        self.write_origin(".github/qualification/qualify.py", "# trusted verifier\n")
        self.write_origin(".github/qualification/source_lock.py", "# trusted resolver\n")
        self.write_origin("README.md", "trusted master\n")
        self._git(self.origin, "add", "-A")
        self._git(self.origin, "commit", "-q", "-m", "trusted master baseline")
        self.master = self.rev(self.origin, "master")

        # Candidate branch on top of master.  The commit must land on the
        # candidate branch, not on master's checked-out worktree.
        self._git(self.origin, "branch", "candidate")
        self.checkout("candidate")
        self.write_origin("README.md", "candidate change\n")
        self._git(self.origin, "add", "-A")
        self._git(self.origin, "commit", "-q", "-m", "candidate change")
        self.candidate = self.rev(self.origin, "candidate")
        self.checkout("master")

        self._git(root, "clone", "-q", str(self.origin), str(self.trusted))
        self._git(self.trusted, "config", "user.email", "d17@example.invalid")
        self._git(self.trusted, "config", "user.name", "D17 Fixture")

    # -- helpers ---------------------------------------------------------- #

    def _git(self, cwd: Path, *args: str) -> str:
        proc = subprocess.run(
            ["git", *args], cwd=str(cwd), capture_output=True, text=True, check=False
        )
        if proc.returncode != 0:
            raise AssertionError(
                "git {} failed in {}: {}".format(" ".join(args), cwd, proc.stderr.strip())
            )
        return proc.stdout.strip()

    def git(self, cwd: Path, *args: str) -> str:
        return self._git(cwd, *args)

    def rev(self, cwd: Path, ref: str) -> str:
        return self._git(cwd, "rev-parse", ref)

    def checkout(self, ref: str) -> None:
        self._git(self.origin, "checkout", "-q", ref)

    def write_origin(self, relpath: str, text: str) -> None:
        path = self.origin / relpath
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit_on_candidate(self, relpath: str, text: str, message: str) -> str:
        """Add a commit on the candidate branch and return its exact SHA."""
        self.checkout("candidate")
        path = self.origin / relpath
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        self._git(self.origin, "add", "-A")
        self._git(self.origin, "commit", "-q", "-m", message)
        sha = self.rev(self.origin, "candidate")
        self.checkout("master")
        self.candidate = sha
        return sha

    def commit_and_promote_master(self, relpath: str, text: str, message: str) -> str:
        """Commit on candidate and point master at it.

        Used to model a trusted default branch that no longer carries the
        qualification definition.
        """
        sha = self.commit_on_candidate(relpath, text, message)
        os.unlink(self.origin / QUALIFY_WORKFLOW)
        self.checkout("candidate")
        self._git(self.origin, "add", "-A")
        self._git(self.origin, "commit", "-q", "-m", "drop the qualification definition")
        sha = self.rev(self.origin, "candidate")
        self._git(self.origin, "branch", "-f", "master", sha)
        self.checkout("master")
        self.master = sha
        return sha


def build_lock(fixture: Fixture, **overrides) -> dict:
    kwargs = {
        "repo": fixture.trusted,
        "candidate_sha": fixture.candidate,
        "fetch_ref": "refs/heads/candidate",
    }
    kwargs.update(overrides)
    return source_lock.build_lock(**kwargs)


# --------------------------------------------------------------------------- #
# Evidence fixtures
# --------------------------------------------------------------------------- #


def write_report(
    root: Path,
    module: str,
    name: str,
    tests: int = 3,
    failures: int = 0,
    errors: int = 0,
    skipped: int = 0,
    extra_attrs: "dict[str, str] | None" = None,
    classname: "str | None" = None,
    emit_cases: bool = True,
) -> Path:
    """Write one candidate-produced surefire report under ``root``.

    ``emit_cases`` mirrors real surefire/TestNG output by writing one
    ``<testcase>`` per counted test.  Setting it False produces a report whose
    skip count cannot be attributed to any class.
    """
    directory = root / module / "target" / "surefire-reports"
    directory.mkdir(parents=True, exist_ok=True)
    attrs = {
        "name": "{}.{}".format(module, name),
        "tests": str(tests),
        "failures": str(failures),
        "errors": str(errors),
        "skipped": str(skipped),
    }
    attrs.update(extra_attrs or {})
    rendered = " ".join('{}="{}"'.format(key, value) for key, value in sorted(attrs.items()))

    cases = ""
    if emit_cases and tests > 0:
        owner = classname or "{}.{}".format(module, name)
        used = {"failure": 0, "error": 0, "skipped": 0}
        for index in range(tests):
            marker = ""
            if used["failure"] < failures:
                marker, used["failure"] = "<failure message=\"boom\"/>", used["failure"] + 1
            elif used["error"] < errors:
                marker, used["error"] = "<error message=\"boom\"/>", used["error"] + 1
            elif used["skipped"] < skipped:
                marker, used["skipped"] = "<skipped message=\"skipped\"/>", used["skipped"] + 1
            cases += '\n  <testcase classname="{}" name="case{}">{}</testcase>'.format(
                owner, index, marker
            )
    path = directory / ("TEST-{}.xml".format(name))
    path.write_text("<testsuite {}>{}\n</testsuite>\n".format(rendered, cases))
    return path


def write_raw_report(root: Path, module: str, name: str, body: str) -> Path:
    directory = root / module / "target" / "surefire-reports"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / ("TEST-{}.xml".format(name))
    path.write_text(body)
    return path


_DEFAULT = object()


def run_report(
    lock: dict,
    completed: bool = True,
    step_exit_codes: "dict | None" = _DEFAULT,
    candidate_sha: "str | None" = None,
    candidate_tree: "str | None" = None,
    extra: "dict | None" = None,
) -> dict:
    payload = {
        "schema": qualify.RUN_REPORT_SCHEMA,
        "candidate_sha": candidate_sha or lock["candidate"]["sha"],
        "candidate_tree": candidate_tree or lock["candidate"]["tree"],
        "maven_command": "xvfb-run -a mvn -B -pl forge-gui-desktop -am test",
        "completed": completed,
        "step_exit_codes": (
            {"materialize_candidate": 0, "bounded_test_surface": 0}
            if step_exit_codes is _DEFAULT
            else step_exit_codes
        ),
    }
    payload.update(extra or {})
    return payload


STRESS_CLASS = "forge.net.NetworkPlayIntegrationTest"


def clean_evidence_root(root: Path) -> Path:
    """A reports root with full, clean, complete evidence for both modules."""
    write_report(root, "forge-game", "AbilityKeyTest", tests=4)
    write_report(root, "forge-gui-desktop", "DeckEditorTest", tests=9)
    write_report(root, "forge-gui-desktop", "CardRulesTest", tests=6)
    return root


def qualification_of(evidence: dict) -> dict:
    """The exact-candidate-qualification block of an evidence document.

    Reading the verdict class back through this accessor keeps the controls
    honest about the evidence-class separation the gate promises.
    """
    block = evidence.get("exact_candidate_qualification")
    if not isinstance(block, dict):
        raise AssertionError("evidence has no exact_candidate_qualification block")
    return block


class FixtureCase(unittest.TestCase):
    """Base class providing a disposable Git fixture per test."""

    def setUp(self) -> None:
        self._tmp = tempfile.mkdtemp(prefix="forge-d17-selftest-")
        self.addCleanup(shutil.rmtree, self._tmp, True)
        self.root = Path(self._tmp)
        self.fixture = Fixture(self.root)
        self.evidence_root = self.root / "evidence"

    def verdict(
        self,
        lock: dict,
        report: dict,
        root: "Path | None" = None,
        expected: "list[str] | None" = None,
        out_of_band: "list[str] | None" = None,
    ) -> dict:
        return qualify.build_evidence(
            lock,
            report,
            root or self.evidence_root,
            expected if expected is not None else EXPECTED_MODULES,
            out_of_band or [],
        )

    def assertNotPass(self, evidence: dict, expected: str) -> None:
        """Assert the exact non-PASS class, not merely 'not PASS'."""
        self.assertEqual(
            evidence["verdict"],
            expected,
            "expected {} but got {} ({})".format(expected, evidence["verdict"], evidence["reason"]),
        )
        self.assertNotEqual(evidence["exit_code"], 0, "a non-PASS verdict must not exit 0")


# --------------------------------------------------------------------------- #
# POSITIVE controls
# --------------------------------------------------------------------------- #


class PositiveExactShaPath(FixtureCase):
    def test_exact_candidate_lock_binds_sha_tree_and_authority(self) -> None:
        lock = build_lock(self.fixture)
        self.assertEqual(lock["status"], "LOCKED")
        self.assertEqual(lock["candidate"]["sha"], self.fixture.candidate)
        self.assertEqual(lock["candidate"]["fetched_from"], "refs/heads/candidate")
        self.assertEqual(lock["workflow_authority"]["sha"], self.fixture.master)
        self.assertEqual(lock["run_identity"]["sha"], self.fixture.master)
        self.assertEqual(lock["run_identity"]["branch"], "master")
        # Candidate TREE is a real, distinct tree bound to the candidate SHA.
        self.assertRegex(lock["candidate"]["tree"], r"^[0-9a-f]{40}$")
        self.assertEqual(
            lock["candidate"]["tree"],
            self.fixture.rev(self.fixture.trusted, self.fixture.candidate + "^{tree}"),
        )
        self.assertEqual(lock["comparison_base"]["sha"], self.fixture.master)
        self.assertIs(lock["comparison_base"]["synthetic_merge_computed"], False)

    def test_explicit_sha_fetch_without_ref_also_binds_exactly(self) -> None:
        lock = build_lock(self.fixture, fetch_ref=None)
        self.assertEqual(lock["candidate"]["sha"], self.fixture.candidate)
        self.assertEqual(lock["candidate"]["fetched_from"], "explicit-sha")

    def test_clean_full_surface_evidence_yields_pass(self) -> None:
        lock = build_lock(self.fixture)
        clean_evidence_root(self.evidence_root)
        evidence = self.verdict(lock, run_report(lock))
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])
        self.assertEqual(evidence["exit_code"], 0)
        self.assertEqual(qualification_of(evidence)["counts"]["tests"], 19)
        self.assertEqual(qualification_of(evidence)["counts"]["failures"], 0)
        self.assertEqual(evidence["candidate"]["sha"], self.fixture.candidate)
        self.assertEqual(evidence["candidate"]["tree"], lock["candidate"]["tree"])
        self.assertEqual(evidence["source_lock"]["workflow_authority_sha"], self.fixture.master)

    def test_pass_declares_no_mergeability_or_rules_credit(self) -> None:
        lock = build_lock(self.fixture)
        clean_evidence_root(self.evidence_root)
        evidence = self.verdict(lock, run_report(lock))
        self.assertEqual(evidence["pr_mergeability"]["credit"], "none")
        self.assertEqual(evidence["synthetic_merge_evidence"]["status"], "NEVER_COMPUTED")
        self.assertEqual(evidence["synthetic_merge_evidence"]["credit"], "none")
        self.assertEqual(evidence["rules_qualification_evidence"]["status"], "NOT_CLAIMED")
        self.assertEqual(evidence["trust_boundary"]["verdict_read_from_candidate"], False)
        self.assertEqual(evidence["exact_candidate_qualification"]["verdict"], evidence["verdict"])


# --------------------------------------------------------------------------- #
# RED controls - identity and authority
# --------------------------------------------------------------------------- #


class RedIdentityAndAuthority(FixtureCase):
    def test_ref_resolving_to_a_different_sha_fails_closed(self) -> None:
        """No silent substitution when the ref and the declared SHA disagree."""
        original = self.fixture.candidate
        other = self.fixture.commit_on_candidate("other.txt", "other\n", "other")
        self.assertNotEqual(other, original)
        # The ref now resolves to `other`; declaring `original` must not be
        # silently satisfied by the fetched object.
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, candidate_sha=original, fetch_ref="refs/heads/candidate")
        self.assertIn("candidate identity mismatch", str(caught.exception))

    def test_synthetic_merge_ref_is_refused(self) -> None:
        """refs/pull/<n>/merge is GitHub's synthetic merge: never a candidate."""
        for forbidden in ("refs/pull/17/merge", "refs/pull/17/head~1", "MERGE_HEAD", "master"):
            with self.subTest(ref=forbidden):
                with self.assertRaises(source_lock.SourceLockError) as caught:
                    build_lock(self.fixture, fetch_ref=forbidden)
                self.assertIn("refusing to fetch unqualified ref", str(caught.exception))

    def test_non_exact_candidate_identity_is_refused(self) -> None:
        """Each malformed identity must be refused *as an identity*.

        The assertion is on the specific rejection reason, not merely on some
        error, so this control cannot pass for the wrong reason (for example
        because a later fetch happened to fail instead).
        """
        cases = {
            "abbreviated": self.fixture.candidate[:12],
            "uppercase": self.fixture.candidate.upper(),
            "ref_name": "refs/heads/candidate",
            "empty": "",
            "none": None,
            "whitespace": " " + self.fixture.candidate,
            "too_long": self.fixture.candidate + "0",
        }
        for label, bad in cases.items():
            with self.subTest(candidate=label):
                with self.assertRaises(source_lock.SourceLockError) as caught:
                    build_lock(self.fixture, candidate_sha=bad)
                self.assertIn(
                    "not a full 40-hex sha",
                    str(caught.exception),
                    "refusal for {!r} was not an identity refusal: {}".format(
                        bad, caught.exception
                    ),
                )

    def test_unknown_candidate_object_fails_closed(self) -> None:
        """Neither an explicit SHA nor a ref may resolve an absent object."""
        missing = "0" * 40
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, candidate_sha=missing, fetch_ref=None)
        self.assertIn("is not present in the trusted repository", str(caught.exception))
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, candidate_sha=missing, fetch_ref="refs/heads/candidate")
        self.assertIn("candidate identity mismatch", str(caught.exception))

    def test_non_trusted_authority_branch_is_refused(self) -> None:
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, branch="candidate")
        self.assertIn("trusted authority branch must be", str(caught.exception))

    def test_running_the_definition_off_the_default_branch_fails_closed(self) -> None:
        """Executing the candidate's copy of the definition is not authority."""
        self.fixture.git(self.fixture.trusted, "checkout", "-q", "-b", "candidate-copy")
        try:
            with self.assertRaises(source_lock.SourceLockError) as caught:
                build_lock(self.fixture)
            self.assertIn("trusted default branch", str(caught.exception))
        finally:
            self.fixture.git(self.fixture.trusted, "checkout", "-q", "master")

    def test_detached_run_head_fails_closed(self) -> None:
        self.fixture.git(self.fixture.trusted, "checkout", "-q", "--detach", self.fixture.master)
        try:
            with self.assertRaises(source_lock.SourceLockError) as caught:
                build_lock(self.fixture)
            self.assertIn("trusted default branch", str(caught.exception))
        finally:
            self.fixture.git(self.fixture.trusted, "checkout", "-q", "master")

    def test_declared_run_sha_disagreeing_with_trusted_head_fails_closed(self) -> None:
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, declared_run_sha=self.fixture.candidate)
        self.assertIn("does not match executing trusted HEAD", str(caught.exception))

    def test_event_base_unrelated_to_authority_fails_closed(self) -> None:
        unrelated = self.fixture.commit_on_candidate("unrelated.txt", "x\n", "unrelated")
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, event_base_sha=unrelated)
        self.assertIn("unrelated to trusted authority", str(caught.exception))

    def test_event_base_ancestor_of_authority_is_recorded_not_rejected(self) -> None:
        lock = build_lock(self.fixture, event_base_sha=self.fixture.master)
        self.assertEqual(lock["event_base"]["relation"], "EQUAL")

    def test_trusted_master_missing_the_definition_is_not_authority(self) -> None:
        """Without the definition on master, nothing may claim qualification."""
        master = self.fixture.commit_and_promote_master(
            "README.md", "master without the definition\n", "advance master"
        )
        self.fixture.git(self.fixture.trusted, "fetch", "-q", "origin")
        self.fixture.git(self.fixture.trusted, "checkout", "-q", "-B", "master", "origin/master")
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, candidate_sha=self.fixture.candidate)
        self.assertIn("does not carry", str(caught.exception))
        self.assertIn(master, str(caught.exception))


class RedLockValidation(FixtureCase):
    """A lock document that overstates trust must be rejected on load."""

    def _valid_lock(self) -> dict:
        return build_lock(self.fixture)

    def _expect_rejected(self, mutate) -> None:
        lock = self._valid_lock()
        mutate(lock)
        with self.assertRaises(source_lock.SourceLockError):
            source_lock.validate_lock(lock)

    def test_verdict_read_from_candidate_assertion_is_rejected(self) -> None:
        self._expect_rejected(lambda lock: lock.update(verdict_read_from_candidate=True))

    def test_candidate_as_validator_assertion_is_rejected(self) -> None:
        self._expect_rejected(lambda lock: lock.update(candidate_code_executed_as_validator=True))

    def test_synthetic_merge_consultation_is_rejected(self) -> None:
        self._expect_rejected(lambda lock: lock.update(synthetic_merge_consulted=True))

    def test_mergeability_consultation_is_rejected(self) -> None:
        self._expect_rejected(lambda lock: lock.update(pr_mergeability_consulted=True))

    def test_comparison_base_claiming_a_synthetic_merge_is_rejected(self) -> None:
        self._expect_rejected(
            lambda lock: lock["comparison_base"].update(synthetic_merge_computed=True)
        )

    def test_comparison_base_claiming_mergeability_consultation_is_rejected(self) -> None:
        self._expect_rejected(
            lambda lock: lock["comparison_base"].update(pr_mergeability_consulted=True)
        )

    def test_candidate_fetched_from_a_ref_must_be_recorded(self) -> None:
        """The provenance of the candidate object is itself part of the lock."""
        self._expect_rejected(lambda lock: lock["candidate"].pop("fetched_from"))

    def test_self_referential_candidate_is_rejected(self) -> None:
        self._expect_rejected(
            lambda lock: lock["candidate"].update(
                sha=lock["workflow_authority"]["sha"], tree=lock["workflow_authority"]["tree"]
            )
        )

    def test_comparison_base_equal_to_candidate_is_rejected(self) -> None:
        self._expect_rejected(
            lambda lock: lock["comparison_base"].update(
                sha=lock["candidate"]["sha"], tree=lock["candidate"]["tree"]
            )
        )

    def test_run_identity_detached_from_authority_is_rejected(self) -> None:
        self._expect_rejected(lambda lock: lock["run_identity"].update(sha="1" * 40))

    def test_non_locked_status_is_rejected(self) -> None:
        self._expect_rejected(lambda lock: lock.update(status="PARTIAL"))

    def test_missing_divergence_record_is_rejected(self) -> None:
        self._expect_rejected(lambda lock: lock.pop("candidate_definition_divergence"))

    def test_wrong_schema_is_rejected(self) -> None:
        self._expect_rejected(lambda lock: lock.update(schema="somebody-elses-schema/9"))

    def test_non_lock_document_is_rejected(self) -> None:
        for bad in ([], "LOCKED", None, 7, {"status": "LOCKED"}):
            with self.subTest(lock=bad):
                with self.assertRaises(source_lock.SourceLockError):
                    source_lock.validate_lock(bad)

    def test_missing_lock_file_is_rejected(self) -> None:
        with self.assertRaises(source_lock.SourceLockError):
            source_lock.load_lock(self.root / "no-such-lock.json")


# --------------------------------------------------------------------------- #
# RED controls - candidate definition trivialization
# --------------------------------------------------------------------------- #


class RedCandidateDefinitionTrivialization(FixtureCase):
    def test_candidate_editing_the_definition_is_recorded_as_divergent(self) -> None:
        """A candidate that weakens the definition cannot do so invisibly."""
        candidate = self.fixture.commit_on_candidate(
            QUALIFY_WORKFLOW,
            WORKFLOW_STUB.replace("echo trusted", "echo trivialized\n"),
            "trivialize the definition",
        )
        self.fixture.git(self.fixture.trusted, "fetch", "-q", "origin")
        lock = build_lock(self.fixture, candidate_sha=candidate, fetch_ref="refs/heads/candidate")
        divergence = lock["candidate_definition_divergence"]
        self.assertTrue(divergence["divergent"])
        self.assertNotEqual(divergence["trusted_blob"], divergence["candidate_blob"])
        # The executed authority is still the trusted default branch blob.
        self.assertEqual(lock["workflow_authority"]["sha"], self.fixture.master)
        self.assertEqual(
            divergence["trusted_blob"],
            source_lock.definition_digest(
                self.fixture.trusted, self.fixture.master, QUALIFY_WORKFLOW
            ),
        )
        # And the divergence is carried into the emitted evidence.
        clean_evidence_root(self.evidence_root)
        evidence = self.verdict(lock, run_report(lock))
        self.assertTrue(evidence["trust_boundary"]["candidate_definition_divergent"])
        self.assertEqual(evidence["trust_boundary"]["verdict_read_from_candidate"], False)

    def test_candidate_deleting_the_definition_is_recorded_as_divergent(self) -> None:
        candidate = self.fixture.commit_on_candidate(
            QUALIFY_WORKFLOW, "name: trivialized\n", "replace the definition"
        )
        self.fixture.git(self.fixture.trusted, "fetch", "-q", "origin")
        lock = build_lock(self.fixture, candidate_sha=candidate, fetch_ref="refs/heads/candidate")
        self.assertTrue(lock["candidate_definition_divergence"]["divergent"])

    def test_unchanged_definition_is_not_divergent(self) -> None:
        lock = build_lock(self.fixture)
        self.assertFalse(lock["candidate_definition_divergence"]["divergent"])


# --------------------------------------------------------------------------- #
# RED controls - verdict derivation
# --------------------------------------------------------------------------- #


class RedVerdictDerivation(FixtureCase):
    def setUp(self) -> None:
        super().setUp()
        self.lock = build_lock(self.fixture)

    def test_run_report_claiming_another_candidate_is_fail(self) -> None:
        clean_evidence_root(self.evidence_root)
        evidence = self.verdict(self.lock, run_report(self.lock, candidate_sha="a" * 40))
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("candidate identity is not bound", evidence["reason"])

    def test_run_report_claiming_another_tree_is_fail(self) -> None:
        clean_evidence_root(self.evidence_root)
        evidence = self.verdict(self.lock, run_report(self.lock, candidate_tree="b" * 40))
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("candidate identity is not bound", evidence["reason"])

    def test_forged_clean_run_report_over_failing_tests_is_fail(self) -> None:
        """Step outcomes are trusted signals; failing counts still dominate."""
        clean_evidence_root(self.evidence_root)
        write_report(self.evidence_root, "forge-game", "BrokenTest", tests=2, failures=2)
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertEqual(qualification_of(evidence)["counts"]["failures"], 2)

    def test_nonzero_trusted_step_is_fail(self) -> None:
        clean_evidence_root(self.evidence_root)
        evidence = self.verdict(
            self.lock,
            run_report(self.lock, step_exit_codes={"bounded_test_surface": 1}),
        )
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("nonzero exit code", evidence["reason"])

    def test_incomplete_run_is_fail(self) -> None:
        clean_evidence_root(self.evidence_root)
        evidence = self.verdict(self.lock, run_report(self.lock, completed=False))
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("did not complete", evidence["reason"])

    def test_absent_step_outcomes_are_fail_not_unknown(self) -> None:
        clean_evidence_root(self.evidence_root)
        for codes in ({}, None, {"x": "0"}, {"x": True}, "0"):
            with self.subTest(step_exit_codes=codes):
                evidence = self.verdict(self.lock, run_report(self.lock, step_exit_codes=codes))
                self.assertNotPass(evidence, qualify.FAIL)

    def test_green_command_with_no_reports_is_not_run(self) -> None:
        """A claimed-green command that produced no evidence proves nothing."""
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.NOT_RUN)
        self.assertEqual(evidence["exit_code"], 3)

    def test_missing_reports_root_directory_is_not_run(self) -> None:
        evidence = self.verdict(self.lock, run_report(self.lock), root=self.root / "absent")
        self.assertNotPass(evidence, qualify.NOT_RUN)

    def test_malformed_report_is_unknown(self) -> None:
        clean_evidence_root(self.evidence_root)
        write_raw_report(self.evidence_root, "forge-game", "Truncated", '<testsuite tests="3"')
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.UNKNOWN)
        self.assertEqual(evidence["exit_code"], 4)
        self.assertTrue(qualification_of(evidence)["report_problems"])

    def test_wrong_root_element_is_unknown(self) -> None:
        clean_evidence_root(self.evidence_root)
        write_raw_report(self.evidence_root, "forge-game", "WrongRoot", "<results tests='3'/>")
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_non_integer_counts_are_unknown(self) -> None:
        clean_evidence_root(self.evidence_root)
        write_raw_report(
            self.evidence_root,
            "forge-game",
            "BadCounts",
            '<testsuite tests="lots" failures="0" errors="0" skipped="0"/>',
        )
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_zero_executed_tests_is_unknown(self) -> None:
        reports = self.evidence_root / "forge-game" / "target" / "surefire-reports"
        if reports.exists():
            shutil.rmtree(reports)
        write_report(self.evidence_root, "forge-game", "EmptyTest", tests=0)
        write_report(self.evidence_root, "forge-gui-desktop", "AlsoEmpty", tests=0)
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_missing_expected_module_is_partial(self) -> None:
        write_report(self.evidence_root, "forge-game", "AbilityKeyTest", tests=4)
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.PARTIAL)
        self.assertEqual(evidence["exit_code"], 2)
        self.assertEqual(qualification_of(evidence)["missing_modules"], ["forge-gui-desktop"])

    def test_skipped_cases_are_partial_not_pass(self) -> None:
        clean_evidence_root(self.evidence_root)
        write_report(
            self.evidence_root,
            "forge-gui-desktop",
            "NetworkPlay",
            tests=5,
            skipped=5,
            classname=STRESS_CLASS,
        )
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.PARTIAL)
        self.assertEqual(qualification_of(evidence)["counts"]["skipped"], 5)
        self.assertEqual(
            qualification_of(evidence)["skips"]["undeclared_skipped_classes"], [STRESS_CLASS]
        )

    def test_declared_out_of_band_skips_do_not_make_the_surface_partial(self) -> None:
        """An explicitly declared opt-in surface is excluded, and only it."""
        clean_evidence_root(self.evidence_root)
        write_report(
            self.evidence_root,
            "forge-gui-desktop",
            "NetworkPlay",
            tests=6,
            skipped=6,
            classname=STRESS_CLASS,
        )
        evidence = self.verdict(self.lock, run_report(self.lock), out_of_band=[STRESS_CLASS])
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])
        skips = qualification_of(evidence)["skips"]
        self.assertEqual(skips["declared_out_of_band_classes"], [STRESS_CLASS])
        self.assertEqual(skips["observed_skipped_classes"], [STRESS_CLASS])
        self.assertEqual(skips["undeclared_skipped_classes"], [])
        self.assertEqual(skips["skipped_total"], 6)

    def test_out_of_band_declaration_cannot_hide_other_skips(self) -> None:
        clean_evidence_root(self.evidence_root)
        write_report(
            self.evidence_root,
            "forge-gui-desktop",
            "NetworkPlay",
            tests=6,
            skipped=6,
            classname=STRESS_CLASS,
        )
        write_report(
            self.evidence_root,
            "forge-gui-desktop",
            "RulesCoverage",
            tests=3,
            skipped=3,
            classname="forge.game.rule.RulesCoverageTest",
        )
        evidence = self.verdict(self.lock, run_report(self.lock), out_of_band=[STRESS_CLASS])
        self.assertNotPass(evidence, qualify.PARTIAL)
        self.assertEqual(
            qualification_of(evidence)["skips"]["undeclared_skipped_classes"],
            ["forge.game.rule.RulesCoverageTest"],
        )

    def test_unattributable_skips_are_never_excluded(self) -> None:
        """A skip count with no class attribution cannot claim an exemption."""
        clean_evidence_root(self.evidence_root)
        write_report(
            self.evidence_root,
            "forge-gui-desktop",
            "Opaque",
            tests=4,
            skipped=4,
            classname=STRESS_CLASS,
            emit_cases=False,
        )
        evidence = self.verdict(self.lock, run_report(self.lock), out_of_band=[STRESS_CLASS])
        self.assertNotPass(evidence, qualify.PARTIAL)
        skips = qualification_of(evidence)["skips"]
        self.assertTrue(skips["unattributable_skips"])
        self.assertEqual(skips["undeclared_skipped_classes"], ["<unattributable>"])

    def test_errors_are_fail(self) -> None:
        clean_evidence_root(self.evidence_root)
        write_report(self.evidence_root, "forge-game", "ErrTest", tests=1, errors=1)
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.FAIL)

    def test_reports_outside_the_bounded_shape_are_not_evidence(self) -> None:
        """Candidate XML elsewhere in the tree cannot pose as qualification."""
        clean_evidence_root(self.evidence_root)
        stray = self.evidence_root / "forge-game" / "TEST-stray.xml"
        stray.parent.mkdir(parents=True, exist_ok=True)
        stray.write_text('<testsuite name="stray" tests="99" failures="0" errors="0" skipped="0"/>')
        nested = self.evidence_root / "forge-game" / "target" / "other" / "TEST-x.xml"
        nested.parent.mkdir(parents=True, exist_ok=True)
        nested.write_text('<testsuite name="x" tests="99" failures="0" errors="0" skipped="0"/>')
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])
        self.assertEqual(qualification_of(evidence)["counts"]["tests"], 19)


# --------------------------------------------------------------------------- #
# RED controls - candidate self-qualification
# --------------------------------------------------------------------------- #


class RedCandidateSelfQualification(FixtureCase):
    def setUp(self) -> None:
        super().setUp()
        self.lock = build_lock(self.fixture)

    def test_fabricated_verdict_attributes_in_a_report_are_discarded(self) -> None:
        """`verdict="PASS"` in candidate XML is data, not a verdict."""
        write_report(
            self.evidence_root,
            "forge-game",
            "ForgedTest",
            tests=0,
            extra_attrs={
                "verdict": "PASS",
                "status": "success",
                "result": "ok",
                "outcome": "passed",
                "passed": "true",
                "success": "1",
            },
        )
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.UNKNOWN)
        ignored = evidence["trust_boundary"]["ignored_candidate_verdict_keys"]
        self.assertEqual(
            sorted(ignored), ["outcome", "passed", "result", "status", "success", "verdict"]
        )

    def test_forged_report_with_many_tests_and_no_execution_is_not_pass(self) -> None:
        """Counting claimed cases without a completed run stays FAIL."""
        write_report(self.evidence_root, "forge-game", "ForgedA", tests=500)
        write_report(self.evidence_root, "forge-gui-desktop", "ForgedB", tests=500)
        evidence = self.verdict(
            self.lock,
            run_report(self.lock, completed=False, step_exit_codes={"bounded_test_surface": 0}),
        )
        self.assertNotPass(evidence, qualify.FAIL)

    def test_candidate_supplied_run_report_is_not_read(self) -> None:
        """The verifier reads only the trusted run report, never the tree's."""
        clean_evidence_root(self.evidence_root)
        forged = self.evidence_root / "run-report.json"
        forged.write_text(json.dumps({"verdict": "PASS", "completed": True, "schema": "x"}))
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])
        self.assertFalse(evidence["trust_boundary"]["verdict_read_from_candidate"])
        # The forged document is present in the candidate tree and ignored.
        self.assertTrue(forged.is_file())

    def test_candidate_supplied_verdict_document_cannot_promote_nothing_run(self) -> None:
        """A candidate-shipped PASS document does not become the verdict."""
        payload = self.evidence_root / "FORGE_CANDIDATE_QUALIFICATION.json"
        payload.parent.mkdir(parents=True, exist_ok=True)
        payload.write_text(json.dumps({"schema": qualify.SCHEMA, "verdict": "PASS", "exit_code": 0}))
        evidence = self.verdict(self.lock, run_report(self.lock))
        self.assertNotPass(evidence, qualify.NOT_RUN)
        self.assertNotEqual(evidence["verdict"], qualify.PASS)

    def test_mergeability_claims_cannot_promote_incomplete_evidence(self) -> None:
        """Synthetic merge / mergeable state carries zero qualification credit."""
        evidence = self.verdict(
            self.lock,
            run_report(
                self.lock,
                extra={
                    "pr_mergeable": True,
                    "mergeable_state": "CLEAN",
                    "synthetic_merge_state": "CLEAN",
                    "mergeStateStatus": "CLEAN",
                    "verdict": "PASS",
                },
            ),
        )
        self.assertNotPass(evidence, qualify.NOT_RUN)
        self.assertEqual(evidence["pr_mergeability"]["status"], "NOT_APPLICABLE")
        self.assertEqual(evidence["synthetic_merge_evidence"]["status"], "NEVER_COMPUTED")

    def test_mergeability_claims_do_not_rescue_a_missing_module(self) -> None:
        write_report(self.evidence_root, "forge-game", "OnlyThis", tests=3)
        evidence = self.verdict(
            self.lock,
            run_report(
                self.lock,
                extra={"pr_mergeable": True, "synthetic_merge_state": "CLEAN", "verdict": "PASS"},
            ),
        )
        self.assertNotPass(evidence, qualify.PARTIAL)

    def test_verdict_cannot_be_widened_by_a_report_with_a_skipped_suite(self) -> None:
        clean_evidence_root(self.evidence_root)
        write_report(
            self.evidence_root,
            "forge-gui-desktop",
            "AllSkipped",
            tests=40,
            skipped=40,
            classname="forge.game.rule.AllSkippedTest",
        )
        evidence = self.verdict(
            self.lock, run_report(self.lock), out_of_band=[STRESS_CLASS]
        )
        self.assertNotPass(evidence, qualify.PARTIAL)

    def test_derive_verdict_is_pure_and_candidate_data_cannot_reach_the_verdict(self) -> None:
        """Two identical trusted inputs give identical verdicts regardless of
        any candidate-authored content in the reports root."""
        clean_evidence_root(self.evidence_root)
        report = run_report(self.lock)
        first = qualify.build_evidence(self.lock, report, self.evidence_root, EXPECTED_MODULES)
        second = qualify.build_evidence(self.lock, report, self.evidence_root, EXPECTED_MODULES)
        self.assertEqual(first["verdict"], second["verdict"])
        self.assertEqual(first["exit_code"], second["exit_code"])

    def test_trusted_inputs_that_do_not_validate_produce_fail_evidence(self) -> None:
        broken = dict(self.lock)
        broken["verdict_read_from_candidate"] = True
        lock_path = self.root / "broken-lock.json"
        lock_path.write_text(json.dumps(broken))
        run_path = self.root / "run.json"
        run_path.write_text(json.dumps(run_report(self.lock)))
        out = self.root / "evidence-out" / "qual.json"
        code = qualify.main(
            [
                "--lock", str(lock_path),
                "--run-report", str(run_path),
                "--reports-root", str(self.evidence_root),
                "--expect-report-module", "forge-game",
                "--expect-report-module", "forge-gui-desktop",
                "--output", str(out),
            ]
        )
        self.assertEqual(code, qualify.EXIT_CODES[qualify.FAIL])
        self.assertNotEqual(code, 0)
        emitted = json.loads(out.read_text())
        self.assertEqual(emitted["verdict"], qualify.FAIL)
        self.assertEqual(emitted["rules_qualification_evidence"]["status"], "NOT_CLAIMED")

    def test_cli_exit_codes_separate_every_verdict_class(self) -> None:
        """End-to-end CLI wiring: only PASS is 0, every class is distinguishable."""
        cases = [
            ("pass", qualify.PASS, 0),
            ("fail", qualify.FAIL, 1),
            ("partial", qualify.PARTIAL, 2),
            ("not_run", qualify.NOT_RUN, 3),
            ("unknown", qualify.UNKNOWN, 4),
        ]
        for label, _verdict, expected_code in cases:
            with self.subTest(case=label):
                root = self.root / ("cli-" + label)
                root.mkdir()
                if label == "pass":
                    clean_evidence_root(root)
                elif label == "fail":
                    clean_evidence_root(root)
                    write_report(root, "forge-game", "Boom", tests=1, failures=1)
                elif label == "partial":
                    # Only one expected module reports.
                    write_report(root, "forge-game", "OnlyThis", tests=3)
                elif label == "unknown":
                    clean_evidence_root(root)
                    write_raw_report(root, "forge-game", "Truncated", '<testsuite tests="1"')
                # "not_run" writes no reports at all.
                lock_path = self.root / "cli-lock.json"
                lock_path.write_text(json.dumps(self.lock))
                run_path = self.root / ("cli-run-%s.json" % label)
                run_path.write_text(json.dumps(run_report(self.lock)))
                out = self.root / ("cli-evidence-%s.json" % label)
                code = qualify.main(
                    [
                        "--lock", str(lock_path),
                        "--run-report", str(run_path),
                        "--reports-root", str(root),
                        "--expect-report-module", "forge-game",
                        "--expect-report-module", "forge-gui-desktop",
                        "--output", str(out),
                    ]
                )
                emitted = json.loads(out.read_text())
                self.assertEqual(emitted["verdict"], _verdict, emitted["reason"])
                self.assertEqual(code, expected_code, "{} -> {}".format(label, emitted["reason"]))


# --------------------------------------------------------------------------- #
# Source-lock CLI control
# --------------------------------------------------------------------------- #


class SourceLockCli(FixtureCase):
    def test_cli_writes_a_valid_lock_for_the_exact_candidate(self) -> None:
        out = self.root / "source-lock.json"
        code = source_lock.main(
            [
                "--repo", str(self.fixture.trusted),
                "--candidate-sha", self.fixture.candidate,
                "--fetch-ref", "refs/heads/candidate",
                "--run-sha", self.fixture.master,
                "--output", str(out),
            ]
        )
        self.assertEqual(code, 0)
        self.assertEqual(source_lock.load_lock(out)["candidate"]["sha"], self.fixture.candidate)

    def test_cli_writes_nothing_when_identity_is_unproven(self) -> None:
        out = self.root / "unproven-lock.json"
        code = source_lock.main(
            [
                "--repo", str(self.fixture.trusted),
                "--candidate-sha", "0" * 40,
                "--output", str(out),
            ]
        )
        self.assertEqual(code, 1)
        self.assertFalse(out.exists(), "no partially proven lock may be emitted")


class WorkflowContractControls(unittest.TestCase):
    """Assert the trust-critical properties of the trusted workflow definition.

    These are executable assertions rather than prose, because each one protects
    a property that is invisible from the Python verifier alone: if the workflow
    stops triggering on the trusted event, gains a default write token, or stops
    uploading a declared module's evidence, the gate silently stops qualifying
    anything.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow_path = HERE.parent / "workflows" / "forge-candidate-qualification.yml"
        cls.text = cls.workflow_path.read_text()
        match = re.search(
            r'^\s*QUALIFY_EXPECTED_MODULES:\s*"([^"]+)"', cls.text, re.MULTILINE
        )
        assert match, "QUALIFY_EXPECTED_MODULES is not declared in the workflow"
        cls.modules = match.group(1).split()
        out_of_band = re.search(
            r'^\s*QUALIFY_OUT_OF_BAND_CLASSES:\s*"([^"]*)"', cls.text, re.MULTILINE
        )
        assert out_of_band, "QUALIFY_OUT_OF_BAND_CLASSES is not declared in the workflow"
        cls.out_of_band = out_of_band.group(1).split()

    @staticmethod
    def _step_run_block(text: str, step_name: str) -> str:
        """Extract the ``run:`` body of the named step.

        Assertions must be made against the executed command, never against a
        substring that a comment could satisfy.  The body is taken by
        indentation relative to its own ``run:`` key, so it is independent of how
        deeply the step happens to be nested.
        """
        lines = text.splitlines()
        try:
            start = next(
                i for i, line in enumerate(lines) if line.strip() == "- name: {}".format(step_name)
            )
        except StopIteration:
            raise AssertionError("step {!r} not found".format(step_name))
        run_index = None
        for index in range(start + 1, len(lines)):
            stripped = lines[index].strip()
            if stripped.startswith("- ") or stripped.startswith("#"):
                break
            if stripped == "run:" or re.match(r"^run:\s*[|>][-+]?[0-9]*$", stripped):
                run_index = index
                break
        if run_index is None:
            raise AssertionError("step {!r} has no run: block".format(step_name))
        indent = len(lines[run_index]) - len(lines[run_index].lstrip())
        body = []
        for line in lines[run_index + 1:]:
            if not line.strip():
                body.append("")
                continue
            if len(line) - len(line.lstrip()) <= indent:
                break
            body.append(line.strip())
        return "\n".join(body).strip()

    def test_verdict_is_surfaced_verbatim_and_never_hardcoded(self) -> None:
        """The recorded verdict must be the verifier's own exit code.

        A hardcoded `exit_code=0` would turn every non-PASS class green, which is
        the single most dangerous edit to this workflow.
        """
        verdict = self._step_run_block(self.text, "Derive the exact-SHA qualification verdict")
        self.assertIn("exit_code=$rc", verdict)
        for forbidden in ("exit_code=0", "exit_code=1", "|| true", "|| exit 0", "exit 0\n"):
            with self.subTest(token=forbidden.strip()):
                self.assertNotIn(forbidden, verdict)
        # The python invocation must not be masked either.
        self.assertNotRegex(verdict, r"qualify\.py[^\n]*\|\|")

        surface = self._step_run_block(self.text, "Surface classification")
        self.assertIn('exit "$EXIT_CODE"', surface)
        self.assertIn('if: always()', self.text)
        # An absent exit code must fail closed rather than default to success.
        self.assertRegex(surface, r'if \[\[ -z "\$EXIT_CODE" \]\][\s\S]{0,200}exit 4')
        self.assertNotIn('echo "exit_code=0"', self.text)

    def test_expected_surface_is_declared_and_not_empty(self) -> None:
        self.assertTrue(self.modules)
        for module in self.modules:
            self.assertRegex(module, r"^[a-z0-9][a-z0-9-]*$")

    def test_every_declared_module_is_uploaded_as_evidence(self) -> None:
        """A declared module that is not uploaded can never report evidence.

        This is a real failure mode: the module would silently read as missing
        on every run. It is asserted here so the two lists cannot drift.
        """
        for module in self.modules:
            with self.subTest(module=module):
                self.assertIn(
                    "/candidate/{}/target/surefire-reports".format(module),
                    self.text,
                    "module {} is declared but never uploaded".format(module),
                )

    def test_qualification_runs_the_same_surface_as_the_existing_gate(self) -> None:
        """D17 must not quietly narrow or diverge from test-build.yaml.

        The comparison is made against the executed command of each gate's own
        run step, so a comment that merely mentions the command cannot satisfy
        this control.
        """
        ours = self._step_run_block(self.text, "Run bounded candidate test surface")
        self.assertIn("mvn -U -B clean test", ours)
        # No module selection or `-am` narrowing: the whole reactor must run.
        self.assertNotIn("-pl ", ours)
        self.assertNotIn("--settings", ours)

        existing_text = (HERE.parent / "workflows" / "test-build.yaml").read_text()
        existing = self._step_run_block(existing_text, "Run tests in virtual framebuffer")
        self.assertIn("mvn -U -B clean test", existing)
        self.assertEqual(
            ours.split()[-3:],
            existing.split()[-3:],
            "D17 and test-build.yaml must invoke the same maven command",
        )

    def test_trigger_is_the_trusted_event_not_the_untrusted_one(self) -> None:
        self.assertIn("pull_request_target:", self.text)
        # A bare `pull_request:` trigger would let the candidate supply the
        # definition that qualifies it.
        self.assertIsNone(
            re.search(r"^\s{2}pull_request:\s*$", self.text, re.MULTILINE),
            "an untrusted pull_request trigger is present",
        )

    def test_no_default_token_capability(self) -> None:
        self.assertIsNotNone(
            re.search(r"^permissions:\s*\{\}\s*$", self.text, re.MULTILINE),
            "top-level permissions must be an explicit empty map",
        )
        for forbidden in ("write-all", "contents: write", "actions: write"):
            with self.subTest(capability=forbidden):
                self.assertNotIn(forbidden, self.text)

    def test_no_continue_on_error(self) -> None:
        self.assertNotIn("continue-on-error", self.text)

    def test_out_of_band_declarations_are_explicit_and_well_formed(self) -> None:
        self.assertTrue(self.out_of_band, "out-of-band list must be explicit, not implicit")
        for klass in self.out_of_band:
            self.assertRegex(klass, r"^[a-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")

    def test_verifier_is_read_from_the_trusted_checkout(self) -> None:
        """The verifier is invoked from the checkout, never from the candidate."""
        self.assertIn(".github/qualification/qualify.py", self.text)
        self.assertIn(".github/qualification/source_lock.py", self.text)
        # The verdict step must run before any `cd` into the candidate tree.
        self.assertNotRegex(self.text, r"cd \"\$RUNNER_TEMP/candidate\"[\s\S]{0,400}qualify\.py")


if __name__ == "__main__":
    unittest.main(verbosity=2)