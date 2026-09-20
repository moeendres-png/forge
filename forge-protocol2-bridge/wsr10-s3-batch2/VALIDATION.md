# R10 Validation

## Identity / hygiene

- Branch `wsr10/forge-s3-partial-batch2-20260920`, base `c48931ab` (R9 tip,
  clean at creation; all other Forge worktrees untouched — verified clean).
- Changes (owned surfaces only): 3 new test files
  (bridge/WsR10KedissBridgeFamilyTest.java,
  bridge/WsR10MagmaBridgeFamilyTest.java,
  gamesimulationtests/wsr10/WsR10PathSimFamilyTest.java) +
  `forge-protocol2-bridge/wsr10-s3-batch2/` evidence (9 files). Zero
  production diffs; zero test-body diffs elsewhere. All probes/scratch
  removed (verified zero refs).

## Qualification (DIRECTLY_VERIFIED)

- New: R10a bridge 4/4, R10b bridge 3/3, R10c sim 4/4 (11 strict tests).
- Fail-before chain: combat-consumed-by-drain (repaired: tight flow);
  tap-2 candidate bound (lean-board redesign); burst arithmetic {2}{R}
  + pool-emptying phases (audit + pool metering isolated each);
  tapAllMana sufficiency; COLOR/mono production silent-drop (localized
  to bridge tap flow, filed); scry-choice framing absent (filed).
- Retention: R6/R8/R9a/R9b/R9c + WS234/WS236 families (see full-suite).
- Full suites: forge-gui-desktop sim + forge-protocol2-bridge (counts below).
- Checkstyle: 0 violations all built modules (enforced in-build).

## Full-suite validation run

- forge-gui-desktop sim: **445 tests, 0 failures, 0 errors, 6 skipped**
  (441 baseline + R10c 4; skips pre-existing, none in R10 files).
- forge-protocol2-bridge: **177/177 PASS, 0 skipped** (170 baseline +
  R10a 4 + R10b 3).
- Checkstyle: **0 violations** in all built reactor modules.
- BUILD SUCCESS (background run 2026-09-20).
- New-test retention (in above totals): R10a 4/4, R10b 3/3, R10c 4/4;
  R6/R8/R9/WS234/WS236 families green.

## Explicitly NOT_RUN / UNKNOWN

- 3 remaining PARTIAL cards (08/11/29) with named harnesses (R11).
- Bridge Path scry seam (BRIDGE_DEFECT ×2 filed with reproduction).
- FULL107, S1/S2/S4 re-screen (retained, not reclaimed),
  Architecture Freeze, Production Provider, Lab/RSP.

## Verdict

R10a/R10b/R10c CLOSED (bounds documented, engine sound where proven) |
S3 6→3 PARTIAL (26/29 SUPPORTED) | FULL107 NOT_RUN |
ARCHITECTURE_FREEZE NOT_CLAIMED | PRODUCTION_PROVIDER NOT_SELECTED.
