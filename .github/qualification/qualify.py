#!/usr/bin/env python3
"""Derive a source-bound exact-SHA candidate qualification verdict for Forge.

Trust boundary
--------------
This script runs from the trusted default-branch checkout and is the only writer
of the qualification verdict.

**Candidate-authored build artifacts carry no credit.** No Surefire report, no
TestNG result file, no generated, copied, renamed or committed XML is read for
any qualification signal. Credit is derived from two trusted ledgers:

``required-surface.json``
    The trusted denominator: produced by running TestNG's ``-dryrun`` over the
    trusted comparison base, so it reflects what the trusted lineage's own tests
    would execute. A candidate cannot shrink it.

``execution-manifest.json`` + ``witness/*.witness.jsonl``
    The trusted observation: the trusted orchestrator launched each module's
    required classes itself, with a trusted listener compiled from the trusted
    branch placed ahead of every candidate class, and that listener recorded each
    dispatched method with its outcome.

Fail-closed contract
--------------------
``PASS`` requires positive proof that the *exact* candidate SHA/TREE executed the
whole trusted required surface, with every required class observed alive and no
undeclared skip. Absent, empty, malformed, mismatched or ambiguous evidence is
``FAIL``, ``PARTIAL``, ``NOT_RUN`` or ``UNKNOWN`` -- never ``PASS``. Each non-PASS
class has its own nonzero exit code, so no consumer can read an unproven
qualification as a green check.

Evidence classes are reported separately and never aliased: PR mergeability,
synthetic-merge evidence, exact candidate qualification, and Rules qualification
evidence.

Scope boundary
--------------
D20/#501 owns general persisted JUnit/timing artifact provenance. This module
implements only the minimum trusted execution provenance D17 needs to stop
candidate-authored build artifacts from manufacturing PASS.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REQUIRED_SURFACE_SCHEMA = "forge.candidate-qualification.required-surface/1"
EXECUTION_MANIFEST_SCHEMA = "forge.candidate-qualification.execution-manifest/1"
WITNESS_SCHEMA = "forge.d17.witness/1"

PASS = "PASS"
FAIL = "FAIL"
PARTIAL = "PARTIAL"
NOT_RUN = "NOT_RUN"
UNKNOWN = "UNKNOWN"

#: Distinct exit codes.  Only PASS exits 0.
EXIT_CODES = {PASS: 0, FAIL: 1, PARTIAL: 2, NOT_RUN: 3, UNKNOWN: 4}

VERDICT_KEYS = ("verdict", "status", "result", "outcome", "passed", "success")

#: TestNG's own process exit codes: 0 = all passed, 1 = suite had failures,
#: 2 = suite completed but some tests were skipped. A skipped-but-complete run is
#: not a broken launch; its skips are judged separately by the skip policy, and
#: its failures are judged by the trusted witness rather than by the exit code.
#: Any other code (or unparseable totals) means the launch itself did not finish.
TESTNG_LAUNCH_COMPLETED_CODES = (0, 2)

_NOT_APPLICABLE = "NOT_APPLICABLE"
_NOT_CLAIMED = "NOT_CLAIMED"

SKIP_POLICY = "SKIPS_OUTSIDE_DECLARED_OUT_OF_BAND_SURFACE_ARE_PARTIAL"


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


# --------------------------------------------------------------------------- #
# Witness ledger
# --------------------------------------------------------------------------- #


def read_witness(path: Path, module: str, nonce: str) -> dict:
    """Parse one trusted witness ledger and recompute every aggregate.

    The closing summary is *not* trusted: counts are recomputed from the
    invocation records, and a mismatch with the summary is itself a problem, so a
    hand-edited summary cannot manufacture credit.
    """
    path = Path(path)
    if not path.is_file():
        raise QualificationError("witness ledger is missing for {}".format(module))
    header = None
    summary = None
    invocations = []
    for number, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except ValueError as exc:
            raise QualificationError(
                "witness ledger {} line {} is malformed: {}".format(path, number, exc)
            )
        if not isinstance(record, dict):
            raise QualificationError("witness record {} is not an object".format(number))
        kind = record.get("kind")
        if kind == "header":
            if header is not None:
                raise QualificationError("witness ledger has multiple headers")
            header = record
        elif kind == "invocation":
            invocations.append(record)
        elif kind == "summary":
            if summary is not None:
                raise QualificationError("witness ledger has multiple summaries")
            summary = record
        else:
            raise QualificationError("witness record {} has unknown kind {!r}".format(number, kind))

    if header is None:
        raise QualificationError("witness ledger for {} has no header".format(module))
    if summary is None:
        raise QualificationError("witness ledger for {} has no summary".format(module))
    if header.get("schema") != WITNESS_SCHEMA:
        raise QualificationError("witness schema is not {!r}".format(WITNESS_SCHEMA))
    if header.get("module") != module:
        raise QualificationError(
            "witness module is {!r} but was launched for {!r}".format(header.get("module"), module)
        )
    if header.get("nonce") != nonce:
        raise QualificationError("witness nonce does not match the trusted run nonce")

    # Sequence must be gap-free and start at zero, so truncated or spliced
    # ledgers cannot validate.
    sequences = []
    per_class_total = {}
    per_class_pass = {}
    per_class_skip = {}
    per_class_fail = {}
    for index, record in enumerate(invocations):
        seq = record.get("seq")
        if not isinstance(seq, int) or isinstance(seq, bool):
            raise QualificationError("witness invocation {} has a non-integer seq".format(index))
        sequences.append(seq)
        if seq != index:
            raise QualificationError(
                "witness sequence is not contiguous at position {}: seq={}".format(index, seq)
            )
        for key in ("class", "method", "status"):
            if not isinstance(record.get(key), str) or not record[key]:
                raise QualificationError("witness invocation {} lacks {!r}".format(index, key))
        if not isinstance(record.get("context"), str) or not record["context"]:
            raise QualificationError("witness invocation {} lacks a TestNG context".format(index))
        status = record["status"]
        if status not in ("PASS", "FAIL", "SKIP"):
            raise QualificationError("witness invocation {} has status {!r}".format(index, status))
        name = record["class"]
        per_class_total[name] = per_class_total.get(name, 0) + 1
        bucket = {"PASS": per_class_pass, "SKIP": per_class_skip, "FAIL": per_class_fail}[status]
        bucket[name] = bucket.get(name, 0) + 1

    recomputed_total = len(invocations)
    if summary.get("tests") != recomputed_total:
        raise QualificationError(
            "witness summary claims {} tests but {} invocations were recorded".format(
                summary.get("tests"), recomputed_total
            )
        )
    if summary.get("failed") != sum(per_class_fail.values()):
        raise QualificationError("witness summary failure total disagrees with its records")
    if summary.get("skipped") != sum(per_class_skip.values()):
        raise QualificationError("witness summary skip total disagrees with its records")

    return {
        "module": module,
        "tests": recomputed_total,
        "failed": sum(per_class_fail.values()),
        "skipped": sum(per_class_skip.values()),
        "passed": sum(per_class_pass.values()),
        "per_class_total": per_class_total,
        "per_class_pass": per_class_pass,
        "per_class_skip": per_class_skip,
        "per_class_fail": per_class_fail,
        "summary_matches_records": True,
    }


# --------------------------------------------------------------------------- #
# Verdict derivation (pure)
# --------------------------------------------------------------------------- #


def derive_verdict(
    lock: dict,
    surface: dict,
    manifest: dict,
    witness_by_module: "dict[str, dict]",
    present_modules: "list[str]",
    out_of_band_classes: "list[str]",
    problems: "list[str]",
) -> dict:
    """Derive the verdict from trusted ledgers only. Pure function."""
    signals: "list[dict]" = []

    def signal(name: str, ok: bool, detail: str) -> None:
        signals.append({"signal": name, "satisfied": bool(ok), "detail": detail})

    # --- 1. Exact candidate identity binding ------------------------------- #
    bound_sha = manifest.get("candidate_sha")
    bound_tree = manifest.get("candidate_tree")
    identity_ok = bound_sha == lock["candidate"]["sha"] and bound_tree == lock["candidate"]["tree"]
    signal(
        "candidate_identity_bound",
        identity_ok,
        "execution manifest claims sha={} tree={}; locked candidate sha={} tree={}".format(
            bound_sha, bound_tree, lock["candidate"]["sha"], lock["candidate"]["tree"]
        ),
    )

    # --- 2. Trusted denominator provenance ---------------------------------- #
    surface_base_ok = surface.get("comparison_base_sha") == lock["comparison_base"]["sha"]
    signal(
        "required_surface_bound_to_comparison_base",
        surface_base_ok,
        "required surface base={} vs locked comparison base={}".format(
            surface.get("comparison_base_sha"), lock["comparison_base"]["sha"]
        ),
    )

    # --- 3. The required surface is genuinely non-empty --------------------- #
    modules = surface.get("modules", {})
    required_total = sum(int(m.get("required_total", 0)) for m in modules.values())
    required_classes = {
        module: list(entry.get("classes", [])) for module, entry in modules.items()
    }
    # Per-class floors come from the trusted dry run, so a class that the trusted
    # lineage itself never executes is not demanded of the candidate, and a class
    # it does execute cannot be partially suppressed.
    required_class_counts = {
        module: dict(entry.get("class_counts") or {}) for module, entry in modules.items()
    }
    signal(
        "required_surface_non_empty",
        required_total > 0 and any(required_classes.values()),
        "{} required invocation(s) across {} module(s)".format(required_total, len(modules)),
    )

    # --- 4. Trusted launches completed -------------------------------------- #
    launch_entries = manifest.get("modules") or {}
    launch_codes = {module: entry.get("launch_exit_code") for module, entry in launch_entries.items()}
    incomplete = {
        module: code
        for module, entry in launch_entries.items()
        for code in [entry.get("launch_exit_code")]
        if not (isinstance(code, int) and not isinstance(code, bool)
                and code in TESTNG_LAUNCH_COMPLETED_CODES
                and isinstance(entry.get("testng_totals"), dict))
    }
    launches_ok = bool(launch_codes) and not incomplete
    signal(
        "trusted_launches_completed",
        launches_ok,
        "launch_exit_codes={} incomplete={}".format(launch_codes, incomplete),
    )

    # --- 5. Witness ledgers are present and authentic ----------------------- #
    expected_modules = sorted(m for m, classes in required_classes.items() if classes)
    # An absent ledger and a malformed ledger are different failures: absent means
    # nothing ran (NOT_RUN), malformed means execution is ambiguous (UNKNOWN).
    signal(
        "witness_ledgers_present",
        sorted(present_modules) == expected_modules,
        "expected={} present={}".format(expected_modules, sorted(present_modules)),
    )
    signal("witness_ledgers_parsed", not problems, "{} parse problem(s)".format(len(problems)))

    # --- 6. Every required class was observed alive ------------------------ #
    dead_classes: "dict[str, list]" = {}
    for module in expected_modules:
        observed = witness_by_module.get(module)
        floors = required_class_counts.get(module, {})
        wanted = required_classes.get(module, [])
        if observed is None:
            dead_classes[module] = list(wanted)
            continue
        alive = observed["per_class_total"]
        missing = [
            name for name in wanted
            if alive.get(name, 0) < max(1, int(floors.get(name, 1)))
        ]
        if missing:
            dead_classes[module] = missing
    signal(
        "required_classes_executed",
        not dead_classes,
        "classes never observed executing: {}".format(
            json.dumps(dead_classes, sort_keys=True) if dead_classes else "none"
        ),
    )

    # --- 7. Observed volume meets the trusted denominator -------------------- #
    observed_total = sum(w["tests"] for w in witness_by_module.values())
    signal(
        "required_invocations_met",
        observed_total >= required_total,
        "observed {} invocation(s) against trusted required {}".format(observed_total, required_total),
    )

    # --- 8. Failures --------------------------------------------------------- #
    failures = sum(w["failed"] for w in witness_by_module.values())
    signal("no_failed_cases", failures == 0, "{} failure(s)".format(failures))

    # --- 9. Skips, classified against the trusted allowlist ------------------ #
    skipped = sum(w["skipped"] for w in witness_by_module.values())
    skip_classes = sorted({
        name for w in witness_by_module.values() for name in w["per_class_skip"]
    })
    declared = set(out_of_band_classes)
    undeclared = sorted(name for name in skip_classes if name not in declared)
    unattributable = skipped > 0 and not skip_classes
    if unattributable:
        undeclared = sorted(set(undeclared) | {"<unattributable>"})
    signal(
        "no_undeclared_skips",
        not undeclared,
        "skipped={} declared={} skipped_classes={} undeclared={}".format(
            skipped, sorted(declared), skip_classes, undeclared
        ),
    )

    by_name = {item["signal"]: item for item in signals}

    if not by_name["candidate_identity_bound"]["satisfied"]:
        verdict, reason = FAIL, "candidate identity is not bound to the locked exact SHA/TREE"
    elif not by_name["required_surface_bound_to_comparison_base"]["satisfied"]:
        verdict, reason = FAIL, "the required surface is not bound to the locked comparison base"
    elif not by_name["trusted_launches_completed"]["satisfied"]:
        verdict, reason = FAIL, (
            "a trusted test launch did not complete cleanly; see trusted_launches_completed"
        )
    elif not by_name["no_failed_cases"]["satisfied"]:
        verdict, reason = FAIL, "trusted execution witness observed failing tests"
    elif not by_name["required_surface_non_empty"]["satisfied"]:
        verdict, reason = NOT_RUN, "no required surface was enumerated from the trusted base"
    elif not by_name["witness_ledgers_present"]["satisfied"]:
        verdict, reason = NOT_RUN, "no trusted execution witness ledger was produced"
    elif not by_name["witness_ledgers_parsed"]["satisfied"]:
        verdict, reason = UNKNOWN, "trusted witness evidence is malformed; execution is ambiguous"
    elif not by_name["required_classes_executed"]["satisfied"]:
        verdict, reason = FAIL, (
            "required test methods were not observed executing at the trusted "
            "per-class denominator"
        )
    elif not by_name["required_invocations_met"]["satisfied"]:
        verdict, reason = PARTIAL, (
            "observed execution is below the trusted required surface; the candidate "
            "reduced the qualified test volume"
        )
    elif not by_name["no_undeclared_skips"]["satisfied"]:
        verdict, reason = PARTIAL, (
            "test cases inside the qualified surface were skipped and are not declared "
            "out of band by the trusted contract: {}".format(undeclared)
        )
    else:
        verdict, reason = PASS, (
            "exact candidate SHA/TREE executed the whole trusted required surface "
            "without failure"
        )

    return {
        "verdict": verdict,
        "reason": reason,
        "signals": signals,
        "counts": {
            "observed_invocations": observed_total,
            "required_invocations": required_total,
            "failures": failures,
            "skipped": skipped,
            "modules_observed": sorted(witness_by_module),
        },
        "required": {
            "comparison_base_sha": surface.get("comparison_base_sha"),
            "denominator_source": surface.get("denominator_source"),
            "modules": required_classes,
            "required_class_counts": required_class_counts,
            "required_total": required_total,
        },
        "dead_classes": dead_classes,
        "problems": problems,
        "skips": {
            "policy": SKIP_POLICY,
            "declared_out_of_band_classes": sorted(declared),
            "observed_skipped_classes": skip_classes,
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
    surface: dict,
    manifest: dict,
    witness_dir: Path,
    out_of_band_classes: "list[str]",
) -> dict:
    witness_by_module: "dict[str, dict]" = {}
    present_modules: "list[str]" = []
    problems: "list[str]" = []
    nonce = manifest.get("nonce")
    for module, entry in (surface.get("modules") or {}).items():
        if not entry.get("classes"):
            continue
        ledger = Path(witness_dir) / (module + ".witness.jsonl")
        if ledger.is_file():
            present_modules.append(module)
        if not isinstance(nonce, str) or not nonce:
            problems.append("execution manifest carries no trusted run nonce")
            break
        try:
            witness_by_module[module] = read_witness(ledger, module, nonce)
        except QualificationError as exc:
            problems.append(str(exc))

    outcome = derive_verdict(
        lock, surface, manifest, witness_by_module, present_modules,
        out_of_band_classes, problems,
    )

    return {
        "schema": "forge.candidate-qualification.evidence/2",
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
            "role": "trusted denominator source; inspected Git data, not mergeability",
        },
        "exact_candidate_qualification": {
            "verdict": outcome["verdict"],
            "reason": outcome["reason"],
            "signals": outcome["signals"],
            "counts": outcome["counts"],
            "required": outcome["required"],
            "dead_classes": outcome["dead_classes"],
            "problems": outcome["problems"],
            "skips": outcome["skips"],
        },
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
            "candidate_build_artifacts_used_as_evidence": False,
            "candidate_reports_read_for_credit": False,
            "execution_observed_by": manifest.get("witness_class"),
            "execution_observed_from": "trusted listener compiled from the trusted default branch",
            "candidate_definition_divergent": lock["candidate_definition_divergence"]["divergent"],
            "candidate_definition_note": lock["candidate_definition_divergence"]["note"],
            "ignored_candidate_verdict_keys": list(VERDICT_KEYS),
            "note": (
                "no Surefire/TestNG report, generated, copied, renamed or committed "
                "candidate artifact is read for any qualification signal; credit comes "
                "only from the trusted dry-run denominator and the trusted witness ledger"
            ),
        },
        "repository": lock.get("repository"),
    }


def write_evidence(evidence: dict, path: Path) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lock", required=True, help="trusted source lock JSON")
    parser.add_argument("--required-surface", required=True, help="trusted required surface ledger")
    parser.add_argument("--execution-manifest", required=True, help="trusted execution manifest")
    parser.add_argument("--witness-dir", required=True, help="directory of trusted witness ledgers")
    parser.add_argument(
        "--out-of-band-test-class", action="append", default=[],
        help="test class declared outside the qualified surface by the trusted contract; repeatable",
    )
    parser.add_argument("--output", required=True, help="path of the evidence JSON to write")
    args = parser.parse_args(argv)

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import source_lock  # noqa: E402  (trusted-path import, intentionally local)

    try:
        lock = source_lock.load_lock(Path(args.lock))
        surface = load_json(Path(args.required_surface), "required surface")
        manifest = load_json(Path(args.execution_manifest), "execution manifest")
        if surface.get("schema") != REQUIRED_SURFACE_SCHEMA:
            raise QualificationError("required surface schema is not {!r}".format(REQUIRED_SURFACE_SCHEMA))
        if manifest.get("schema") != EXECUTION_MANIFEST_SCHEMA:
            raise QualificationError("execution manifest schema is not {!r}".format(EXECUTION_MANIFEST_SCHEMA))
    except (source_lock.SourceLockError, QualificationError) as exc:
        evidence = {
            "schema": "forge.candidate-qualification.evidence/2",
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
        lock, surface, manifest, Path(args.witness_dir), args.out_of_band_test_class
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