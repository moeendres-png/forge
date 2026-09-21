# Workstream Contract — R10 Forge S3 Partial Batch 2 (2026-09-20)

## Objective

Close the next S3 PARTIAL batch (3 families) with dedicated harnesses +
strict actual-card behavior tests, continuing the R9 pattern: multiplayer
combat redirect (Kediss/CARD_04), divided reuse (Magma Opus/CARD_09),
commander-type scry (Path of Ancestry/CARD_27). Each family: fail-before
probe, dedicated harness (test-only unless systemic engine defect proven),
pass-after strict tests, evidence seal. R11 takes Fuse/Jeska/Boseiju.

## Source Lock

- Base: R9 tip `c48931ab4e00bc47b4d8619db8ddde052538dcd2` (23/29
  SUPPORTED, 6 PARTIAL: 04/08/09/11/27/29).
- This branch: `wsr10/forge-s3-partial-batch2-20260920`.
- This worktree: `/home/moeen/code/ws-r10-forge-s3-partial-batch2-20260920`.
- Census (read-only reference): `forge-protocol2-bridge/ws234-s3/`
  S3_ACTUAL_CARD_CENSUS.json.
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- 3 families × (fail-before + harness + ≥3 strict tests + seal):
  R10a Kediss 3P combat, R10b Magma divided reuse, R10c Path scry.
- Retention: R6/R8/R9 suites green (no re-claim).
- Bridge + sim regression on touched modules; checkstyle enforced.
- Evidence per family (verdicts, census delta, seal, handoff).

## Out of Scope

- CARD_08/11/29 (explicit R11 backlog) + remaining PARTIAL branches.
- Production Rules-Core changes unless a family proves ENGINE_DEFECT
  with fail-before evidence (minimal systemic fix + full requal).
- Lab/RSP/topology, provider selection, Architecture Freeze, FULL107.

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge worktrees read-only, never written (verified clean before/after).

## Dependencies

- R9 tip + seals (provenance); S3 census/gap map (scoping); WS217
  divided seam (Core, in ancestry) for Magma.

## Hard Gates

- Rules Authority: engine owns legality; no second Rules Engine; no
  first/random/default/AI/GUI/silent-skip; unsupported fail closed.
- Actual-card behavior only; UNKNOWN stays UNKNOWN; disabled tests carry
  blockers.
- No weakening to pass; every repair needs fail-before + root cause.

## Evidence Requirements

Per family: census delta, fail-before output, harness disposition
(HARNESS/ENGINE/SCRIPT/FIXTURE/AI_DEFECT), pass-after runs, regression
counts, seal JSON, handoff.

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff.

## Stop Conditions

Stop only when: all 3 families sealed with green regression, or a genuine
terminal blocker is proven (unfixable engine defect → reproducible issue;
authority gate; owner conflict). Remediable failures are diagnostic.
