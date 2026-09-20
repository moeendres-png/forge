# R13 Validation

## Identity / hygiene

- Branch `wsr13/forge-choicemana-scry-surface-20260920`, base `adab6bb1`
  (R12 tip, clean at creation; all other Forge worktrees untouched —
  verified clean).
- Changes (owned surfaces only): 2 production files
  (ExternalPlayerController: payingMana recording + arrangeForScry +
  docs; no classifier change) + 1 test file
  (WsR13PathBridgeFamilyTest 3/3) + `forge-protocol2-bridge/
  wsr13-choicemana-scry/` evidence (7 files). Probes removed (verified
  zero refs). No other production/rules/script diffs.

## Qualification (DIRECTLY_VERIFIED)

- New: Path bridge family 3/3 (shared scry + 2 negatives).
- Fail-before: synchronized pool/stack/frozen traces, empty payingMana,
  arrangeForScry failReason (all sealed in root cause + R10c notes).
- Full suites: forge-gui-desktop sim + forge-protocol2-bridge (counts below).
- Checkstyle: 0 violations all built modules (enforced in-build).

## Full-suite validation run

- forge-gui-desktop sim: **445 tests, 0 failures, 0 errors, 6 skipped**
  (skips pre-existing).
- forge-protocol2-bridge: **190/190 PASS, 0 skipped** (187 baseline +
  R13 Path 3).
- Checkstyle: **0 violations** in all built reactor modules.
- BUILD SUCCESS, total 13:49 min (foreground run 2026-09-20; an earlier
  background attempt died mid-suite with no output — environmental flake,
  rerun clean).
- Changed-test retention (in above totals): Path family 3/3.

## Explicitly NOT_RUN / UNKNOWN

- Scry-N > 7 equivalent (128-cap; fail-closed by design).
- FULL107, S1/S2/S4 re-screen (retained), Freeze, Provider, Lab/RSP.

## Verdict

Tap-drop repaired | scry surface CLOSED | Path bridge SUPPORTED |
S3 29/29 retained FULL | FULL107 NOT_RUN |
ARCHITECTURE_FREEZE NOT_CLAIMED | PRODUCTION_PROVIDER NOT_SELECTED.
