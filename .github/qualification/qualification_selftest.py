#!/usr/bin/env python3
"""Red and positive controls for the Forge exact-SHA candidate qualification.

This suite is the qualification's own proof that it cannot be trivialized. It is
stdlib-only (``unittest``) because Forge has no Python test infrastructure, and it
builds real Git repositories in temporary directories so the controls exercise the
same ``git`` calls the workflow makes rather than a mock.

Evidence model under test
-------------------------
Credit derives from two trusted ledgers only:

``required surface``
    what the trusted comparison base would execute (TestNG ``-dryrun``);
``execution manifest`` + ``witness/*.witness.jsonl``
    what the trusted orchestrator observed the candidate actually dispatch.

Candidate-authored build artifacts -- Surefire/TestNG XML, generated, copied,
renamed or committed reports -- carry **no** credit. The ``RedForgedCandidate
Artifacts`` and ``Adversarial`` classes prove that directly.

Control classes
---------------
POSITIVE  the exact-SHA path resolves, binds, and yields PASS from honest
          witnessed execution.
RED       a wrong or trivialized candidate state must fail closed. Every RED
          control asserts a *specific non-PASS class*, never merely "not PASS",
          so a control cannot pass for the wrong reason.
"""

from __future__ import annotations

import hashlib
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
import trusted_execution  # noqa: E402

QUALIFY_WORKFLOW = ".github/workflows/forge-candidate-qualification.yml"
EXPECTED_MODULES = ["forge-game", "forge-gui-desktop"]
STRESS_CLASS = "forge.net.NetworkPlayIntegrationTest"
WITNESS_CLASS = "forge.d17.witness.QualifiedExecutionListener"

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

        # Candidate branch on top of master.
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
        """Commit on candidate and point master at it (drops the definition)."""
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
# Trusted-evidence fixtures
# --------------------------------------------------------------------------- #

NONCE = "0123456789abcdef0123456789abcdef"


def required_surface(modules=None, totals=None, classes=None, base=None, class_counts=None):
    """A trusted required-surface ledger as produced from the comparison base."""
    modules = modules or EXPECTED_MODULES
    totals = totals or {module: 3 for module in modules}
    classes = classes or {module: ["pkg.C{}".format(i) for i in range(2)] for module in modules}
    # Default floor is liveness (1); the per-class floor controls set it explicitly.
    class_counts = class_counts or {
        module: {name: 1 for name in classes[module]} for module in modules
    }
    return {
        "schema": qualify.REQUIRED_SURFACE_SCHEMA,
        "comparison_base_sha": base or "b" * 40,
        "comparison_base_tree": "c" * 40,
        "trusted_argline": ["--add-opens", "java.base/java.lang=ALL-UNNAMED"],
        "denominator_source": "TestNG -dryrun over the trusted comparison base",
        "coverage_gaps": {
            "source_test_obligation_classes_not_executed": [],
            "source_test_annotation_inventory": {},
            "d24_framework_not_run_classes": [],
            "d24_framework_blocker_bases": [],
            "d24_framework_not_run_source_inventory": {},
            "d24_enabled_source_methods_not_run": 0,
            "explicitly_disabled_source_classes": [],
            "d22_disabled_rules_tests": [],
            "classification": "NOT_RUN_OR_DISABLED_NOT_PASS",
        },
        "whole_reactor_coverage_complete": True,
        "modules": {
            module: {
                "module": module,
                "classes": list(classes[module]),
                "class_counts": dict(class_counts[module]),
                "required_total": totals[module],
                "required_passed": totals[module],
                "required_failed": 0,
                "required_skipped": 0,
            }
            for module in modules
        },
    }


#: Candidate Maven/build code and hostile bytecode use different accounts.
BUILD_USER = "d17build"
SANDBOX_USER = "d17exec"
#: Ledger path -> digest of the bytes the trusted listener wrote and the trusted
#: orchestrator authenticated. Bytes written later by any other route (a test
#: simulating candidate tampering) do not match, exactly as in production.
_AUTHENTICATED_LEDGERS: "dict[str, str]" = {}


def integrity_ok(user=SANDBOX_USER, build_user=BUILD_USER) -> dict:
    """INTEGRITY.json as sandbox.py verify writes it for an untampered run."""
    users = [user] + ([] if build_user == user else [build_user])
    return {"schema": qualify.INTEGRITY_SCHEMA, "status": "OK", "user": user,
            "users": users, "violations": []}


def authenticate(manifest: dict, witness_dir: Path) -> dict:
    """Record, as the trusted orchestrator does, which ledger copies it authenticated."""
    import copy

    out = copy.deepcopy(manifest)
    for module, entry in (out.get("modules") or {}).items():
        digest = _AUTHENTICATED_LEDGERS.get(str(Path(witness_dir) / (module + ".witness.jsonl")))
        if digest and "ledger_authentication" not in entry:
            entry["ledger_authentication"] = qualify.LEDGER_AUTHENTICATED
            entry["ledger_sha256"] = digest
    return out


def execution_manifest(candidate_sha, candidate_tree, modules=None, launch_codes=None,
                       nonce=NONCE, base=None):
    """A trusted execution manifest as written by the trusted orchestrator."""
    modules = modules or EXPECTED_MODULES
    launch_codes = launch_codes or {module: 0 for module in modules}
    return {
        "schema": qualify.EXECUTION_MANIFEST_SCHEMA,
        "nonce": nonce,
        "candidate_sha": candidate_sha,
        "candidate_tree": candidate_tree,
        "comparison_base_sha": base or "b" * 40,
        "trusted_argline": ["--add-opens", "java.base/java.lang=ALL-UNNAMED"],
        "witness_class": WITNESS_CLASS,
        "witness_source": "witness/forge/d17/witness/QualifiedExecutionListener.java",
        "candidate_artifacts_used_as_evidence": False,
        "candidate_build_identity": BUILD_USER,
        "candidate_execution_identity": SANDBOX_USER,
        "build_execution_identity_separated": True,
        "candidate_build": {"exit_code": 0, "user": BUILD_USER,
                            "maven_repository": "/trusted/m2/repository",
                            "offline": True},
        "test_bytecode_origin": "trusted_compile_of_comparison_base_git_export",
        "trusted_test_source_sha": base or "b" * 40,
        "candidate_test_sources_used_for_credit": False,
        "hostile_bytecode_containment_required": True,
        "candidate_build_definition_divergence": [],
        "maven_repository_authority": {
            "path": "/trusted/m2/repository",
            "mode": "trusted_read_only_offline",
        },
        "modules": {
            module: {
                "module": module,
                "required_classes": ["pkg.C{}".format(i) for i in range(2)],
                "launch_exit_code": launch_codes[module],
                "execution_identity": SANDBOX_USER,
                "classpath_digest": "d" * 64,
                "testng_totals": {"total": 3, "passed": 3, "failed": 0, "skipped": 0},
                "testng_version_entry": "testng-7.8.0.jar",
                "hostile_bytecode_containment": qualify.CONTAINMENT_ENFORCED,
                "containment_violation": "null",
                "log_tail": "",
            }
            for module in modules
        },
        # Written by cmd_execute before any launch: what trusted javac produced,
        # and the admission of the launch classpath.
        "trusted_test_compilation": {
            module: {"module": module, "exit_code": 0, "compiled_required": ["pkg.C0", "pkg.C1"]}
            for module in modules
        },
        "launch_classpath_admission": {
            module: {"scanned_classes": 2, "findings": [], "tampered_jars": [], "unverified_jars": [],
                     "trusted_maven_repository": "/home/runner/.m2/repository"}
            for module in modules
        },
    }


def write_witness(witness_dir: Path, module: str, invocations, nonce=NONCE,
                  summary_override=None, omit_summary=False, raw_extra=None):
    """Write a trusted witness ledger exactly as the trusted listener would.

    ``invocations`` is a list of ``(class, method, status)`` tuples.
    """
    witness_dir.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps({"kind": "header", "schema": qualify.WITNESS_SCHEMA,
                         "module": module, "nonce": nonce}, sort_keys=True)]
    per_total, per_skip, per_fail = {}, {}, {}
    for index, (klass, method, status) in enumerate(invocations):
        lines.append(json.dumps({
            "kind": "invocation", "seq": index, "class": klass, "method": method,
            "status": status, "invoked": status != "SKIP", "context": "TestNG", "thread": "TestNG-0",
        }, sort_keys=True))
        per_total[klass] = per_total.get(klass, 0) + 1
        bucket = {"PASS": {}, "SKIP": per_skip, "FAIL": per_fail}[status]
        bucket[klass] = bucket.get(klass, 0) + 1
    if not omit_summary:
        summary = {
            "kind": "summary",
            "tests": len(invocations),
            "failed": sum(per_fail.values()),
            "skipped": sum(per_skip.values()),
            "per_class_total": per_total,
            "skip_classes": per_skip,
            "fail_classes": per_fail,
            "containment": qualify.CONTAINMENT_ENFORCED,
            "containment_violation": "null",
            "last_seq": len(invocations) - 1,
        }
        if summary_override:
            summary.update(summary_override)
        lines.append(json.dumps(summary, sort_keys=True))
    if raw_extra:
        lines.extend(raw_extra)
    path = witness_dir / (module + ".witness.jsonl")
    path.write_text("\n".join(lines) + "\n")
    _AUTHENTICATED_LEDGERS[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return path


def honest_invocations(module: str, total: int, skip_classes=()):
    """One passing invocation per required class, plus optional stress skips."""
    records = []
    classes = ["pkg.C0", "pkg.C1"]
    each = max(1, total // len(classes))
    for klass in classes:
        for index in range(each):
            records.append((klass, "test{}".format(index), "PASS"))
    while len(records) < total:
        records.append((classes[-1], "extra{}".format(len(records)), "PASS"))
    for klass in skip_classes:
        records.append((klass, "stress{}".format(len(records)), "SKIP"))
    return records


def write_forged_surefire(root: Path, module: str, tests: int = 500, name="TEST-TestSuite.xml"):
    """Write a syntactically valid green candidate Surefire/TestNG report."""
    directory = root / module / "target" / "surefire-reports"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    path.write_text(
        '<testsuite name="TestSuite" tests="{}" failures="0" errors="0" skipped="0" '
        'time="0.1">\n</testsuite>\n'.format(tests)
    )
    return path


def qualification_of(evidence: dict) -> dict:
    """The exact-candidate-qualification block of an evidence document."""
    block = evidence.get("exact_candidate_qualification")
    if not isinstance(block, dict):
        raise AssertionError("evidence has no exact_candidate_qualification block")
    return block


class EvidenceCase(unittest.TestCase):
    """Base class providing a disposable Git fixture per test."""

    def setUp(self) -> None:
        self._tmp = tempfile.mkdtemp(prefix="forge-d17-selftest-")
        self.addCleanup(shutil.rmtree, self._tmp, True)
        self.root = Path(self._tmp)
        self.fixture = Fixture(self.root)
        self.lock = build_lock(self.fixture)
        self.witness_dir = self.root / "evidence" / "witness"
        self.forged_dir = self.root / "candidate-target"
        self.surface = required_surface(base=self.lock["comparison_base"]["sha"])
        self.manifest = execution_manifest(
            self.lock["candidate"]["sha"],
            self.lock["candidate"]["tree"],
            base=self.lock["comparison_base"]["sha"],
        )

    def honest(self, out_of_band=None, modules=None, totals=None):
        """Populate a complete, honest trusted evidence set."""
        modules = modules or EXPECTED_MODULES
        totals = totals or {module: 3 for module in modules}
        for module in modules:
            write_witness(self.witness_dir, module,
                          honest_invocations(module, totals[module],
                                             ([STRESS_CLASS] if out_of_band else ())))
        return modules, totals

    def verdict(self, surface=None, manifest=None, out_of_band=None, witness_dir=None,
                integrity="ok", authenticated=True):
        witness_dir = witness_dir if witness_dir is not None else self.witness_dir
        manifest = manifest if manifest is not None else self.manifest
        if authenticated:
            manifest = authenticate(manifest, witness_dir)
        return qualify.build_evidence(
            self.lock,
            surface if surface is not None else self.surface,
            manifest,
            witness_dir,
            out_of_band or [],
            integrity_ok() if integrity == "ok" else integrity,
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
# PRESERVED: positive exact-SHA path
# --------------------------------------------------------------------------- #


class PositiveExactShaPath(EvidenceCase):
    def test_exact_candidate_lock_binds_sha_tree_and_authority(self) -> None:
        self.assertEqual(self.lock["status"], "LOCKED")
        self.assertEqual(self.lock["candidate"]["sha"], self.fixture.candidate)
        self.assertEqual(self.lock["candidate"]["fetched_from"], "refs/heads/candidate")
        self.assertEqual(self.lock["workflow_authority"]["sha"], self.fixture.master)
        self.assertEqual(self.lock["run_identity"]["sha"], self.fixture.master)
        self.assertEqual(self.lock["run_identity"]["branch"], "master")
        self.assertRegex(self.lock["candidate"]["tree"], r"^[0-9a-f]{40}$")
        self.assertEqual(
            self.lock["candidate"]["tree"],
            self.fixture.rev(self.fixture.trusted, self.fixture.candidate + "^{tree}"),
        )
        self.assertEqual(self.lock["comparison_base"]["sha"], self.fixture.master)
        self.assertIs(self.lock["comparison_base"]["synthetic_merge_computed"], False)

    def test_explicit_sha_fetch_without_ref_also_binds_exactly(self) -> None:
        lock = build_lock(self.fixture, fetch_ref=None)
        self.assertEqual(lock["candidate"]["sha"], self.fixture.candidate)
        self.assertEqual(lock["candidate"]["fetched_from"], "explicit-sha")

    def test_honest_witnessed_execution_yields_pass(self) -> None:
        """Control H: an honest exact candidate must be able to reach PASS."""
        self.honest()
        evidence = self.verdict(out_of_band=[])
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])
        self.assertEqual(evidence["exit_code"], 0)
        self.assertEqual(evidence["candidate"]["sha"], self.fixture.candidate)
        self.assertEqual(evidence["candidate"]["tree"], self.lock["candidate"]["tree"])
        self.assertEqual(
            evidence["source_lock"]["workflow_authority_sha"], self.fixture.master
        )
        self.assertEqual(qualification_of(evidence)["counts"]["observed_invocations"], 6)
        self.assertEqual(qualification_of(evidence)["counts"]["required_invocations"], 6)

    def test_declared_stress_skips_still_reach_pass(self) -> None:
        self.honest(out_of_band=[STRESS_CLASS])
        evidence = self.verdict(out_of_band=[STRESS_CLASS])
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])
        skips = qualification_of(evidence)["skips"]
        self.assertEqual(skips["declared_out_of_band_classes"], [STRESS_CLASS])
        self.assertEqual(skips["undeclared_skipped_classes"], [])
        self.assertEqual(skips["skipped_total"], 2)

    def test_pass_declares_no_mergeability_or_rules_credit(self) -> None:
        self.honest()
        evidence = self.verdict()
        self.assertEqual(evidence["pr_mergeability"]["credit"], "none")
        self.assertEqual(evidence["synthetic_merge_evidence"]["status"], "NEVER_COMPUTED")
        self.assertEqual(evidence["synthetic_merge_evidence"]["credit"], "none")
        self.assertEqual(evidence["rules_qualification_evidence"]["status"], "NOT_CLAIMED")
        self.assertEqual(evidence["trust_boundary"]["verdict_read_from_candidate"], False)
        self.assertEqual(qualification_of(evidence)["verdict"], evidence["verdict"])

    def test_pass_asserts_candidate_artifacts_were_not_evidence(self) -> None:
        self.honest()
        boundary = self.verdict()["trust_boundary"]
        self.assertIs(boundary["candidate_build_artifacts_used_as_evidence"], False)
        self.assertIs(boundary["candidate_reports_read_for_credit"], False)
        self.assertEqual(boundary["execution_observed_by"], WITNESS_CLASS)

    def test_candidate_owned_test_source_cannot_become_qualification_authority(self) -> None:
        import copy
        self.honest()
        manifest = copy.deepcopy(self.manifest)
        manifest["trusted_test_source_sha"] = self.fixture.candidate
        manifest["candidate_test_sources_used_for_credit"] = True
        manifest["test_bytecode_origin"] = "candidate"
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("test bodies", evidence["reason"])

    def test_test_source_selector_always_returns_comparison_base(self) -> None:
        self.assertEqual(
            trusted_execution.trusted_test_source_commit("a" * 40, "b" * 40),
            "a" * 40,
        )

    def test_generic_source_test_obligation_not_executed_is_partial(self) -> None:
        import copy
        self.honest()
        surface = copy.deepcopy(self.surface)
        surface["whole_reactor_coverage_complete"] = False
        surface["coverage_gaps"]["source_test_obligation_classes_not_executed"] = [
            "pkg.HiddenCoverageTest"
        ]
        surface["coverage_gaps"]["source_test_annotation_inventory"] = {
            "pkg.HiddenCoverageTest": {
                "source_present": True,
                "test_annotations": 2,
                "explicitly_disabled_annotations": 0,
                "enabled_source_methods": 2,
            }
        }
        evidence = self.verdict(surface=surface)
        self.assertNotPass(evidence, qualify.PARTIAL)
        coverage = qualification_of(evidence)["coverage"]
        self.assertEqual(
            coverage["source_test_obligation_classes_not_executed"],
            ["pkg.HiddenCoverageTest"],
        )

    def test_source_annotation_inventory_ignores_comments_and_strings(self) -> None:
        source = """
        class X {
          // @Test public void fake1() {}
          String text = "@Test(enabled=false)";
          /* @Test public void fake2() {} */
          @Test public void real() {}
          @Test(enabled = false) public void disabled() {}
        }
        """
        code = trusted_execution._java_code_only(source)
        annotations = list(re.finditer(r"@Test\b(?:\s*\((.*?)\))?", code, re.S))
        self.assertEqual(len(annotations), 2)
        self.assertFalse(any("fake" in match.group(0) for match in annotations))

    def test_d24_framework_not_run_makes_honest_candidate_partial(self) -> None:
        import copy
        self.honest()
        surface = copy.deepcopy(self.surface)
        surface["whole_reactor_coverage_complete"] = False
        surface["coverage_gaps"]["d24_framework_not_run_classes"] = [
            "forge.deck.DeckRecognizerTest"
        ]
        surface["coverage_gaps"]["d24_framework_not_run_source_inventory"] = {
            "forge.deck.DeckRecognizerTest": {
                "source_present": True,
                "test_annotations": 84,
                "explicitly_disabled_annotations": 0,
                "enabled_source_methods": 84,
            }
        }
        surface["coverage_gaps"]["d24_enabled_source_methods_not_run"] = 84
        evidence = self.verdict(surface=surface)
        self.assertNotPass(evidence, qualify.PARTIAL)
        coverage = qualification_of(evidence)["coverage"]
        self.assertFalse(coverage["whole_reactor_complete"])
        self.assertEqual(coverage["d24_framework_not_run_classes"],
                         ["forge.deck.DeckRecognizerTest"])
        self.assertEqual(coverage["d24_enabled_source_methods_not_run"], 84)

    def test_disabled_rules_test_is_not_conflated_with_d24_or_stress_skip(self) -> None:
        import copy
        self.honest(out_of_band=[STRESS_CLASS])
        surface = copy.deepcopy(self.surface)
        surface["whole_reactor_coverage_complete"] = False
        surface["coverage_gaps"]["d22_disabled_rules_tests"] = [
            "forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection104"
            "#test_104_3f_if_a_player_would_win_and_lose_simultaneously_he_loses"
        ]
        evidence = self.verdict(surface=surface, out_of_band=[STRESS_CLASS])
        self.assertNotPass(evidence, qualify.PARTIAL)
        self.assertEqual(
            qualification_of(evidence)["coverage"]["d24_framework_not_run_classes"], [])
        self.assertEqual(
            qualification_of(evidence)["skips"]["undeclared_skipped_classes"], [])

    def test_candidate_maven_build_definition_divergence_is_unknown(self) -> None:
        import copy
        self.honest()
        manifest = copy.deepcopy(self.manifest)
        # Keep otherwise-valid evidence so this control is discriminating: the
        # build-definition divergence alone must prevent PASS.
        manifest["candidate_build_definition_divergence"] = ["pom.xml"]
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.UNKNOWN)
        self.assertIn("Maven build/plugin authority", evidence["reason"])

    def test_missing_trusted_maven_repository_authority_is_unknown(self) -> None:
        import copy
        self.honest()
        manifest = copy.deepcopy(self.manifest)
        manifest["maven_repository_authority"] = None
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.UNKNOWN)
        self.assertIn("trusted Maven", evidence["reason"])

    def test_build_definition_diff_detects_poms_and_maven_core_config(self) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="forge-d17-builddef-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        repo = tmp / "repo"
        (repo / ".mvn").mkdir(parents=True)
        (repo / "module").mkdir()
        subprocess.run(["git", "init", "-q", "-b", "master", "."], cwd=str(repo), check=True)
        subprocess.run(["git", "config", "user.email", "a@b.invalid"], cwd=str(repo), check=True)
        subprocess.run(["git", "config", "user.name", "T"], cwd=str(repo), check=True)
        (repo / "pom.xml").write_text("<project/>\n")
        (repo / "module" / "pom.xml").write_text("<project/>\n")
        (repo / ".mvn" / "extensions.xml").write_text("<extensions/>\n")
        (repo / "mvnw").write_text("#!/bin/sh\n")
        (repo / "ordinary.txt").write_text("base\n")
        subprocess.run(["git", "add", "-A"], cwd=str(repo), check=True)
        subprocess.run(["git", "commit", "-q", "-m", "base"], cwd=str(repo), check=True)
        base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=str(repo), text=True).strip()
        (repo / "module" / "pom.xml").write_text("<project><build/></project>\n")
        (repo / ".mvn" / "extensions.xml").write_text("<extensions><extension/></extensions>\n")
        (repo / "ordinary.txt").write_text("candidate\n")
        subprocess.run(["git", "add", "-A"], cwd=str(repo), check=True)
        subprocess.run(["git", "commit", "-q", "-m", "candidate"], cwd=str(repo), check=True)
        candidate = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=str(repo), text=True).strip()
        self.assertEqual(
            trusted_execution.build_definition_divergence(repo, base, candidate),
            [".mvn/extensions.xml", "module/pom.xml"],
        )

    def test_containment_violation_is_fail_even_when_tests_are_green(self) -> None:
        import copy
        self.honest()
        manifest = copy.deepcopy(self.manifest)
        manifest["modules"]["forge-game"]["hostile_bytecode_containment"] = (
            "SECURITY_MANAGER_VIOLATED"
        )
        witness = self.witness_dir / "forge-game.witness.jsonl"
        lines = [json.loads(line) for line in witness.read_text().splitlines()]
        lines[-1]["containment"] = "SECURITY_MANAGER_VIOLATED"
        lines[-1]["containment_violation"] = "candidate RuntimePermission getClassLoader"
        witness.write_text("\n".join(json.dumps(line, sort_keys=True) for line in lines) + "\n")
        _AUTHENTICATED_LEDGERS[str(witness)] = hashlib.sha256(witness.read_bytes()).hexdigest()
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("containment", evidence["reason"])

    def test_containment_unavailable_is_unknown_not_fallback(self) -> None:
        import copy
        self.honest()
        manifest = copy.deepcopy(self.manifest)
        manifest["modules"]["forge-game"]["hostile_bytecode_containment"] = (
            qualify.CONTAINMENT_UNAVAILABLE
        )
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.UNKNOWN)


# --------------------------------------------------------------------------- #
# PRESERVED: identity and authority red controls
# --------------------------------------------------------------------------- #


class RedIdentityAndAuthority(EvidenceCase):
    def test_ref_resolving_to_a_different_sha_fails_closed(self) -> None:
        original = self.fixture.candidate
        other = self.fixture.commit_on_candidate("other.txt", "other\n", "other")
        self.assertNotEqual(other, original)
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, candidate_sha=original, fetch_ref="refs/heads/candidate")
        self.assertIn("candidate identity mismatch", str(caught.exception))

    def test_synthetic_merge_ref_is_refused(self) -> None:
        for forbidden in ("refs/pull/17/merge", "refs/pull/17/head~1", "MERGE_HEAD", "master"):
            with self.subTest(ref=forbidden):
                with self.assertRaises(source_lock.SourceLockError) as caught:
                    build_lock(self.fixture, fetch_ref=forbidden)
                self.assertIn("refusing to fetch unqualified ref", str(caught.exception))

    def test_non_exact_candidate_identity_is_refused(self) -> None:
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
                    "not a full 40-hex sha", str(caught.exception),
                    "refusal for {!r} was not an identity refusal".format(bad),
                )

    def test_unknown_candidate_object_fails_closed(self) -> None:
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

    def test_event_base_equal_to_authority_is_recorded(self) -> None:
        lock = build_lock(self.fixture, event_base_sha=self.fixture.master)
        self.assertEqual(lock["event_base"]["relation"], "EQUAL")

    def test_trusted_master_missing_the_definition_is_not_authority(self) -> None:
        master = self.fixture.commit_and_promote_master(
            "README.md", "master without the definition\n", "advance master"
        )
        self.fixture.git(self.fixture.trusted, "fetch", "-q", "origin")
        self.fixture.git(self.fixture.trusted, "checkout", "-q", "-B", "master", "origin/master")
        with self.assertRaises(source_lock.SourceLockError) as caught:
            build_lock(self.fixture, candidate_sha=self.fixture.candidate)
        self.assertIn("does not carry", str(caught.exception))
        self.assertIn(master, str(caught.exception))


# --------------------------------------------------------------------------- #
# PRESERVED: lock-document trust validation
# --------------------------------------------------------------------------- #


class RedLockValidation(EvidenceCase):
    def _expect_rejected(self, mutate) -> None:
        lock = build_lock(self.fixture)
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
# PRESERVED: candidate definition trivialization
# --------------------------------------------------------------------------- #


class RedCandidateDefinitionTrivialization(EvidenceCase):
    def test_candidate_editing_the_definition_is_recorded_as_divergent(self) -> None:
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
        self.assertEqual(lock["workflow_authority"]["sha"], self.fixture.master)
        self.honest()
        evidence = qualify.build_evidence(lock, self.surface, self.manifest,
                                          self.witness_dir, [])
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
# PRESERVED: witness-ledger integrity (replaces report parsing)
# --------------------------------------------------------------------------- #


class RedWitnessLedgerIntegrity(EvidenceCase):
    def setUp(self) -> None:
        super().setUp()
        self.honest()

    def test_missing_witness_ledger_is_not_run(self) -> None:
        for module in EXPECTED_MODULES:
            (self.witness_dir / (module + ".witness.jsonl")).unlink()
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.NOT_RUN)
        self.assertEqual(evidence["exit_code"], 3)

    def test_empty_witness_ledger_is_unknown(self) -> None:
        for module in EXPECTED_MODULES:
            (self.witness_dir / (module + ".witness.jsonl")).write_text("")
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)
        self.assertEqual(evidence["exit_code"], 4)

    def test_witness_nonce_mismatch_is_unknown(self) -> None:
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 3),
                          nonce="f" * 32)
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_witness_module_mismatch_is_unknown(self) -> None:
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 3))
        forged = self.witness_dir / "forge-game.witness.jsonl"
        lines = forged.read_text().replace('"module": "forge-game"', '"module": "forge-ai"')
        forged.write_text(lines)
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_witness_without_summary_is_unknown(self) -> None:
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 3),
                          omit_summary=True)
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_witness_with_a_gap_in_the_sequence_is_unknown(self) -> None:
        records = honest_invocations("forge-game", 3)
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records)
        path = self.witness_dir / "forge-game.witness.jsonl"
        lines = path.read_text().splitlines()
        lines[2] = json.dumps({"kind": "invocation", "seq": 7, "class": "pkg.C0",
                               "method": "x", "status": "PASS", "context": "TestNG"})
        path.write_text("\n".join(lines) + "\n")
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)
        self.assertTrue(any("contiguous" in p for p in qualification_of(evidence)["problems"]))

    def test_witness_summary_claiming_more_tests_than_records_is_unknown(self) -> None:
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 3),
                          summary_override={"tests": 999})
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_witness_summary_failure_total_tampering_is_unknown(self) -> None:
        records = [("pkg.C0", "a", "FAIL"), ("pkg.C1", "b", "PASS")]
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records,
                          summary_override={"failed": 0})
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_malformed_witness_line_is_unknown(self) -> None:
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 3))
        path = self.witness_dir / "forge-game.witness.jsonl"
        path.write_text(path.read_text() + "{not json\n")
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_invocations_without_a_testng_context_are_rejected(self) -> None:
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 3))
        path = self.witness_dir / "forge-game.witness.jsonl"
        lines = path.read_text().splitlines()
        record = json.loads(lines[1])
        record["context"] = ""
        lines[1] = json.dumps(record, sort_keys=True)
        path.write_text("\n".join(lines) + "\n")
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_unknown_witness_record_kind_is_unknown(self) -> None:
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 3),
                          raw_extra=['{"kind":"verdict","value":"PASS"}'])
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_unexpected_verdict_kind_in_a_witness_is_rejected(self) -> None:
        """A candidate-supplied verdict record inside a ledger is not accepted."""
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 3),
                          raw_extra=['{"kind":"verdict","verdict":"PASS","exit_code":0}'])
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)


# --------------------------------------------------------------------------- #
# PRESERVED: verdict derivation red controls
# --------------------------------------------------------------------------- #


class RedVerdictDerivation(EvidenceCase):
    def test_manifest_claiming_another_candidate_is_fail(self) -> None:
        self.honest()
        manifest = execution_manifest("a" * 40, self.lock["candidate"]["tree"],
                                      base=self.lock["comparison_base"]["sha"])
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("candidate identity is not bound", evidence["reason"])

    def test_manifest_claiming_another_tree_is_fail(self) -> None:
        self.honest()
        manifest = execution_manifest(self.lock["candidate"]["sha"], "b" * 40,
                                      base=self.lock["comparison_base"]["sha"])
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.FAIL)

    def test_required_surface_not_bound_to_the_comparison_base_is_fail(self) -> None:
        self.honest()
        surface = required_surface(base="9" * 40)
        evidence = self.verdict(surface=surface)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("not bound to the locked comparison base", evidence["reason"])

    def test_failed_launch_is_fail(self) -> None:
        """TestNG exit 1 means the suite had failures."""
        self.honest()
        manifest = execution_manifest(
            self.lock["candidate"]["sha"], self.lock["candidate"]["tree"],
            launch_codes={"forge-game": 0, "forge-gui-desktop": 1},
            base=self.lock["comparison_base"]["sha"],
        )
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("did not complete cleanly", evidence["reason"])

    def test_launch_completing_with_skips_is_not_a_broken_launch(self) -> None:
        """TestNG exit 2 means completed-with-skips, which the skip policy judges."""
        self.honest(out_of_band=[STRESS_CLASS])
        manifest = execution_manifest(
            self.lock["candidate"]["sha"], self.lock["candidate"]["tree"],
            launch_codes={"forge-game": 0, "forge-gui-desktop": 2},
            base=self.lock["comparison_base"]["sha"],
        )
        evidence = self.verdict(manifest=manifest, out_of_band=[STRESS_CLASS])
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])

    def test_crashed_launch_without_totals_is_fail(self) -> None:
        self.honest()
        manifest = execution_manifest(
            self.lock["candidate"]["sha"], self.lock["candidate"]["tree"],
            launch_codes={"forge-game": 0, "forge-gui-desktop": 137},
            base=self.lock["comparison_base"]["sha"],
        )
        manifest["modules"]["forge-gui-desktop"]["testng_totals"] = None
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.FAIL)

    def test_witnessed_failure_is_fail(self) -> None:
        records = [("pkg.C0", "a", "FAIL"), ("pkg.C0", "b", "PASS"), ("pkg.C1", "c", "PASS")]
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records)
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("failing tests", evidence["reason"])

    def test_empty_required_surface_is_not_run(self) -> None:
        self.honest()
        surface = required_surface(totals={m: 0 for m in EXPECTED_MODULES},
                                   classes={m: [] for m in EXPECTED_MODULES},
                                   base=self.lock["comparison_base"]["sha"])
        evidence = self.verdict(surface=surface)
        self.assertNotPass(evidence, qualify.NOT_RUN)

    def test_missing_expected_module_witness_is_not_run(self) -> None:
        self.honest()
        (self.witness_dir / "forge-gui-desktop.witness.jsonl").unlink()
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.NOT_RUN)

    def test_undeclared_skips_are_partial_not_pass(self) -> None:
        records = honest_invocations("forge-game", 3, skip_classes=[STRESS_CLASS])
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records)
        evidence = self.verdict(out_of_band=[])
        self.assertNotPass(evidence, qualify.PARTIAL)
        self.assertEqual(
            qualification_of(evidence)["skips"]["undeclared_skipped_classes"], [STRESS_CLASS]
        )

    def test_declared_stress_skip_is_not_partial(self) -> None:
        records = honest_invocations("forge-game", 3, skip_classes=[STRESS_CLASS])
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records)
        evidence = self.verdict(out_of_band=[STRESS_CLASS])
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])

    def test_out_of_band_declaration_cannot_hide_other_skips(self) -> None:
        records = honest_invocations("forge-game", 3, skip_classes=[STRESS_CLASS])
        records = records + [("pkg.RulesCoverage", "r1", "SKIP")]
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records)
        evidence = self.verdict(out_of_band=[STRESS_CLASS])
        self.assertNotPass(evidence, qualify.PARTIAL)
        self.assertIn("pkg.RulesCoverage",
                      qualification_of(evidence)["skips"]["undeclared_skipped_classes"])

    def test_mergeability_claims_cannot_promote_incomplete_evidence(self) -> None:
        manifest = dict(self.manifest)
        manifest.update({
            "pr_mergeable": True, "mergeable_state": "CLEAN",
            "synthetic_merge_state": "CLEAN", "mergeStateStatus": "CLEAN", "verdict": "PASS",
        })
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.NOT_RUN)
        self.assertEqual(evidence["pr_mergeability"]["status"], "NOT_APPLICABLE")
        self.assertEqual(evidence["synthetic_merge_evidence"]["status"], "NEVER_COMPUTED")


# --------------------------------------------------------------------------- #
# MANDATORY A-H: candidate-authored artifacts cannot manufacture credit
# --------------------------------------------------------------------------- #


class RedTrustDomain(EvidenceCase):
    """Coordinator finding at 17d42d7e: the candidate shared the validator's identity.

    Credit now requires an orchestrator-authenticated ledger copy, candidate
    execution as the separate sandbox account, and a verified INTEGRITY.json.
    """

    def test_an_honest_but_unauthenticated_ledger_is_not_credit(self) -> None:
        self.honest()
        evidence = self.verdict(authenticated=False)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("orchestrator-authenticated", evidence["reason"])

    def test_a_ledger_rewritten_after_authentication_is_not_credit(self) -> None:
        self.honest()
        manifest = authenticate(self.manifest, self.witness_dir)
        # Candidate-side rewrite with self-consistent, inflated content: every
        # content check passes, only the authenticated digest can tell.
        path = self.witness_dir / "forge-game.witness.jsonl"
        records = honest_invocations("forge-game", 6)
        lines = [json.dumps({"kind": "header", "schema": qualify.WITNESS_SCHEMA,
                             "module": "forge-game", "nonce": NONCE}, sort_keys=True)]
        for index, (klass, method, status) in enumerate(records):
            lines.append(json.dumps({"kind": "invocation", "seq": index, "class": klass, "method": method,
                                     "status": status, "invoked": status != "SKIP", "context": "TestNG", "thread": "TestNG-0"},
                                    sort_keys=True))
        per = {}
        for klass, _, _ in records:
            per[klass] = per.get(klass, 0) + 1
        lines.append(json.dumps({"kind": "summary", "tests": len(records), "failed": 0, "skipped": 0,
                                 "per_class_total": per, "skip_classes": {}, "fail_classes": {},
                                 "containment": qualify.CONTAINMENT_ENFORCED,
                                 "containment_violation": "null",
                                 "last_seq": len(records) - 1}, sort_keys=True))
        path.write_text("\n".join(lines) + "\n")
        evidence = self.verdict(manifest=manifest, authenticated=False)
        self.assertNotPass(evidence, qualify.FAIL)
        signals = {s["signal"]: s for s in qualification_of(evidence)["signals"]}
        self.assertIn("digest_mismatch", signals["witness_ledgers_authenticated"]["detail"])

    def test_candidate_code_run_as_a_trusted_identity_is_not_credit(self) -> None:
        import copy

        self.honest()
        for identity in ("root", "runner", "", None):
            with self.subTest(identity=identity):
                # Every identity field agrees, so only the trusted-identity rule can refuse.
                manifest = copy.deepcopy(self.manifest)
                manifest["candidate_execution_identity"] = identity
                for entry in manifest["modules"].values():
                    entry["execution_identity"] = identity
                self.assertNotPass(self.verdict(manifest=manifest, integrity=integrity_ok(identity)),
                                   qualify.FAIL)

    def test_a_ledger_the_orchestrator_rejected_is_not_credit_even_with_a_matching_digest(self) -> None:
        import copy

        self.honest()
        manifest = authenticate(self.manifest, self.witness_dir)
        manifest = copy.deepcopy(manifest)
        manifest["modules"]["forge-game"]["ledger_authentication"] = "REJECTED:ledger_line_3_mac_mismatch"
        evidence = self.verdict(manifest=manifest, authenticated=False)
        self.assertNotPass(evidence, qualify.FAIL)
        signals = {s["signal"]: s for s in qualification_of(evidence)["signals"]}
        self.assertIn("REJECTED", signals["witness_ledgers_authenticated"]["detail"])

    def test_build_and_execution_must_use_distinct_untrusted_identities(self) -> None:
        import copy

        self.honest()
        manifest = copy.deepcopy(self.manifest)
        manifest["candidate_build_identity"] = SANDBOX_USER
        manifest["build_execution_identity_separated"] = False
        manifest["candidate_build"]["user"] = SANDBOX_USER
        collapsed_integrity = integrity_ok(SANDBOX_USER, SANDBOX_USER)
        evidence = self.verdict(manifest=manifest, integrity=collapsed_integrity)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("distinct untrusted OS identities", evidence["reason"])

    def test_a_launch_under_another_identity_is_not_credit(self) -> None:
        import copy

        self.honest()
        manifest = copy.deepcopy(self.manifest)
        manifest["modules"]["forge-game"]["execution_identity"] = "runner"
        self.assertNotPass(self.verdict(manifest=manifest), qualify.FAIL)

    def test_integrity_violation_is_fail_whatever_else_holds(self) -> None:
        self.honest()
        violated = dict(integrity_ok(), status="VIOLATION",
                        violations=["trusted_state_integrity_violation: qualify.py differs from Git"])
        evidence = self.verdict(integrity=violated)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("integrity was violated", evidence["reason"])

    def test_missing_or_foreign_integrity_is_never_pass(self) -> None:
        self.honest()
        self.assertNotPass(self.verdict(integrity=None), qualify.UNKNOWN)
        self.assertNotPass(self.verdict(integrity=dict(integrity_ok(), schema="other/1")), qualify.UNKNOWN)
        self.assertNotPass(self.verdict(integrity=integrity_ok("someone-else")), qualify.FAIL)


def _mac_ledger(bodies, key: bytes, nonce: str) -> bytes:
    """Write a ledger exactly as QualifiedExecutionListener chains it."""
    import hmac as _hmac

    chain, out = nonce, []
    for body in bodies:
        chain = _hmac.new(key, (chain + "\n" + body).encode("utf-8"), hashlib.sha256).hexdigest()
        out.append(body[:-1] + ',"mac":"' + chain + '"}')
    return ("\n".join(out) + "\n").encode("utf-8")


class TrustedLedgerAuthentication(unittest.TestCase):
    """The orchestrator accepts only an unbroken HMAC chain under this run's key."""

    KEY = bytes(range(32))
    BODIES = [
        '{"kind":"header","schema":"forge.d17.witness/1","module":"forge-game","nonce":"n1"}',
        '{"kind":"invocation","seq":0,"class":"pkg.C0","method":"a","status":"PASS"}',
        '{"kind":"summary","tests":1,"failed":0,"skipped":0}',
    ]

    def test_an_unbroken_chain_verifies(self) -> None:
        lines, problem = trusted_execution.verify_ledger(_mac_ledger(self.BODIES, self.KEY, "n1"), self.KEY, "n1")
        self.assertIsNone(problem)
        self.assertEqual(len(lines), 3)

    def test_every_forgery_is_rejected(self) -> None:
        good = _mac_ledger(self.BODIES, self.KEY, "n1")
        lines = good.decode().splitlines()
        forged_tail = '{"kind":"invocation","seq":1,"class":"pkg.C1","method":"b","status":"PASS"}'
        cases = {
            "appended_unauthenticated_line": good + (forged_tail + "\n").encode(),
            "line_altered": good.replace(b'"status":"PASS"', b'"status":"SKIP"'),
            "lines_reordered": ("\n".join([lines[0], lines[2], lines[1]]) + "\n").encode(),
            "other_key": _mac_ledger(self.BODIES, bytes(32), "n1"),
            "replayed_from_another_run": _mac_ledger(self.BODIES, self.KEY, "n0"),
            "middle_line_dropped": ("\n".join([lines[0], lines[2]]) + "\n").encode(),
            "empty": b"",
            "not_utf8": b"\xff\xfe",
        }
        for name, data in cases.items():
            with self.subTest(case=name):
                accepted, problem = trusted_execution.verify_ledger(data, self.KEY, "n1")
                self.assertIsNone(accepted, name)
                self.assertTrue(problem, name)

    def test_the_trusted_testng_closure_is_pinned(self) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="forge-d17-pins-"))
        self.addCleanup(shutil.rmtree, str(tmp), True)
        # A complete closure by name, with one substituted jar: only the digest
        # pin can refuse it.
        closure = []
        for name in trusted_execution.TRUSTED_TESTNG_PINS:
            path = tmp / name
            path.write_bytes(b"not the pinned " + name.encode())
            closure.append(path)
        with self.assertRaisesRegex(trusted_execution.ExecutionError, "pinned digest"):
            trusted_execution.verify_trusted_testng(closure)
        other = tmp / "evil-testng.jar"
        other.write_bytes(b"x")
        with self.assertRaises(trusted_execution.ExecutionError):
            trusted_execution.verify_trusted_testng([other])

    def test_candidate_test_output_and_installed_siblings_never_reach_the_classpath(self) -> None:
        root = Path("/srv/d17-sandbox/candidate")
        entries = [
            "/srv/d17-sandbox/candidate/forge-game/target/test-classes",
            "/srv/d17-sandbox/home/.m2/repository/forge/forge-core/2.0/forge-core-2.0.jar",
            "/srv/d17-sandbox/home/.m2/repository/org/testng/testng/7.10.2/testng-7.10.2.jar",
        ]
        kept, dropped = trusted_execution.sanitize_classpath(entries, root, [])
        self.assertEqual(kept, ["/srv/d17-sandbox/home/.m2/repository/org/testng/testng/7.10.2/testng-7.10.2.jar"])
        self.assertEqual(sorted(dropped), sorted(entries[:2]))


class RedForgedCandidateArtifacts(EvidenceCase):
    """The property the reviewed head failed: forged reports must earn nothing."""

    def _forge_everywhere(self, tests=500):
        """Candidate lifecycle writes green XML into every expected module."""
        for module in EXPECTED_MODULES:
            write_forged_surefire(self.forged_dir, module, tests=tests)

    def test_A_suppression_plus_forged_green_xml_never_passes(self) -> None:
        """Control A: hardcoded suppression, zero execution, forged green XML."""
        self._forge_everywhere()
        # No witness exists because nothing executed.
        evidence = self.verdict(witness_dir=self.root / "absent")
        self.assertNotPass(evidence, qualify.NOT_RUN)

    def test_A_forged_xml_alongside_a_thin_witness_is_fail(self) -> None:
        """Forged XML plus a witness that never touched the required classes."""
        self._forge_everywhere()
        write_witness(self.witness_dir, "forge-game",
                      [("pkg.Other", "x", "PASS")])
        write_witness(self.witness_dir, "forge-gui-desktop",
                      [("pkg.Other", "x", "PASS")])
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("not observed executing at the trusted per-class denominator",
                      evidence["reason"])

    def test_B_maven_success_with_zero_required_execution_never_passes(self) -> None:
        """Control B: candidate claims success, forges expected counts, runs nothing."""
        self._forge_everywhere(tests=666)
        manifest = execution_manifest(
            self.lock["candidate"]["sha"], self.lock["candidate"]["tree"],
            base=self.lock["comparison_base"]["sha"],
        )
        for entry in manifest["modules"].values():
            entry["testng_totals"] = {"total": 666, "passed": 660, "failed": 0, "skipped": 6}
        evidence = self.verdict(manifest=manifest, witness_dir=self.root / "absent")
        self.assertNotPass(evidence, qualify.NOT_RUN)

    def test_C_forged_reports_in_every_expected_module_never_pass(self) -> None:
        """Control C: green XML present in forge-game, forge-gui-desktop, bridge."""
        for module in ("forge-game", "forge-gui-desktop", "forge-protocol2-bridge"):
            write_forged_surefire(self.forged_dir, module)
        evidence = self.verdict(witness_dir=self.root / "absent")
        self.assertNotPass(evidence, qualify.NOT_RUN)

    def test_D_copied_and_renamed_reports_never_pass(self) -> None:
        """Control D: a real report copied and renamed into the expected path."""
        self.honest()
        source = self.witness_dir / "forge-game.witness.jsonl"
        copy = self.forged_dir / "forge-gui-desktop" / "target" / "surefire-reports"
        copy.mkdir(parents=True, exist_ok=True)
        (copy / "TEST-Copied.xml").write_text(
            '<testsuite name="TestSuite" tests="450" failures="0" errors="0" skipped="0"/>'
        )
        (copy / "TEST-TestSuite.xml.bak").write_text(
            '<testsuite name="TestSuite" tests="450" failures="0" errors="0" skipped="0"/>'
        )
        evidence = self.verdict()
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])
        # The copied files changed nothing: credit came only from the witness.
        self.assertEqual(qualification_of(evidence)["counts"]["observed_invocations"], 6)
        self.assertIs(
            evidence["trust_boundary"]["candidate_reports_read_for_credit"], False
        )

    def test_E_real_failure_with_forged_green_reports_is_fail(self) -> None:
        """Control E: forged green XML must never mask an observed real failure."""
        self._forge_everywhere()
        records = [("pkg.C0", "a", "FAIL"), ("pkg.C0", "b", "PASS"), ("pkg.C1", "c", "PASS")]
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records)
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("failing tests", evidence["reason"])

    def test_F_candidate_fabricated_run_report_grants_no_credit(self) -> None:
        """Control F: a candidate-shipped manifest claiming success is rejected."""
        self._forge_everywhere()
        fabricated = self.root / "candidate-target" / "execution-manifest.json"
        fabricated.parent.mkdir(parents=True, exist_ok=True)
        fabricated.write_text(json.dumps(execution_manifest(
            self.lock["candidate"]["sha"], self.lock["candidate"]["tree"],
            base=self.lock["comparison_base"]["sha"])))
        fabricated.write_text(json.dumps(dict(
            json.loads(fabricated.read_text()), candidate_sha="f" * 40, completed=True,
            step_exit_codes={"bounded_test_surface": 0}, verdict="PASS", exit_code=0,
        )))
        # The verifier reads only the trusted manifest path; the fabricated file
        # is in the candidate tree and is never consulted.
        evidence = self.verdict(witness_dir=self.root / "absent")
        self.assertNotPass(evidence, qualify.NOT_RUN)

    def test_F_candidate_shipped_verdict_document_grants_no_credit(self) -> None:
        payload = self.forged_dir / "FORGE_CANDIDATE_QUALIFICATION.json"
        payload.parent.mkdir(parents=True, exist_ok=True)
        payload.write_text(json.dumps({"schema": "forge.candidate-qualification.evidence/2",
                                       "verdict": "PASS", "exit_code": 0}))
        evidence = self.verdict(witness_dir=self.root / "absent")
        self.assertNotPass(evidence, qualify.NOT_RUN)

    def test_candidate_verdict_shaped_fields_in_manifest_are_not_read(self) -> None:
        self.honest()
        manifest = dict(self.manifest, verdict="PASS", status="PASS", result="PASS",
                        outcome="PASS", passed=True, success=True)
        evidence = self.verdict(manifest=manifest)
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])
        self.assertFalse(evidence["trust_boundary"]["verdict_read_from_candidate"])

    def test_G_required_class_suppressed_never_passes(self) -> None:
        """Control G: required tests not discovered/executed => no PASS."""
        records = [("pkg.C0", "a", "PASS")]  # pkg.C1 never runs
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records)
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertEqual(
            sorted(qualification_of(evidence)["dead_classes"]["forge-game"]), ["pkg.C1"]
        )

    def test_G_reduced_volume_below_trusted_denominator_is_partial(self) -> None:
        """Control G: required classes alive but the volume was cut."""
        surface = required_surface(totals={"forge-game": 40, "forge-gui-desktop": 40},
                                   base=self.lock["comparison_base"]["sha"])
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, honest_invocations(module, 4))
        evidence = self.verdict(surface=surface)
        self.assertNotPass(evidence, qualify.PARTIAL)
        self.assertIn("reduced the qualified test volume", evidence["reason"])

    def test_partial_class_suppression_below_the_trusted_floor_fails(self) -> None:
        """G, refined: the trusted per-class floor must be met, not just liveness."""
        floors = {"pkg.C0": 5, "pkg.C1": 1}
        surface = required_surface(
            totals={"forge-game": 6, "forge-gui-desktop": 6},
            class_counts={"forge-game": floors, "forge-gui-desktop": floors},
            base=self.lock["comparison_base"]["sha"],
        )
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, [
                ("pkg.C0", "t0", "PASS"), ("pkg.C0", "t1", "PASS"),
                ("pkg.C1", "t2", "PASS"),
            ])
        evidence = self.verdict(surface=surface)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertEqual(qualification_of(evidence)["dead_classes"]["forge-game"], ["pkg.C0"])

    def test_exactly_meeting_the_trusted_per_class_floor_passes(self) -> None:
        floors = {"pkg.C0": 2, "pkg.C1": 1}
        surface = required_surface(
            totals={"forge-game": 3, "forge-gui-desktop": 3},
            class_counts={"forge-game": floors, "forge-gui-desktop": floors},
            base=self.lock["comparison_base"]["sha"],
        )
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, [
                ("pkg.C0", "t0", "PASS"), ("pkg.C0", "t1", "PASS"),
                ("pkg.C1", "t2", "PASS"),
            ])
        evidence = self.verdict(surface=surface)
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])

    def test_candidate_inflating_one_class_cannot_paper_over_another(self) -> None:
        """Padding one class must not satisfy another class's trusted floor."""
        floors = {"pkg.C0": 2, "pkg.C1": 4}
        surface = required_surface(
            totals={"forge-game": 10, "forge-gui-desktop": 10},
            class_counts={"forge-game": floors, "forge-gui-desktop": floors},
            base=self.lock["comparison_base"]["sha"],
        )
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, [
                ("pkg.C0", "t{}".format(i), "PASS") for i in range(9)
            ] + [("pkg.C1", "x", "PASS")])
        evidence = self.verdict(surface=surface)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertEqual(qualification_of(evidence)["dead_classes"]["forge-game"], ["pkg.C1"])

    def test_forged_reports_alone_never_change_a_failing_verdict(self) -> None:
        """Absolute property: candidate artifacts have zero influence."""
        records = [("pkg.C0", "a", "FAIL"), ("pkg.C0", "b", "PASS"), ("pkg.C1", "c", "PASS")]
        for module in EXPECTED_MODULES:
            write_witness(self.witness_dir, module, records)
        without = self.verdict()["verdict"]
        self._forge_everywhere()
        with_forged = self.verdict()["verdict"]
        self.assertEqual(without, qualify.FAIL)
        self.assertEqual(with_forged, qualify.FAIL)


# --------------------------------------------------------------------------- #
# PRESERVED: CLI exit-code separation
# --------------------------------------------------------------------------- #


class VerdictCli(EvidenceCase):
    def _run(self, surface=None, manifest=None, out_of_band=None, witness_dir=None):
        lock_path = self.root / "lock.json"
        lock_path.write_text(json.dumps(self.lock))
        surface_path = self.root / "surface.json"
        surface_path.write_text(json.dumps(surface if surface is not None else self.surface))
        witness_dir = witness_dir if witness_dir is not None else self.witness_dir
        manifest_path = self.root / "manifest.json"
        manifest_path.write_text(json.dumps(authenticate(manifest if manifest is not None else self.manifest,
                                                         witness_dir)))
        integrity_path = self.root / "INTEGRITY.json"
        integrity_path.write_text(json.dumps(integrity_ok()))
        out = self.root / "evidence-out" / "qual.json"
        argv = [
            "--lock", str(lock_path),
            "--required-surface", str(surface_path),
            "--execution-manifest", str(manifest_path),
            "--witness-dir", str(witness_dir),
            "--integrity", str(integrity_path),
            "--output", str(out),
        ]
        for klass in out_of_band or []:
            argv += ["--out-of-band-test-class", klass]
        code = qualify.main(argv)
        return code, json.loads(out.read_text())

    def test_cli_separates_every_verdict_class(self) -> None:
        cases = [
            ("pass", qualify.PASS, 0, None, None),
            ("fail", qualify.FAIL, 1, "failure", None),
            ("partial", qualify.PARTIAL, 2, "thin", None),
            ("not_run", qualify.NOT_RUN, 3, None, "absent"),
            ("unknown", qualify.UNKNOWN, 4, "malformed", None),
        ]
        for label, expected, code_expected, mode, missing in cases:
            with self.subTest(case=label):
                self.honest()
                surface, manifest, out_of_band = self.surface, self.manifest, []
                if mode == "failure":
                    records = [("pkg.C0", "a", "FAIL"), ("pkg.C0", "b", "PASS"),
                               ("pkg.C1", "c", "PASS")]
                    for module in EXPECTED_MODULES:
                        write_witness(self.witness_dir, module, records)
                elif mode == "thin":
                    surface = required_surface(
                        totals={"forge-game": 30, "forge-gui-desktop": 30},
                        base=self.lock["comparison_base"]["sha"])
                    for module in EXPECTED_MODULES:
                        write_witness(self.witness_dir, module, honest_invocations(module, 3))
                elif mode == "malformed":
                    path = self.witness_dir / "forge-game.witness.jsonl"
                    path.write_text("{broken\n")
                code, emitted = self._run(surface, manifest, out_of_band,
                                          self.root / missing if missing else None)
                self.assertEqual(emitted["verdict"], expected, emitted["reason"])
                self.assertEqual(code, code_expected,
                                 "{} -> {}".format(label, emitted["reason"]))

    def test_cli_rejects_wrong_input_schemas(self) -> None:
        self.honest()
        code, emitted = self._run(surface=dict(self.surface, schema="wrong/1"))
        self.assertEqual(code, qualify.EXIT_CODES[qualify.FAIL])
        self.assertEqual(emitted["verdict"], qualify.FAIL)
        code, emitted = self._run(manifest=dict(self.manifest, schema="wrong/1"))
        self.assertEqual(code, qualify.EXIT_CODES[qualify.FAIL])

    def test_cli_emits_fail_evidence_when_the_lock_does_not_validate(self) -> None:
        broken = dict(self.lock, verdict_read_from_candidate=True)
        lock_path = self.root / "broken-lock.json"
        lock_path.write_text(json.dumps(broken))
        out = self.root / "broken-evidence.json"
        code = qualify.main([
            "--lock", str(lock_path),
            "--required-surface", str(self.root / "s.json"),
            "--execution-manifest", str(self.root / "m.json"),
            "--witness-dir", str(self.witness_dir),
            "--integrity", str(self.root / "i.json"),
            "--output", str(out),
        ])
        self.assertEqual(code, qualify.EXIT_CODES[qualify.FAIL])
        emitted = json.loads(out.read_text())
        self.assertEqual(emitted["verdict"], qualify.FAIL)
        self.assertEqual(emitted["rules_qualification_evidence"]["status"], "NOT_CLAIMED")


# --------------------------------------------------------------------------- #
# PRESERVED: source-lock CLI
# --------------------------------------------------------------------------- #


class SourceLockCli(EvidenceCase):
    def test_cli_writes_a_valid_lock_for_the_exact_candidate(self) -> None:
        out = self.root / "source-lock.json"
        code = source_lock.main([
            "--repo", str(self.fixture.trusted),
            "--candidate-sha", self.fixture.candidate,
            "--fetch-ref", "refs/heads/candidate",
            "--run-sha", self.fixture.master,
            "--output", str(out),
        ])
        self.assertEqual(code, 0)
        self.assertEqual(source_lock.load_lock(out)["candidate"]["sha"], self.fixture.candidate)

    def test_cli_writes_nothing_when_identity_is_unproven(self) -> None:
        out = self.root / "unproven-lock.json"
        code = source_lock.main([
            "--repo", str(self.fixture.trusted),
            "--candidate-sha", "0" * 40,
            "--output", str(out),
        ])
        self.assertEqual(code, 1)
        self.assertFalse(out.exists(), "no partially proven lock may be emitted")


# --------------------------------------------------------------------------- #
# PRESERVED + EXTENDED: workflow contract controls
# --------------------------------------------------------------------------- #


class TrustedOrchestratorControls(unittest.TestCase):
    """Controls on the trusted orchestrator itself.

    The orchestrator decides what runs and what counts. These controls pin the
    two properties that make the witness trustworthy: the trusted listener is
    first on the launch classpath, and the required surface is enumerated with
    Surefire's real default include patterns rather than a narrowed subset.
    """

    def test_trusted_witness_classpath_entry_is_first(self) -> None:
        assembled = trusted_execution.assemble_classpath(
            "/evidence/witness-classes", ["/cand/target/test-classes", "/cand/target/classes", "/dep.jar"]
        )
        entries = assembled.split(":")
        self.assertEqual(entries[0], "/evidence/witness-classes")
        self.assertEqual(entries[1:], ["/cand/target/test-classes", "/cand/target/classes", "/dep.jar"])

    def test_witness_classpath_survives_an_empty_candidate_classpath(self) -> None:
        self.assertEqual(
            trusted_execution.assemble_classpath("/w", []), "/w"
        )

    def test_candidate_maven_build_runs_offline_against_trusted_repository(self) -> None:
        import inspect
        source = inspect.getsource(trusted_execution.cmd_execute)
        self.assertIn('"-o", "-B", "-q"', source)
        self.assertIn('"-Dmaven.repo.local=" + str(trusted_maven_repo)', source)
        self.assertIn('"-Dmaven.compiler.proc=none"', source)
        self.assertIn("build_definition_divergence(", source)

    def test_required_surface_include_patterns_match_surefire_defaults(self) -> None:
        self.assertEqual(
            sorted(trusted_execution.TEST_INCLUDE_PATTERNS),
            sorted(["Test*.java", "*Test.java", "*Tests.java", "*TestCase.java"]),
        )
        import fnmatch
        for name, expected in [
            ("TestSuiteX.java", True),      # Test*.java
            ("TestUtilities.java", True),   # Test*.java (prefix match, as Surefire does)
            ("AbilityKeyTest.java", True),  # *Test.java
            ("FooTests.java", True),        # *Tests.java
            ("FooTestCase.java", True),     # *TestCase.java
            ("Support.java", False),
            ("Helper.java", False),
            ("MyTestbench.java", False),
        ]:
            with self.subTest(name=name):
                matched = any(fnmatch.fnmatch(name, p)
                              for p in trusted_execution.TEST_INCLUDE_PATTERNS)
                self.assertEqual(matched, expected, name)

    def test_required_classes_come_from_the_trusted_comparison_base_tree(self) -> None:
        """The required class list must be read from Git, not from the working tree."""
        tmp = tempfile.mkdtemp(prefix="forge-d17-surface-")
        self.addCleanup(shutil.rmtree, tmp, True)
        root = Path(tmp)
        repo = root / "repo"
        (repo / "forge-game" / "src" / "test" / "java" / "pkg").mkdir(parents=True)
        subprocess.run(["git", "init", "-q", "-b", "master", "."], cwd=str(repo), check=True)
        subprocess.run(["git", "config", "user.email", "a@b.invalid"], cwd=str(repo), check=True)
        subprocess.run(["git", "config", "user.name", "T"], cwd=str(repo), check=True)
        base = repo / "forge-game" / "src" / "test" / "java" / "pkg"
        for name in ("AlphaTest.java", "BetaTest.java", "Support.java", "TestHelperX.java"):
            (base / name).write_text("package pkg;\n")
        subprocess.run(["git", "add", "-A"], cwd=str(repo), check=True)
        subprocess.run(["git", "commit", "-q", "-m", "base"], cwd=str(repo), check=True)
        base_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=str(repo), text=True).strip()

        found = trusted_execution.required_classes_for_module(repo, "forge-game", base_sha)
        self.assertEqual(found, ["pkg.AlphaTest", "pkg.BetaTest", "pkg.TestHelperX"])

        # A file present only in the working tree must not enter the required set.
        (base / "GammaTest.java").write_text("package pkg;\n")
        self.assertEqual(
            trusted_execution.required_classes_for_module(repo, "forge-game", base_sha),
            ["pkg.AlphaTest", "pkg.BetaTest", "pkg.TestHelperX"],
        )

    def test_required_classes_reject_an_unresolvable_base(self) -> None:
        tmp = tempfile.mkdtemp(prefix="forge-d17-surface2-")
        self.addCleanup(shutil.rmtree, tmp, True)
        repo = Path(tmp)
        subprocess.run(["git", "init", "-q", "-b", "master", "."], cwd=str(repo), check=True)
        with self.assertRaises(trusted_execution.ExecutionError):
            trusted_execution.required_classes_for_module(repo, "forge-game", "0" * 40)


def synthetic_class(strings) -> bytes:
    """A class file whose constant pool holds ``strings`` as CONSTANT_Utf8 entries.

    The admission scan reads only the constant pool, which is where every class,
    member and descriptor reference and every string literal lives.
    """
    pool = b"".join(b"\x01" + len(t.encode()).to_bytes(2, "big") + t.encode() for t in strings)
    pool += b"\x07" + (1).to_bytes(2, "big")  # a Class entry, to exercise the tag table
    return (b"\xca\xfe\xba\xbe" + (0).to_bytes(2, "big") + (61).to_bytes(2, "big")
            + (len(strings) + 2).to_bytes(2, "big") + pool + b"\x00" * 8)


class RedLaunchClasspathAdmission(EvidenceCase):
    """Review P1/P2 at 63731d9f: what may run next to the witness, and from where.

    One ``@Listeners`` class (or main code reaching ``Reporter``/``ISuite``) can
    rewrite the outcome of every other class in the TestNG suite, and a required
    class trusted javac did not produce would load from candidate bytecode.
    """

    def _with(self, module_key, field, value, record="launch_classpath_admission"):
        import copy
        manifest = copy.deepcopy(self.manifest)
        manifest[record][module_key][field] = value
        return manifest

    def test_honest_admission_passes(self) -> None:
        self.honest()
        self.assertEqual(self.verdict()["verdict"], qualify.PASS)

    def test_listener_registration_is_fail_even_with_all_tests_passing(self) -> None:
        self.honest()
        finding = [{"entry": "trusted-compiled tests", "class": "pkg/C0.class",
                    "problems": ["references org/testng/annotations/Listeners"]}]
        evidence = self.verdict(manifest=self._with("forge-game", "findings", finding))
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("launch_classpath_admitted", evidence["reason"])

    def test_tampered_dependency_jar_is_fail(self) -> None:
        self.honest()
        evidence = self.verdict(manifest=self._with("forge-gui-desktop", "tampered_jars",
                                                    ["org/powermock/x/1/x-1.jar"]))
        self.assertNotPass(evidence, qualify.FAIL)

    def test_unverified_dependency_jar_is_unknown(self) -> None:
        self.honest()
        evidence = self.verdict(manifest=self._with("forge-game", "unverified_jars", ["new/dep/1/dep-1.jar"]))
        self.assertNotPass(evidence, qualify.UNKNOWN)

    def test_missing_admission_record_is_unknown(self) -> None:
        import copy
        self.honest()
        manifest = copy.deepcopy(self.manifest)
        del manifest["launch_classpath_admission"]
        self.assertNotPass(self.verdict(manifest=manifest), qualify.UNKNOWN)

    def test_infrastructure_stop_before_compiling_is_unknown(self) -> None:
        """The real shape: cmd_execute's manifest after a trusted-side error, with no launches."""
        manifest = dict(self.manifest, modules={}, error="trusted TestNG input does not match its pinned digest")
        manifest.pop("trusted_test_compilation")
        manifest.pop("launch_classpath_admission")
        evidence = self.verdict(manifest=manifest, authenticated=False)
        self.assertNotPass(evidence, qualify.UNKNOWN)
        self.assertIn("stopped before compiling", evidence["reason"])

    def test_candidate_build_failure_is_still_fail(self) -> None:
        manifest = dict(self.manifest, modules={}, error="candidate build failed (exit 1)",
                        candidate_build={"exit_code": 1, "user": BUILD_USER})
        manifest.pop("trusted_test_compilation")
        manifest.pop("launch_classpath_admission")
        self.assertNotPass(self.verdict(manifest=manifest, authenticated=False), qualify.FAIL)

    def test_failed_trusted_compile_is_fail(self) -> None:
        self.honest()
        manifest = self._with("forge-game", "exit_code", 1, record="trusted_test_compilation")
        evidence = self.verdict(manifest=manifest)
        self.assertNotPass(evidence, qualify.FAIL)
        self.assertIn("trusted compilation", evidence["reason"])

    def test_required_class_not_trusted_compiled_is_fail(self) -> None:
        self.honest()
        manifest = self._with("forge-game", "compiled_required", ["pkg.C0"], record="trusted_test_compilation")
        self.assertNotPass(self.verdict(manifest=manifest), qualify.FAIL)

    def test_missing_compile_record_is_unknown(self) -> None:
        """Trusted infrastructure that stopped before compiling is not a candidate FAIL, nor credit."""
        import copy
        self.honest()
        manifest = copy.deepcopy(self.manifest)
        del manifest["trusted_test_compilation"]
        self.assertNotPass(self.verdict(manifest=manifest), qualify.UNKNOWN)


class FrozenLaunchClasspath(unittest.TestCase):
    """Review P1-B at e05e6f17: later launches must not read candidate-writable classpath state."""

    def test_admitted_bytes_are_frozen_and_launched_from_the_copy(self) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="d17-freeze-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        tests, root = tmp / "tests", tmp / "candidate"
        trusted_m2, candidate_m2 = tmp / "trusted-m2", tmp / "home" / ".m2" / "repository"
        out = root / "forge-game" / "target" / "classes" / "forge"
        for base in (tests, out, trusted_m2, candidate_m2):
            base.mkdir(parents=True)
        (out / "Game.class").write_bytes(synthetic_class(["forge/Game"]))
        rel = Path("org/dep/1/dep-1.jar")
        for repo in (trusted_m2, candidate_m2):
            (repo / rel).parent.mkdir(parents=True)
            (repo / rel).write_bytes(b"jar bytes")
        entries = [str(root / "forge-game" / "target" / "classes"), str(candidate_m2 / rel),
                   str(root / "not-yet-there")]
        staging, launch_root, cache = tmp / "staging", tmp / "bundle", {}
        scan = trusted_execution.scan_launch_classpath(tests, entries, root, candidate_m2, trusted_m2,
                                                       freeze=(staging, launch_root, cache))
        self.assertEqual(scan["findings"], [])
        launched = scan["launch_entries"]
        self.assertEqual(len(launched), 2, launched)
        self.assertTrue(all(entry.startswith(str(launch_root)) for entry in launched))
        frozen_dir = staging / Path(launched[0]).relative_to(launch_root)
        self.assertEqual((frozen_dir / "forge" / "Game.class").read_bytes(), synthetic_class(["forge/Game"]))
        # An earlier launch rewriting the candidate's tree changes nothing frozen.
        (out / "Game.class").write_bytes(synthetic_class(["forge/Game", "org/testng/Reporter"]))
        (root / "not-yet-there").mkdir()
        self.assertEqual((frozen_dir / "forge" / "Game.class").read_bytes(), synthetic_class(["forge/Game"]))
        # The same source admitted for a second module reuses the copy and its findings.
        again = trusted_execution.scan_launch_classpath(tests, entries[:2], root, candidate_m2, trusted_m2,
                                                        freeze=(staging, launch_root, cache))
        self.assertEqual(again["launch_entries"], launched)

    def test_execute_module_never_reads_the_candidate_classpath_file(self) -> None:
        import inspect
        source = inspect.getsource(trusted_execution.execute_module)
        self.assertNotIn("candidate_classpath(", source)
        params = inspect.signature(trusted_execution.execute_module).parameters
        self.assertIn("candidate_code_entries", params)
        self.assertIn("trusted_dependency_entries", params)

    def test_candidate_loader_parent_cannot_expose_trusted_dependency_bridge(self) -> None:
        source = (Path(__file__).resolve().parent / "witness" / "forge" / "d17" / "witness"
                  / "Containment.java").read_text()
        block = source.split("static final class CandidateCodeLoader", 1)[1].split(
            "static final class TrustedTestLoader", 1)[0]
        self.assertIn("super(urls, ClassLoader.getPlatformClassLoader());", block)
        self.assertIn("this.dependencies = dependencies;", block)
        self.assertNotIn("super(urls, dependencies);", block)

    def test_candidate_bytecode_is_never_on_the_system_classpath(self) -> None:
        import inspect
        source = inspect.getsource(trusted_execution.execute_module)
        self.assertIn(
            'full_cp = assemble_classpath(bundle / "witness", staged_testng)',
            source,
        )
        self.assertNotIn(
            'assemble_classpath(bundle / "witness", staged_testng + list(candidate_code_entries))',
            source,
        )
        self.assertIn('--candidate-code', source)
        self.assertIn('--trusted-dependency', source)


class IntegrityRecordShape(EvidenceCase):
    """Review P2 at 63731d9f: qualify must accept the record sandbox.py verify really writes."""

    def test_verify_record_names_the_account_and_is_accepted(self) -> None:
        import sandbox
        doc = sandbox.integrity(self.root / "no-repo", "0" * 40, ".github", [],
                                SANDBOX_USER, [], [BUILD_USER])
        self.assertEqual(doc.get("user"), SANDBOX_USER)
        self.assertEqual(doc.get("users"), [SANDBOX_USER, BUILD_USER])
        # Same record, as it reads once every check held.
        doc.update({"status": "OK", "violations": []})
        self.honest()
        evidence = self.verdict(integrity=doc)
        self.assertEqual(evidence["verdict"], qualify.PASS, evidence["reason"])

    def test_a_pass_that_was_never_dispatched_is_not_credit(self) -> None:
        """Review P1-A at e05e6f17: a dry-run success carries invoked=false."""
        self.honest()
        path = self.witness_dir / "forge-game.witness.jsonl"
        path.write_text(path.read_text().replace('"invoked": true', '"invoked": false', 1))
        evidence = self.verdict()
        self.assertNotPass(evidence, qualify.UNKNOWN)
        self.assertTrue(any("without dispatch" in p for p in qualification_of(evidence)["problems"]))

    def test_a_tail_after_the_summary_is_not_credit(self) -> None:
        self.honest()
        path = self.witness_dir / "forge-game.witness.jsonl"
        path.write_text(path.read_text() + json.dumps({
            "kind": "invocation", "seq": 99, "class": "pkg.C0", "method": "late", "status": "PASS", "invoked": True,
            "context": "TestNG", "thread": "TestNG-0"}, sort_keys=True) + "\n")
        self.assertNotEqual(self.verdict()["verdict"], qualify.PASS)


class BytecodeAdmissionScan(unittest.TestCase):
    """The constant-pool scan that feeds launch_classpath_admission."""

    def findings(self, strings, name="forge/FooTest"):
        return trusted_execution.bytecode_findings(name, synthetic_class(strings))

    def test_forge_test_vocabulary_is_admitted(self) -> None:
        self.assertEqual(self.findings([
            "forge/FooTest", "org/testng/annotations/Test", "Lorg/testng/annotations/BeforeClass;",
            "org/testng/Assert", "org/testng/AssertJUnit", "org/testng/SkipException",
            "Lorg/testng/annotations/DataProvider;", "org/testng/Assert$ThrowingRunnable",
            "org.testng.Assert.assertEquals",
        ]), [])

    def test_listener_and_result_access_are_refused(self) -> None:
        for reference in ("Lorg/testng/annotations/Listeners;", "org/testng/IInvokedMethodListener",
                          "org/testng/ITestListener", "org/testng/IHookable", "org/testng/IConfigurable",
                          "org/testng/Reporter", "org/testng/ITestResult", "org/testng/ISuite",
                          "org/testng/ITestContext", "org/testng/TestNG", "org/testng/internal/Utils",
                          "Lorg/testng/annotations/Factory;", "Lorg/testng/annotations/ObjectFactory;",
                          "org.testng.Reporter", "org.testng.internal.TestResult"):
            with self.subTest(reference=reference):
                self.assertTrue(self.findings(["forge/FooTest", reference]), reference)

    def test_witness_package_and_references_are_refused(self) -> None:
        self.assertTrue(self.findings(["forge/d17/witness/Spy"], name="forge/d17/witness/Spy"))
        self.assertTrue(self.findings(["forge/FooTest", "forge.d17.witness.QualifiedExecutionListener"]))

    def test_unparseable_class_is_refused(self) -> None:
        self.assertTrue(trusted_execution.bytecode_findings("forge/X", b"not a class"))
        self.assertTrue(trusted_execution.bytecode_findings("forge/X", synthetic_class(["a"])[:12]))

    def test_dependency_jar_must_equal_the_trusted_repository(self) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="d17-admit-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        tests, root = tmp / "tests", tmp / "candidate"
        trusted_m2, candidate_m2 = tmp / "trusted-m2", tmp / "home" / ".m2" / "repository"
        for base in (tests, root, trusted_m2, candidate_m2):
            base.mkdir(parents=True)
        (tests / "pkg").mkdir()
        (tests / "pkg" / "C0.class").write_bytes(synthetic_class(["pkg/C0", "org/testng/annotations/Test"]))
        rel = Path("org/dep/1/dep-1.jar")
        for repo, body in ((trusted_m2, b"same"), (candidate_m2, b"same")):
            (repo / rel).parent.mkdir(parents=True)
            (repo / rel).write_bytes(body)
        new = Path("org/new/1/new-1.jar")
        (candidate_m2 / new).parent.mkdir(parents=True)
        (candidate_m2 / new).write_bytes(b"new")
        entries = [str(candidate_m2 / rel), str(candidate_m2 / new)]
        scan = trusted_execution.scan_launch_classpath(tests, entries, root, candidate_m2, trusted_m2)
        self.assertEqual((scan["findings"], scan["tampered_jars"]), ([], []))
        self.assertEqual(scan["unverified_jars"], [str(new)])
        (candidate_m2 / rel).write_bytes(b"rewritten by the candidate account")
        scan = trusted_execution.scan_launch_classpath(tests, entries, root, candidate_m2, trusted_m2)
        self.assertEqual(scan["tampered_jars"], [str(rel)])

    def test_candidate_output_directory_is_scanned_and_fifos_refused(self) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="d17-admit-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        tests, root = tmp / "tests", tmp / "candidate"
        classes = root / "forge-game" / "target" / "classes" / "forge"
        classes.mkdir(parents=True)
        tests.mkdir()
        (classes / "Evil.class").write_bytes(synthetic_class(["forge/Evil", "org/testng/Reporter"]))
        os.mkfifo(str(classes / "Blocker.class"))
        scan = trusted_execution.scan_launch_classpath(
            tests, [str(root / "forge-game" / "target" / "classes")], root, tmp / "m2", None)
        problems = {f["class"]: f["problems"] for f in scan["findings"]}
        self.assertIn("references org/testng/Reporter", problems["forge/Evil.class"])
        self.assertEqual(problems["forge/Blocker.class"], ["not a regular class file"])
        outside = trusted_execution.scan_launch_classpath(tests, [str(tmp)], root, tmp / "m2", None)
        self.assertEqual(outside["unverified_jars"], [str(Path(os.path.realpath(tmp)))])
        self.assertEqual(outside["findings"], [])


class ExportIntegrity(unittest.TestCase):
    """The candidate is built and its tests recompiled from bytes equal to its blobs.

    ``git archive`` applies the tree's own ``.gitattributes``; a candidate could
    otherwise test bytes that are not its locked commit (ported from C12 CTRL-67).
    """

    def _commit(self, repo: Path, files: dict) -> str:
        for rel, text in files.items():
            (repo / rel).parent.mkdir(parents=True, exist_ok=True)
            (repo / rel).write_text(text)
        git = ["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@t"]
        subprocess.run(git + ["add", "-A"], check=True)
        subprocess.run(git + ["commit", "-q", "-m", "c"], check=True)
        return subprocess.run(git + ["rev-parse", "HEAD"], check=True, capture_output=True,
                              text=True).stdout.strip()

    def _repo(self, files: dict) -> tuple[Path, str]:
        tmp = Path(tempfile.mkdtemp(prefix="d17-export-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        repo = tmp / "repo"
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        return tmp, self._commit(repo, files)

    def test_plain_export_matches_its_blobs(self) -> None:
        import sandbox
        tmp, sha = self._repo({"src/A.java": "class A {}\n", "run.sh": "echo $Format:%H$\n"})
        sandbox.export_commit(tmp / "repo", sha, tmp / "out")
        self.assertEqual((tmp / "out" / "run.sh").read_text(), "echo $Format:%H$\n")

    def test_export_subst_is_refused(self) -> None:
        import sandbox
        tmp, sha = self._repo({".gitattributes": "*.java export-subst\n",
                               "src/A.java": "class A { String v = \"$Format:%H$\"; }\n"})
        with self.assertRaisesRegex(sandbox.SandboxError, "differs from its blobs"):
            sandbox.export_commit(tmp / "repo", sha, tmp / "out")

    def test_export_ignore_is_refused(self) -> None:
        import sandbox
        tmp, sha = self._repo({".gitattributes": "src/B.java export-ignore\n",
                               "src/A.java": "class A {}\n", "src/B.java": "class B {}\n"})
        with self.assertRaisesRegex(sandbox.SandboxError, "differs from its blobs"):
            sandbox.export_commit(tmp / "repo", sha, tmp / "out")


class WorkflowContractControls(unittest.TestCase):
    """Assert the trust-critical properties of the trusted workflow definition."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow_path = HERE.parent / "workflows" / "forge-candidate-qualification.yml"
        cls.text = cls.workflow_path.read_text()
        match = re.search(r"^\s*QUALIFY_EXPECTED_MODULES:\s*\"([^\"]+)\"", cls.text, re.MULTILINE)
        assert match, "QUALIFY_EXPECTED_MODULES is not declared in the workflow"
        cls.modules = match.group(1).split()
        out_of_band = re.search(
            r"^\s*QUALIFY_OUT_OF_BAND_CLASSES:\s*\"([^\"]*)\"", cls.text, re.MULTILINE)
        assert out_of_band, "QUALIFY_OUT_OF_BAND_CLASSES is not declared in the workflow"
        cls.out_of_band = out_of_band.group(1).split()

    @staticmethod
    def _step_run_block(text: str, step_name: str) -> str:
        """Extract the ``run:`` body of the named step by indentation.

        Assertions must be made against the executed command, never against a
        substring a comment could satisfy.
        """
        lines = text.splitlines()
        try:
            start = next(i for i, line in enumerate(lines)
                         if line.strip() == "- name: {}".format(step_name))
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

    def test_expected_surface_is_declared_and_not_empty(self) -> None:
        self.assertTrue(self.modules)
        for module in self.modules:
            self.assertRegex(module, r"^[a-z0-9][a-z0-9-]*$")

    def test_trigger_is_the_trusted_event_not_the_untrusted_one(self) -> None:
        self.assertIn("pull_request_target:", self.text)
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

    def test_verdict_is_surfaced_verbatim_and_never_hardcoded(self) -> None:
        verdict = self._step_run_block(self.text, "Derive the exact-SHA qualification verdict")
        self.assertIn("exit_code=$rc", verdict)
        for forbidden in ("exit_code=0", "exit_code=1", "|| true"):
            with self.subTest(token=forbidden):
                self.assertNotIn(forbidden, verdict)
        self.assertNotRegex(verdict, r"qualify\.py[^\n]*\|\|")
        surface = self._step_run_block(self.text, "Surface classification")
        self.assertIn('exit "$EXIT_CODE"', surface)
        self.assertIn('if: always()', self.text)

    def test_out_of_band_declarations_are_explicit_and_well_formed(self) -> None:
        self.assertTrue(self.out_of_band, "out-of-band list must be explicit, not implicit")
        self.assertEqual(self.out_of_band, [STRESS_CLASS],
                         "only the measured network-stress class may be declared")
        for klass in self.out_of_band:
            self.assertRegex(klass, r"^[a-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")

    def test_verifier_is_read_from_the_trusted_checkout(self) -> None:
        self.assertIn(".github/qualification/qualify.py", self.text)
        self.assertIn(".github/qualification/source_lock.py", self.text)
        self.assertIn(".github/qualification/trusted_execution.py", self.text)

    def test_execution_uses_the_trusted_orchestrator_not_the_candidate_build(self) -> None:
        """The gate must not take test counts from the candidate's Maven run."""
        self.assertIn("trusted_execution.py", self.text)
        # The candidate's own test phase must not be the source of credit.
        self.assertNotRegex(self.text, r"mvn[^\n]*\btest\b")
        # Witness and required surface ledgers must be produced and consumed.
        self.assertIn("required-surface.json", self.text)
        self.assertIn("execution-manifest.json", self.text)
        self.assertIn("witness", self.text)


    def test_candidate_build_and_execution_use_distinct_sandbox_accounts(self) -> None:
        """Build/plugin code cannot share the hostile-bytecode execution UID."""
        prepare = self._step_run_block(self.text, "Prepare candidate sandbox (separate OS identity)")
        for required in ('sandbox.py prepare', '--user "$D17_BUILD_USER"', "--harden-world-writable",
                         "--stage-jdk", "--stage-maven", '--probe "$GITHUB_WORKSPACE"', '--probe "$RUNNER_TEMP"',
                         'sandbox.py seal'):
            with self.subTest(prepare=required):
                self.assertIn(required, prepare)
        execute = self._step_run_block(self.text, "Run trusted witnessed execution as the sandbox account")
        for required in ("trusted_execution.py execute", '--sandbox-user "$D17_BUILD_USER"',
                         '--execution-user "$D17_EXEC_USER"',
                         '--execution-sandbox-dir "$D17_EXEC_SANDBOX_DIR"',
                         '--mvn "$D17_TRUSTED_MAVEN/bin/mvn"', '--java "$D17_TRUSTED_JDK/bin/java"',
                         "--trusted-testng", 'export PATH="$D17_TRUSTED_PATH"', "sandbox.py seal"):
            with self.subTest(execute=required):
                self.assertIn(required, execute)
        verify = self._step_run_block(self.text, "Verify trusted state integrity")
        self.assertIn("sandbox.py verify", verify)
        self.assertIn('--user "$D17_EXEC_USER"', verify)
        self.assertIn('--additional-user "$D17_BUILD_USER"', verify)
        self.assertIn("INTEGRITY.json", verify)
        verdict = self._step_run_block(self.text, "Derive the exact-SHA qualification verdict")
        self.assertIn("--integrity", verdict)
        # The candidate tree is never checked out into a runner-owned workspace.
        self.assertNotIn("git clone --quiet --no-checkout --shared", self.text)
        self.assertNotRegex(self.text, r"-Dforge\.d17\.(nonce|witness)")
        # Trusted steps after candidate code never resolve python from PATH.
        for step in (execute, verify):
            self.assertNotRegex(step, r"(^|\s)python3 ")
        # Trusted Python on the runner the candidate shares runs isolated, without
        # site (no .pth file runs) and without writing bytecode caches.
        for step in (prepare, execute, verify):
            for line in re.findall(r"/usr/bin/python3[^\n]*", step):
                with self.subTest(python=line):
                    self.assertTrue(line.startswith("/usr/bin/python3 -I -S -B "), line)
        # Every dependency jar is compared with the runner's own repository, and
        # verify re-derives trusted files at the proved authority, not at HEAD.
        self.assertIn('--trusted-maven-repo "$HOME/.m2/repository"', execute)
        self.assertIn('--trusted-sha "$AUTHORITY_SHA"', verify)
        self.assertNotIn("rev-parse HEAD", verify)
        self.assertIn('--probe "$D17_TRUSTED_MAVEN"', verify)
        # prepare resolves Maven first, then switches to the trusted PATH it probes.
        self.assertLess(prepare.index("maven_home="), prepare.index('export PATH="$D17_TRUSTED_PATH"'))
        self.assertLess(prepare.index('export PATH="$D17_TRUSTED_PATH"'), prepare.index("sandbox.py prepare"))
        self.assertIn('--stage-maven "$maven_home"', prepare)

    def test_trusted_state_lives_outside_world_writable_opt(self) -> None:
        for key in ("D17_BUNDLE_DIR", "D17_RUNTIME_DIR", "D17_TRUSTED_JDK", "D17_TRUSTED_MAVEN"):
            match = re.search(r"^\s*{}:\s*(\S+)".format(key), self.text, re.MULTILINE)
            self.assertIsNotNone(match, key)
            self.assertTrue(match.group(1).startswith("/var/lib/"), key)

if __name__ == "__main__":
    unittest.main(verbosity=2)