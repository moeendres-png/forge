# R9c Root Cause — Finale X>=10 (CARD_15)

Verdict: `TEST_GAP` + one bridge completeness bound honored (not a defect).

## Fail-before chain (DIRECTLY_VERIFIED)

1. X_ANNOUNCE free-input (0..MAX) + 12-mana payment (13 MANA frames for
   X=10+UU) work natively through the bridge.
2. Resolution session_failed at the untap branch: `UntapEffect.untapChoose`
   calls `chooseCardsForEffect(list, 0..5)`; with 12 tapped Islands the
   candidate subsets are C(12,0..5)=1586 > the bridge 128-combination
   completeness cap → fail-closed by design (parkSingleChoice/parkChoices
   never present partial sets). Engine + rule correct; bridge bound
   honored, not bypassed.
3. Redesign (test-only): 5 Islands + 7 Mox Sapphires (nonland mana) keeps
   the untap set at C(5,0..5)=32. Full branch then green: shuffle (grave
   emptied), draw 10 (net +9), five Islands untapped, self-exile,
   11-mana decline fail-closed.

## Disposition

CARD_15 X>=10: SUPPORTED (shuffle/draw/untap/exile verified; no-max-hand
static CODE_DERIVED same SubAbility chain). 12-tapped-land untap framing
remains UNSUPPORTED by bridge design (128-cap) — documented bound, never
PASS by assumption.
