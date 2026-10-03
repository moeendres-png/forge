#!/usr/bin/env python3
"""Trusted orchestration of exact-SHA candidate qualification execution.

Trust boundary
--------------
This module runs from the trusted default-branch checkout.  It is the only thing
that launches the test JVMs, and it derives qualification credit from what it
observed those JVMs dispatch -- never from an artifact the candidate's build
produced.

Why this exists
---------------
The previous D17 design parsed the candidate's own
``target/surefire-reports/TEST-*.xml`` and granted PASS from those counts after a
candidate-controlled Maven run exited successfully.  A candidate could suppress
tests in its POM, return Maven success, and commit or lifecycle-generate green
XML with plausible counts.  Trusted Python parsing candidate numbers is not
provenance.  Candidate-authored build artifacts therefore carry **no** credit.

The replacement has two trusted halves:

``required surface``
    Enumerated by running TestNG's own ``-dryrun`` over the **trusted comparison
    base** checkout.  This is an observation of what the trusted lineage's tests
    would execute, so it is a trusted denominator that no candidate can shrink.

``observed execution``
    Recorded by ``forge/d17/witness/QualifiedExecutionListener.java``, compiled
    here from trusted source and placed ahead of every candidate class on the
    classpath.  It records each dispatched method with its outcome.

Candidate-controlled inputs exist (POMs, sources, ``mvn test-compile``,
``dependency:build-classpath``) because the code under qualification must be
compiled.  Their influence is bounded and fail-closed by construction: each can
only *reduce* the set of required methods observed as dispatched.  None can
increase it, and none is read as evidence.  If any of them is subverted the run
records fewer invocations than the trusted denominator requires, and the verdict
is not PASS.

Scope boundary
--------------
D20/#501 owns general persisted JUnit/timing artifact provenance and retention.
This module implements only the minimum trusted execution provenance D17 needs to
stop candidate-authored build artifacts from manufacturing PASS.  It persists a
witness ledger and a required-surface ledger; it does not build a general
artifact store.
"""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
from pathlib import Path

REQUIRED_SURFACE_SCHEMA = "forge.candidate-qualification.required-surface/1"
EXECUTION_MANIFEST_SCHEMA = "forge.candidate-qualification.execution-manifest/1"
WITNESS_SCHEMA = "forge.d17.witness/1"

#: Trusted JVM flags, taken from the trusted comparison base rather than from the
#: candidate POM so that a candidate cannot remove them to change behaviour.
DEFAULT_TRUSTED_ARGLINE = [
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.time=ALL-UNNAMED",
    "--add-opens", "java.base/java.text=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.regex=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.stream=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens", "java.desktop/javax.imageio.spi=ALL-UNNAMED",
]

#: Surefire's default test include patterns, reproduced here so the trusted
#: required surface matches what Forge's existing gate would have discovered.
#: These are globs, matched with real glob semantics.
TEST_INCLUDE_PATTERNS = ("Test*.java", "*Test.java", "*Tests.java", "*TestCase.java")

WITNESS_CLASS = "forge.d17.witness.QualifiedExecutionListener"
WITNESS_SOURCE = "witness/forge/d17/witness/QualifiedExecutionListener.java"


class ExecutionError(Exception):
    """The trusted execution could not be completed. Always fail closed."""


def log(message: str) -> None:
    sys.stdout.write("d17-exec: {}\n".format(message))
    sys.stdout.flush()


def run(argv, cwd, timeout=None, env=None):
    """Run a subprocess and capture its result without raising."""
    merged = dict(os.environ)
    if env:
        merged.update(env)
    try:
        proc = subprocess.run(
            argv, cwd=str(cwd), capture_output=True, text=True,
            check=False, timeout=timeout, env=merged,
        )
    except subprocess.TimeoutExpired:
        return 124, "", "timeout after {}s".format(timeout)
    except OSError as exc:
        return 127, "", str(exc)
    return proc.returncode, proc.stdout, proc.stderr


def git(repo: Path, *args: str) -> str:
    proc = subprocess.run(["git", *args], cwd=str(repo), capture_output=True,
                          text=True, check=False)
    if proc.returncode != 0:
        raise ExecutionError("git {} failed: {}".format(" ".join(args), proc.stderr.strip()))
    return proc.stdout.strip()


# --------------------------------------------------------------------------- #
# Required surface: what the trusted comparison base would execute
# --------------------------------------------------------------------------- #


def required_classes_for_module(repo: Path, module: str, base: str) -> "list[str]":
    """Baseline required test classes, read from trusted Git data only.

    The include patterns are Surefire's defaults, matched with real glob
    semantics.  Understating this set would let a candidate delete required
    tests and still satisfy the trusted denominator, so the patterns are applied
    exactly rather than approximated with ``endswith``.
    """
    listing = git(repo, "ls-tree", "-r", "--name-only", base, "--",
                  "{}/src/test/java".format(module))
    classes = []
    for path in sorted(listing.splitlines()):
        if not path.endswith(".java"):
            continue
        name = path.rsplit("/", 1)[-1]
        if not any(fnmatch.fnmatch(name, pattern) for pattern in TEST_INCLUDE_PATTERNS):
            continue
        classes.append(path[len(module) + len("/src/test/java/"):-len(".java")].replace("/", "."))
    return classes


def module_classpath(module_dir: Path, relative_cp: str) -> "list[str]":
    """Classpath entries for a module: its own outputs then its dependencies."""
    entries = [str(module_dir / "target" / "test-classes"), str(module_dir / "target" / "classes")]
    deps = (module_dir / relative_cp).read_text().strip() if (module_dir / relative_cp).is_file() else ""
    entries.extend([entry for entry in deps.split(":") if entry])
    return entries


def resolve_testng_jar(entries: "list[str]") -> str:
    for entry in entries:
        if os.path.basename(entry).startswith("testng-") and entry.endswith(".jar"):
            return entry
    raise ExecutionError("TestNG is not present on the resolved test classpath")


def compile_and_resolve(repo: Path, modules, cp_rel, label: str):
    """Compile the reactor and resolve each module's test classpath.

    Invoked from the reactor root because Forge uses CI-friendly ``${revision}``
    versions, which a single-module build cannot resolve.  ``cp_rel`` is relative
    so each module writes its own file under its own ``target/``.
    """
    # -am is required: Forge uses CI-friendly ${revision} versions, so a module
    # cannot resolve its siblings unless they are in the reactor.
    selection = ["-pl", ",".join(modules), "-am"]
    code, _, err = run(["mvn", "-B", "-q", "-DskipTests", "test-compile"] + selection,
                       repo, timeout=14400)
    if code != 0:
        raise ExecutionError("{} test-compile failed: {}".format(label, err[-800:]))
    code, _, err = run(["mvn", "-B", "-q", "dependency:build-classpath",
                        "-Dmdep.outputFile=" + cp_rel, "-DincludeScope=test"] + selection,
                       repo, timeout=14400)
    if code != 0:
        raise ExecutionError("{} classpath resolution failed: {}".format(label, err[-800:]))
    for module in modules:
        if not (repo / module / cp_rel).is_file():
            raise ExecutionError(
                "{} classpath missing for module {} (did the module build?)".format(label, module)
            )


def dryrun_module(repo: Path, module: str, base: str, java: str, argline, xvfbrun, cp_rel):
    """Run TestNG ``-dryrun`` over the trusted comparison base for one module.

    TestNG's dry run executes no test bodies but resolves the full suite, so its
    totals are exactly the number of invocations the trusted lineage's own tests
    would produce. That is the trusted denominator: it comes from the trusted
    comparison base, so a candidate cannot shrink it.
    """
    module_dir = repo / module
    classes = required_classes_for_module(repo, module, base)
    if not classes:
        return {"module": module, "classes": [], "required_total": 0,
                "required_passed": 0, "required_skipped": 0, "required_failed": 0}

    entries = module_classpath(module_dir, cp_rel)
    resolve_testng_jar(entries)
    out_dir = module_dir / "target" / "d17-dryrun"
    shutil.rmtree(out_dir, ignore_errors=True)
    cmd = [java] + list(argline) + ["-cp", ":".join(entries), "org.testng.TestNG",
                                  "-d", str(out_dir), "-dryrun",
                                  "-testclass", ",".join(classes)]
    code, stdout, stderr = run(xvfbrun + cmd, module_dir, timeout=7200)
    totals = _parse_testng_totals(stdout + stderr)
    if totals is None:
        raise ExecutionError("could not read TestNG dry-run totals for {}".format(module))
    return {
        "module": module,
        "classes": classes,
        "required_total": totals["total"],
        "required_passed": totals["passed"],
        "required_failed": totals["failed"],
        "required_skipped": totals["skipped"],
    }


_TOTAL_RE = re.compile(
    r"Total tests run:\s*(\d+),\s*Passes:\s*(\d+),\s*Failures:\s*(\d+),\s*Skips:\s*(\d+)"
)


def _parse_testng_totals(text: str):
    match = None
    for match in _TOTAL_RE.finditer(text):
        pass
    if match is None:
        return None
    return {
        "total": int(match.group(1)),
        "passed": int(match.group(2)),
        "failed": int(match.group(3)),
        "skipped": int(match.group(4)),
    }


def build_required_surface(trusted_repo: Path, base: str, modules, java, argline, xvfbrun, cp_rel) -> dict:
    log("comparison base: compile and resolve classpaths")
    compile_and_resolve(trusted_repo, modules, cp_rel, "comparison base")
    surface = {
        "schema": REQUIRED_SURFACE_SCHEMA,
        "comparison_base_sha": base,
        "comparison_base_tree": git(trusted_repo, "rev-parse", "{}^{{tree}}".format(base)),
        "trusted_argline": list(argline),
        "denominator_source": "TestNG -dryrun over the trusted comparison base",
        "modules": {},
    }
    for module in modules:
        log("required surface: dry-run {}".format(module))
        surface["modules"][module] = dryrun_module(
            trusted_repo, module, base, java, argline, xvfbrun, cp_rel
        )
        log("  required_total={} required_skipped={} required_classes={}".format(
            surface["modules"][module]["required_total"],
            surface["modules"][module]["required_skipped"],
            len(surface["modules"][module]["classes"]),
        ))
    return surface


# --------------------------------------------------------------------------- #
# Candidate compilation and trusted execution
# --------------------------------------------------------------------------- #


def compile_witness(trusted_repo: Path, classes_dir: Path, java: str, testng_jar: str):
    """Compile the trusted witness from trusted source with the trusted JDK."""
    classes_dir.mkdir(parents=True, exist_ok=True)
    # The witness source lives beside this trusted script, not at the repo root.
    witness_root = Path(__file__).resolve().parent / "witness"
    sources = sorted(str(p) for p in witness_root.rglob("*.java"))
    if not sources:
        raise ExecutionError("trusted witness source is missing")
    code, _, err = run([java.replace("bin/java", "bin/javac") if java.endswith("/bin/java") else "javac",
                        "-cp", testng_jar, "-d", str(classes_dir)] + sources,
                       trusted_repo, timeout=900)
    if code != 0:
        raise ExecutionError("trusted witness compilation failed: {}".format(err[-800:]))
    return classes_dir


def assemble_classpath(witness_classes, entries) -> str:
    """Assemble a launch classpath with the trusted listener classes first.

    The trusted witness must precede every candidate class so no candidate class
    of the same name can shadow it and capture the TestNG callbacks. This is a
    separate function so the ordering is unit-testable without launching a JVM.
    """
    return ":".join([str(witness_classes)] + list(entries))


def execute_module(candidate_dir: Path, module: str, classes, witness_dir: Path,
                   witness_classes: Path, nonce: str, java: str, argline, xvfbrun, cp_rel):
    """Launch one module's required classes under the trusted witness."""
    module_dir = candidate_dir / module
    entries = module_classpath(module_dir, cp_rel)
    testng = resolve_testng_jar(entries)
    full_cp = assemble_classpath(witness_classes, entries)
    out_dir = witness_dir / ("testng-" + module)
    shutil.rmtree(out_dir, ignore_errors=True)
    cmd = [java] + list(argline) + [
        "-cp", full_cp,
        "-Dforge.d17.module=" + module,
        "-Dforge.d17.nonce=" + nonce,
        "-Dforge.d17.witness.dir=" + str(witness_dir),
        "org.testng.TestNG", "-d", str(out_dir),
        "-listener", WITNESS_CLASS,
        "-testclass", ",".join(classes),
    ]
    code, stdout, stderr = run(xvfbrun + cmd, module_dir, timeout=7200)
    digest = hashlib.sha256(full_cp.encode("utf-8")).hexdigest()
    totals = _parse_testng_totals(stdout + stderr)
    return {
        "module": module,
        "required_classes": list(classes),
        "launch_exit_code": code,
        "classpath_digest": digest,
        "testng_totals": totals,
        "testng_version_entry": os.path.basename(testng),
        "log_tail": (stdout + stderr)[-4000:],
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--trusted-repo", default=".", help="trusted default-branch checkout")
    parser.add_argument("--candidate-dir", required=True, help="exact candidate working tree")
    parser.add_argument("--evidence-dir", required=True, help="trusted evidence directory")
    parser.add_argument("--modules", required=True, help="space separated required modules")
    parser.add_argument("--java", default="java")
    parser.add_argument("--xvfb-run", default="", help="prefix such as 'xvfb-run -a'")
    parser.add_argument("--cp-rel", default="target/d17-cp.txt")
    parser.add_argument("--candidate-sha", required=True,
                        help="exact locked candidate SHA this execution is bound to")
    parser.add_argument("--candidate-tree", required=True,
                        help="exact locked candidate TREE this execution is bound to")
    parser.add_argument("--comparison-base", required=True,
                        help="exact trusted comparison base SHA to enumerate the required surface from")
    parser.add_argument("--skip-required-surface", action="store_true",
                        help="reuse an existing required-surface ledger")
    args = parser.parse_args(argv)

    trusted_repo = Path(args.trusted_repo).resolve()
    candidate_dir = Path(args.candidate_dir).resolve()
    evidence_dir = Path(args.evidence_dir).resolve()
    modules = args.modules.split()
    argline = list(DEFAULT_TRUSTED_ARGLINE)
    xvfbrun = args.xvfb_run.split() if args.xvfb_run else []

    # A stale evidence directory must never be mistaken for this run's evidence.
    shutil.rmtree(evidence_dir, ignore_errors=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    witness_dir = evidence_dir / "witness"
    witness_dir.mkdir(parents=True, exist_ok=True)
    nonce = secrets.token_hex(16)
    log("evidence dir {} nonce {}".format(evidence_dir, nonce))

    try:
        # 1. Trusted denominator from the trusted comparison base.
        surface_path = evidence_dir / "required-surface.json"
        if args.skip_required_surface and surface_path.is_file():
            surface = json.loads(surface_path.read_text())
            log("reusing required surface {}".format(surface_path))
        else:
            surface = build_required_surface(
                trusted_repo, args.comparison_base,
                modules, args.java, argline, xvfbrun, args.cp_rel,
            )
            surface_path.write_text(json.dumps(surface, indent=2, sort_keys=True) + "\n")
        log("required surface total={}".format(
            sum(m["required_total"] for m in surface["modules"].values())))

        # 2. Compile the candidate. Candidate-influenced; can only reduce coverage.
        compile_and_resolve(candidate_dir, modules, args.cp_rel, "candidate")

        # 3. Compile the trusted witness against the candidate-resolved TestNG API.
        probe_entries = module_classpath(candidate_dir / modules[0], args.cp_rel)
        testng_jar = resolve_testng_jar(probe_entries)
        witness_classes = compile_witness(
            trusted_repo, evidence_dir / "witness-classes", args.java, testng_jar
        )
        log("trusted witness compiled against {}".format(os.path.basename(testng_jar)))

        # 4. Launch each module's required classes under the trusted witness.
        results = {}
        for module in modules:
            required = surface["modules"][module]["classes"]
            if not required:
                results[module] = {"module": module, "required_classes": [],
                                   "launch_exit_code": 0, "skipped_no_required_tests": True}
                continue
            log("executing {} ({} required classes)".format(module, len(required)))
            results[module] = execute_module(
                candidate_dir, module, required, witness_dir, witness_classes,
                nonce, args.java, argline, xvfbrun, args.cp_rel,
            )
            log("  {} launch exit={} testng totals={}".format(
                module, results[module]["launch_exit_code"], results[module]["testng_totals"]))

        manifest = {
            "schema": EXECUTION_MANIFEST_SCHEMA,
            "nonce": nonce,
            "candidate_sha": args.candidate_sha,
            "candidate_tree": args.candidate_tree,
            "comparison_base_sha": args.comparison_base,
            "candidate_dir_name": candidate_dir.name,
            "trusted_argline": argline,
            "witness_class": WITNESS_CLASS,
            "witness_source": WITNESS_SOURCE,
            "candidate_artifacts_used_as_evidence": False,
            "modules": results,
        }
        (evidence_dir / "execution-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n"
        )
    except ExecutionError as exc:
        sys.stderr.write("d17-exec: {}\n".format(exc))
        return 1
    return 0



if __name__ == "__main__":  # pragma: no cover - CLI entry point
    raise SystemExit(main())