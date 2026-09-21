# Workstream Contract — R11 Forge S3 Finale (2026-09-20)

## Objective

Close the LAST THREE S3 PARTIAL branches (S3 → 29/29 SUPPORTED): Fuse
combined (Wear//Tear/CARD_11), Jeska planeswalker (CARD_08), Boseiju
saga chapters + transform (CARD_29). Each family: fail-before probe,
dedicated harness (test-only unless systemic engine defect proven),
pass-after strict tests, evidence seal.

## Source Lock

- Base: R10 tip `1e68c5cad66db930f7bc41056585b71e25819988` (26/29
  SUPPORTED, 3 PARTIAL: 08/11/29).
- This branch: `wsr11/forge-s3-finale-20260920`.
- This worktree: `/home/moeen/code/ws-r11-forge-s3-finale-20260920`.
- Census (read-only reference): `forge-protocol2-bridge/ws234-s3/`.
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- 3 families × (fail-before + harness + ≥3 strict tests + seal):
  R11a Jeska (commander-count loyalty, triple, ultimate),
  R11b Fuse (fused Wear//Tear),
  R11c Boseiju (3 chapters + transform, multi-turn).
- Retention: R6/R8/R9/R10 suites green (no re-claim).
- Bridge + sim regression on touched modules; checkstyle enforced.
- Evidence per family (verdicts, census delta to 29/29, seal, handoff).

## Out of Scope

- Production Rules-Core changes unless a family proves ENGINE_DEFECT
  with fail-before evidence (minimal systemic fix + full requal).
- S1/S2/S4 re-screen, FULL107, Lab/RSP, Freeze, Provider.

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge worktrees read-only, never written (verified clean before/after).

## Dependencies

- R10 tip + seals (provenance); S3 census (scoping).

## Hard Gates

- Rules Authority: engine owns legality; no second Rules Engine; no
  first/random/default/AI/GUI/silent-skip; unsupported fail closed.
- Actual-card behavior only; UNKNOWN stays UNKNOWN; disabled tests carry
  blockers.
- No weakening to pass; every repair needs fail-before + root cause.

## Evidence Requirements

Per family: census delta, fail-before output, harness disposition,
pass-after runs, regression counts, seal JSON, handoff. Final S3
denominator statement (29/29 or exact remainder with blockers).

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff.

## Stop Conditions

Stop only when: all 3 families sealed with green regression (S3 FULL),
or a genuine terminal blocker is proven for a family (reproducible
issue + PARTIAL preserved with cause — S3 stays non-full honestly).
Remediable failures are diagnostic.
