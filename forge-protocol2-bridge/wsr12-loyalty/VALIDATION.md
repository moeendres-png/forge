# R12 Validation

## Identity / hygiene

- Branch `wsr12/forge-loyalty-cost-surface-20260920`, base `fafbbf1d`
  (R11 tip, clean at creation; all other Forge worktrees untouched —
  verified clean).
- Changes (owned surfaces only): 2 production files
  (BridgeCostDecisionMaker visits + ExternalPlayerController classifier
  + doc) + Jeska test file (2 enabled, tight drivers, strict asserts) +
  `forge-protocol2-bridge/wsr12-loyalty/` evidence (7 files). No other
  production/rules/script diffs; no donor evidence copied.

## Qualification (DIRECTLY_VERIFIED)

- New/changed: Jeska family 4/4 (loyalty-2, triple 2→6, ultimate X=2
  to p2 + SBA death, SBA-zero).
- Fail-before: R11a-1 blocker (COMPLEX_COST UNSUPPORTED frames) on base.
- Full suites: forge-gui-desktop sim + forge-protocol2-bridge (counts below).
- Checkstyle: 0 violations all built modules (enforced in-build).

## Full-suite validation run

- forge-gui-desktop sim: **445 tests, 0 failures, 0 errors, 6 skipped**
  (skips pre-existing).
- forge-protocol2-bridge: **187/187 PASS, 0 skipped** (185 baseline +
  Jeska 2 newly enabled).
- Checkstyle: **0 violations** in all built reactor modules.
- BUILD SUCCESS (background run 2026-09-20).
- Changed-test retention (in above totals): Jeska family 4/4.

## Explicitly NOT_RUN / UNKNOWN

- Non-from-source counter costs (still COMPLEX_COST/null/rollback).
- FULL107, S1/S2/S4 re-screen (retained), Freeze, Provider, Lab/RSP.

## Verdict

Loyalty surface CLOSED | Jeska activations SUPPORTED |
S3 29/29 SUPPORTED (FULL) | FULL107 NOT_RUN |
ARCHITECTURE_FREEZE NOT_CLAIMED | PRODUCTION_PROVIDER NOT_SELECTED.
