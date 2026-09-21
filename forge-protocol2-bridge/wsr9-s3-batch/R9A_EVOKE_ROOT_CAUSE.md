# R9a Root Cause — Evoke/Fear (Shriekmaw/CARD_18)

Verdict: `TEST_GAP` (no defect anywhere). First strict run 5/5 green.

- Engine: evoke alternative-cost enumeration, 1B payment, evoke-sacrifice
  trigger, ETB destroy, Fear keyword all native (DIRECTLY_VERIFIED).
- Harness: R8-pattern sim shape (devMode board, engine-AI targets,
  WS236-S1 drain) sufficed; no new harness needed.
- Census CARD_18 "needs evoke/combat harness" was a test gap, same class
  as WS234 micros.

## Disposition

CARD_18 Evoke/Fear: SUPPORTED (was PARTIAL). Sim-only proof; bridge
variants deferred (no bridge-specific gap found; sim seam sufficient).
