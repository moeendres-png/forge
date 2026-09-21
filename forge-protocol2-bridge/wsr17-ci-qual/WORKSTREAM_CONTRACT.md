# Workstream Contract — R17 Forge CI Qualification (2026-09-20)

## Objective

Reproducible qualification of the simulator implementation through CI:
reproduce the WS236 Java-21 Test-build failure locally, repair legitimate
failures, and qualify the final configuration. No generic CI expansion;
no assertion weakening; no omitted tests; no manufactured green.

## Source Lock

- Base: R16 tip `7af7b322bccd4e0a5ee3adca37974462151b69eb` (6P, S3 FULL).
- This branch: `wsr17/forge-ci-qualification-20260920`.
- This worktree: `/home/moeen/code/ws-r17-forge-ci-qualification-20260920`.
- Reference (read-only): CI history via gh (WS236 run 35039212911:
  Java 17 success, Java 21 failure; logs expired).
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- Full root `mvn clean test` reproduction (CI-equivalent: Java 21)
  on this worktree; triage of every failure.
- Repair of legitimate product/test defects found (minimal, with
  fail-before + root cause + requal).
- CI configuration repair ONLY where the file itself is wrong
  (proven by local reproduction); workflows stay otherwise untouched.
- Evidence (failure analysis, repair proof, final config status).

## Out of Scope

- New behavior tests; S-scope re-claims; FULL107; promotion; Lab/RSP.
- Pushing branches or triggering CI runs (no remote actions at all).

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge worktrees read-only, never written (verified clean before/after).

## Dependencies

- R16 tip (all suites green scoped); CI history (provenance).

## Hard Gates

- Rules Authority preserved; no second Rules Engine.
- Never weaken an assertion, omit a failing test, or manufacture green
  to satisfy CI. A legitimately environmental failure is documented
  with proof (fails identically without workstream changes), never
  silenced.
- No production change without fail-before + full affected requal.

## Forbidden Shortcuts

Per AGENTS.md §2 plus: no `-Dtest` exclusions committed; no surefire
skip flags; no profile surgery to dodge modules.

## Evidence Requirements

Failure log excerpt + classification (product/test/environmental) →
repair diff (if any) → full-build result → config verdict.

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff.

## Stop Conditions

Stop only when: full CI-equivalent build is green with sealed evidence,
or remaining failures are proven environmental/pre-existing with
rerun proof, or a genuine terminal blocker appears. Remediable failures
are diagnostic.
