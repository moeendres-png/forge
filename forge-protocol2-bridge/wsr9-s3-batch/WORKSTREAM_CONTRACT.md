# Workstream Contract — R9 Forge S3 Partial Batch (2026-09-19)

## Objective

Close the next batch of S3 PARTIAL branches with dedicated harnesses +
strict actual-card behavior tests, continuing the R6/R8 pattern at larger
scope (3 families in one workstream): Evoke/Fear (Shriekmaw/CARD_18),
retarget (Bolt Bend/CARD_22 + Flare of Duplication/CARD_13), X>=10
(Finale of Revelation/CARD_15). Each family: fail-before probe,
dedicated harness (test-only unless systemic engine defect is proven),
pass-after strict tests, evidence seal.

## Source Lock

- Base: `r6`-line tip `ca655ecda3e9f4a5e128262e51c0fe6e102c54d4`
  (R8 overload seal; contains R6 DRAW_EVENT + WS236-S1 + WS236 + WS234).
- This branch: `wsr9/forge-s3-partial-batch-20260919`.
- This worktree: `/home/moeen/code/ws-r9-forge-s3-partial-batch-20260919`.
- Donor census (read-only reference): `forge-protocol2-bridge/ws234-s3/`
  (29-denominator, 14 SUPPORTED / 15 PARTIAL at WS234; minus R6 lifeloss
  minus R8 overload = 13 remaining).
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- 3 families × (fail-before + harness + ≥4 strict tests + seal):
  R9a Evoke/Fear, R9b retarget, R9c X>=10.
- Retention: WS236 F4 + WS236-S1 + R6 + R8 suites green (no re-claim).
- Full sim + bridge regression on touched modules.
- Evidence per family (verdicts, census delta, seal, handoff).

## Out of Scope

- Remaining PARTIALs (Fuse combined, Kediss redirect, Boseiju
  chapters/transform, mana/scry, planeswalkers, Veyran/Harmonic base
  beyond retention) → explicit successor backlog, never silent.
- Production Rules-Core semantic changes unless a family proves
  ENGINE_DEFECT with fail-before evidence (then minimal systemic fix +
  full requal).
- Lab/RSP/topology, provider selection, Architecture Freeze, FULL107.

## Ownership

Single writer: this session on this branch/worktree only. R6/R8/csn
worktrees read-only, never written.

## Dependencies

- R8 tip + seals (provenance); S3 census/gap map (scoping).
- Offline Maven (verified before claiming green).

## Hard Gates

- Rules Authority: engine owns legality; no second Rules Engine; no
  first/random/default/AI/GUI/silent-skip; unsupported fail closed.
- Actual-card behavior only: no construction-only credit; UNKNOWN stays
  UNKNOWN; disabled tests carry blockers, never PASS.
- No weakening to pass; every repair needs fail-before + root cause.

## Evidence Requirements

Per family: census delta (SUPPORTED/PARTIAL with card IDs), fail-before
output, harness disposition (HARNESS/ENGINE/SCRIPT/FIXTURE_DEFECT),
pass-after runs, regression counts, seal JSON, handoff.

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff (Lab AGENTS §13 adapted for Forge).

## Stop Conditions

Stop only when: all 3 families sealed with green regression, or a genuine
terminal blocker is proven (engine defect unfixable on owned surfaces →
reproducible issue + escalate; authority gate; owner conflict).
Remediable failures are diagnostic — repair and continue.
