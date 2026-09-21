# R11c Root Cause — Boseiju saga (CARD_29)

Verdict: `TEST_GAP` + two workstream-owned choreography corrections
(documented, rules-correct). Engine: `NO_DEFECT`.

## Fail-before chain (DIRECTLY_VERIFIED)

1. Chapter-I search framed with all subsets (incl. empty); explicit
   2-Forest pick required (blind first-option takes nothing).
2. Lore accretes ONLY on the saga controller's own MAIN1s (CR 703.4f):
   in 4P that's every 4th game turn (ETB 1 + turn 5 (2) + turn 9 (3)).
   Early "lore stuck at 2" readings were turn-arithmetic error (mine),
   not engine: per-turn sampling proved ETB + turn-5 accretion, and the
   400-iteration budget covers 10+ game turns.
3. Chapter-II grave-land pick must be explicit (Forest label); blind
   first-option risks skip options. Chapter III (exile + return
   transformed) resolves choice-free; Branch P/T tracks lands exactly
   (4/4 → 5/5 after an extra land drop).

## Disposition

CARD_29 chapters + transform: SUPPORTED (search-2, grave-to-top,
exile-return, P/T tracking). Sim lore-counter probe (deleted after use)
additionally confirmed chapter queuing per count.
