#!/usr/bin/env python3
"""Derive a source-bound exact-SHA candidate qualification verdict for Forge.

Trust boundary
--------------
This script runs from the trusted default-branch checkout and is the only writer
of the qualification verdict.  It never reads a verdict from the candidate tree.
A candidate that can author its own qualification result does not need to pass
any test, so candidate-produced files contribute *counts only*: this module
parses candidate test reports for executed/failed/skipped counts and never for a
status, verdict, pass/fail flag or any other success signal.  ``derive_verdict``
is a pure function of the trusted source lock, the trusted run report and those
counts.

Fail-closed contract
--------------------
``PASS`` requires positive proof that the *exact* candidate was checked out, that
the bounded expected test surface reported, and that every expected test executed
without failure.  Absent, empty, malformed, incomplete or ambiguous evidence is
``FAIL``, ``PARTIAL``, ``NOT_RUN`` or ``UNKNOWN`` -- never ``PASS``.  Each
non-PASS class has its own nonzero exit code so that no consumer can read a
non-PASS qualification as a green check.

Evidence classes are reported separately and are never aliased into each other:

* ``pr_mergeability`` -- not an input to this verdict at all;
* ``synthetic_merge_evidence`` -- never computed here;
* ``exact_candidate_qualification`` -- the only class this gate asserts;
* ``rules_qualification_evidence`` -- explicitly NOT CLAIMED, because a green
  CI qualification is not a Magic Rules qualification.
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SCHEMA = "forge.candidate-qualification.evidence/1"
RUN_REPORT_SCHEMA = "forge.candidate-qualification.run-report/1"

PASS = "PASS"
FAIL = "FAIL"
PARTIAL = "PARTIAL"
NOT_RUN = "NOT_RUN"
UNKNOWN = "UNKNOWN"

#: Distinct exit codes.  Only PASS exits 0.
EXIT_CODES = {PASS: 0, FAIL: 1, PARTIAL: 2, NOT_RUN: 3, UNKNOWN: 4}

#: Test reports are discovered only at this fixed path shape.
REPORT_PREFIX = "TEST-"
REPORT_SUFFIX = ".xml"
REPORT_TARGET_DIR = "target"
REPORT_DIR_NAME = "surefire-reports"

#: Keys that would let candidate-authored data smuggle in a verdict.  Their
#: presence in a report is ignored by construction and reported as ignored.
VERDICT_KEYS = ("verdict", "status", "result", "outcome", "passed", "success")

_NOT_APPLICABLE = "NOT_APPLICABLE"
_NOT_CLAIMED = "NOT_CLAIMED"


class QualificationError(Exception):
    """The trusted inputs are unusable.  Always fail closed."""


# --------------------------------------------------------------------------- #
# Trusted input loading
# --------------------------------------------------------------------------- #


def load_json(path: Path, label: str) -> dict:
    path = Path(path)
    if not path.is_file():
        raise QualificationError("{} is missing: {}".format(label, path))
    try:
        data = json.loads(path.read_text())
    except (ValueError, OSError) as exc:
        raise QualificationError("{} is unreadable: {}".format(label, exc))
    if not isinstance(data, dict):
        raise QualificationError("{} is not a JSON object".format(label))
    return data


def _as_int(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise QualificationError("{} must be an integer, got {!r}".format(label, value))
    return value


def _int_from(element: ET.Element, name: str) -> int:
    raw = element.get(name)
    if raw is None:
        return 0
    try:
        return int(raw)
    except ValueError:
        raise ValueError("attribute {}={!r} is not an integer".format(name, raw))


# --------------------------------------------------------------------------- #
# Candidate test reports: counts only, never a verdict
# --------------------------------------------------------------------------- #


def discover_reports(reports_root: Path) -> "list[Path]":
    """Discover candidate-produced surefire reports under ``reports_root``.

    Only files shaped exactly ``<module>/target/surefire-reports/TEST-*.xml``
    are read, so unrelated candidate XML anywhere else in the tree cannot pose
    as qualification evidence.
    """
    reports_root = Path(reports_root)
    if not reports_root.is_dir():
        return []
    found = []
    for path in reports_root.rglob(REPORT_PREFIX + "*" + REPORT_SUFFIX):
        parts = path.relative_to(reports_root).parts
        if len(parts) != 4:
            continue
        if parts[1] != REPORT_TARGET_DIR or parts[2] != REPORT_DIR_NAME:
            continue
        found.append(path)
    return sorted(found)


def parse_report(path: Path) -> dict:
    """Parse one surefire report into counts and skipped-class attribution only.

    ``ignored_verdict_keys`` records any verdict-shaped attribute that was seen
    and deliberately discarded.  Nothing else from the report is read.
    """
    path = Path(path)
    try:
        root = ET.parse(str(path)).getroot()
    except ET.ParseError as exc:
        raise ValueError("{}: malformed test report: {}".format(path, exc))
    if root.tag != "testsuite":
        raise ValueError("{}: root element is {!r}, expected 'testsuite'".format(path, root.tag))

    ignored = sorted(key for key in VERDICT_KEYS if root.get(key) is not None)
    skipped_classes = set()
    for case in root.iter("testcase"):
        if any(child.tag == "skipped" for child in case):
            name = case.get("classname")
            # A skip that cannot be attributed to a class is not attributable to
            # an out-of-band declaration either.
            skipped_classes.add(name if name else "")
    return {
        "report": path.name,
        "module": path.parts[-4],
        "tests": _int_from(root, "tests"),
        "failures": _int_from(root, "failures"),
        "errors": _int_from(root, "errors"),
        "skipped": _int_from(root, "skipped"),
        "skipped_classes": sorted(skipped_classes),
        "ignored_verdict_keys": ignored,
    }


def parse_reports(paths: "list[Path]") -> "tuple[list[dict], list[str]]":
    """Parse every report; return ``(parsed, problems)``.

    A single unparseable report makes the whole evidence set ambiguous, so the
    problem is reported rather than silently dropped.
    """
    parsed: "list[dict]" = []
    problems: "list[str]" = []
    for path in paths:
        try:
            parsed.append(parse_report(path))
        except ValueError as exc:
            problems.append(str(exc))
    return parsed, problems


def module_totals(parsed: "list[dict]") -> "dict[str, dict]":
    totals: "dict[str, dict]" = {}
    for report in parsed:
        bucket = totals.setdefault(
            report["module"],
            {"reports": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0},
        )
        bucket["reports"] += 1
        for key in ("tests", "failures", "errors", "skipped"):
            bucket[key] += report[key]
    return totals


# --------------------------------------------------------------------------- #
# Verdict derivation (pure)
# --------------------------------------------------------------------------- #


def derive_verdict(
    lock: dict,
    run_report: dict,
    parsed_reports: "list[dict]",
    report_problems: "list[str]",
    expected_modules: "list[str]",
    out_of_band_classes: "list[str]",
    report_count: int,
) -> dict:
    """Derive the qualification verdict.

    Pure function: the result depends only on the arguments.  Candidate-produced
    reports contribute counts and skipped-class attribution; they cannot
    contribute a status.
    """
    signals: "list[dict]" = []

    def signal(name: str, ok: bool, detail: str) -> None:
        signals.append({"signal": name, "satisfied": bool(ok), "detail": detail})

    # --- 1. Exact candidate identity binding -------------------------------- #
    bound_sha = run_report.get("candidate_sha")
    bound_tree = run_report.get("candidate_tree")
    identity_ok = bound_sha == lock["candidate"]["sha"] and bound_tree == lock["candidate"]["tree"]
    signal(
        "candidate_identity_bound",
        identity_ok,
        "run report claims sha={} tree={}; locked candidate sha={} tree={}".format(
            bound_sha, bound_tree, lock["candidate"]["sha"], lock["candidate"]["tree"]
        ),
    )

    # --- 2. Trusted run state ---------------------------------------------- #
    completed = run_report.get("completed") is True
    signal("run_completed", completed, "completed={!r}".format(run_report.get("completed")))

    step_codes = run_report.get("step_exit_codes")
    step_codes_ok = (
        isinstance(step_codes, dict)
        and bool(step_codes)
        and all(
            isinstance(code, int) and not isinstance(code, bool) and code == 0
            for code in step_codes.values()
        )
    )
    signal(
        "trusted_steps_succeeded",
        step_codes_ok,
        "step_exit_codes={!r}".format(step_codes),
    )

    # --- 3. Report discovery and parse integrity ---------------------------- #
    signal(
        "reports_present",
        report_count > 0,
        "{} candidate test report(s) discovered".format(report_count),
    )
    signal(
        "reports_parsed",
        not report_problems,
        "{} parse problem(s)".format(len(report_problems)),
    )

    totals = module_totals(parsed_reports)
    executed = sum(bucket["tests"] for bucket in totals.values())
    failures = sum(bucket["failures"] for bucket in totals.values())
    errors = sum(bucket["errors"] for bucket in totals.values())
    skipped = sum(bucket["skipped"] for bucket in totals.values())

    signal("tests_executed", executed > 0, "{} test case(s) executed".format(executed))

    missing_modules = sorted(set(expected_modules) - set(totals))
    signal(
        "expected_surface_reported",
        not missing_modules,
        "expected={} missing={}".format(sorted(expected_modules), missing_modules),
    )

    # Skipped cases are not executed proof.  Only skips inside a test class the
    # trusted contract declares out of band are excluded, and the exclusion list
    # is part of the trusted definition, never candidate data.  A skip that
    # cannot be attributed to a declared class stays uncredited.
    observed_skipped_classes = sorted(
        {name for report in parsed_reports for name in report["skipped_classes"]}
    )
    declared = set(out_of_band_classes)
    undeclared = sorted(name for name in observed_skipped_classes if name not in declared)
    unattributable = skipped > 0 and not observed_skipped_classes
    if unattributable:
        undeclared = sorted(set(undeclared) | {"<unattributable>"})

    signal(
        "no_undeclared_skips",
        not undeclared,
        "skipped={} declared_out_of_band={} observed_classes={} undeclared={}".format(
            skipped, sorted(declared), observed_skipped_classes, undeclared
        ),
    )

    signal(
        "no_failed_cases",
        failures == 0 and errors == 0,
        "{} failure(s), {} error(s)".format(failures, errors),
    )

    by_name = {item["signal"]: item for item in signals}

    # --- 5. Classification, strongest negative signal first ----------------- #
    if not by_name["candidate_identity_bound"]["satisfied"]:
        verdict = FAIL
        reason = "candidate identity is not bound to the locked exact SHA/TREE"
    elif not by_name["trusted_steps_succeeded"]["satisfied"]:
        verdict = FAIL
        reason = "a trusted qualification step reported a nonzero exit code"
    elif not by_name["run_completed"]["satisfied"]:
        verdict = FAIL
        reason = "the qualification run did not complete"
    elif not by_name["no_failed_cases"]["satisfied"]:
        verdict = FAIL
        reason = "candidate test evidence contains failures or errors"
    elif not by_name["reports_present"]["satisfied"]:
        verdict = NOT_RUN
        reason = "no candidate test report was discovered; execution is unproven"
    elif not by_name["reports_parsed"]["satisfied"]:
        verdict = UNKNOWN
        reason = "candidate test evidence is malformed; the executed surface is ambiguous"
    elif not by_name["tests_executed"]["satisfied"]:
        verdict = UNKNOWN
        reason = "candidate test reports contain no executed test case"
    elif not by_name["expected_surface_reported"]["satisfied"]:
        verdict = PARTIAL
        reason = "expected qualification modules reported no evidence"
    elif not by_name["no_undeclared_skips"]["satisfied"]:
        verdict = PARTIAL
        reason = (
            "test cases inside the qualified surface were skipped and are not "
            "declared out of band by the trusted contract: {}".format(undeclared)
        )
    else:
        verdict = PASS
        reason = "exact candidate SHA/TREE executed the full bounded surface without failure"

    return {
        "verdict": verdict,
        "reason": reason,
        "signals": signals,
        "counts": {
            "tests": executed,
            "failures": failures,
            "errors": errors,
            "skipped": skipped,
            "reports": report_count,
            "modules": totals,
        },
        "expected_modules": sorted(expected_modules),
        "missing_modules": missing_modules,
        "report_problems": report_problems,
        "skips": {
            "policy": "SKIPS_OUTSIDE_DECLARED_OUT_OF_BAND_SURFACE_ARE_PARTIAL",
            "declared_out_of_band_classes": sorted(declared),
            "observed_skipped_classes": observed_skipped_classes,
            "undeclared_skipped_classes": undeclared,
            "unattributable_skips": unattributable,
            "skipped_total": skipped,
        },
    }


# --------------------------------------------------------------------------- #
# Evidence assembly
# --------------------------------------------------------------------------- #


def build_evidence(
    lock: dict,
    run_report: dict,
    reports_root: Path,
    expected_modules: "list[str]",
    out_of_band_classes: "list[str]" = None,
) -> dict:
    """Assemble the complete, separately-classed qualification evidence."""
    paths = discover_reports(reports_root)
    parsed, problems = parse_reports(paths)
    outcome = derive_verdict(
        lock,
        run_report,
        parsed,
        problems,
        expected_modules,
        list(out_of_band_classes or []),
        len(paths),
    )

    ignored_keys = sorted({key for report in parsed for key in report["ignored_verdict_keys"]})

    return {
        "schema": SCHEMA,
        "verdict": outcome["verdict"],
        "exit_code": EXIT_CODES[outcome["verdict"]],
        "reason": outcome["reason"],
        "source_lock": {
            "workflow_authority_sha": lock["workflow_authority"]["sha"],
            "workflow_authority_tree": lock["workflow_authority"]["tree"],
            "workflow_authority_ref": lock["workflow_authority"]["ref"],
            "trusted_default_branch": lock["trusted_default_branch"],
            "run_identity_sha": lock["run_identity"]["sha"],
            "event_base": lock.get("event_base"),
        },
        "candidate": {
            "sha": lock["candidate"]["sha"],
            "tree": lock["candidate"]["tree"],
            "fetched_from": lock["candidate"]["fetched_from"],
        },
        "comparison_base": {
            "sha": lock["comparison_base"]["sha"],
            "tree": lock["comparison_base"]["tree"],
            "role": "inspected Git data only; not mergeability, not a synthetic merge",
        },
        "exact_candidate_qualification": {
            "verdict": outcome["verdict"],
            "reason": outcome["reason"],
            "signals": outcome["signals"],
            "counts": outcome["counts"],
            "expected_modules": outcome["expected_modules"],
            "missing_modules": outcome["missing_modules"],
            "report_problems": outcome["report_problems"],
            "skips": outcome["skips"],
        },
        # --- Separately reported, never-promoted evidence classes ------------ #
        "pr_mergeability": {
            "status": _NOT_APPLICABLE,
            "credit": "none",
            "note": "PR mergeability is not an input to exact-SHA candidate qualification",
        },
        "synthetic_merge_evidence": {
            "status": "NEVER_COMPUTED",
            "credit": "none",
            "note": "no synthetic merge is computed or consulted by this gate",
        },
        "rules_qualification_evidence": {
            "status": _NOT_CLAIMED,
            "credit": "none",
            "note": (
                "a green CI qualification is not a Magic Rules qualification; "
                "this gate asserts no Rules correctness credit"
            ),
        },
        "trust_boundary": {
            "candidate_code_executed_as_validator": False,
            "verdict_read_from_candidate": False,
            "candidate_definition_divergent": lock["candidate_definition_divergence"]["divergent"],
            "candidate_definition_note": lock["candidate_definition_divergence"]["note"],
            "ignored_candidate_verdict_keys": ignored_keys,
            "note": (
                "candidate test reports contributed executed/failed/skipped counts and "
                "skipped-class attribution only; verdict-shaped attributes found in them "
                "were discarded and listed above"
            ),
        },
        "maven_command": run_report.get("maven_command"),
        "repository": lock.get("repository"),
    }


def write_evidence(evidence: dict, path: Path) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n")


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lock", required=True, help="trusted source lock JSON")
    parser.add_argument("--run-report", required=True, help="trusted run report JSON")
    parser.add_argument(
        "--reports-root",
        required=True,
        help="root of the candidate checkout to discover surefire reports in",
    )
    parser.add_argument(
        "--expect-report-module",
        action="append",
        default=[],
        required=True,
        help="module that must report; repeatable",
    )
    parser.add_argument(
        "--out-of-band-test-class",
        action="append",
        default=[],
        help=(
            "test class declared outside the qualified surface by the trusted "
            "contract; only skips in these classes are excluded. Repeatable."
        ),
    )
    parser.add_argument("--output", required=True, help="path of the evidence JSON to write")
    args = parser.parse_args(argv)

    # The trusted validator is imported from the trusted checkout only.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import source_lock  # noqa: E402  (trusted-path import, intentionally local)

    try:
        lock = source_lock.load_lock(Path(args.lock))
        run_report = load_json(Path(args.run_report), "run report")
    except (source_lock.SourceLockError, QualificationError) as exc:
        # Fail closed, but stay visible: emit an explicit FAIL evidence document
        # instead of leaving no artifact at all.
        evidence = {
            "schema": SCHEMA,
            "verdict": FAIL,
            "exit_code": EXIT_CODES[FAIL],
            "reason": "trusted qualification inputs are unusable: {}".format(exc),
            "exact_candidate_qualification": {
                "verdict": FAIL,
                "reason": "trusted qualification inputs are unusable: {}".format(exc),
            },
            "pr_mergeability": {"status": _NOT_APPLICABLE, "credit": "none"},
            "synthetic_merge_evidence": {"status": "NEVER_COMPUTED", "credit": "none"},
            "rules_qualification_evidence": {"status": _NOT_CLAIMED, "credit": "none"},
            "trust_boundary": {"candidate_code_executed_as_validator": False},
        }
        write_evidence(evidence, Path(args.output))
        sys.stderr.write("qualify: {}\n".format(exc))
        return EXIT_CODES[FAIL]

    evidence = build_evidence(
        lock,
        run_report,
        Path(args.reports_root),
        args.expect_report_module,
        args.out_of_band_test_class,
    )
    write_evidence(evidence, Path(args.output))
    sys.stdout.write(
        "qualify: {} candidate={} tree={} (reason: {})\n".format(
            evidence["verdict"],
            evidence["candidate"]["sha"],
            evidence["candidate"]["tree"],
            evidence["reason"],
        )
    )
    return int(evidence["exit_code"])


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    raise SystemExit(main())