# R12 Root Cause — Loyalty-Cost Surface + Jeska Closure (CARD_08)

## Fail-before (DIRECTLY_VERIFIED on base)

R11a's activation tests disabled with blocker R11a-1: any priority frame
offering Jeska's loyalty abilities parked UNSUPPORTED with reason
`COMPLEX_COST:CostPutCounter / CostRemoveCounter` (classifier). Sim seam
cannot drive the required combat deterministically. No engine defect
claimed; production surface missing.

## Production repair (minimal, semantics-preserving)

`BridgeCostDecisionMaker` (2 visit methods) + `ExternalPlayerController`
(2 classifier lines + doc):

- `visit(CostPutCounter)`: from-source forced → `PaymentDecision.card
  (source)` (mirrors HumanCostDecision minus confirms; submit = intent).
  Non-source → null → rollback, fail closed (unchanged).
- `visit(CostRemoveCounter)`: from-source forced → sufficiency check
  (`maxCounters < cntRemoved` → null) + `GameEntityCounterTable`
  (replicated from HumanCostDecision) → `PaymentDecision.counters`.
  "All"/any-counter/non-source → null → rollback (unchanged).
- X arrives exclusively via framed X_ANNOUNCE (existing surface; no
  defaults invented). Classifier allowlists exactly these two types;
  everything else still COMPLEX_COST → UNSUPPORTED.
- No new frame kinds; no confirm dialogs; no heuristic choices; no
  Rules-Core changes. Unpayable/ambiguous shapes roll back audited
  (same class as MANA decline).

## Pass-after (DIRECTLY_VERIFIED on this branch)

- Jeska [0]: offered as `(0)` alongside `(-X)`; Bear target picked;
  combat tripled 2 → 6 (3/3 related asserts).
- Jeska ultimate: `(-X)` offered; X=2 via X_ANNOUNCE; p2 picked among
  offered targets; exactly 2 damage to p2, no friendly fire, Jeska pays
  2 loyalty and dies to SBA 704.5i (payment proof).
- Jeska family 4/4 green (loyalty-2, triple, ultimate, SBA-zero).

## Verdict

CARD_08 Jeska: SUPPORTED (was the last PARTIAL). S3 29/29 SUPPORTED.
Failure class of the gap: PRODUCTION_SURFACE_GAP (now closed), never an
engine rules defect.
