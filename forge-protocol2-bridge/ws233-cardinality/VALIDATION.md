# WS233 Validation

## Identity / hygiene (executed, PASS)

- Entry: branch `ws233/forge-variable-player-provider-cardinality-20260915`,
  HEAD `1cbb7cdc3f5`, tree `a5f35229…`, clean worktree. G1 PASS.
- Implementation commit `626da23912` scoped to bridge provider + bridge tests
  + deck5 fixture (no S3/Lab/RSP touch; verified via `git status`/diffstat).

## Baseline (executed pre-fix, DIRECTLY_VERIFIED)

- `BridgeEngineTest#testCreateValidation` PASS at base (2P
  `PLAYER_COUNT_UNSUPPORTED` live; 4P lifecycle OK).
- `WS233CardinalityTest#testNegativeCountsFailClosed` PASS pre-fix;
  `#testTwoPlayerLifecycle` FAILS pre-fix with exactly
  `player_count_unsupported ... got 2` (red for the intended reason).

## Qualification (executed post-fix, DIRECTLY_VERIFIED)

- `WS233CardinalityTest`: 7/7 PASS (negatives + 2/3/4/5P lifecycles + 2P/5P
  seeded record/replay with equal public digests).
- `WS233CardinalityProcessTest`: 4/4 PASS (dedicated OS process per count,
  exit 0, stdout JSON-only, engine identity asserted in-child).
- Full bridge suite: 150/150 PASS (139 base + 11 new), 0 failures/errors/skips.
- Checkstyle: informational only (2885 pre-existing module-wide violations at
  base scope; touched lines add no new category; not an enforced gate).

## Not run (explicitly)

- FULL107 campaign: NOT_RUN. S5: NOT_RUN. Lab recomputation: not owned.
- 3P in-process replay: NOT_RUN (bounds 2P/5P + 4P suite cover the
  count-parametric coordinates; recorded as a gap, not a pass).
- Fresh-process negatives (0P/1P/6P): NOT_RUN over the pipe (rejection
  precedes session creation; in-JVM boundary proof is the operative evidence).

## Verdict

Validation supports S2 PASS within the WS233 contract. No promotion claim
(S3 FAIL inherited).
