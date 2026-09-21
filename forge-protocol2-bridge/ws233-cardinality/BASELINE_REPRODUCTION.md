# WS233 Baseline Reproduction (audit base 1cbb7cdc3f5)

## Gate text (CODE_DERIVED)

`BridgeEngine.java:426-429`: `handles.size() != 4` ->
`PLAYER_COUNT_UNSUPPORTED` ("this bridge qualifies exactly four players; got N").

## Runtime baseline (DIRECTLY_VERIFIED, pre-fix)

- `BridgeEngineTest#testCreateValidation` PASS at audit base (1 test, 0 failures;
  surefire `forge.bridge.BridgeEngineTest.txt`, run 2026-09-15 pre-mutation):
  - 2-handle `create_commander_game` -> `PLAYER_COUNT_UNSUPPORTED` (S2 FAIL proven live);
  - 4-handle create/start -> decision parked, all controllers external non-AI.
- 3P/5P: rejected by the same unconditional gate (no per-count probe in tree at
  base; gate text + 2P probe jointly prove it). Post-fix WS233 matrix probes
  every count independently — no inference from 4P.

## What the fix must change (boundary proof)

After remediation the 2-handle rejection assertion in `testCreateValidation`
MUST be rebound (2P becomes legal); 1P/6P rejections take over the
`PLAYER_COUNT_UNSUPPORTED` boundary proof. Unknown-handle (`UNKNOWN_DECK_HANDLE`)
and injection-rejection semantics are unchanged.
