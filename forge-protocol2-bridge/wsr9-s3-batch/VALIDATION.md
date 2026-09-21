# R9 Validation

## Identity / hygiene

- Branch `wsr9/forge-s3-partial-batch-20260919`, base `ca655ecd` (R8 tip,
  clean at creation; R6/R8/csn worktrees untouched — verified clean).
- Changes (owned surfaces only): 3 new test files
  (gamesimulationtests/wsr9/WsR9EvokeFearFamilyTest.java,
  bridge/WsR9RetargetBridgeFamilyTest.java,
  bridge/WsR9FinaleX10BridgeFamilyTest.java) + `forge-protocol2-bridge/
  wsr9-s3-batch/` evidence (8 files). Zero production diffs
  (engine/rules/scripts untouched); zero test-body diffs elsewhere.

## Qualification (DIRECTLY_VERIFIED)

- New: R9a sim 5/5, R9b bridge 5/5, R9c bridge 2/2 (12 strict tests).
- Fail-before chain: sim no-mana harness defect (repaired); AI
  ChangeTargets/chooseNewTargets inability (AI_DEFECT preserved, seam
  moved to bridge); priority-round choreography (repaired); Forest-mana
  for G (repaired); sac-route picking (repaired); COST_SELECTION sac
  answers (repaired); ReduceCost-{R} rules correction (mine, CR 117.7a);
  12-land untap 128-cap bound (honored, Mox redesign).
- Retention: R6 5/5, R8 5/5, WS234/WS236 families green (see full-suite).
- Full suites: forge-gui-desktop sim + forge-protocol2-bridge (bridge
  161-family incl.) — counts from the validation run below.
- Checkstyle: 0 violations all built modules (enforced in-build).

## Full-suite validation run

- forge-gui-desktop sim: **441 tests, 0 failures, 0 errors, 6 skipped**
  (skips are pre-existing `enabled=false`/assumes elsewhere; none in R9
  files), checkstyle enforced.
- forge-protocol2-bridge: **170/170 PASS, 0 skipped** (incl. R9b 5 + R9c 2).
- Checkstyle: **0 violations** in all built reactor modules.
- BUILD SUCCESS, total 14:04 min (background run 2026-09-20).
- New-test retention (in above totals): R9a sim 5/5, R9b bridge 5/5,
  R9c bridge 2/2; R6/R8/WS234/WS236 families green.

## Explicitly NOT_RUN / UNKNOWN

- 6 remaining PARTIAL cards (04/08/09/11/27/29) with named blockers.
- Sim-seam ChangeTargets resolution (AI_DEFECT).
- FULL107, S1/S2/S4 re-screen (retained per WS234/R8, not reclaimed),
  Architecture Freeze, Production Provider, Lab/RSP.

## Verdict

R9a/R9b/R9c CLOSED (TEST_GAP/AI_DEFECT bounds documented, engine sound) |
S3 10→6 PARTIAL (23/29 SUPPORTED) | FULL107 NOT_RUN |
ARCHITECTURE_FREEZE NOT_CLAIMED | PRODUCTION_PROVIDER NOT_SELECTED.
