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
    required classes itself, as the separate sandbox account, with a trusted
    driver and listener compiled from the trusted branch placed ahead of every
    candidate class, and that listener recorded each dispatched method with its
    outcome. Each ledger is HMAC-chained with a key only the trusted driver held;
    the orchestrator verified the chain before copying the ledger into trusted
    evidence and recorded the copy's digest in the manifest.

``INTEGRITY.json``
    Written by ``sandbox.py verify`` after every candidate execution: trusted
    files re-derived from Git, evidence seals intact, no candidate process alive,
    no trusted path writable by the candidate account. Anything but ``OK`` denies
    PASS.

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
import hashlib
import json
import sys
from pathlib import Path

REQUIRED_SURFACE_SCHEMA = "forge.candidate-qualification.required-surface/2"
EXECUTION_MANIFEST_SCHEMA = "forge.candidate-qualification.execution-manifest/2"
WITNESS_SCHEMA = "forge.d17.witness/2"

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

#: The orchestrator's verdict on a ledger's HMAC chain; nothing else is credit.
LEDGER_AUTHENTICATED = "HMAC_CHAIN_VERIFIED"
CONTAINMENT_ENFORCED = "SECURITY_MANAGER_ENFORCED"
CONTAINMENT_UNAVAILABLE = "UNAVAILABLE"
INTEGRITY_SCHEMA = "forge.candidate-qualification.integrity/1"
#: Accounts that must never be the identity that executed candidate code.
TRUSTED_IDENTITIES = ("root", "runner")

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
            if summary is not None:
                raise QualificationError("witness ledger records an invocation after its summary")
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
        # The listener records a PASS only for a result TestNG really dispatched
        # to its method (never a dry-run success); anything else is not credit.
        if status == "PASS" and record.get("invoked") is not True:
            raise QualificationError("witness invocation {} is a PASS without dispatch".format(index))
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
    containment = summary.get("containment")
    if containment not in (CONTAINMENT_ENFORCED, "SECURITY_MANAGER_VIOLATED"):
        raise QualificationError("witness summary has no valid hostile-bytecode containment state")

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
        "containment": containment,
        "containment_violation": summary.get("containment_violation"),
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
    integrity: "dict | None" = None,
    ledger_digests: "dict[str, str] | None" = None,
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
    test_source_ok = (
        manifest.get("trusted_test_source_sha") == lock["comparison_base"]["sha"]
        and manifest.get("candidate_test_sources_used_for_credit") is False
        and manifest.get("test_bytecode_origin") == "trusted_compile_of_comparison_base_git_export"
    )
    signal(
        "trusted_test_source_bound_to_comparison_base",
        test_source_ok,
        "test_source_sha={} origin={} candidate_tests_used={}".format(
            manifest.get("trusted_test_source_sha"), manifest.get("test_bytecode_origin"),
            manifest.get("candidate_test_sources_used_for_credit")),
    )
    build_divergence = manifest.get("candidate_build_definition_divergence")
    maven_authority = manifest.get("maven_repository_authority")
    build_definition_ok = (
        isinstance(build_divergence, list)
        and not build_divergence
        and isinstance(maven_authority, dict)
        and maven_authority.get("mode") == "trusted_read_only_offline"
        and isinstance(maven_authority.get("path"), str)
        and bool(maven_authority.get("path"))
    )
    signal(
        "candidate_build_definition_trusted",
        build_definition_ok,
        "divergence={} maven_repository_authority={}".format(
            build_divergence, maven_authority),
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

    # D24/D20/D22 source-policy obligations are distinct from the executable
    # TestNG dry-run denominator.  Their presence means honest infrastructure
    # may be valid, but whole-reactor test coverage is still only PARTIAL.
    coverage_gaps = surface.get("coverage_gaps")
    coverage_gaps = coverage_gaps if isinstance(coverage_gaps, dict) else {}
    framework_not_run = list(coverage_gaps.get("d24_framework_not_run_classes") or [])
    d24_inventory = coverage_gaps.get("d24_framework_not_run_source_inventory")
    d24_inventory = d24_inventory if isinstance(d24_inventory, dict) else {}
    d24_enabled_source_methods_not_run = coverage_gaps.get(
        "d24_enabled_source_methods_not_run")
    if not isinstance(d24_enabled_source_methods_not_run, int) or isinstance(
            d24_enabled_source_methods_not_run, bool):
        d24_enabled_source_methods_not_run = sum(
            int(item.get("enabled_source_methods", 0))
            for item in d24_inventory.values() if isinstance(item, dict)
        )
    explicit_disabled = list(coverage_gaps.get("explicitly_disabled_source_classes") or [])
    d22_disabled = list(coverage_gaps.get("d22_disabled_rules_tests") or [])
    coverage_complete = (
        surface.get("whole_reactor_coverage_complete") is True
        and not framework_not_run and not explicit_disabled and not d22_disabled
    )
    signal(
        "whole_reactor_coverage_complete",
        coverage_complete,
        "D24_NOT_RUN={} D24_enabled_source_methods_not_run={} explicit_disabled={} "
        "D22_disabled={}".format(
            framework_not_run, d24_enabled_source_methods_not_run,
            explicit_disabled, d22_disabled),
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

    # --- 10. Candidate code ran as the separate sandbox account -------------- #
    identity = manifest.get("candidate_execution_identity")
    launched_as = sorted({
        str(entry.get("execution_identity"))
        for module, entry in launch_entries.items()
        if module in expected_modules
    })
    isolated = (
        isinstance(identity, str) and bool(identity) and identity not in TRUSTED_IDENTITIES
        and launched_as == [identity]
        # When integrity was verified, it must have probed this same account.
        and (not isinstance(integrity, dict) or integrity.get("user") == identity)
    )
    signal(
        "candidate_executed_as_sandbox_account",
        isolated,
        "manifest identity={!r} launches={} integrity user={!r}".format(
            identity, launched_as, (integrity or {}).get("user") if isinstance(integrity, dict) else None
        ),
    )
    build_identity = manifest.get("candidate_build_identity")
    integrity_users = (integrity or {}).get("users") if isinstance(integrity, dict) else None
    build_execution_separated = (
        isinstance(build_identity, str) and bool(build_identity)
        and isinstance(identity, str) and bool(identity)
        and build_identity != identity
        and build_identity not in TRUSTED_IDENTITIES
        and identity not in TRUSTED_IDENTITIES
        and manifest.get("build_execution_identity_separated") is True
        and (manifest.get("candidate_build") or {}).get("user") in (None, build_identity)
        and (not isinstance(integrity_users, list)
             or sorted(set(integrity_users)) == sorted({build_identity, identity}))
    )
    signal(
        "build_and_execution_identities_separated",
        build_execution_separated,
        "build={!r} execute={!r} integrity_users={!r}".format(
            build_identity, identity, integrity_users),
    )

    # --- 11. Trusted state integrity held across every candidate execution -- #
    integrity_status = integrity.get("status") if isinstance(integrity, dict) else None
    integrity_ok = (
        isinstance(integrity, dict)
        and integrity.get("schema") == INTEGRITY_SCHEMA
        and integrity_status == "OK"
    )
    signal(
        "trusted_state_integrity",
        integrity_ok,
        "integrity status={!r} violations={}".format(
            integrity_status, (integrity or {}).get("violations") if isinstance(integrity, dict) else None
        ),
    )

    # --- 12. Every credited ledger is the orchestrator-authenticated copy --- #
    digests = ledger_digests or {}
    unauthenticated = {}
    for module in expected_modules:
        entry = launch_entries.get(module) or {}
        if entry.get("ledger_authentication") != LEDGER_AUTHENTICATED:
            unauthenticated[module] = entry.get("ledger_authentication") or "absent"
        elif not isinstance(entry.get("ledger_sha256"), str) or entry.get("ledger_sha256") != digests.get(module):
            unauthenticated[module] = "digest_mismatch"
    signal(
        "witness_ledgers_authenticated",
        not unauthenticated,
        "unauthenticated ledgers: {}".format(json.dumps(unauthenticated, sort_keys=True) if unauthenticated else "none"),
    )

    # --- 13. Hostile candidate bytecode containment ------------------------ #
    containment_states = {}
    for module in expected_modules:
        launch = launch_entries.get(module) or {}
        witness = witness_by_module.get(module) or {}
        containment_states[module] = {
            "manifest": launch.get("hostile_bytecode_containment"),
            "witness": witness.get("containment"),
        }
    containment_unavailable = any(
        state.get("manifest") == CONTAINMENT_UNAVAILABLE
        for state in containment_states.values()
    )
    containment_ok = bool(containment_states) and all(
        state.get("manifest") == CONTAINMENT_ENFORCED
        and state.get("witness") == CONTAINMENT_ENFORCED
        for state in containment_states.values()
    )
    signal(
        "hostile_candidate_bytecode_contained",
        containment_ok,
        "states={}".format(json.dumps(containment_states, sort_keys=True)),
    )

    # --- 14. Required classes ran from trusted-compiled bytecode ------------ #
    # The trusted test directory precedes the candidate's classes on the launch
    # classpath, so a required class trusted javac did not produce would be
    # loaded from candidate-built bytecode (main output or a dependency).
    compilation = manifest.get("trusted_test_compilation")
    compilation = compilation if isinstance(compilation, dict) else {}
    not_trusted_compiled: "dict[str, str]" = {}
    for module in expected_modules:
        record = compilation.get(module)
        wanted = sorted(required_classes.get(module, []))
        code = record.get("exit_code") if isinstance(record, dict) else None
        compiled = record.get("compiled_required") if isinstance(record, dict) else None
        if not isinstance(record, dict):
            # Trusted infrastructure stopped before compiling (pin mismatch,
            # sandbox not ready): not a candidate failure, and never credit.
            continue
        elif isinstance(code, bool) or code != 0:
            not_trusted_compiled[module] = "trusted javac exit {!r}".format(code)
        elif not isinstance(compiled, list) or sorted(compiled) != wanted:
            missing = sorted(set(wanted) - set(compiled if isinstance(compiled, list) else []))
            not_trusted_compiled[module] = "required classes not trusted-compiled: {}".format(missing[:10])
    signal(
        "required_tests_trusted_compiled",
        not not_trusted_compiled,
        "not trusted-compiled: {}".format(
            json.dumps(not_trusted_compiled, sort_keys=True) if not_trusted_compiled else "none"
        ),
    )

    # --- 15. The launch classpath was admitted before candidate code ran ----- #
    # Candidate-authored bytecode may not register a suite-wide TestNG listener,
    # hook or object factory, reach a test result, or touch the witness package;
    # a dependency jar must equal the trusted Maven repository's copy.
    admission = manifest.get("launch_classpath_admission")
    admission = admission if isinstance(admission, dict) else {}
    rejected: "dict[str, dict]" = {}
    unadmitted: "dict[str, object]" = {}
    for module in expected_modules:
        scan = admission.get(module)
        if not isinstance(compilation.get(module), dict):
            unadmitted[module] = "no trusted compilation record"
        elif not isinstance(scan, dict) or not all(
            isinstance(scan.get(key), list) for key in ("findings", "tampered_jars", "unverified_jars")
        ):
            unadmitted[module] = "no admission record"
        elif scan["findings"] or scan["tampered_jars"]:
            rejected[module] = {"findings": scan["findings"][:5], "tampered_jars": scan["tampered_jars"][:5]}
        elif scan["unverified_jars"]:
            unadmitted[module] = {"unverified_jars": scan["unverified_jars"][:5]}
    signal(
        "launch_classpath_admitted",
        not rejected and not unadmitted,
        "rejected={} unadmitted={}".format(
            json.dumps(rejected, sort_keys=True) if rejected else "none",
            json.dumps(unadmitted, sort_keys=True) if unadmitted else "none",
        ),
    )

    by_name = {item["signal"]: item for item in signals}

    if integrity_status == "VIOLATION":
        verdict, reason = FAIL, "trusted state integrity was violated during candidate execution"
    elif containment_unavailable:
        verdict, reason = UNKNOWN, (
            "hostile candidate bytecode containment is unavailable on this runtime; "
            "uncontained execution receives no qualification credit"
        )
    elif not by_name["candidate_identity_bound"]["satisfied"]:
        verdict, reason = FAIL, "candidate identity is not bound to the locked exact SHA/TREE"
    elif isinstance(build_divergence, list) and build_divergence:
        verdict, reason = UNKNOWN, (
            "candidate changes Maven build/plugin authority relative to the trusted comparison "
            "base; D17 refuses candidate-controlled build definitions: {}".format(
                build_divergence[:20])
        )
    elif (isinstance(manifest.get("error"), str) and not compilation
          and (manifest.get("candidate_build") or {}).get("exit_code") in (None, 0)):
        # Trusted infrastructure stopped before it compiled anything (TestNG pin
        # mismatch, sandbox not READY): neither a candidate failure nor credit.
        verdict, reason = UNKNOWN, (
            "trusted execution stopped before compiling the candidate's tests: {}".format(
                manifest.get("error")[:300])
        )
    elif rejected:
        # Checked before any outcome: such code can rewrite every other outcome.
        verdict, reason = FAIL, (
            "the launch classpath holds candidate bytecode that can change other tests' "
            "outcomes, or a dependency jar that differs from the trusted repository; "
            "see launch_classpath_admitted"
        )
    elif not by_name["required_tests_trusted_compiled"]["satisfied"]:
        verdict, reason = FAIL, (
            "a required test class was not produced by trusted compilation of the locked "
            "export; see required_tests_trusted_compiled"
        )
    elif not by_name["required_surface_bound_to_comparison_base"]["satisfied"]:
        verdict, reason = FAIL, "the required surface is not bound to the locked comparison base"
    elif not by_name["trusted_test_source_bound_to_comparison_base"]["satisfied"]:
        verdict, reason = FAIL, (
            "qualification test bodies are not bound to the trusted comparison-base source"
        )
    elif not by_name["candidate_build_definition_trusted"]["satisfied"]:
        verdict, reason = UNKNOWN, (
            "trusted Maven build/plugin authority was not established for the candidate"
        )
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
    elif not by_name["witness_ledgers_authenticated"]["satisfied"]:
        verdict, reason = FAIL, (
            "a credited witness ledger is not the orchestrator-authenticated copy; "
            "see witness_ledgers_authenticated"
        )
    elif not by_name["hostile_candidate_bytecode_contained"]["satisfied"]:
        verdict, reason = FAIL, (
            "candidate production bytecode did not remain inside the trusted in-JVM "
            "containment boundary; see hostile_candidate_bytecode_contained"
        )
    elif not by_name["candidate_executed_as_sandbox_account"]["satisfied"]:
        verdict, reason = FAIL, (
            "candidate code was not shown to run as the separate sandbox account"
        )
    elif not by_name["build_and_execution_identities_separated"]["satisfied"]:
        verdict, reason = FAIL, (
            "candidate Maven/build code and hostile production bytecode were not proven "
            "to run under distinct untrusted OS identities"
        )
    elif not by_name["trusted_state_integrity"]["satisfied"]:
        verdict, reason = UNKNOWN, (
            "trusted state integrity was not verified after candidate execution"
        )
    elif not by_name["launch_classpath_admitted"]["satisfied"]:
        verdict, reason = UNKNOWN, (
            "the launch classpath was not admitted (missing compilation or admission record, "
            "or a dependency jar absent from the trusted repository); see launch_classpath_admitted"
        )
    elif not by_name["whole_reactor_coverage_complete"]["satisfied"]:
        verdict, reason = PARTIAL, (
            "trusted execution is valid for the executable surface, but source-policy "
            "obligations remain NOT_RUN/disabled (D24/D20/D22); full Forge test coverage "
            "is not claimed"
        )
    else:
        verdict, reason = PASS, (
            "exact candidate SHA/TREE executed the complete trusted source-policy surface "
            "without failure"
        )

    return {
        "verdict": verdict,
        "reason": reason,
        "signals": signals,
        "coverage": {
            "whole_reactor_complete": coverage_complete,
            "d24_framework_not_run_classes": framework_not_run,
            "d24_framework_not_run_source_inventory": d24_inventory,
            "d24_enabled_source_methods_not_run": d24_enabled_source_methods_not_run,
            "explicitly_disabled_source_classes": explicit_disabled,
            "d22_disabled_rules_tests": d22_disabled,
        },
        "build_authority": {
            "candidate_build_definition_divergence": build_divergence,
            "maven_repository_authority": maven_authority,
            "trusted": build_definition_ok,
        },
        "containment": {
            "required": True,
            "states": containment_states,
            "unavailable": containment_unavailable,
        },
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
    integrity: "dict | None" = None,
) -> dict:
    witness_by_module: "dict[str, dict]" = {}
    ledger_digests: "dict[str, str]" = {}
    present_modules: "list[str]" = []
    problems: "list[str]" = []
    nonce = manifest.get("nonce")
    for module, entry in (surface.get("modules") or {}).items():
        if not entry.get("classes"):
            continue
        ledger = Path(witness_dir) / (module + ".witness.jsonl")
        if ledger.is_file():
            present_modules.append(module)
            ledger_digests[module] = hashlib.sha256(ledger.read_bytes()).hexdigest()
        if not isinstance(nonce, str) or not nonce:
            problems.append("execution manifest carries no trusted run nonce")
            break
        try:
            witness_by_module[module] = read_witness(ledger, module, nonce)
        except QualificationError as exc:
            problems.append(str(exc))

    outcome = derive_verdict(
        lock, surface, manifest, witness_by_module, present_modules,
        out_of_band_classes, problems, integrity, ledger_digests,
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
            "coverage": outcome["coverage"],
            "containment": outcome["containment"],
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
            "candidate_execution_identity": manifest.get("candidate_execution_identity"),
            "test_bytecode_origin": manifest.get("test_bytecode_origin"),
            "candidate_test_sources_used_for_credit": manifest.get("candidate_test_sources_used_for_credit"),
            "hostile_bytecode_containment_required": manifest.get("hostile_bytecode_containment_required"),
            "hostile_bytecode_containment": outcome["containment"],
            "trusted_state_integrity": (integrity or {}).get("status") if isinstance(integrity, dict) else None,
            "candidate_definition_divergent": lock["candidate_definition_divergence"]["divergent"],
            "candidate_definition_note": lock["candidate_definition_divergence"]["note"],
            "ignored_candidate_verdict_keys": list(VERDICT_KEYS),
            "note": (
                "no Surefire/TestNG report, generated, copied, renamed or committed "
                "candidate artifact is read for any qualification signal; candidate-owned "
                "test bodies are not executed for credit; trusted comparison-base tests exercise "
                "candidate production bytecode only behind the required containment boundary"
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
    parser.add_argument("--integrity", required=True, help="INTEGRITY.json written by sandbox.py verify")
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

    try:
        integrity = json.loads(Path(args.integrity).read_text())
    except (OSError, ValueError):
        integrity = None
    evidence = build_evidence(
        lock, surface, manifest, Path(args.witness_dir), args.out_of_band_test_class, integrity
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