# R13 Root Cause — Spent-Mana Recording + Scry Arrangement

## Fail-before chain (DIRECTLY_VERIFIED)

1. Path scry silent via bridge while sim fires (R10c): pool verified
   holding Path's R (R13SYNC pool=1), Hatchling paid + resolved, yet no
   scry trigger, no choice frame, no session failure.
2. `getPayingMana()` empty post-payment (R13PAY probe): bridge pool
   payment discards spent mana.
3. Mechanism: AI path passes `sa.getPayingMana()` into
   `payManaCostFromPool` (ComputerUtilMana:608); bridge passed a fresh
   throwaway list (ExternalPlayerController payFromPoolWithTaps). Spent
   watchers (TriggersWhenSpent via applyPayingManaEffects on stack-add)
   and mana hooks read getPayingMana → silenced through bridge payments.
4. First scry attempt then parked the choice: `arrangeForScry` threw
   unsupported (`failReason=... arrangeForScry ... not represented`).

## Production repair (minimal, mirror-patterns)

- `payFromPoolWithTaps`: pass `sa.getPayingMana()` (AI mirror). Partial
  deductions behave identically to the AI path (recorded even on
  rollback paths, same as AI).
- `arrangeForScry`: bottom-subset framing via GENERIC_SELECTION
  (2^N subsets, 128-combination cap, canonical encounter order, always
  framed even binary). No new frame kinds; census-stable.
- No Rules-Core changes; no heuristic/default choices; X/amounts only
  via framed inputs; over-cap shapes still fail closed.

## Pass-after (DIRECTLY_VERIFIED)

- Scry trigger fires on shared-type Path spend; GENERIC_SELECTION
  `scry_arrange` options (`bottom [] top [Plains;]` /
  `bottom [Plains;] top []`); answered; Hatchling resolves; counts
  coherent. Negatives (non-sharing, other-mana) silent as required.

## Verdict

CARD_27 bridge seam: SUPPORTED (was UNSUPPORTED with 2 filed defects).
Spent-mana recording benefits every bridge pool payment (correctness
restoration, not behavior change); full regression adjudicates impact.
