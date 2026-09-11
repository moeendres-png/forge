# 05 — Full-Cast Closure (DIRECTLY_VERIFIED)

Evidence class: DIRECTLY_VERIFIED (test runs on the final committed
production+test HEAD; see 07 for the matrix).

## A04 exact (native, actual cards)

Doubling Season + Hardened Scales + Stonecoil Serpent, X=3 chosen through
the native `announceRequirements` decision (range-checked), 3 Forests
tapped through the normal cost path, spell stacked by the pipeline,
resolved through the real stack, replacement ordering engine-owned
(observed: 3 ordering calls, both replacements contesting):

- instance entry, static Human/provider entry, real priority-loop game,
  Human-shape pool payment, 4-player game, drawn card + resolved doublers,
  SBA/last-state rounds, 2xDS + 2xHS board: Serpent remains on the
  battlefield with 7|8 (14 on the doubled board) in every configuration.
- `A04_ETB_REPLACEMENT_REACHED=PASS` (ordering calls observed, max 2/4
  contesting); `A04_FINAL_COUNTERS=7_OR_8`; `A04_PERMANENT_X_GLOBAL_LEAK=NO`
  (see 06); `A04_DOUBLE_APPLICATION_REGRESSION=NO` (no 15; multi-doubler
  totals exact).

## Generic/systemic proof

Hangarback Walker X=2 + Corpsejack Menace + Winding Constrictor through
the same native pipeline: enters with more than base 2 via replacement
ordering. Base isolation: Serpent X=3 with no multipliers enters with
exactly 3.

## Closure limits (stated plainly)

What is proven: the Forge natural cast/payment/resolution pipeline
implements CR 107.3m correctly for X permanent spells, end to end, with
engine-owned replacement ordering. What is NOT proven: remediation of the
WS62 provider-run journal shape (0 replacement frames), which did not
reproduce Forge-side (see 02/03). No production behavior change was
required for the proven closure; the production delta (04) is
behavior-neutral cleanup + law correction.
