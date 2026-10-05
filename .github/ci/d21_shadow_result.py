#!/usr/bin/env python3
"""D21 shadow result verifier; source-lock-specific and non-authoritative."""

from __future__ import annotations
import argparse
import json
import pathlib
import xml.etree.ElementTree as ET
from collections import Counter

RED_CLASS = "forge.d21.D21ModuleAccessRedControlTest"
RED_METHOD = "reflectiveUriAccessRequiresModuleOpen"
EXPECTED_NON_RED_FAILURES = {
    "forge.card.CardDbCardMockTestCase": 18,
    "forge.card.CardDbLazyCardLoadingCardMockTestCase": 1,
    "forge.card.CardDbPerformanceTests": 18,
    "forge.card.CardDbWithNoImageCardDbMockTestCase": 18,
    "forge.card.CardEditionCollectionCardMockTestCase": 1,
    "forge.deck.DeckRecognizerTest": 12,
}

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--maven-exit", type=int, required=True)
    parser.add_argument("--duration-seconds", type=float, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--source-tree", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    xmls = sorted(pathlib.Path(args.repo).glob("**/target/surefire-reports/TEST-*.xml"))
    failures = []
    totals = Counter()
    for path in xmls:
        suite = ET.parse(path).getroot()
        for case in suite.iter("testcase"):
            cls = case.attrib.get("classname", "")
            name = case.attrib.get("name", "")
            totals["testcases"] += 1
            if case.find("skipped") is not None:
                totals["skipped"] += 1
                continue
            node = case.find("failure")
            if node is None:
                node = case.find("error")
            if node is not None:
                totals["failed"] += 1
                failures.append((cls, name, (node.attrib.get("type") or "") + ": " + (node.attrib.get("message") or "")))
            else:
                totals["passed"] += 1

    red = [f for f in failures if f[0] == RED_CLASS and f[1] == RED_METHOD]
    non_red = Counter(cls for cls, _name, _msg in failures if cls != RED_CLASS)
    expected = Counter(EXPECTED_NON_RED_FAILURES)
    unexpected = [
        {"class": cls, "method": name, "message": message}
        for cls, name, message in failures
        if cls != RED_CLASS and cls not in expected
    ]
    verdict = len(red) == 1 and non_red == expected and args.maven_exit != 0 and not unexpected

    report = {
        "schema": "forge.d21-shadow-result/1",
        "source": {"sha": args.source_sha, "tree": args.source_tree},
        "duration_seconds": args.duration_seconds,
        "maven_exit_code": args.maven_exit,
        "totals": dict(totals),
        "red_control": {
            "class": RED_CLASS,
            "method": RED_METHOD,
            "detected": len(red) == 1,
            "failure": red[0][2] if red else None,
        },
        "expected_non_red_failure_counts": dict(expected),
        "observed_non_red_failure_counts": dict(non_red),
        "non_red_outcome_equivalence": non_red == expected,
        "unexpected_failure_ids": unexpected,
        "report_files": [str(path) for path in xmls],
        "shadow_verdict": "PASS" if verdict else "FAIL",
        "qualification_credit": "NONE_SHADOW_ONLY",
    }
    pathlib.Path(args.output).write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if verdict else 1

if __name__ == "__main__":
    raise SystemExit(main())
