#!/usr/bin/env python3
"""D20 Forge source-bound test provenance collector and adversarial self-tests."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from decimal import Decimal, InvalidOperation
from pathlib import Path

SCHEMA = "forge.test-provenance/1"
KNOWN_SCHEMA = "forge.known-not-run/1"
PASS, FAIL, PARTIAL, UNKNOWN = "PASS", "FAIL", "PARTIAL", "UNKNOWN"
TEST_ANNOTATION = re.compile(r"@(?:org\.testng\.annotations\.)?Test\b")
DISABLED_TEST = re.compile(
    r"@(?:org\.testng\.annotations\.)?Test\s*\([^)]*\benabled\s*=\s*false\b[^)]*\)",
    re.DOTALL,
)
PACKAGE = re.compile(r"(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;")


def lname(tag):
    return tag.rsplit("}", 1)[-1]


def strip_java(text):
    pattern = re.compile(
        r"/\*.*?\*/|//[^\n]*|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'",
        re.DOTALL,
    )
    return pattern.sub(lambda m: "\n" * m.group(0).count("\n"), text)


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def git(repo, *args):
    proc = subprocess.run(
        ["git", *args], cwd=str(repo), text=True, capture_output=True, check=False
    )
    if proc.returncode:
        raise RuntimeError(
            "git {} failed: {}".format(" ".join(args), proc.stderr.strip())
        )
    return proc.stdout.strip()


def source_identity(repo, expected_sha=None):
    sha = git(repo, "rev-parse", "HEAD")
    tree = git(repo, "rev-parse", "HEAD^{tree}")
    errors = []
    if expected_sha and sha != expected_sha:
        errors.append(
            "checked-out source SHA {} does not match expected {}".format(
                sha, expected_sha
            )
        )
    return {"sha": sha, "tree": tree, "errors": errors}


def default_modules(repo):
    try:
        root = ET.parse(str(repo / "pom.xml")).getroot()
    except (ET.ParseError, OSError) as exc:
        raise ValueError("root pom.xml unreadable: {}".format(exc))
    modules = []
    for child in list(root):
        if lname(child.tag) != "modules":
            continue
        for module in list(child):
            if lname(module.tag) == "module" and module.text and module.text.strip():
                modules.append(module.text.strip())
        break
    if not modules:
        raise ValueError("root pom.xml declares no default-reactor modules")
    if modules != list(dict.fromkeys(modules)):
        raise ValueError("root pom.xml contains duplicate default-reactor modules")
    return modules


def inventories(repo, modules):
    all_sources, annotated = {}, {}
    for module in modules:
        root = repo / module / "src/test/java"
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.java")):
            rel = path.relative_to(repo).as_posix()
            clean = strip_java(path.read_text(encoding="utf-8", errors="replace"))
            package = PACKAGE.search(clean)
            fqn = "{}.{}".format(package.group(1), path.stem) if package else path.stem
            meta = {
                "path": rel,
                "module": module,
                "declared_test_annotations": len(TEST_ANNOTATION.findall(clean)),
                "declared_disabled_test_annotations": len(DISABLED_TEST.findall(clean)),
            }
            all_sources[fqn] = meta
            if meta["declared_test_annotations"]:
                annotated[fqn] = meta
    return all_sources, annotated


def dec(raw):
    if raw in (None, ""):
        return Decimal("0")
    try:
        return Decimal(raw)
    except InvalidOperation as exc:
        raise ValueError("invalid duration {!r}".format(raw)) from exc


def report_paths(repo, modules):
    paths = []
    for module in modules:
        root = repo / module / "target/surefire-reports"
        if root.exists():
            paths.extend(root.glob("TEST-*.xml"))
    return sorted(p for p in paths if p.is_file())


def parse_report(repo, path):
    try:
        root = ET.parse(str(path)).getroot()
    except (ET.ParseError, OSError) as exc:
        raise ValueError("{}: malformed/unreadable XML: {}".format(path, exc))
    cases, observed = [], set()
    total_time = Decimal("0")
    for node in root.iter():
        if lname(node.tag) != "testcase":
            continue
        classname = node.get("classname") or ""
        duration = dec(node.get("time"))
        total_time += duration
        status = PASS
        for child in list(node):
            kind = lname(child.tag)
            if kind in ("failure", "error"):
                status = FAIL
                break
            if kind == "skipped":
                status = "SKIP"
                break
        if classname:
            observed.add(classname.split("$", 1)[0])
        cases.append(
            {
                "classname": classname,
                "name": node.get("name") or "",
                "status": status,
                "time_seconds": format(duration, "f"),
            }
        )
    return (
        {
            "path": path.relative_to(repo).as_posix(),
            "sha256": sha256(path),
            "testcase_count": len(cases),
            "time_seconds": format(total_time, "f"),
        },
        cases,
        observed,
    )


def load_known(path):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise ValueError("known-NOT_RUN file unreadable: {}".format(exc))
    if data.get("schema") != KNOWN_SCHEMA:
        raise ValueError("known-NOT_RUN schema mismatch")
    for key in ("source_test_classes_not_observed", "framework_blockers"):
        value = data.get(key)
        if not isinstance(value, list) or not all(isinstance(x, str) and x for x in value):
            raise ValueError("{} must be a list of non-empty strings".format(key))
        if value != sorted(set(value)):
            raise ValueError("{} must be sorted and unique".format(key))
    return data


def read_exit(path):
    try:
        value = int(path.read_text(encoding="utf-8").strip())
    except (OSError, ValueError) as exc:
        raise ValueError("Maven exit receipt missing/invalid: {}".format(exc))
    if not 0 <= value <= 255:
        raise ValueError("Maven exit code outside 0..255")
    return value


def collect(repo, known_path, exit_path, java_version, expected_sha=None, run=None):
    repo = repo.resolve()
    errors = []
    try:
        identity = source_identity(repo, expected_sha)
        errors += identity.pop("errors")
    except RuntimeError as exc:
        identity = {"sha": None, "tree": None}
        errors.append(str(exc))
    try:
        modules = default_modules(repo)
    except ValueError as exc:
        modules = []
        errors.append(str(exc))
    try:
        maven_exit = read_exit(exit_path)
    except ValueError as exc:
        maven_exit = None
        errors.append(str(exc))
    try:
        known = load_known(known_path)
    except ValueError as exc:
        known = {
            "source_test_classes_not_observed": [],
            "framework_blockers": [],
        }
        errors.append(str(exc))

    all_sources, annotated = inventories(repo, modules)
    reports, cases, observed = [], [], set()
    for path in report_paths(repo, modules):
        try:
            report, parsed, classes = parse_report(repo, path)
        except ValueError as exc:
            errors.append(str(exc))
            continue
        reports.append(report)
        cases += parsed
        observed |= classes

    if not reports:
        errors.append("no Surefire TEST-*.xml reports were found")
    elif not cases:
        errors.append("Surefire reports contained zero testcase records")

    missing = sorted(set(annotated) - observed)
    expected = set(known.get("source_test_classes_not_observed", []))
    unexpected = sorted(set(missing) - expected)
    stale = sorted(expected - set(missing))
    blockers = list(known.get("framework_blockers", []))
    missing_blockers = sorted(x for x in blockers if x not in all_sources)

    baseline_ok = not unexpected and not stale
    if unexpected:
        errors.append("new source test classes became NOT_RUN: " + ", ".join(unexpected))
    if stale:
        errors.append(
            "known NOT_RUN baseline is stale; observed/removed: " + ", ".join(stale)
        )
    if missing_blockers:
        errors.append("framework blocker source disappeared: " + ", ".join(missing_blockers))

    provenance = PASS if not errors else FAIL
    baseline = PASS if baseline_ok else FAIL
    if not baseline_ok:
        coverage = FAIL
    elif missing:
        coverage = PARTIAL
    elif annotated:
        coverage = PASS
    else:
        coverage = UNKNOWN
    build = UNKNOWN if maven_exit is None else (PASS if maven_exit == 0 else FAIL)

    if FAIL in (provenance, baseline, build):
        overall = FAIL
    elif UNKNOWN in (provenance, coverage, build):
        overall = UNKNOWN
    elif coverage == PARTIAL:
        overall = PARTIAL
    else:
        overall = PASS

    total_time = sum((dec(c["time_seconds"]) for c in cases), Decimal("0"))
    receipt = {
        "schema": SCHEMA,
        "source": identity,
        "run": run or {},
        "java_version": str(java_version),
        "default_reactor_modules": modules,
        "build_outcome": build,
        "maven_exit_code": maven_exit,
        "provenance_integrity": provenance,
        "known_not_run_baseline_integrity": baseline,
        "coverage_completeness": coverage,
        "overall_classification": overall,
        "reports": reports,
        "normalized_results": {
            "testcase_count": len(cases),
            "duration_seconds": format(total_time, "f"),
            "failures": sum(c["status"] == FAIL for c in cases),
            "skipped": sum(c["status"] == "SKIP" for c in cases),
            "testcases": sorted(
                cases, key=lambda c: (c["classname"], c["name"], c["status"])
            ),
        },
        "coverage": {
            "source_test_class_count": len(annotated),
            "observed_test_class_count": len(observed),
            "source_test_classes_not_observed": missing,
            "expected_known_not_run": sorted(expected),
            "unexpected_not_run": unexpected,
            "stale_known_not_run": stale,
            "framework_blockers": blockers,
            "framework_blocker_sources_missing": missing_blockers,
        },
        "validation_errors": errors,
        "not_claimed": {
            "rules_qualification": True,
            "production_provider_selected": False,
            "architecture_freeze_claimed": False,
        },
    }
    return receipt, provenance == PASS and baseline == PASS


def write_receipt(receipt, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def append_summary(receipt, path):
    missing = receipt["coverage"]["source_test_classes_not_observed"]
    lines = [
        "### Forge D20 test provenance",
        "",
        "- source: {} / TREE {}".format(
            receipt["source"].get("sha"), receipt["source"].get("tree")
        ),
        "- Java: {}".format(receipt["java_version"]),
        "- build outcome: {}".format(receipt["build_outcome"]),
        "- provenance integrity: {}".format(receipt["provenance_integrity"]),
        "- known-NOT_RUN baseline integrity: {}".format(
            receipt["known_not_run_baseline_integrity"]
        ),
        "- coverage completeness: {}".format(receipt["coverage_completeness"]),
        "- overall classification: {}".format(receipt["overall_classification"]),
        "- normalized testcase records: {}".format(
            receipt["normalized_results"]["testcase_count"]
        ),
        "- source test classes not observed: {}".format(len(missing)),
    ]
    lines += ["  - NOT_RUN: " + cls for cls in missing]
    if receipt["validation_errors"]:
        lines += ["- validation error: " + e for e in receipt["validation_errors"]]
    lines += [
        "",
        "Build outcome, provenance integrity, and coverage completeness are separate. "
        "Artifact upload success is not test PASS.",
    ]
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


class Controls(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "pom.xml").write_text(
            "<project><modules><module>module</module></modules></project>\n",
            encoding="utf-8",
        )
        self.exit = self.root / "exit.txt"
        self.exit.write_text("0\n", encoding="utf-8")
        self.orig = globals()["source_identity"]
        globals()["source_identity"] = lambda repo, expected_sha=None: {
            "sha": "a" * 40,
            "tree": "b" * 40,
            "errors": [],
        }

    def tearDown(self):
        globals()["source_identity"] = self.orig
        self.tmp.cleanup()

    def java(self, fqn, annotated=True, module="module"):
        parts = fqn.split(".")
        path = self.root / module / "src/test/java" / Path(*parts).with_suffix(".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        annotation = "@org.testng.annotations.Test\n" if annotated else ""
        path.write_text(
            "package {};\npublic class {} {{\n{}public void x() {{}}\n}}\n".format(
                ".".join(parts[:-1]), parts[-1], annotation
            ),
            encoding="utf-8",
        )

    def report(self, fqn, malformed=False, nested=False, module="module"):
        out = self.root / module / "target/surefire-reports"
        out.mkdir(parents=True, exist_ok=True)
        path = out / ("TEST-" + fqn.rsplit(".", 1)[-1] + ".xml")
        if malformed:
            path.write_text("<testsuite><broken>", encoding="utf-8")
        else:
            classname = fqn + "$Inner" if nested else fqn
            path.write_text(
                '<testsuite><testcase classname="{}" name="x" time="0.25"/></testsuite>\n'.format(
                    classname
                ),
                encoding="utf-8",
            )
        return path

    def known(self, missing=(), blockers=()):
        path = self.root / "known.json"
        path.write_text(
            json.dumps(
                {
                    "schema": KNOWN_SCHEMA,
                    "source_test_classes_not_observed": sorted(missing),
                    "framework_blockers": sorted(blockers),
                }
            ),
            encoding="utf-8",
        )
        return path

    def run_collect(self, known):
        return collect(self.root, known, self.exit, "21", "a" * 40)

    def test_positive(self):
        self.java("example.RealTest")
        self.report("example.RealTest")
        r, ok = self.run_collect(self.known())
        self.assertTrue(ok)
        self.assertEqual(PASS, r["overall_classification"])
        self.assertEqual("0.25", r["normalized_results"]["duration_seconds"])
        self.assertEqual(["module"], r["default_reactor_modules"])

    def test_known_not_run_is_partial_not_pass(self):
        self.java("example.RealTest")
        self.java("example.BlockedTest")
        self.report("example.RealTest")
        r, ok = self.run_collect(self.known(["example.BlockedTest"]))
        self.assertTrue(ok)
        self.assertEqual(PARTIAL, r["coverage_completeness"])
        self.assertEqual(PARTIAL, r["overall_classification"])

    def test_new_not_run_is_red(self):
        self.java("example.RealTest")
        self.java("example.LostTest")
        self.report("example.RealTest")
        r, ok = self.run_collect(self.known())
        self.assertFalse(ok)
        self.assertIn("example.LostTest", r["coverage"]["unexpected_not_run"])

    def test_stale_baseline_is_red(self):
        self.java("example.RecoveredTest")
        self.report("example.RecoveredTest")
        r, ok = self.run_collect(self.known(["example.RecoveredTest"]))
        self.assertFalse(ok)
        self.assertIn("example.RecoveredTest", r["coverage"]["stale_known_not_run"])

    def test_malformed_report_is_red(self):
        self.java("example.BadReportTest")
        self.report("example.BadReportTest", malformed=True)
        r, ok = self.run_collect(self.known(["example.BadReportTest"]))
        self.assertFalse(ok)
        self.assertEqual(FAIL, r["provenance_integrity"])

    def test_no_reports_is_red(self):
        self.java("example.NoReportTest")
        r, ok = self.run_collect(self.known(["example.NoReportTest"]))
        self.assertFalse(ok)
        self.assertTrue(any("no Surefire" in e for e in r["validation_errors"]))

    def test_maven_failure_never_becomes_pass(self):
        self.java("example.RealTest")
        self.report("example.RealTest")
        self.exit.write_text("1\n", encoding="utf-8")
        r, ok = self.run_collect(self.known())
        self.assertTrue(ok)
        self.assertEqual(FAIL, r["build_outcome"])
        self.assertEqual(FAIL, r["overall_classification"])

    def test_blocker_is_not_a_test_class(self):
        self.java("example.RealTest")
        self.java("example.BaseHarness", annotated=False)
        self.report("example.RealTest")
        r, ok = self.run_collect(self.known(blockers=["example.BaseHarness"]))
        self.assertTrue(ok)
        self.assertEqual([], r["coverage"]["source_test_classes_not_observed"])

    def test_disappeared_blocker_is_red(self):
        self.java("example.RealTest")
        self.report("example.RealTest")
        r, ok = self.run_collect(self.known(blockers=["example.GoneHarness"]))
        self.assertFalse(ok)
        self.assertTrue(any("framework blocker" in e for e in r["validation_errors"]))

    def test_nested_class_credits_top_level(self):
        self.java("example.OwnerTest")
        self.report("example.OwnerTest", nested=True)
        r, ok = self.run_collect(self.known())
        self.assertTrue(ok)
        self.assertEqual([], r["coverage"]["source_test_classes_not_observed"])

    def test_non_reactor_source_is_ignored(self):
        self.java("example.RealTest")
        self.report("example.RealTest")
        self.java("shadow.NotRunTest", module=".github/materialized")
        r, ok = self.run_collect(self.known())
        self.assertTrue(ok)
        self.assertNotIn("shadow.NotRunTest", r["coverage"]["source_test_classes_not_observed"])

    def test_comments_and_strings_do_not_create_tests(self):
        path = self.root / "module/src/test/java/example/Helper.java"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            'package example;\n// @Test\npublic class Helper { String x="@Test"; }\n',
            encoding="utf-8",
        )
        _, annotated = inventories(self.root, ["module"])
        self.assertNotIn("example.Helper", annotated)

    def test_source_mismatch_is_red(self):
        globals()["source_identity"] = lambda repo, expected_sha=None: {
            "sha": "c" * 40,
            "tree": "b" * 40,
            "errors": ["source mismatch"],
        }
        self.java("example.RealTest")
        self.report("example.RealTest")
        r, ok = self.run_collect(self.known())
        self.assertFalse(ok)
        self.assertEqual(FAIL, r["provenance_integrity"])

    def test_missing_exit_receipt_is_red(self):
        self.java("example.RealTest")
        self.report("example.RealTest")
        self.exit.unlink()
        r, ok = self.run_collect(self.known())
        self.assertFalse(ok)
        self.assertEqual(UNKNOWN, r["build_outcome"])


def selftest(verbose=False):
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(Controls)
    result = unittest.TextTestRunner(verbosity=2 if verbose else 1).run(suite)
    return 0 if result.wasSuccessful() else 1


def main(argv=None):
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    tests = sub.add_parser("selftest")
    tests.add_argument("-v", "--verbose", action="store_true")
    collect_p = sub.add_parser("collect")
    collect_p.add_argument("--repo", default=".")
    collect_p.add_argument("--known-not-run", required=True)
    collect_p.add_argument("--maven-exit-file", required=True)
    collect_p.add_argument("--java-version", required=True)
    collect_p.add_argument("--expected-source-sha")
    collect_p.add_argument("--output", required=True)
    collect_p.add_argument("--summary")
    collect_p.add_argument("--event-name", default="")
    collect_p.add_argument("--event-sha", default="")
    collect_p.add_argument("--pr-head-sha", default="")
    collect_p.add_argument("--run-id", default="")
    args = parser.parse_args(argv)

    if args.command == "selftest":
        return selftest(args.verbose)

    receipt, ok = collect(
        Path(args.repo),
        Path(args.known_not_run),
        Path(args.maven_exit_file),
        args.java_version,
        args.expected_source_sha,
        {
            "event_name": args.event_name,
            "event_sha": args.event_sha,
            "pr_head_sha": args.pr_head_sha,
            "run_id": args.run_id,
        },
    )
    write_receipt(receipt, Path(args.output))
    if args.summary:
        append_summary(receipt, Path(args.summary))
    print(
        "D20 provenance: integrity={} baseline={} coverage={} build={} overall={}".format(
            receipt["provenance_integrity"],
            receipt["known_not_run_baseline_integrity"],
            receipt["coverage_completeness"],
            receipt["build_outcome"],
            receipt["overall_classification"],
        )
    )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
