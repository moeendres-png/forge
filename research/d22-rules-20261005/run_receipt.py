#!/usr/bin/env python3
"""Run the D22 targeted Forge tests with one code delta applied and write a receipt.

Usage: run_receipt.py NAME PATCH|none JAVA_HOME
Applies PATCH to a clean tree at HEAD, runs the targeted classes under
xvfb-run, records the surefire XML (gzip) and a receipt bound to HEAD/TREE,
the patch hash and the JDK, then reverts the patch. LOCAL_OBSERVED only.
"""
import datetime, gzip, hashlib, json, os, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path

name, java_home = sys.argv[1], sys.argv[3]
patch = None if sys.argv[2] == "none" else Path(sys.argv[2]).resolve()
root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
out = root / "research" / "d22-rules-20261005"
if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root, text=True).strip():
    sys.exit("tracked tree must be clean")
sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=root, text=True).strip()
env = dict(os.environ, JAVA_HOME=java_home, PATH=f"{java_home}/bin:" + os.environ["PATH"], LANG="C.UTF-8")
version = subprocess.run([f"{java_home}/bin/java", "-version"], capture_output=True, text=True, env=env).stderr
version = "\n".join(l for l in version.splitlines() if "JAVA_TOOL_OPTIONS" not in l) + "\n"
command = ["xvfb-run", "-a", "mvn", "-B", "-o", "-pl", "forge-gui-desktop", "-am",
           "-Dtest=ComprehensiveRulesSection104,LichDamageReplacementTest,LichFixtureInitializationTest,D24ExecutionGuardTest",
           "-Dsurefire.failIfNoSpecifiedTests=false", "clean", "test"]
if patch:
    subprocess.run(["git", "apply", str(patch)], cwd=root, check=True)
try:
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    proc = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
    completed = datetime.datetime.now(datetime.timezone.utc).isoformat()
finally:
    if patch:
        subprocess.run(["git", "apply", "-R", str(patch)], cwd=root, check=True)
xml_path = root / "forge-gui-desktop" / "target" / "surefire-reports" / "TEST-TestSuite.xml"
raw = xml_path.read_bytes()
suite = ET.fromstring(raw)
failures = []
for case in suite.iter("testcase"):
    bad = case.find("failure")
    if bad is None:
        bad = case.find("error")
    if bad is not None:
        failures.append({"class": case.get("classname"), "method": case.get("name"),
                         "message": (bad.get("type") or "") + ": " + (bad.get("message") or "")})
receipt = {
    "source_sha": sha, "source_tree": tree,
    "code_delta_sha256": hashlib.sha256(patch.read_bytes()).hexdigest() if patch else None,
    "code_delta_file": patch.name if patch else None,
    "java": Path(java_home).name.split("-")[1], "java_version": version,
    "command": command, "started_at": started, "completed_at": completed,
    "maven_exit": proc.returncode,
    "suite": {k: suite.get(k) for k in ("name", "time", "tests", "errors", "skipped", "failures")},
    "failures": failures,
    "raw_xml_sha256": hashlib.sha256(raw).hexdigest(),
    "qualification_credit": "none",
    "trusted_exact_sha_candidate_qualification": "NOT_CLAIMED",
}
(out / f"{name}.xml.gz").write_bytes(gzip.compress(raw, mtime=0))
(out / f"{name}.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps({"exit": proc.returncode, "suite": receipt["suite"], "failures": failures}, indent=1))
