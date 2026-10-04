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
import time
import os
import sys
import xml.etree.ElementTree as ET
from decimal import Decimal, InvalidOperation
from pathlib import Path

SCHEMA = "forge.test-provenance/3"
KNOWN_SCHEMA = "forge.known-not-run/2"
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
    changed = git(repo, 'diff', '--name-only', 'HEAD', '--')
    untracked = git(repo, 'ls-files', '--others', '--exclude-standard')
    if changed or untracked:
        errors.append('execution source differs from declared Git TREE: '+changed+' '+untracked)
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
        raise ValueError("missing testcase duration")
    try:
        value = Decimal(raw)
        if not value.is_finite() or value < 0:
            raise ValueError("non-finite/negative duration")
        return value
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
        resolved = path.resolve()
        absolute = path.absolute()
        try:
            resolved.relative_to(repo.resolve())
        except ValueError as exc:
            raise ValueError('XML report path escapes repository') from exc
        if path.is_symlink() or resolved != absolute:
            raise ValueError('symlinked/escaped XML report path')
        raw = path.read_bytes()
        if b'\x00' in raw:
            raise ValueError('unsupported XML report encoding')
        if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
            raise ValueError('non-regular/unsupported XML report')
        root = ET.fromstring(raw)
        if lname(root.tag) != 'testsuite':
            raise ValueError('single testsuite required')
        counters = {}
        for key in ('tests','failures','errors','skipped'):
            value = root.get(key)
            if value is None or not re.fullmatch(r'[0-9]+', value):
                raise ValueError('missing/nonnegative integer suite counter: '+key)
            counters[key] = int(value)
        nodes = [n for n in root.iter() if lname(n.tag)=='testcase']
        for node in nodes:
            terminal = [
                lname(child.tag)
                for child in list(node)
                if lname(child.tag) in ('failure', 'error', 'skipped')
            ]
            if len(terminal) > 1:
                raise ValueError('testcase contains multiple terminal outcomes')
        actual = {'tests':len(nodes), 'failures':sum(any(lname(c.tag)=='failure' for c in list(n)) for n in nodes),
                  'errors':sum(any(lname(c.tag)=='error' for c in list(n)) for n in nodes),
                  'skipped':sum(any(lname(c.tag)=='skipped' for c in list(n)) for n in nodes)}
        if counters != actual or sum(counters[k] for k in ('failures','errors','skipped')) > counters['tests']:
            raise ValueError('suite counters disagree with raw testcases')
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
        outcome = "PASS"
        for child in list(node):
            kind = lname(child.tag)
            if kind == "failure":
                status = FAIL
                outcome = "FAILURE"
                break
            if kind == "error":
                status = FAIL
                outcome = "ERROR"
                break
            if kind == "skipped":
                status = "SKIP"
                outcome = "SKIP"
                break
        if classname:
            observed.add(classname.split("$", 1)[0])
        cases.append(
            {
                "classname": classname,
                "name": node.get("name") or "",
                "status": status,
                "outcome": outcome,
                "time_seconds": format(duration, "f"),
            }
        )
    return (
        {
            "path": path.relative_to(repo).as_posix(),
            "sha256": sha256(path),
            "suite_counters": dict(counters),
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
    disabled = data.get('intentionally_disabled_source_classes')
    if not isinstance(disabled, dict) or any(not isinstance(k,str) or not re.fullmatch(r'[0-9a-f]{64}',str(v)) for k,v in disabled.items()):
        raise ValueError('disabled source identities missing/invalid')
    return data


def read_exit(path, identity, run, java_version):
    try:
        if (not isinstance(run,dict) or set(run)!={'event_name','event_sha','pr_head_sha','run_id','run_attempt'}
                or not all(isinstance(v,str) for v in run.values())
                or not run['run_id'].isdigit() or not run['run_attempt'].isdigit() or int(run['run_attempt'])<1
                or run['event_sha']!=identity.get('sha')
                or run['event_name'] not in ('push','pull_request','workflow_dispatch','schedule')
                or (run['pr_head_sha'] and not re.fullmatch(r'[0-9a-f]{40}',run['pr_head_sha']))):
            raise ValueError('Independent execution run context invalid')
        if path.is_symlink():
            raise ValueError('non-regular execution receipt')
        doc = json.loads(path.read_text(encoding='utf-8'))
        if (doc.get('schema') != 'forge.maven-execution/1' or doc.get('status') != 'FINISHED'
                or doc.get('source') != identity or doc.get('run') != run
                or doc.get('java_version') != str(java_version)):
            raise ValueError('execution receipt source/TREE/run/attempt/Java mismatch or incomplete')
        value = doc.get('maven_exit_code')
        before, after = doc.get('started_ns'), doc.get('finished_ns')
        if (type(value) is not int or not 0 <= value <= 255 or type(before) is not int
                or type(after) is not int or not 0 < before <= after <= time.time_ns()):
            raise ValueError('invalid execution receipt exit/timing')
        return value, doc
    except (OSError, ValueError, AttributeError, TypeError) as exc:
        raise ValueError('Maven execution receipt missing/invalid: '+str(exc)) from exc

def execution_record(path, repo, run, java_version, finish=None):
    identity = source_identity(repo)
    if identity.pop('errors'):
        raise ValueError('execution source unavailable')
    if finish is None:
        doc = {'schema':'forge.maven-execution/1', 'status':'RUNNING', 'source':identity,
               'run':run, 'java_version':str(java_version), 'started_ns':time.time_ns(),
               'finished_ns':None, 'maven_exit_code':None}
    else:
        doc = json.loads(path.read_text())
        if (doc.get('status')!='RUNNING' or doc.get('source')!=identity
                or doc.get('run')!=run or doc.get('java_version')!=str(java_version)):
            raise ValueError('execution source/run changed before Maven completion')
        doc.update(status='FINISHED',finished_ns=time.time_ns(),maven_exit_code=finish)
    if path.is_symlink():
        raise ValueError('non-regular receipt destination')
    path.write_text(json.dumps(doc,indent=2,sort_keys=True)+'\n')



def collect(repo, known_path, exit_path, java_version, expected_sha=None, run=None, expected_tree=None):
    repo = repo.resolve()
    provenance_errors = []
    baseline_errors = []
    try:
        identity = source_identity(repo, expected_sha)
        provenance_errors += identity.pop("errors")
        if not expected_sha or not expected_tree or identity['tree'] != expected_tree:
            provenance_errors.append('expected source SHA/TREE absent or mismatched')
    except RuntimeError as exc:
        identity = {"sha": None, "tree": None}
        provenance_errors.append(str(exc))
    try:
        modules = default_modules(repo)
    except ValueError as exc:
        modules = []
        provenance_errors.append(str(exc))
    try:
        maven_exit, execution = read_exit(exit_path, identity, run or {}, java_version)
    except ValueError as exc:
        maven_exit, execution = None, None
        provenance_errors.append(str(exc))
    try:
        known = load_known(known_path)
    except ValueError as exc:
        known = {
            "source_test_classes_not_observed": [],
            "framework_blockers": [],
        }
        baseline_errors.append(str(exc))

    all_sources, annotated = inventories(repo, modules)
    reports, cases, observed = [], [], set()
    for path in report_paths(repo, modules):
        try:
            report, parsed, classes = parse_report(repo, path)
        except ValueError as exc:
            provenance_errors.append(str(exc))
            continue
        if execution and not execution['started_ns'] <= path.stat().st_mtime_ns <= execution['finished_ns']:
            provenance_errors.append('report outside this Maven execution interval: '+path.relative_to(repo).as_posix())
        report['mtime_ns'] = path.stat().st_mtime_ns
        reports.append(report)
        cases += parsed
        observed |= classes

    if not reports:
        provenance_errors.append("no Surefire TEST-*.xml reports were found")
    elif not cases:
        provenance_errors.append("Surefire reports contained zero testcase records")

    missing = sorted(set(annotated) - observed)
    disabled = known.get('intentionally_disabled_source_classes', {})
    disabled_errors = []
    for name, source_hash in disabled.items():
        source = all_sources.get(name)
        if source is None or sha256(repo/source['path']) != source_hash or name not in missing:
            disabled_errors.append('explicit disabled-source baseline drift/observed: '+name)
    baseline_errors += disabled_errors
    expected = set(known.get("source_test_classes_not_observed", [])) | set(disabled)
    unexpected = sorted(set(missing) - expected)
    stale = sorted(expected - set(missing))
    blockers = list(known.get("framework_blockers", []))
    missing_blockers = sorted(x for x in blockers if x not in all_sources)

    baseline_ok = not unexpected and not stale and not missing_blockers and not baseline_errors
    if unexpected:
        baseline_errors.append("new source test classes became NOT_RUN: " + ", ".join(unexpected))
    if stale:
        baseline_errors.append(
            "known NOT_RUN baseline is stale; observed/removed: " + ", ".join(stale)
        )
    if missing_blockers:
        baseline_errors.append("framework blocker source disappeared: " + ", ".join(missing_blockers))

    provenance = PASS if not provenance_errors else FAIL
    baseline = PASS if baseline_ok else FAIL
    if not baseline_ok:
        coverage = FAIL
    elif missing:
        coverage = PARTIAL
    elif annotated:
        # Observing each class does not prove that each required method ran.
        coverage = UNKNOWN
    else:
        coverage = UNKNOWN
    build = UNKNOWN if maven_exit is None else (PASS if maven_exit == 0 else FAIL)
    failure_count = sum(c["outcome"] == "FAILURE" for c in cases)
    error_count = sum(c["outcome"] == "ERROR" for c in cases)
    skipped_count = sum(c["outcome"] == "SKIP" for c in cases)
    passed_count = sum(c["outcome"] == "PASS" for c in cases)
    test_result = UNKNOWN if not cases else (FAIL if failure_count or error_count else PASS)

    if FAIL in (provenance, baseline, build, test_result):
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
        "evidence_authority": {
            "producer": "candidate-controlled test-build workflow",
            "trusted_exact_sha_candidate_qualification": "NOT_CLAIMED",
            "qualification_credit": "none",
            "note": (
                "D20 proves the provenance carried by this test-build receipt; "
                "D17 owns trusted exact-SHA candidate qualification and must not "
                "derive credit merely from this candidate-controlled document"
            ),
        },
        "source": identity,
        "run": run or {},
        "java_version": str(java_version),
        "default_reactor_modules": modules,
        "build_outcome": build,
        "test_result_outcome": test_result,
        "maven_exit_code": maven_exit,
        "maven_execution": execution,
        "provenance_integrity": provenance,
        "known_not_run_baseline_integrity": baseline,
        "coverage_completeness": coverage,
        "class_presence_observation": PARTIAL if missing else PASS if annotated else UNKNOWN,
        "method_denominator": "UNKNOWN_NOT_ATTESTED",
        "overall_classification": overall,
        "reports": reports,
        "normalized_results": {
            "testcase_count": len(cases),
            "duration_seconds": format(total_time, "f"),
            "passed": passed_count,
            "failures": failure_count,
            "errors": error_count,
            "skipped": skipped_count,
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
            "intentionally_disabled_source_classes": sorted(disabled),
            "framework_blocker_sources_missing": missing_blockers,
        },
        "provenance_errors": provenance_errors,
        "baseline_errors": baseline_errors,
        "validation_errors": provenance_errors + baseline_errors,
        "not_claimed": {
            "rules_qualification": True,
            "production_provider_selected": False,
            "architecture_freeze_claimed": False,
        },
    }
    return receipt, provenance == PASS and baseline == PASS and test_result != FAIL


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
        "- structured test-result outcome: {}".format(receipt["test_result_outcome"]),
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
        self.run_identity = {'event_name':'pull_request','event_sha':'a'*40,'pr_head_sha':'a'*40,'run_id':'42','run_attempt':'1'}
        self.execution = {'schema':'forge.maven-execution/1','status':'FINISHED',
                          'source':{'sha':'a'*40,'tree':'b'*40},'run':self.run_identity,'java_version':'21',
                          'started_ns':time.time_ns(),'finished_ns':None,'maven_exit_code':0}
        self.exit.write_text(json.dumps(self.execution))
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
                '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="{}" name="x" time="0.25"/></testsuite>\n'.format(
                    classname
                ),
                encoding="utf-8",
            )
        stamp=time.time_ns()
        os.utime(path,ns=(stamp,stamp))
        return path

    def known(self, missing=(), blockers=()):
        path = self.root / "known.json"
        path.write_text(
            json.dumps(
                {
                    "schema": KNOWN_SCHEMA,
                    "source_test_classes_not_observed": sorted(missing),
                    "framework_blockers": sorted(blockers),
                    "intentionally_disabled_source_classes": {},
                }
            ),
            encoding="utf-8",
        )
        return path

    def run_collect(self, known):
        if self.exit.exists():
            try:
                doc=json.loads(self.exit.read_text());doc['finished_ns']=time.time_ns();self.exit.write_text(json.dumps(doc))
            except ValueError:
                pass
        return collect(self.root, known, self.exit, "21", "a" * 40, self.run_identity, "b" * 40)

    def test_positive(self):
        self.java("example.RealTest")
        self.report("example.RealTest")
        r, ok = self.run_collect(self.known())
        self.assertTrue(ok)
        self.assertEqual(UNKNOWN, r["overall_classification"])
        self.assertEqual(PASS, r["build_outcome"])
        self.assertEqual(PASS, r["test_result_outcome"])
        self.assertEqual("0.25", r["normalized_results"]["duration_seconds"])
        self.assertEqual(["module"], r["default_reactor_modules"])
        self.assertEqual("none", r["evidence_authority"]["qualification_credit"])
        self.assertEqual(
            "NOT_CLAIMED",
            r["evidence_authority"]["trusted_exact_sha_candidate_qualification"],
        )

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
        self.assertEqual(PASS, r["provenance_integrity"])
        self.assertEqual(FAIL, r["known_not_run_baseline_integrity"])

    def test_stale_baseline_is_red(self):
        self.java("example.RecoveredTest")
        self.report("example.RecoveredTest")
        r, ok = self.run_collect(self.known(["example.RecoveredTest"]))
        self.assertFalse(ok)
        self.assertIn("example.RecoveredTest", r["coverage"]["stale_known_not_run"])
        self.assertEqual(PASS, r["provenance_integrity"])
        self.assertEqual(FAIL, r["known_not_run_baseline_integrity"])

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
        doc=json.loads(self.exit.read_text());doc['maven_exit_code']=1;self.exit.write_text(json.dumps(doc))
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
        self.assertTrue(any("framework blocker" in e for e in r["baseline_errors"]))
        self.assertEqual(PASS, r["provenance_integrity"])
        self.assertEqual(FAIL, r["known_not_run_baseline_integrity"])

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

    def test_worktree_drift_and_untracked_test_input_are_red(self):
        self.java('example.RealTest')
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        subprocess.run(['git','-C',str(self.root),'add','pom.xml','module/src'],check=True)
        subprocess.run(['git','-C',str(self.root),'-c','user.name=Control','-c','user.email=control@example.invalid','commit','-qm','source identity control'],check=True)
        clean=self.orig(self.root);self.assertEqual([],clean['errors'])
        source=self.root/'module/src/test/java/example/RealTest.java'
        source.write_text(source.read_text()+'// tracked drift\\n')
        self.assertTrue(self.orig(self.root)['errors'])
        subprocess.run(['git','-C',str(self.root),'checkout','--','module/src/test/java/example/RealTest.java'],check=True)
        injected=self.root/'module/src/test/java/example/Injected.java';injected.write_text('package example; public class Injected {}\\n')
        self.assertTrue(self.orig(self.root)['errors'])
        injected.unlink()
        main=self.root/'module/src/main/java/example/InjectedRuntime.java';main.parent.mkdir(parents=True,exist_ok=True)
        main.write_text('package example; public class InjectedRuntime {}\\n')
        self.assertTrue(self.orig(self.root)['errors'])
        main.unlink()
        resource=self.root/'module/src/test/resources/injected.properties';resource.parent.mkdir(parents=True,exist_ok=True)
        resource.write_text('outside_tree=true\\n')
        self.assertTrue(self.orig(self.root)['errors'])

    def test_missing_exit_receipt_is_red(self):
        self.java("example.RealTest")
        self.report("example.RealTest")
        self.exit.unlink()
        r, ok = self.run_collect(self.known())
        self.assertFalse(ok)
        self.assertEqual(UNKNOWN, r["build_outcome"])


    def test_impossible_counters_and_missing_durations_are_red(self):
        self.java('example.RealTest'); path=self.report('example.RealTest'); original=path.read_text()
        variants=[original.replace('tests="1"','tests="0"'), original.replace('failures="0"','failures="-1"'),
                  original.replace('errors="0"','errors="1"'), original.replace('skipped="0"','skipped="2"')]
        variants += [original.replace('time="0.25"','time="'+value+'"') for value in ('NaN','Infinity','-1','')]
        variants.append(original.replace(' time="0.25"',''))
        variants.append('<!DOCTYPE testsuite [<!ENTITY forged "x">]>'+original)
        for xml in variants:
            with self.subTest(xml=xml):
                path.write_text(xml);r,ok=self.run_collect(self.known())
                self.assertFalse(ok);self.assertEqual(FAIL,r['provenance_integrity'])

    def test_multiple_terminal_outcomes_are_red(self):
        self.java('example.RealTest');path=self.report('example.RealTest')
        path.write_text(
            '<testsuite tests="2" failures="1" errors="0" skipped="1">'
            '<testcase classname="example.RealTest" name="ambiguous" time="0.10"><skipped/><failure/></testcase>'
            '<testcase classname="example.RealTest" name="pass" time="0.15"/></testsuite>\n'
        )
        with self.assertRaisesRegex(ValueError, 'multiple terminal outcomes'):
            parse_report(self.root, path)
        r,ok=self.run_collect(self.known());self.assertFalse(ok)
        self.assertEqual(FAIL,r['provenance_integrity'])

    def test_encoded_dtd_or_entity_input_is_red(self):
        self.java('example.RealTest');path=self.report('example.RealTest')
        xml='<!DOCTYPE testsuite [<!ENTITY x "boom">]><testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="example.RealTest" name="x" time="0.25"/></testsuite>'
        path.write_bytes(xml.encode('utf-16'))
        r,ok=self.run_collect(self.known());self.assertFalse(ok)
        self.assertEqual(FAIL,r['provenance_integrity'])

    def test_structured_failure_error_skip_counters_are_distinct(self):
        self.java('example.RealTest');path=self.report('example.RealTest')
        path.write_text(
            '<testsuite tests="4" failures="1" errors="1" skipped="1">'
            '<testcase classname="example.RealTest" name="pass" time="0.10"/>'
            '<testcase classname="example.RealTest" name="failure" time="0.20"><failure/></testcase>'
            '<testcase classname="example.RealTest" name="error" time="0.30"><error/></testcase>'
            '<testcase classname="example.RealTest" name="skip" time="0.40"><skipped/></testcase></testsuite>\n'
        )
        report,cases,_=parse_report(self.root,path)
        self.assertEqual({'tests':4,'failures':1,'errors':1,'skipped':1},report['suite_counters'])
        outcomes=[c['outcome'] for c in cases]
        self.assertEqual(['PASS','FAILURE','ERROR','SKIP'],outcomes)
        r,ok=self.run_collect(self.known());self.assertFalse(ok)
        self.assertEqual(PASS,r['build_outcome'])
        self.assertEqual(FAIL,r['test_result_outcome'])
        self.assertEqual({'passed':1,'failures':1,'errors':1,'skipped':1},
                         {k:r['normalized_results'][k] for k in ('passed','failures','errors','skipped')})
        self.assertEqual(FAIL,r['overall_classification'])

    def test_source_disabled_debt_is_partial_hash_bound_and_not_exempt(self):
        self.java('example.RealTest');self.report('example.RealTest');self.java('example.DisabledTest')
        source=self.root/'module/src/test/java/example/DisabledTest.java'
        source.write_text(source.read_text().replace('@org.testng.annotations.Test','@org.testng.annotations.Test(enabled=false)'))
        known=self.known();doc=json.loads(known.read_text());doc['intentionally_disabled_source_classes']={'example.DisabledTest':sha256(source)};known.write_text(json.dumps(doc))
        r,ok=self.run_collect(known);self.assertTrue(ok);self.assertEqual(PARTIAL,r['coverage_completeness'])
        source.write_text(source.read_text().replace('enabled=false','enabled=true'))
        r,ok=self.run_collect(known);self.assertFalse(ok)
        source.write_text(source.read_text().replace('enabled=true','enabled=false'));self.report('example.DisabledTest')
        r,ok=self.run_collect(known);self.assertFalse(ok)

    def test_symlinked_reports_are_red(self):
        self.java('example.RealTest');path=self.report('example.RealTest');raw=path.read_text();real=self.root/'raw.xml';real.write_text(raw);path.unlink();path.symlink_to(real)
        r,ok=self.run_collect(self.known());self.assertFalse(ok)

    def test_symlinked_report_parent_escape_is_red(self):
        self.java('example.RealTest');path=self.report('example.RealTest')
        report_dir=path.parent;outside=self.root/'outside-reports';outside.mkdir()
        real=outside/path.name;path.replace(real);report_dir.rmdir();report_dir.symlink_to(outside,target_is_directory=True)
        r,ok=self.run_collect(self.known());self.assertFalse(ok)
        self.assertEqual(FAIL,r['provenance_integrity'])
        self.assertTrue(any('symlinked/escaped XML report path' in x for x in r['provenance_errors']))


    def test_one_reported_method_cannot_claim_complete_method_coverage(self):
        self.java('example.RealTest');self.report('example.RealTest')
        source=self.root/'module/src/test/java/example/RealTest.java'
        source.write_text(source.read_text().replace('public void x() {}','public void x() {} @org.testng.annotations.Test public void omitted() {}'))
        r,ok=self.run_collect(self.known());self.assertTrue(ok)
        self.assertEqual(UNKNOWN,r['coverage_completeness']);self.assertEqual('UNKNOWN_NOT_ATTESTED',r['method_denominator'])


    def test_tree_and_execution_identity_mismatch_are_red(self):
        self.java('example.RealTest');self.report('example.RealTest');known=self.known()
        doc=json.loads(self.exit.read_text());doc['finished_ns']=time.time_ns();self.exit.write_text(json.dumps(doc))
        r,ok=collect(self.root,known,self.exit,'21','a'*40,self.run_identity,'c'*40)
        self.assertFalse(ok);self.assertEqual(FAIL,r['provenance_integrity'])
        for field in ('source','run','java_version','status'):
            changed=dict(doc);changed[field]='other';self.exit.write_text(json.dumps(changed))
            r,ok=self.run_collect(known);self.assertFalse(ok);self.assertEqual(UNKNOWN,r['build_outcome'])

    def test_stale_raw_report_cannot_be_reused_in_new_run(self):
        self.java('example.RealTest');path=self.report('example.RealTest');os.utime(path,ns=(1,1))
        r,ok=self.run_collect(self.known());self.assertFalse(ok)
        self.assertTrue(any('outside this Maven execution interval' in x for x in r['provenance_errors']))

    def test_run_attempt_and_matrix_leg_receipt_replay_are_red(self):
        self.java('example.RealTest');self.report('example.RealTest');known=self.known();positive,ok=self.run_collect(known);self.assertTrue(ok);original=json.loads(self.exit.read_text())
        for key,value in (('run_id','43'),('run_attempt','2'),('event_sha','c'*40),('pr_head_sha','d'*40)):
            doc=json.loads(json.dumps(original));doc['run'][key]=value;self.exit.write_text(json.dumps(doc))
            r,ok=self.run_collect(known);self.assertFalse(ok)
        self.exit.write_text(json.dumps(original));r,ok=collect(self.root,known,self.exit,'17','a'*40,self.run_identity,'b'*40);self.assertFalse(ok)

    def test_missing_tree_binding_cannot_claim_provenance(self):
        self.java('example.RealTest');self.report('example.RealTest')
        known=self.known();positive,ok=self.run_collect(known);self.assertTrue(ok)
        r,ok=collect(self.root,known,self.exit,'21','a'*40,self.run_identity)
        self.assertFalse(ok);self.assertEqual(0,r['maven_exit_code'])


    def test_full_cli_execution_receipt_and_stale_replay(self):
        self.java('example.RealTest'); known=self.known()
        (self.root/'.gitignore').write_text('exit.txt\nknown.json\nmodule/target/\nresult.json\n')
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        subprocess.run(['git','-C',str(self.root),'add','pom.xml','.gitignore','module/src'],check=True)
        subprocess.run(['git','-C',str(self.root),'-c','user.name=Control','-c','user.email=control@example.invalid','commit','-qm','execution control'],check=True)
        sha=git(self.root,'rev-parse','HEAD');tree=git(self.root,'rev-parse','HEAD^{tree}')
        common=['--repo',str(self.root),'--maven-exit-file',str(self.exit),'--java-version','21',
                '--event-name','push','--event-sha',sha,'--pr-head-sha','','--run-id','42','--run-attempt','1']
        script=str(Path(__file__).resolve())
        def cli(mode,args):
            return subprocess.run([sys.executable,'-I',script,mode,*args],capture_output=True,text=True)
        self.assertEqual(cli('execution-start',common).returncode,0)
        report=self.report('example.RealTest')
        self.assertEqual(cli('execution-finish',common+['--exit-code','0']).returncode,0)
        out=self.root/'result.json'
        collect_args=common+['--known-not-run',str(known),'--expected-source-sha',sha,'--expected-source-tree',tree,'--output',str(out)]
        positive=cli('collect',collect_args);self.assertEqual(positive.returncode,0,positive.stderr)
        doc=json.loads(out.read_text());self.assertEqual(PASS,doc['provenance_integrity']);self.assertEqual(0,doc['maven_exit_code'])
        os.utime(report,ns=(1,1));negative=cli('collect',collect_args);self.assertNotEqual(negative.returncode,0)
        self.assertTrue(any('outside this Maven execution interval' in x for x in json.loads(out.read_text())['provenance_errors']))


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
    collect_p.add_argument("--expected-source-sha", required=True)
    collect_p.add_argument("--expected-source-tree", required=True)
    collect_p.add_argument("--output", required=True)
    collect_p.add_argument("--summary")
    collect_p.add_argument("--event-name", default="")
    collect_p.add_argument("--event-sha", default="")
    collect_p.add_argument("--pr-head-sha", default="")
    collect_p.add_argument("--run-id", required=True)
    collect_p.add_argument("--run-attempt", required=True)
    for name in ('execution-start','execution-finish'):
        command=sub.add_parser(name)
        command.add_argument('--repo',default='.')
        command.add_argument('--maven-exit-file',required=True)
        command.add_argument('--java-version',required=True)
        command.add_argument('--event-name',required=True)
        command.add_argument('--event-sha',required=True)
        command.add_argument('--pr-head-sha',default='')
        command.add_argument('--run-id',required=True)
        command.add_argument('--run-attempt',required=True)
        if name=='execution-finish':command.add_argument('--exit-code',type=int,required=True)
    args = parser.parse_args(argv)

    if args.command == "selftest":
        return selftest(args.verbose)

    if args.command in ('execution-start','execution-finish'):
        run={key:getattr(args,key) for key in ('event_name','event_sha','pr_head_sha','run_id','run_attempt')}
        execution_record(Path(args.maven_exit_file),Path(args.repo),run,args.java_version,
                         finish=args.exit_code if args.command=='execution-finish' else None)
        return 0

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
            "run_attempt": args.run_attempt,
        },
        args.expected_source_tree,
    )
    write_receipt(receipt, Path(args.output))
    if args.summary:
        append_summary(receipt, Path(args.summary))
    print(
        "D20 provenance: integrity={} baseline={} coverage={} build={} tests={} overall={}".format(
            receipt["provenance_integrity"],
            receipt["known_not_run_baseline_integrity"],
            receipt["coverage_completeness"],
            receipt["build_outcome"],
            receipt["test_result_outcome"],
            receipt["overall_classification"],
        )
    )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
