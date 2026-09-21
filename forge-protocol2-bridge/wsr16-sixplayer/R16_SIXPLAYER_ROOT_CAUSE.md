# R16 Root Cause — Six-Player Gate Widening

## Fail-before (DIRECTLY_VERIFIED on base)

- Engine probe: native 6P constructed game (6 seats, 40 life each,
  p1..p6 priority rings, clean shutdown) — engine capable, no defect.
- Bridge gate rejected 6P (`MAX_PLAYERS=5`, PLAYER_COUNT_UNSUPPORTED);
  WS233 negative test asserted 6P rejection (contract of its time).

## Production repair (minimal, contract-evolution)

- `BridgeEngine`: MAX_PLAYERS 5→6 (+ comment/message wording). Caps
  derive from constants. No other production diffs.
- WS233 tests updated with R16 rationale: negatives moved 6P→7P (extra
  distinct handle via deck-targeted re-import); process caps assert 6;
  pods + deck-targeted; new fresh-process 6P test.
- New: WsR16SixPlayerFamilyTest (lifecycle, combat split, Kediss fan-out
  to five, concede 6→5 + cleanup, 6-canary hidden matrix, twin
  match + diverge).

## Pass-after (DIRECTLY_VERIFIED)

- 6P lifecycle/combat/trigger/concede/hidden/twins green (7 tests).
- 7P rejected, no session leaked, no truncation (updated negative).
- Full suites green (sim 445, bridge 213/213); 2–5P behavior unchanged.

## Verdict

6P SUPPORTED (bounded: lifecycle + actual-card + hidden + twins);
7P+ FAIL_CLOSED. No engine/rules changes. 2–5P correctness preserved
(proven by green retention, not assumption).
