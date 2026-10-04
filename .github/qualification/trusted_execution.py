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

Trust domain (Coordinator finding at ``17d42d7e``)
-------------------------------------------------
Candidate code never runs as the identity that owns trusted state:

* the required surface is enumerated, and the trusted driver and listener are
  compiled against a pinned, digest-checked TestNG, before any candidate code
  runs; that bytecode is staged root-owned and read-only;
* the candidate build (``mvn test-compile`` and ``dependency:build-classpath``)
  and every candidate test JVM run as the separate sandbox account
  (``sandbox.py``) with an allowlisted environment, and every candidate process
  is reaped after each run;
* the executed test bytecode is compiled here, by trusted code, from a
  ``git archive`` export of the locked candidate commit with annotation
  processing disabled (``-proc:none``); the candidate's ``target/test-classes``
  is never executed, and test resources never contribute ``.class`` files or
  TestNG service registrations;
* the witness ledger is HMAC-chained with a per-module key that reaches the
  trusted driver on stdin only; this orchestrator verifies every line before
  copying the ledger into trusted evidence, so a ledger line the candidate
  writes, alters, reorders or replays is rejected;
* ``sandbox.py verify`` later re-derives every trusted file from Git and checks
  the evidence seals (``INTEGRITY.json``), which ``qualify.py`` requires.

The candidate's POMs, main sources and dependency resolution stay candidate
influenced: they decide which dependency and main classes load, never which
test bytecode runs, whether an invocation counts, or the verdict rule.

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
import hmac
import json
import os
import re
import secrets
import shutil
import stat
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REQUIRED_SURFACE_SCHEMA = "forge.candidate-qualification.required-surface/2"
EXECUTION_MANIFEST_SCHEMA = "forge.candidate-qualification.execution-manifest/2"
WITNESS_SCHEMA = "forge.d17.witness/2"

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

# D24/#528: source tests that TestNG 7 cannot currently discover because their
# PowerMockTestCase ancestry depends on the removed IObjectFactory API.  They are
# source-policy obligations, not part of the executable TestNG dry-run count.
# A class automatically leaves this gap when the trusted comparison base really
# executes it; D17 never turns this list into PASS credit.
D24_FRAMEWORK_BLOCKED_CLASSES = (
    "forge.card.CardDbCardMockTestCase",
    "forge.card.CardDbLazyCardLoadingCardMockTestCase",
    "forge.card.CardDbPerformanceTests",
    "forge.card.CardDbWithNoImageCardDbMockTestCase",
    "forge.card.CardEditionCollectionCardMockTestCase",
    "forge.deck.DeckRecognizerTest",
    "forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection103",
    "forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection104",
)
D24_FRAMEWORK_BLOCKER_BASES = (
    "forge.card.CardMockTestCase",
    "forge.gamesimulationtests.BaseGameSimulationTest",
)

# D20/#501 read-only inventory: explicit class-level disabled source debt is a
# separate evidence class from D24 framework NOT_RUN and from runtime stress
# skips.  It is included only while the class exists in the trusted source tree
# and remains absent from the executable dry-run surface.
EXPLICIT_DISABLED_SOURCE_CLASSES = (
    "forge.BoosterDraft1Test",
    "forge.BoosterDraftTest",
    "forge.GuiDownloadPicturesLQTest",
    "forge.GuiDownloadSetPicturesLQTest",
    "forge.PanelTest",
    "forge.RunTest",
    "forge.deck.generate.Generate2ColorDeckTest",
    "forge.deck.generate.Generate3ColorDeckTest",
    "forge.deck.generate.Generate5ColorDeckTest",
    "forge.gui.ListChooserTest",
    "forge.gui.game.CardDetailPanelTest",
    "forge.model.FModelTest",
)

D22_DISABLED_CLASS = "forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection104"
D22_DISABLED_METHOD = "test_104_3f_if_a_player_would_win_and_lose_simultaneously_he_loses"

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


def build_definition_divergence(repo: Path, comparison_base: str, candidate_sha: str) -> "list[str]":
    """Return candidate changes that can alter Maven/plugin execution authority.

    D17 qualifies production source under the trusted comparison-base build
    definition. A candidate may not change a POM, Maven core-extension/config
    file or wrapper and still receive qualification credit: doing so would let a
    candidate-controlled plugin/build definition manufacture the bytecode under
    test.
    """
    changed = git(repo, "diff", "--name-only", comparison_base, candidate_sha).splitlines()
    return sorted(path for path in changed if (
        path == "pom.xml"
        or path.endswith("/pom.xml")
        or path == "mvnw"
        or path == "mvnw.cmd"
        or path.startswith(".mvn/")
    ))


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


def _dryrun_class_counts(out_dir: Path) -> "dict[str, int]":
    """Per-class executable test counts from a TestNG dry run.

    TestNG's ``testng-results.xml`` from a dry run lists every test method it
    would invoke, grouped by class. That is the trusted per-class denominator.
    Filename-matched classes that contain no runnable tests are therefore
    excluded by trusted observation rather than by heuristic.
    """
    results = out_dir / "testng-results.xml"
    if not results.is_file():
        raise ExecutionError("dry run produced no testng-results.xml in {}".format(out_dir))
    try:
        root = ET.parse(str(results)).getroot()
    except ET.ParseError as exc:
        raise ExecutionError("dry-run results are malformed: {}".format(exc))
    counts: "dict[str, int]" = {}
    for element in root.iter("class"):
        name = element.get("name")
        if not name:
            continue
        for method in element.iter("test-method"):
            if method.get("is-config") == "true":
                continue
            counts[name] = counts.get(name, 0) + 1
    return counts


def dryrun_module(repo: Path, module: str, classes, java: str, argline, xvfbrun, cp_rel):
    """Run TestNG ``-dryrun`` over a byte-verified comparison-base export.

    TestNG's dry run executes no test bodies but resolves the full suite, so its
    totals are exactly the number of invocations the trusted lineage's own tests
    would produce. That is the trusted denominator: it comes from the trusted
    comparison base, so a candidate cannot shrink it.
    """
    module_dir = repo / module
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
    class_counts = _dryrun_class_counts(out_dir)
    executable = sorted(class_counts)
    return {
        "module": module,
        "classes": executable,
        "candidate_classes_considered": len(classes),
        "class_counts": class_counts,
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


def _source_test_classes(repo: Path, base: str, modules) -> set:
    found = set()
    for module in modules:
        listing = git(repo, "ls-tree", "-r", "--name-only", base, "--",
                      "{}/src/test/java".format(module))
        prefix = module + "/src/test/java/"
        for path in listing.splitlines():
            if path.startswith(prefix) and path.endswith(".java"):
                found.add(path[len(prefix):-len(".java")].replace("/", "."))
    return found


def _source_for_class(repo: Path, base: str, modules, class_name: str) -> "str | None":
    suffix = class_name.replace(".", "/") + ".java"
    for module in modules:
        path = "{}/src/test/java/{}".format(module, suffix)
        try:
            return git(repo, "show", "{}:{}".format(base, path))
        except ExecutionError:
            continue
    return None


def _java_code_only(source: str) -> str:
    """Blank comments and string/char literals while preserving code/newlines.

    Source-level TestNG annotation inventory is evidence, so comment examples or
    string literals containing @Test must not create synthetic obligations.
    """
    out = []
    i, state = 0, "code"
    while i < len(source):
        ch = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                out.extend("  "); i += 2; state = "line"; continue
            if ch == "/" and nxt == "*":
                out.extend("  "); i += 2; state = "block"; continue
            if source.startswith('"""', i):
                out.extend("   "); i += 3; state = "textblock"; continue
            if ch == '"':
                out.append(" "); i += 1; state = "string"; continue
            if ch == "'":
                out.append(" "); i += 1; state = "char"; continue
            out.append(ch); i += 1; continue
        if state == "line":
            if ch == "\n":
                out.append("\n"); state = "code"
            else:
                out.append(" ")
            i += 1; continue
        if state == "block":
            if ch == "*" and nxt == "/":
                out.extend("  "); i += 2; state = "code"; continue
            out.append("\n" if ch == "\n" else " "); i += 1; continue
        if state == "textblock":
            if source.startswith('"""', i):
                out.extend("   "); i += 3; state = "code"; continue
            out.append("\n" if ch == "\n" else " "); i += 1; continue
        if state in ("string", "char"):
            quote = '"' if state == "string" else "'"
            if ch == "\\":
                out.append(" ")
                if i + 1 < len(source):
                    out.append("\n" if source[i + 1] == "\n" else " ")
                    i += 2
                else:
                    i += 1
                continue
            if ch == quote:
                out.append(" "); i += 1; state = "code"; continue
            out.append("\n" if ch == "\n" else " "); i += 1
    return "".join(out)


def _testng_annotation_inventory(repo: Path, base: str, modules, classes) -> dict:
    """Transparent source-level obligations for TestNG test source.

    This is not substituted for real execution. It records trusted-source @Test
    annotations so silently undiscovered test classes cannot disappear behind
    the executable dry-run denominator.
    """
    inventory = {}
    for class_name in sorted(classes):
        source = _source_for_class(repo, base, modules, class_name)
        if source is None:
            inventory[class_name] = {"source_present": False}
            continue
        code = _java_code_only(source)
        simple_testng_test = bool(re.search(
            r"\bimport\s+org\.testng\.annotations\.(?:Test|\*)\s*;", code))
        pattern = (
            r"@(?:org\.testng\.annotations\.Test|Test)\b(?:\s*\((.*?)\))?"
            if simple_testng_test else
            r"@org\.testng\.annotations\.Test\b(?:\s*\((.*?)\))?"
        )
        annotations = list(re.finditer(pattern, code, re.S))
        disabled = 0
        for match in annotations:
            args = match.group(1) or ""
            if re.search(r"\benabled\s*=\s*false\b", args):
                disabled += 1
        inventory[class_name] = {
            "source_present": True,
            "test_annotations": len(annotations),
            "explicitly_disabled_annotations": disabled,
            "enabled_source_methods": len(annotations) - disabled,
        }
    return inventory


def _d22_disabled_method_present(repo: Path, base: str, modules) -> bool:
    suffix = D22_DISABLED_CLASS.replace(".", "/") + ".java"
    for module in modules:
        path = "{}/src/test/java/{}".format(module, suffix)
        try:
            source = git(repo, "show", "{}:{}".format(base, path))
        except ExecutionError:
            continue
        code = _java_code_only(source)
        marker = re.compile(
            r"@Test\s*\(\s*enabled\s*=\s*false\s*\).*?\b{}\s*\(".format(
                re.escape(D22_DISABLED_METHOD)), re.S)
        return bool(marker.search(code))
    return False


def build_required_surface(trusted_repo: Path, base: str, modules, java, argline, xvfbrun, cp_rel) -> dict:
    import sandbox

    # The class inventory and the bytecode TestNG dry-runs must have one source
    # authority.  Building in the workflow-authority checkout while enumerating
    # class names from an older merge base would silently mix two policies.
    # ExportIntegrity proves this temporary tree equals the locked comparison
    # base's Git blobs before its trusted Maven/plugins run.
    export_parent = Path(tempfile.mkdtemp(prefix="d17-comparison-base-"))
    comparison_root = export_parent / "repo"
    try:
        sandbox.export_commit(trusted_repo, base, comparison_root)
        log("comparison base: compile and resolve classpaths from verified export")
        compile_and_resolve(comparison_root, modules, cp_rel, "comparison base")
        surface = {
            "schema": REQUIRED_SURFACE_SCHEMA,
            "comparison_base_sha": base,
            "comparison_base_tree": git(trusted_repo, "rev-parse", "{}^{{tree}}".format(base)),
            "trusted_argline": list(argline),
            "denominator_source": "TestNG -dryrun over a byte-verified trusted comparison-base export",
            "denominator_build_origin": "verified_git_export_of_comparison_base",
            "modules": {},
        }
        for module in modules:
            log("required surface: dry-run {}".format(module))
            classes = required_classes_for_module(trusted_repo, module, base)
            surface["modules"][module] = dryrun_module(
                comparison_root, module, classes, java, argline, xvfbrun, cp_rel
            )
            log("  required_total={} required_skipped={} required_classes={}".format(
                surface["modules"][module]["required_total"],
                surface["modules"][module]["required_skipped"],
                len(surface["modules"][module]["classes"]),
            ))
    finally:
        shutil.rmtree(export_parent, ignore_errors=True)

    executable = {
        name for entry in surface["modules"].values() for name in entry.get("classes", [])
    }
    source_classes = _source_test_classes(trusted_repo, base, modules)
    framework_not_run = sorted(
        name for name in D24_FRAMEWORK_BLOCKED_CLASSES
        if name in source_classes and name not in executable
    )
    explicit_disabled = sorted(
        name for name in EXPLICIT_DISABLED_SOURCE_CLASSES
        if name in source_classes and name not in executable
    )
    d22_disabled = []
    if _d22_disabled_method_present(trusted_repo, base, modules):
        d22_disabled.append(D22_DISABLED_CLASS + "#" + D22_DISABLED_METHOD)
    all_source_inventory = _testng_annotation_inventory(
        trusted_repo, base, modules, source_classes)
    source_test_obligations_not_executed = sorted(
        name for name, item in all_source_inventory.items()
        if isinstance(item, dict)
        and int(item.get("test_annotations", 0)) > 0
        and name not in executable
    )
    d24_inventory = {
        name: all_source_inventory.get(name, {"source_present": False})
        for name in (
            framework_not_run + [
                item for item in D24_FRAMEWORK_BLOCKER_BASES if item in source_classes
            ]
        )
    }
    surface["coverage_gaps"] = {
        "d24_framework_not_run_classes": framework_not_run,
        "d24_framework_blocker_bases": [
            name for name in D24_FRAMEWORK_BLOCKER_BASES if name in source_classes
        ],
        "source_test_obligation_classes_not_executed": source_test_obligations_not_executed,
        "source_test_annotation_inventory": {
            name: all_source_inventory[name]
            for name in source_test_obligations_not_executed
        },
        "d24_framework_not_run_source_inventory": d24_inventory,
        "d24_enabled_source_methods_not_run": sum(
            int(item.get("enabled_source_methods", 0))
            for item in d24_inventory.values() if isinstance(item, dict)
        ),
        "explicitly_disabled_source_classes": explicit_disabled,
        "d22_disabled_rules_tests": d22_disabled,
        "classification": "NOT_RUN_OR_DISABLED_NOT_PASS",
    }
    surface["whole_reactor_coverage_complete"] = not (
        source_test_obligations_not_executed
        or framework_not_run or explicit_disabled or d22_disabled
    )
    return surface


# --------------------------------------------------------------------------- #
# Candidate compilation and trusted execution
# --------------------------------------------------------------------------- #

#: The TestNG the trusted driver and listener are compiled against and launched
#: with, pinned by file digest. Resolved by a trusted step before any candidate
#: code runs; a candidate's own TestNG on the classpath comes later and never
#: loads first.
TRUSTED_TESTNG_PINS = {
    "testng-7.10.2.jar": "225fd56447f2e5e439db3b483a79cd9f294fad9f357f8352b12ee6a3411ebb15",
    "jcommander-1.82.jar": "deeac157c8de6822878d85d0c7bc8467a19cc8484d37788f7804f039dde280b1",
    "jquery-3.7.1.jar": "262016dd3a559df87aefbe392804e9bf620787c9204c0ab8522d4c231ea65097",
    "slf4j-api-1.7.36.jar": "d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0",
}
DRIVER_CLASS = "forge.d17.witness.TrustedTestNGDriver"
LEDGER_AUTHENTICATED = "HMAC_CHAIN_VERIFIED"
CONTAINMENT_ENFORCED = "SECURITY_MANAGER_ENFORCED"
#: Forge's own reactor artifacts, as installed in a Maven repository. A sibling
#: resolved from an installed jar is not the candidate's code; the candidate's
#: reactor output replaces it.
STALE_SIBLING_MARKERS = ("/.m2/repository/forge/",)
_MAC_SUFFIX = re.compile(r',"mac":"([0-9a-f]{64})"\}$')


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_trusted_testng(jars) -> "list[Path]":
    """Exactly the pinned TestNG closure, each file matching its pinned digest."""
    found = {}
    for jar in jars:
        path = Path(jar)
        if path.name not in TRUSTED_TESTNG_PINS:
            raise ExecutionError("unpinned trusted TestNG input {}".format(path.name))
        if sha256_file(path) != TRUSTED_TESTNG_PINS[path.name]:
            raise ExecutionError("trusted TestNG input {} does not match its pinned digest".format(path.name))
        found[path.name] = path
    missing = sorted(set(TRUSTED_TESTNG_PINS) - set(found))
    if missing:
        raise ExecutionError("trusted TestNG closure incomplete: {}".format(",".join(missing)))
    return [found[name] for name in sorted(found)]


def javac_of(java: str) -> str:
    if java.endswith("/bin/java"):
        return java[: -len("java")] + "javac"
    home = os.environ.get("JAVA_HOME", "")
    candidate = Path(home) / "bin" / "javac" if home else None
    if candidate and candidate.is_file():
        return str(candidate)
    found = shutil.which("javac", path="/usr/sbin:/usr/bin:/sbin:/bin")
    if not found:
        raise ExecutionError("javac not found in JAVA_HOME or system directories")
    return found


def compile_witness(trusted_repo: Path, classes_dir: Path, java: str, testng_jar: str):
    """Compile the trusted driver and listener from trusted source against trusted TestNG."""
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    classes_dir.mkdir(parents=True)
    # The witness source lives beside this trusted script, not at the repo root.
    witness_root = Path(__file__).resolve().parent / "witness"
    sources = sorted(str(p) for p in witness_root.rglob("*.java"))
    if not sources:
        raise ExecutionError("trusted witness source is missing")
    code, _, err = run([javac_of(java), "-proc:none", "-nowarn", "-cp", testng_jar, "-d", str(classes_dir)]
                       + sources, trusted_repo, timeout=900)
    if code != 0:
        raise ExecutionError("trusted witness compilation failed: {}".format(err[-800:]))
    return classes_dir


def assemble_classpath(witness_classes, entries) -> str:
    """Assemble a launch classpath with the trusted driver and listener first.

    The trusted witness must precede every candidate class so no candidate class
    of the same name can shadow it and capture the TestNG callbacks. This is a
    separate function so the ordering is unit-testable without launching a JVM.
    """
    return ":".join([str(witness_classes)] + list(entries))


def class_file_digests(root: Path) -> dict:
    if not root.is_dir():
        return {}
    return {p.relative_to(root).as_posix(): sha256_file(p) for p in root.rglob("*.class") if p.is_file()}


def trusted_compile_tests(export_root: Path, module: str, classpath, out: Path, java: str) -> dict:
    """Compile a module's test sources from the exact-SHA Git export, in trusted code.

    ``-proc:none``: no annotation processor, and so no candidate or dependency
    code, runs during compilation. Test resources are copied afterwards without
    any ``.class`` file or TestNG service registration, and every compiled class
    must be byte-identical after the copy.
    """
    module_dir = export_root / module
    root = module_dir / "src" / "test" / "java"
    sources = sorted(str(p) for p in root.rglob("*.java") if p.is_file() and not p.is_symlink()) if root.is_dir() else []
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    record = {"module": module, "sources": len(sources), "exit_code": None, "stderr_tail": "",
              "dropped_test_resources": []}
    if not sources:
        record["exit_code"] = 0
        return record
    argfile = out.parent / "{}.sources".format(out.name)
    argfile.write_text("\n".join('"{}"'.format(s.replace("\\", "\\\\")) for s in sources) + "\n")
    code, _, err = run([javac_of(java), "-proc:none", "-nowarn", "-encoding", "UTF-8", "-d", str(out),
                        "-cp", ":".join(classpath), "@{}".format(argfile)], module_dir, timeout=3600)
    record["exit_code"] = code
    record["stderr_tail"] = err.strip()[-2000:]
    compiled = class_file_digests(out)
    resources = module_dir / "src" / "test" / "resources"
    if resources.is_dir():
        def ignore(directory, names, base=resources):
            dropped = []
            for name in names:
                path = Path(directory) / name
                rel = path.relative_to(base).as_posix()
                if path.is_symlink() or name.endswith(".class") or rel.startswith("META-INF/services/org.testng"):
                    dropped.append(name)
                    record["dropped_test_resources"].append(rel)
            return dropped
        shutil.copytree(resources, out, dirs_exist_ok=True, symlinks=False, ignore=ignore)
    if class_file_digests(out) != compiled:
        raise ExecutionError("test resources altered trusted-compiled bytecode in {}".format(module))
    return record


def compiled_class_names(root: Path) -> set:
    return {".".join(p.relative_to(root).with_suffix("").parts) for p in root.rglob("*.class")
            if "$" not in p.name} if root.is_dir() else set()


#: The TestNG API that candidate-authored bytecode may reference: test and
#: configuration annotations, assertions and SkipException. Everything else in
#: org.testng can register a suite-wide listener, hook or object factory, or
#: reach a test result (@Listeners, IHookable, IConfigurable, I*Listener,
#: Reporter, ITestResult, ITestContext, ISuite, TestNG, org.testng.internal,
#: @Factory, @ObjectFactory). One such registration in one class would let it
#: rewrite the outcome of every other class in the suite.
ALLOWED_TESTNG_REFERENCE = re.compile(
    r"^org/testng/(?:annotations/(?:Test|BeforeClass|AfterClass|BeforeMethod|AfterMethod|BeforeTest|"
    r"AfterTest|BeforeSuite|AfterSuite|BeforeGroups|AfterGroups|DataProvider|Parameters|Optional|"
    r"NoInjection|Ignore)|Assert|AssertJUnit|SkipException|asserts/[A-Za-z0-9_]+|"
    r"collections/[A-Za-z0-9_]+)(?:\$[\w$]*)?$"
)
_TESTNG_REFERENCE = re.compile(r"org[/.]testng[/.][A-Za-z0-9_$/.]*[A-Za-z0-9_$]")
_WITNESS_REFERENCE = re.compile(r"forge[/.]d17[/.]witness")
WITNESS_PACKAGE = "forge/d17/witness/"


def class_constant_strings(data: bytes) -> "list[str]":
    """Every CONSTANT_Utf8 of a class file: class, member and descriptor names and string literals."""
    if data[:4] != b"\xca\xfe\xba\xbe" or len(data) < 10:
        raise ValueError("not a class file")
    count = int.from_bytes(data[8:10], "big")
    offset, index, out = 10, 1, []
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = int.from_bytes(data[offset:offset + 2], "big")
            offset += 2
            out.append(data[offset:offset + length].decode("utf-8", "replace"))
            offset += length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        else:
            raise ValueError("unknown constant pool tag {}".format(tag))
        if offset > len(data):
            raise ValueError("truncated constant pool")
        index += 1
    return out


def _testng_reference_allowed(reference: str) -> bool:
    parts = reference.replace(".", "/").split("/")
    # A dotted literal may name a member after the class ("org.testng.Assert.fail").
    return any(ALLOWED_TESTNG_REFERENCE.match("/".join(parts[:end])) for end in range(len(parts), 2, -1))


def bytecode_findings(class_name: str, data: bytes) -> "list[str]":
    """Why one candidate-authored class may not run next to the trusted witness."""
    try:
        strings = class_constant_strings(data)
    except (ValueError, IndexError) as exc:
        return ["unparseable class file: {}".format(exc)]
    found = set()
    if class_name.startswith(WITNESS_PACKAGE):
        found.add("declares a class in the trusted witness package")
    for text in strings:
        if _WITNESS_REFERENCE.search(text):
            found.add("references the trusted witness")
        for reference in _TESTNG_REFERENCE.findall(text):
            if not _testng_reference_allowed(reference):
                found.add("references " + reference.replace(".", "/"))
    return sorted(found)


#: Largest single file admitted onto the frozen launch classpath. A planted
#: (sparse) giant would otherwise exhaust trusted memory or disk.
MAX_ADMITTED_FILE = 512 * 1024 * 1024
_OVERSIZED = object()


def _regular_file_bytes(path: Path):
    """The bytes of a regular file, never following a final symlink or opening a FIFO or device."""
    try:
        fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        return None
    with os.fdopen(fd, "rb") as handle:
        info = os.fstat(handle.fileno())
        if not stat.S_ISREG(info.st_mode):
            return None
        if info.st_size > MAX_ADMITTED_FILE:
            return _OVERSIZED
        return handle.read(MAX_ADMITTED_FILE + 1)


def _scan_class_tree(label: str, root: Path, result: dict, freeze_to: "Path | None" = None) -> None:
    """Scan every class under ``root``; with ``freeze_to``, copy the regular files read.

    The copy is made from the same bytes the scan judged, so what later runs is
    exactly what was admitted. Symlinks, FIFOs and devices are never followed or
    copied.
    """
    for directory, dirnames, filenames in os.walk(str(root), followlinks=False):
        for name in list(dirnames):
            if os.path.islink(os.path.join(directory, name)):
                dirnames.remove(name)
                result["findings"].append({"entry": label, "class": os.path.relpath(os.path.join(directory, name), root),
                                           "problems": ["symlinked directory on the launch classpath"]})
        for name in filenames:
            path = Path(directory) / name
            rel = path.relative_to(root).as_posix()
            if not name.endswith(".class") and freeze_to is None:
                continue
            data = _regular_file_bytes(path)
            if data is _OVERSIZED:
                result["findings"].append({"entry": label, "class": rel,
                                           "problems": ["file larger than {} bytes".format(MAX_ADMITTED_FILE)]})
                continue
            if name.endswith(".class"):
                if data is None:
                    result["findings"].append({"entry": label, "class": rel, "problems": ["not a regular class file"]})
                    continue
                result["scanned_classes"] += 1
                problems = bytecode_findings(rel[: -len(".class")], data)
                if problems:
                    result["findings"].append({"entry": label, "class": rel, "problems": problems})
            if freeze_to is not None and data is not None:
                target = freeze_to / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)


def _scan_jar_bytes(label: str, data: bytes, result: dict) -> None:
    import io
    import zipfile
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            for name in jar.namelist():
                if name.endswith(".class"):
                    result["scanned_classes"] += 1
                    problems = bytecode_findings(name[: -len(".class")], jar.read(name))
                    if problems:
                        result["findings"].append({"entry": label, "class": name, "problems": problems})
    except (zipfile.BadZipFile, OSError, RuntimeError) as exc:
        result["findings"].append({"entry": label, "class": None, "problems": ["unreadable jar: {}".format(exc)]})


def scan_launch_classpath(tests_dir: Path, entries, candidate_root: Path, candidate_m2: Path,
                          trusted_m2: "Path | None", freeze: "tuple[Path, Path, dict] | None" = None) -> dict:
    """Admit the launch classpath before any candidate test code runs next to the witness.

    Candidate-authored bytecode (the trusted-compiled test classes and every
    directory or jar the candidate build produced) may reference only the
    allowed TestNG API and nothing in the witness package. A dependency jar is
    admitted only byte-identical to the trusted runner's Maven repository: the
    candidate account owns its own copy and can rewrite any jar in it.

    With ``freeze=(staging, launch_root, cache)`` every admitted entry is copied,
    from the bytes judged, into ``staging`` (later staged root-owned and
    read-only as ``launch_root``), and ``result["launch_entries"]`` lists those
    copies. Launches use only that list: the candidate's own classpath file,
    output directories and Maven repository stay writable by code running in an
    earlier launch, so nothing is read from them again. ``cache`` shares copies
    of the same source between modules.
    """
    result = {"scanned_classes": 0, "findings": [], "tampered_jars": [], "unverified_jars": [],
              "trusted_maven_repository": str(trusted_m2) if trusted_m2 else None,
              "launch_entries": [], "candidate_code_entries": [], "trusted_dependency_entries": []}
    _scan_class_tree("trusted-compiled tests", tests_dir, result)
    candidate_root = Path(os.path.realpath(candidate_root))
    candidate_m2 = Path(os.path.realpath(candidate_m2))
    staging, launch_root, cache = freeze if freeze else (None, None, {})

    def remember(key: str, rel: str, before: dict, kind: str) -> None:
        cache[key] = {
            "rel": rel,
            "kind": kind,
            "findings": result["findings"][len(before["findings"]):],
            "scanned": result["scanned_classes"] - before["scanned"],
        }
        if launch_root is not None:
            frozen = str(launch_root / rel)
            result["launch_entries"].append(frozen)
            result[kind].append(frozen)

    for entry in entries:
        real = Path(os.path.realpath(entry))
        key = str(real)
        if key in cache:
            hit = cache[key]
            result["findings"].extend(hit["findings"])
            result["scanned_classes"] += hit["scanned"]
            if launch_root is not None:
                frozen = str(launch_root / hit["rel"])
                result["launch_entries"].append(frozen)
                result[hit["kind"]].append(frozen)
            continue
        before = {"findings": list(result["findings"]), "scanned": result["scanned_classes"]}
        if real.is_dir():
            if not real.is_relative_to(candidate_root):
                # The candidate wrote the classpath file: a directory outside its
                # own tree is neither its build output nor admitted.
                result["unverified_jars"].append(key)
                continue
            rel = "cp/{}".format(len(cache))
            _scan_class_tree(entry, real, result, freeze_to=(staging / rel) if staging else None)
            if staging is not None:
                (staging / rel).mkdir(parents=True, exist_ok=True)
            remember(key, rel, before, "candidate_code_entries")
            continue
        if not real.exists():
            continue  # frozen launches list only what exists now
        if trusted_m2 and real.is_relative_to(trusted_m2):
            rel_path = real.relative_to(trusted_m2)
            data = _regular_file_bytes(real)
            if not isinstance(data, bytes):
                result["unverified_jars"].append(str(rel_path))
            else:
                rel = "deps/" + rel_path.as_posix()
                if staging is not None:
                    (staging / rel).parent.mkdir(parents=True, exist_ok=True)
                    (staging / rel).write_bytes(data)
                remember(key, rel, before, "trusted_dependency_entries")
            continue
        if real.is_relative_to(candidate_m2):
            rel_path = real.relative_to(candidate_m2)
            trusted = (trusted_m2 / rel_path) if trusted_m2 else None
            mine = _regular_file_bytes(real)
            theirs = _regular_file_bytes(trusted) if trusted else None
            if not isinstance(mine, bytes) or not isinstance(theirs, bytes):
                result["unverified_jars"].append(str(rel_path))
            elif hashlib.sha256(mine).digest() != hashlib.sha256(theirs).digest():
                result["tampered_jars"].append(str(rel_path))
            else:
                rel = "deps/" + rel_path.as_posix()
                if staging is not None:
                    (staging / rel).parent.mkdir(parents=True, exist_ok=True)
                    (staging / rel).write_bytes(theirs)
                remember(key, rel, before, "trusted_dependency_entries")
            continue
        if real.is_relative_to(candidate_root) and real.suffix == ".jar":
            data = _regular_file_bytes(real)
            if not isinstance(data, bytes):
                result["findings"].append({"entry": entry, "class": None, "problems": ["not a regular jar of admissible size"]})
                continue
            _scan_jar_bytes(entry, data, result)
            rel = "cp/{}.jar".format(len(cache))
            if staging is not None:
                (staging / rel).parent.mkdir(parents=True, exist_ok=True)
                (staging / rel).write_bytes(data)
            remember(key, rel, before, "candidate_code_entries")
            continue
        result["unverified_jars"].append(key)
    return result


def sanitize_classpath(entries, candidate_root: Path, modules) -> "tuple[list[str], list[str]]":
    """Drop installed Forge sibling jars and the candidate's own test output.

    The module's tests run from trusted-compiled bytecode, so every
    ``target/test-classes`` directory the candidate build produced is dropped.
    """
    kept, dropped = [], []
    for entry in entries:
        normalized = entry.replace("\\", "/")
        if any(marker in normalized for marker in STALE_SIBLING_MARKERS) or normalized.rstrip("/").endswith(
            "/target/test-classes"
        ):
            dropped.append(entry)
        else:
            kept.append(entry)
    reactor = [str(candidate_root / m / "target" / "classes") for m in modules
               if (candidate_root / m / "target" / "classes").is_dir()]
    return reactor + [e for e in kept if e not in reactor], dropped


def candidate_classpath(candidate_root: Path, module: str, cp_rel: str) -> "list[str]":
    path = candidate_root / module / cp_rel
    raw = read_candidate_bytes(path)
    if raw is None:
        raise ExecutionError("candidate classpath missing for module {} (did the module build?)".format(module))
    return [e for e in raw.decode("utf-8", "replace").strip().split(":") if e]


def read_candidate_bytes(path: Path):
    """Read a file the candidate account could have written, refusing symlinks."""
    try:
        fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        return None
    with os.fdopen(fd, "rb") as handle:
        # A FIFO or device planted by the candidate would block or never end.
        if not stat.S_ISREG(os.fstat(handle.fileno()).st_mode):
            return None
        return handle.read(64 * 1024 * 1024)


def verify_ledger(data: bytes, key: bytes, nonce: str) -> "tuple[list[str] | None, str | None]":
    """Authenticate a ledger line by line against the per-module HMAC chain.

    Returns the verified lines, or the reason the ledger is rejected. Any line
    that is not part of the unbroken chain from the header rejects the ledger.
    """
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return None, "ledger_not_utf8"
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines = lines[:-1]
    if not lines:
        return None, "ledger_empty"
    chain = nonce
    for number, line in enumerate(lines, 1):
        match = _MAC_SUFFIX.search(line)
        if not match:
            return None, "ledger_line_{}_unauthenticated".format(number)
        body = line[: match.start()] + "}"
        expected = hmac.new(key, (chain + "\n" + body).encode("utf-8"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, match.group(1)):
            return None, "ledger_line_{}_mac_mismatch".format(number)
        chain = expected
    return lines, None


def execute_module(run_root: Path, module: str, classes, launch_dir: Path, bundle: Path,
                   testng_jars, nonce: str, java: str, argline, xvfbrun,
                   candidate_code_entries, trusted_dependency_entries, dropped,
                   user: str, home: Path, evidence_witness: Path):
    """Launch one module's required classes as the sandbox account, under the trusted driver.

    ``launch_entries`` are the admitted, frozen copies in the trusted bundle. The
    candidate's classpath file and output directories are never read again here:
    code running in an earlier module's launch could have rewritten them.
    """
    import sandbox

    staged_testng = [str(bundle / "testng" / Path(j).name) for j in testng_jars]
    tests = bundle / "tests" / module
    # Only trusted witness/TestNG bytecode is on the JVM system classpath.
    # Candidate production and trusted-base tests enter through restricted,
    # separately identified child loaders inside TrustedTestNGDriver.
    full_cp = assemble_classpath(bundle / "witness", staged_testng)
    ledger = launch_dir / (module + ".witness.jsonl")
    cmd = list(xvfbrun) + [java] + list(argline) + [
        "-Djava.security.manager=allow", "-XX:+DisableAttachMechanism",
        "-cp", full_cp, DRIVER_CLASS,
        "--module", module, "--nonce", nonce, "--ledger", str(ledger),
        "--protected-root", str(launch_dir),
        "--output-dir", str(launch_dir / ("testng-" + module)),
        "--trusted-test-root", str(tests),
    ]
    for entry in candidate_code_entries:
        cmd += ["--candidate-code", entry]
    for entry in trusted_dependency_entries:
        cmd += ["--trusted-dependency", entry]
    for jar in staged_testng:
        cmd += ["--trusted-jar", jar]
    for name in classes:
        cmd += ["--class", name]
    key = secrets.token_bytes(32)
    try:
        proc = sandbox.run_candidate(user, home, run_root / module, cmd, timeout=7200,
                                     stdin_bytes=key.hex().encode("ascii") + b"\n")
        code, stdout, stderr = proc.returncode, proc.stdout, proc.stderr
    except sandbox.SandboxError as exc:
        code, stdout, stderr = 125, "", "sandbox failure: {}".format(exc)
    raw = read_candidate_bytes(ledger)
    entry = {
        "module": module,
        "required_classes": list(classes),
        "launch_exit_code": code,
        "execution_identity": user,
        "classpath_digest": hashlib.sha256(
            (full_cp + "\n" + "\n".join(candidate_code_entries)
             + "\n" + "\n".join(trusted_dependency_entries)).encode("utf-8")
        ).hexdigest(),
        "testng_totals": _parse_testng_totals(stdout + stderr),
        "testng_version_entry": os.path.basename(staged_testng[0]) if staged_testng else None,
        "stale_or_candidate_test_entries_dropped": dropped,
        "log_tail": (stdout + stderr)[-4000:],
    }
    if code == 78:
        entry["hostile_bytecode_containment"] = "UNAVAILABLE"
    if raw is None:
        entry["ledger_authentication"] = "MISSING"
        return entry
    lines, problem = verify_ledger(raw, key, nonce)
    if problem:
        entry["ledger_authentication"] = "REJECTED:" + problem
        return entry
    evidence_witness.mkdir(parents=True, exist_ok=True)
    trusted_copy = evidence_witness / (module + ".witness.jsonl")
    trusted_copy.write_text("\n".join(lines) + "\n", encoding="utf-8")
    entry["ledger_authentication"] = LEDGER_AUTHENTICATED
    entry["ledger_sha256"] = sha256_file(trusted_copy)
    entry["ledger_lines"] = len(lines)
    try:
        summary = json.loads(lines[-1])
        entry["hostile_bytecode_containment"] = summary.get("containment")
        entry["containment_violation"] = summary.get("containment_violation")
    except (ValueError, IndexError):
        entry["hostile_bytecode_containment"] = None
    return entry


def cmd_surface(args) -> int:
    trusted_repo = Path(args.trusted_repo).resolve()
    evidence_dir = Path(args.evidence_dir).resolve()
    modules = args.modules.split()
    argline = list(DEFAULT_TRUSTED_ARGLINE)
    xvfbrun = args.xvfb_run.split() if args.xvfb_run else []
    # A stale evidence directory must never be mistaken for this run's evidence.
    shutil.rmtree(evidence_dir, ignore_errors=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    try:
        surface = build_required_surface(trusted_repo, args.comparison_base, modules, args.java,
                                         argline, xvfbrun, args.cp_rel)
    except ExecutionError as exc:
        sys.stderr.write("d17-exec: {}\n".format(exc))
        return 1
    (evidence_dir / "required-surface.json").write_text(json.dumps(surface, indent=2, sort_keys=True) + "\n")
    log("required surface total={}".format(sum(m["required_total"] for m in surface["modules"].values())))
    return 0


def trusted_test_source_commit(comparison_base: str, candidate_sha: str) -> str:
    """The only source commit from which qualification test bodies may be compiled.

    Candidate-owned tests are data outside the authority boundary.  Keeping this
    as an explicit contract makes source-deletion/body-weakening controls
    testable and mutation-checkable.
    """
    if comparison_base == candidate_sha:
        # Equality can legitimately occur for an honest default-branch candidate;
        # authority still comes from the comparison-base role, never from the
        # candidate role.
        return comparison_base
    return comparison_base


def cmd_execute(args) -> int:
    import sandbox

    trusted_repo = Path(args.trusted_repo).resolve()
    evidence_dir = Path(args.evidence_dir).resolve()
    sandbox_dir = Path(args.sandbox_dir).resolve()
    execution_sandbox = Path(args.execution_sandbox_dir).resolve()
    candidate_root = sandbox_dir / "candidate"
    bundle = Path(args.bundle_dir)
    work = Path(args.work_dir).resolve()
    modules = args.modules.split()
    argline = list(DEFAULT_TRUSTED_ARGLINE)
    xvfbrun = args.xvfb_run.split() if args.xvfb_run else []
    nonce = secrets.token_hex(16)
    manifest = {
        "schema": EXECUTION_MANIFEST_SCHEMA,
        "nonce": nonce,
        "candidate_sha": args.candidate_sha,
        "candidate_tree": args.candidate_tree,
        "comparison_base_sha": args.comparison_base,
        "trusted_argline": argline,
        "witness_class": WITNESS_CLASS,
        "witness_source": WITNESS_SOURCE,
        "driver_class": DRIVER_CLASS,
        "candidate_build_identity": args.sandbox_user,
        "candidate_execution_identity": args.execution_user,
        "build_execution_identity_separated": args.sandbox_user != args.execution_user,
        "test_bytecode_origin": "trusted_compile_of_comparison_base_git_export",
        "trusted_test_source_sha": trusted_test_source_commit(args.comparison_base, args.candidate_sha),
        "candidate_test_sources_used_for_credit": False,
        "hostile_bytecode_containment_required": True,
        "ledger_authentication_scheme": "HMAC-SHA256 chain behind mandatory code-domain containment",
        "candidate_artifacts_used_as_evidence": False,
        "candidate_build_definition_divergence": [],
        "maven_repository_authority": None,
        "modules": {},
    }
    manifest_path = evidence_dir / "execution-manifest.json"
    try:
        surface = json.loads((evidence_dir / "required-surface.json").read_text())
        prepared = json.loads(Path(args.sandbox_prepare).read_text())
        if prepared.get("status") != "READY" or prepared.get("user") != args.sandbox_user:
            raise ExecutionError("sandbox is not READY for {}: {}".format(args.sandbox_user, prepared.get("error")))
        if prepared.get("candidate_sha") != args.candidate_sha:
            raise ExecutionError("sandbox candidate {} is not the locked {}".format(
                prepared.get("candidate_sha"), args.candidate_sha))
        testng_jars = verify_trusted_testng(args.trusted_testng)
        manifest["trusted_testng"] = {p.name: TRUSTED_TESTNG_PINS[p.name] for p in testng_jars}
        home = sandbox.sandbox_home(sandbox_dir)
        if args.sandbox_user == args.execution_user:
            raise ExecutionError("candidate build and hostile-bytecode execution must use different OS identities")
        execution_home = sandbox.prepare_sandbox(args.execution_user, execution_sandbox)
        trusted_maven_repo = (Path(args.trusted_maven_repo).resolve()
                              if args.trusted_maven_repo else None)
        if trusted_maven_repo is None or not trusted_maven_repo.is_dir():
            raise ExecutionError("trusted Maven repository is required")
        divergence = build_definition_divergence(
            trusted_repo, args.comparison_base, args.candidate_sha)
        manifest["candidate_build_definition_divergence"] = divergence
        manifest["maven_repository_authority"] = {
            "path": str(trusted_maven_repo),
            "mode": "trusted_read_only_offline",
        }
        if divergence:
            raise ExecutionError(
                "candidate changes Maven build authority: {}".format(
                    ", ".join(divergence[:20])))

        # 1. Trusted bytecode first: driver and listener, before any candidate code.
        staging = work / "bundle"
        if staging.exists():
            shutil.rmtree(staging)
        (staging / "testng").mkdir(parents=True)
        for jar in testng_jars:
            shutil.copy2(jar, staging / "testng" / jar.name)
        testng_main = str(staging / "testng" / "testng-7.10.2.jar")
        compile_witness(trusted_repo, staging / "witness", args.java, testng_main)

        # 2. Compile the exact candidate production source with the trusted
        # comparison-base Maven definition and a trusted, read-only dependency/
        # plugin repository.  The candidate cannot supply a Maven plugin or
        # mutate a plugin jar before it executes.
        build = sandbox.run_candidate(
            args.sandbox_user, home, candidate_root,
            [args.mvn, "-o", "-B", "-q",
             "-Dmaven.repo.local=" + str(trusted_maven_repo),
             "-Dmaven.compiler.proc=none",
             "-DskipTests", "test-compile", "dependency:build-classpath",
             "-Dmdep.outputFile=" + args.cp_rel, "-DincludeScope=test",
             "-pl", ",".join(modules), "-am"],
            timeout=14400,
        )
        manifest["candidate_build"] = {
            "exit_code": build.returncode,
            "user": args.sandbox_user,
            "maven_repository": str(trusted_maven_repo),
            "offline": True,
            "stderr_tail": build.stderr[-2000:],
        }
        if build.returncode != 0:
            raise ExecutionError("candidate build failed (exit {})".format(build.returncode))

        # 3. Trusted compilation of the comparison-base test policy.  Candidate
        # test source is never executed for qualification credit.
        export = work / "trusted-tests-export"
        sandbox.export_commit(
            trusted_repo, trusted_test_source_commit(args.comparison_base, args.candidate_sha), export
        )
        # Runtime working-directory data comes from an immutable exact-candidate
        # Git export rather than from the candidate-writable build tree.
        run_export = work / "candidate-runtime-export"
        sandbox.export_commit(trusted_repo, args.candidate_sha, run_export)
        records, scans, frozen, dropped = {}, {}, {}, {}
        for module in modules:
            entries, dropped[module] = sanitize_classpath(
                candidate_classpath(candidate_root, module, args.cp_rel), candidate_root, modules)
            out = staging / "tests" / module
            records[module] = trusted_compile_tests(export, module, [str(j) for j in testng_jars] + entries,
                                                    out, args.java)
            records[module]["compiled_required"] = sorted(
                set(surface["modules"][module]["classes"]) & compiled_class_names(out))
            # Before any candidate test code runs next to the witness: admit the
            # classpath and freeze the admitted bytes into the trusted bundle.
            scans[module] = scan_launch_classpath(
                out, entries, candidate_root, home / ".m2" / "repository",
                trusted_maven_repo,
                freeze=(staging / "classpath", bundle / "classpath", frozen))
        manifest["trusted_test_compilation"] = records
        manifest["launch_classpath_admission"] = scans
        sandbox.stage_readonly(staging, bundle)

        # 4. Hostile bytecode runs under a second UID.  A delayed process left
        # by candidate Maven/plugin code cannot ptrace, signal or write the
        # execution JVM/ledger as a different UID.  The per-run temp directory
        # is private to the execution UID as well.
        launch_dir = execution_sandbox / "witness-out"
        exec_tmp = execution_sandbox / "tmp"
        sandbox.run_candidate(
            args.execution_user, execution_home, execution_sandbox,
            ["/bin/sh", "-c",
             'rm -rf "$1" "$2" && mkdir -p "$1" "$2" && chmod 0700 "$1" "$2"',
             "d17", str(launch_dir), str(exec_tmp)])
        manifest["execution_sandbox"] = {
            "user": args.execution_user,
            "root": str(execution_sandbox),
            "private_tmp": str(exec_tmp),
        }
        for module in modules:
            required = surface["modules"][module]["classes"]
            if not required:
                manifest["modules"][module] = {"module": module, "required_classes": [],
                                               "launch_exit_code": 0, "skipped_no_required_tests": True}
                continue
            log("executing {} ({} required classes) as {}".format(module, len(required), args.sandbox_user))
            manifest["modules"][module] = execute_module(
                run_export, module, required, launch_dir, bundle, testng_jars, nonce,
                args.java, argline + ["-Djava.io.tmpdir=" + str(exec_tmp)], xvfbrun,
                scans[module]["candidate_code_entries"],
                scans[module]["trusted_dependency_entries"],
                dropped[module], args.execution_user, execution_home,
                evidence_dir / "witness",
            )
            log("  {} exit={} ledger={} totals={}".format(
                module, manifest["modules"][module]["launch_exit_code"],
                manifest["modules"][module]["ledger_authentication"],
                manifest["modules"][module]["testng_totals"]))
    except (ExecutionError, sandbox.SandboxError, OSError, KeyError, ValueError, MemoryError) as exc:
        manifest["error"] = str(exc)
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        sys.stderr.write("d17-exec: {}\n".format(exc))
        return 1
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    def common(p):
        p.add_argument("--trusted-repo", default=".", help="trusted default-branch checkout")
        p.add_argument("--evidence-dir", required=True, help="trusted evidence directory")
        p.add_argument("--modules", required=True, help="space separated required modules")
        p.add_argument("--java", default="java")
        p.add_argument("--xvfb-run", default="", help="prefix such as '/usr/bin/xvfb-run -a'")
        p.add_argument("--cp-rel", default="target/d17-cp.txt")
        p.add_argument("--comparison-base", required=True,
                       help="exact trusted comparison base SHA to enumerate the required surface from")

    s = sub.add_parser("surface", help="enumerate the required surface from the trusted comparison base")
    common(s)

    e = sub.add_parser("execute", help="build and run the exact candidate as the sandbox account")
    common(e)
    e.add_argument("--candidate-sha", required=True, help="exact locked candidate SHA")
    e.add_argument("--candidate-tree", required=True, help="exact locked candidate TREE")
    e.add_argument("--sandbox-dir", required=True)
    e.add_argument("--sandbox-user", required=True, help="candidate Maven/build OS identity")
    e.add_argument("--execution-user", required=True, help="separate hostile-bytecode JVM OS identity")
    e.add_argument("--execution-sandbox-dir", required=True)
    e.add_argument("--sandbox-prepare", required=True, help="SANDBOX_PREPARE.json written by sandbox.py prepare")
    e.add_argument("--bundle-dir", required=True, help="root-owned read-only directory for trusted bytecode")
    e.add_argument("--work-dir", required=True)
    e.add_argument("--mvn", required=True, help="staged trusted Maven launcher (absolute path)")
    e.add_argument("--trusted-testng", action="append", required=True,
                   help="pinned TestNG closure jar resolved by a trusted step; repeatable")
    e.add_argument("--trusted-maven-repo", default=None,
                   help="the runner's own Maven repository, the trusted copy every dependency jar must equal")

    args = parser.parse_args(argv)
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    return cmd_surface(args) if args.command == "surface" else cmd_execute(args)


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    raise SystemExit(main())