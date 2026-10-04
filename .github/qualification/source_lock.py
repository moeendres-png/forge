#!/usr/bin/env python3
"""Resolve and prove the trusted/candidate source identities for Forge qualification.

Trust boundary
--------------
This script executes from the trusted default-branch checkout only.  Candidate
Git objects are read strictly as data (``rev-parse`` / ``merge-base``).  No
candidate file is executed, imported, sourced, copied into trusted state, or
consulted for a verdict.  A candidate that can author its own qualification
result does not need to pass any test, so the candidate tree is never an input
to the definition of qualification.

Three identities are kept separate and are never collapsed:

``workflow_authority``
    The commit on the trusted default branch whose tree supplies the executed
    workflow definition and this verifier.  This is the only authority.

``candidate``
    The exact full 40-hex commit that is qualified.  Its TREE is bound here and
    must match the TREE observed by the qualification job.

``comparison_base``
    The trusted/candidate merge base, inspected as Git data only.  No merge is
    computed and no mergeability state is read anywhere in this module.  Merge
    base identity is not mergeability, and synthetic-merge evidence carries no
    qualification credit.

Fail-closed contract
--------------------
Any identity that cannot be proven is a hard failure.  There is no partial,
inferred, abbreviated or substituted identity.  An unproven identity is never
treated as a match, and there is no fallback to another SHA, to a synthetic
merge, or to PR mergeability.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

SCHEMA = "forge.candidate-qualification.source-lock/1"

#: The trusted default branch of this repository.
TRUSTED_DEFAULT_BRANCH = "master"

#: Workflow file that defines the qualification and therefore its own authority.
QUALIFICATION_WORKFLOW = ".github/workflows/forge-candidate-qualification.yml"

#: Trusted-path prefix of every file that participates in defining qualification.
TRUSTED_QUALIFICATION_PREFIX = ".github/qualification/"

#: Verifier that must exist at the trusted authority, or the lock is not proven.
TRUSTED_VERIFIER = TRUSTED_QUALIFICATION_PREFIX + "qualify.py"

HEX40 = re.compile(r"^[0-9a-f]{40}$")

FETCH_REF = re.compile(r"^refs/(pull/[1-9][0-9]*/head|heads/[A-Za-z0-9._/-]+)$")

#: Required keys per identity block in a lock document.  ``run_identity`` is the
#: executing commit and therefore carries a branch rather than a tree ref.
_REQUIRED_KEYS = {
    "workflow_authority": ("sha", "tree", "ref", "trusted_default_branch_tip", "workflow_path"),
    "candidate": ("sha", "tree", "ref", "fetched_from"),
    "comparison_base": (
        "sha",
        "tree",
        "ref",
        "synthetic_merge_computed",
        "pr_mergeability_consulted",
    ),
    "run_identity": ("sha", "branch"),
}

#: Identities that must additionally carry a bound TREE.
_TREE_BOUND_IDENTITIES = ("workflow_authority", "candidate", "comparison_base")


class SourceLockError(Exception):
    """A source identity could not be proven.  Always fail closed."""


def git(*args: str, cwd: Path) -> str:
    """Run a read-only git command in ``cwd`` and return trimmed stdout."""
    proc = subprocess.run(
        ["git", *args],
        cwd=str(cwd),
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise SourceLockError(
            "git {} failed ({}): {}".format(" ".join(args), proc.returncode, proc.stderr.strip())
        )
    return proc.stdout.strip()


def require_sha(value: object, label: str) -> str:
    """Require a full lowercase 40-hex object name.

    Abbreviated, uppercase, ref-shaped and empty values are rejected so an
    identity can never be resolved loosely.
    """
    if not isinstance(value, str) or not HEX40.match(value):
        raise SourceLockError("{} is not a full 40-hex sha: {!r}".format(label, value))
    return value


def require_tree(value: object, label: str) -> str:
    """Require a full 40-hex tree object name."""
    if not isinstance(value, str) or not HEX40.match(value):
        raise SourceLockError("{} is not a full 40-hex tree: {!r}".format(label, value))
    return value


def _identity_block(sha: object, repo: Path, ref: str, label: str) -> dict:
    sha = require_sha(sha, label)
    tree = require_tree(git("rev-parse", "{}^{{tree}}".format(sha), cwd=repo), "{} tree".format(label))
    return {"sha": sha, "tree": tree, "ref": ref}


def resolve_workflow_authority(repo: Path, branch: str = TRUSTED_DEFAULT_BRANCH) -> dict:
    """Resolve the trusted default-branch commit that owns the qualification.

    The authority is read from the local trusted ref in the executing checkout,
    never from the event payload and never from the candidate.  The checkout
    must be on the trusted default branch, so the executed definition is
    byte-identical to the definition on the default branch.
    """
    if branch != TRUSTED_DEFAULT_BRANCH:
        raise SourceLockError(
            "trusted authority branch must be {!r}, got {!r}".format(TRUSTED_DEFAULT_BRANCH, branch)
        )
    tip = require_sha(
        git("rev-parse", "refs/heads/{}".format(branch), cwd=repo), "trusted default branch tip"
    )
    listing = git("ls-tree", "-r", "--name-only", tip, cwd=repo).splitlines()
    for required in (QUALIFICATION_WORKFLOW, TRUSTED_VERIFIER):
        if required not in listing:
            raise SourceLockError(
                "trusted default branch {} does not carry {}".format(tip, required)
            )
    authority = _identity_block(
        tip, repo, "refs/heads/{}".format(branch), "workflow_authority"
    )
    authority["trusted_default_branch_tip"] = tip
    authority["workflow_path"] = QUALIFICATION_WORKFLOW
    return authority


def resolve_run_identity(repo: Path, declared_run_sha: object = None) -> dict:
    """Bind the commit that the qualification run itself executes from.

    The run must be on the trusted default branch tip.  A detached HEAD, a
    different branch, or a declared run SHA that disagrees with the executing
    HEAD is a hard failure: qualification must never execute from candidate or
    untrusted code.
    """
    head = require_sha(git("rev-parse", "HEAD", cwd=repo), "run HEAD")
    branch = git("rev-parse", "--abbrev-ref", "HEAD", cwd=repo)
    if branch != TRUSTED_DEFAULT_BRANCH:
        raise SourceLockError(
            "qualification executed from {!r}; trusted default branch {!r} required".format(
                branch, TRUSTED_DEFAULT_BRANCH
            )
        )
    tip = git("rev-parse", "refs/heads/{}".format(branch), cwd=repo)
    if head != tip:
        raise SourceLockError(
            "qualification run HEAD {} is not the trusted default branch tip {}".format(head, tip)
        )
    if declared_run_sha is not None and require_sha(declared_run_sha, "declared run sha") != head:
        raise SourceLockError(
            "declared run sha {} does not match executing trusted HEAD {}".format(
                require_sha(declared_run_sha, "declared run sha"), head
            )
        )
    return {"sha": head, "branch": branch}


def resolve_event_base(repo: Path, event_base_sha: object, authority_sha: str) -> dict:
    """Record the event's base SHA and prove its relation to the authority.

    A `pull_request_target` event's ``github.sha`` is a base-branch commit.  The
    executed authority is the live trusted tip, so the two may differ by benign
    default-branch advancement.  They must not be unrelated: an event base that
    is neither the authority nor an ancestor of it means the run is not anchored
    to the trusted lineage and qualification fails closed.
    """
    if event_base_sha is None:
        return {"event_base_sha": None, "relation": "NOT_PROVIDED"}
    base = require_sha(event_base_sha, "event base sha")
    if base == authority_sha:
        relation = "EQUAL"
    else:
        proc = subprocess.run(
            ["git", "merge-base", "--is-ancestor", base, authority_sha],
            cwd=str(repo),
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode == 0:
            relation = "ANCESTOR_OF_AUTHORITY"
        elif proc.returncode == 1:
            raise SourceLockError(
                "event base {} is unrelated to trusted authority {}".format(base, authority_sha)
            )
        else:
            raise SourceLockError(
                "could not prove relation of event base {} to authority {}: {}".format(
                    base, authority_sha, proc.stderr.strip()
                )
            )
    return {"event_base_sha": base, "relation": relation}


def _commit_present(repo: Path, sha: str) -> bool:
    proc = subprocess.run(
        ["git", "cat-file", "-e", "{}^{{commit}}".format(sha)],
        cwd=str(repo),
        capture_output=True,
        text=True,
        check=False,
    )
    return proc.returncode == 0


def resolve_candidate(repo: Path, candidate_sha: object, fetch_ref: object = None) -> dict:
    """Fetch and prove the exact candidate commit, and bind its TREE.

    When ``fetch_ref`` is given (for example ``refs/pull/<n>/head``), the object
    obtained from that ref must equal ``candidate_sha`` exactly.  A mismatch is
    a hard failure.  The resolver never continues with the fetched SHA, never
    substitutes a branch tip, and never accepts a synthetic merge commit.
    """
    candidate_sha = require_sha(candidate_sha, "candidate")

    if fetch_ref:
        if not isinstance(fetch_ref, str) or not FETCH_REF.match(fetch_ref):
            raise SourceLockError("refusing to fetch unqualified ref {!r}".format(fetch_ref))
        git("fetch", "--no-tags", "origin", fetch_ref, cwd=repo)
        fetched = require_sha(git("rev-parse", "FETCH_HEAD", cwd=repo), "fetched candidate head")
        if fetched != candidate_sha:
            raise SourceLockError(
                "candidate identity mismatch: expected {} for {} but the ref resolved to {}".format(
                    candidate_sha, fetch_ref, fetched
                )
            )
    else:
        if not _commit_present(repo, candidate_sha):
            try:
                git("fetch", "--no-tags", "origin", candidate_sha, cwd=repo)
            except SourceLockError as exc:
                raise SourceLockError(
                    "candidate object {} is not present in the trusted repository and could "
                    "not be fetched from origin: {}".format(candidate_sha, exc)
                )

    if not _commit_present(repo, candidate_sha):
        raise SourceLockError(
            "candidate object {} is not present in the trusted repository after fetch".format(
                candidate_sha
            )
        )
    block = _identity_block(candidate_sha, repo, fetch_ref or "sha", "candidate")
    block["fetched_from"] = fetch_ref or "explicit-sha"
    return block


def resolve_comparison_base(
    repo: Path, candidate_sha: str, branch: str = TRUSTED_DEFAULT_BRANCH
) -> dict:
    """Resolve the trusted/candidate merge base as inspected Git data only."""
    base = require_sha(
        git("merge-base", "refs/heads/{}".format(branch), candidate_sha, cwd=repo),
        "comparison_base",
    )
    block = _identity_block(base, repo, "merge-base", "comparison_base")
    block["synthetic_merge_computed"] = False
    block["pr_mergeability_consulted"] = False
    return block


def definition_digest(repo: Path, commit: "str", path: str) -> str:
    """Digest one definition blob at ``commit`` as inspected data.

    ``commit`` may be a full object name or a trusted ref; a ref is resolved
    inside the trusted checkout and must then be a full object name.
    """
    resolved = require_sha(git("rev-parse", "{}^{{commit}}".format(commit), cwd=repo), "commit")
    return require_sha(
        git("rev-parse", "{}:{}".format(resolved, path), cwd=repo), "{} blob".format(path)
    )


def candidate_definition_divergence(repo: Path, candidate_sha: str) -> dict:
    """Compare the candidate's copy of the qualification definition to trusted.

    The candidate's copy is never used to define or alter qualification.  The
    comparison exists so a candidate that edits the definition cannot do so
    invisibly: divergence is recorded as an explicit visible signal.
    """
    trusted_blob = definition_digest(
        repo, "refs/heads/{}".format(TRUSTED_DEFAULT_BRANCH), QUALIFICATION_WORKFLOW
    )
    try:
        candidate_blob = definition_digest(repo, candidate_sha, QUALIFICATION_WORKFLOW)
    except SourceLockError:
        return {
            "workflow_path": QUALIFICATION_WORKFLOW,
            "trusted_blob": trusted_blob,
            "candidate_blob": None,
            "divergent": True,
            "note": "candidate does not carry the qualification workflow definition",
        }
    return {
        "workflow_path": QUALIFICATION_WORKFLOW,
        "trusted_blob": trusted_blob,
        "candidate_blob": candidate_blob,
        "divergent": candidate_blob != trusted_blob,
        "note": "candidate definition is data only and was not used as qualification authority",
    }


def build_lock(
    repo: Path,
    candidate_sha: object,
    fetch_ref: object = None,
    branch: str = TRUSTED_DEFAULT_BRANCH,
    declared_run_sha: object = None,
    event_base_sha: object = None,
) -> dict:
    """Build the source lock document.

    Raises :class:`SourceLockError` on any unproven identity.  There is no
    partially populated lock.
    """
    repo = Path(repo)
    authority = resolve_workflow_authority(repo, branch)
    run = resolve_run_identity(repo, declared_run_sha)
    candidate = resolve_candidate(repo, candidate_sha, fetch_ref)
    base = resolve_comparison_base(repo, candidate["sha"], branch)
    event = resolve_event_base(repo, event_base_sha, authority["sha"])

    lock = {
        "schema": SCHEMA,
        "status": "LOCKED",
        "repository": git("config", "--get", "remote.origin.url", cwd=repo) or "unknown",
        "trusted_default_branch": branch,
        "workflow_authority": authority,
        "candidate": candidate,
        "comparison_base": base,
        "run_identity": run,
        "event_base": event,
        "candidate_definition_divergence": candidate_definition_divergence(repo, candidate["sha"]),
        "candidate_code_executed_as_validator": False,
        "verdict_read_from_candidate": False,
        "synthetic_merge_consulted": False,
        "pr_mergeability_consulted": False,
    }
    return validate_lock(lock)


def validate_lock(lock: object) -> dict:
    """Fail closed unless every required identity in ``lock`` is proven."""
    if not isinstance(lock, dict):
        raise SourceLockError("source lock is not an object")
    if lock.get("schema") != SCHEMA:
        raise SourceLockError("source lock schema is not {!r}".format(SCHEMA))
    if lock.get("status") != "LOCKED":
        raise SourceLockError("source lock is not LOCKED: {!r}".format(lock.get("status")))
    if lock.get("trusted_default_branch") != TRUSTED_DEFAULT_BRANCH:
        raise SourceLockError(
            "source lock trusted default branch is {!r}".format(lock.get("trusted_default_branch"))
        )
    for identity, keys in _REQUIRED_KEYS.items():
        block = lock.get(identity)
        if not isinstance(block, dict):
            raise SourceLockError("source lock identity {!r} is missing".format(identity))
        missing = [key for key in keys if key not in block]
        if missing:
            raise SourceLockError(
                "source lock identity {!r} lacks {}".format(identity, ", ".join(missing))
            )
        require_sha(block.get("sha"), "{} sha".format(identity))
    for identity in _TREE_BOUND_IDENTITIES:
        require_tree(lock[identity].get("tree"), "{} tree".format(identity))

    divergence = lock.get("candidate_definition_divergence")
    if not isinstance(divergence, dict) or "divergent" not in divergence:
        raise SourceLockError("source lock does not record candidate definition divergence")
    require_sha(divergence.get("trusted_blob"), "trusted definition blob")

    for assertion in (
        "candidate_code_executed_as_validator",
        "verdict_read_from_candidate",
        "synthetic_merge_consulted",
        "pr_mergeability_consulted",
    ):
        if lock.get(assertion) is not False:
            raise SourceLockError("source lock does not assert {} = false".format(assertion))
    if lock["comparison_base"]["synthetic_merge_computed"] is not False:
        raise SourceLockError("comparison base claims a synthetic merge was computed")
    if lock["comparison_base"]["pr_mergeability_consulted"] is not False:
        raise SourceLockError("comparison base claims PR mergeability was consulted")

    authority = lock["workflow_authority"]
    run = lock["run_identity"]
    if run["sha"] != authority["sha"]:
        raise SourceLockError("run identity is not the trusted workflow authority")
    if authority["trusted_default_branch_tip"] != authority["sha"]:
        raise SourceLockError("workflow authority is not the trusted default branch tip")
    if lock["candidate"]["sha"] == authority["sha"]:
        raise SourceLockError(
            "candidate and workflow authority are the same commit; qualification would be self-referential"
        )
    if lock["candidate"]["sha"] == lock["comparison_base"]["sha"]:
        raise SourceLockError("comparison base equals the candidate; nothing was qualified")
    return lock


def load_lock(path: Path) -> dict:
    """Read and re-validate a source lock document."""
    path = Path(path)
    if not path.is_file():
        raise SourceLockError("source lock is missing: {}".format(path))
    try:
        lock = json.loads(path.read_text())
    except (ValueError, OSError) as exc:
        raise SourceLockError("source lock is unreadable: {}".format(exc))
    return validate_lock(lock)


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=".", help="trusted repository checkout")
    parser.add_argument("--candidate-sha", required=True, help="exact 40-hex candidate commit")
    parser.add_argument(
        "--fetch-ref",
        default=None,
        help="exact ref to fetch the candidate from, e.g. refs/pull/17/head",
    )
    parser.add_argument("--branch", default=TRUSTED_DEFAULT_BRANCH)
    parser.add_argument(
        "--run-sha",
        default=None,
        help="declared run SHA; must equal the executing trusted HEAD",
    )
    parser.add_argument(
        "--event-base-sha",
        default=None,
        help="event base SHA (github.sha) to prove against the trusted authority",
    )
    parser.add_argument("--output", required=True, help="path of the source lock to write")
    args = parser.parse_args(argv)

    try:
        lock = build_lock(
            Path(args.repo),
            args.candidate_sha,
            fetch_ref=args.fetch_ref,
            branch=args.branch,
            declared_run_sha=args.run_sha,
            event_base_sha=args.event_base_sha,
        )
    except SourceLockError as exc:
        # No partially populated lock is written: absence of evidence must be
        # visible as absence, never as a lock a downstream step could trust.
        sys.stderr.write("source-lock: {}\n".format(exc))
        return 1

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n")
    sys.stdout.write(
        "source-lock: LOCKED candidate={} tree={} authority={}\n".format(
            lock["candidate"]["sha"],
            lock["candidate"]["tree"],
            lock["workflow_authority"]["sha"],
        )
    )
    return 0


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    raise SystemExit(main())