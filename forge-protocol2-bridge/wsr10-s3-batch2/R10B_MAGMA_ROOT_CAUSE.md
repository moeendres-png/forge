# R10b Root Cause — Magma divided reuse (CARD_09)

Verdict: `TEST_GAP` + two workstream-owned arithmetic/choreography
corrections (documented, rules-correct). Engine + bridge: `NO_DEFECT`.

## Fail-before chain (DIRECTLY_VERIFIED)

1. Divided target-set framing (all subsets incl. empty) + exact-vector
   submit through the native WS217 seam: works (2+2 applied, audits
   divided_allocation_submitted/chosen/allocated).
2. Tap-2 targeting fits the 128-cap only with lean boards (12 permanents
   = too many candidates → fail-closed session; redesigned to 5-8).
3. Burst-mana choreography (mine, twice wrong): Seething Song costs
   {2}{R} (not {R}); floating pools empty across phase boundaries, so the
   spender must be submitted at the first priority after the burst
   resolves (check-then-act, no empty full rounds). Audit trail
   (mana_payment_declined, execution_declined) + pool metering (5R)
   isolated each shortfall precisely.
4. Audit-driven verification: divided vector applied, tap pair picked,
   full 8-mana payment completed, resolution (damage/token/draw/grave)
   all native.

## Disposition

CARD_09 divided+tap+token+draw: SUPPORTED (2+2 damage accounting exact,
Bear tapped, 4/4 token, net hand counts, graveyard). Treasure
activation: SUPPORTED (discard + UR, Treasure Token). 7-mana decline:
SUPPORTED fail-closed.
