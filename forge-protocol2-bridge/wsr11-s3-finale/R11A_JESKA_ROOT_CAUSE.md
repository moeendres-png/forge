# R11a Root Cause — Jeska planeswalker (CARD_08)

Verdict: `PARTIAL` retained (entry branches CLOSED, activation branches
blocked). No engine defect found; one production-surface gap filed.

## Closed (DIRECTLY_VERIFIED)

- Loyalty entry equals commander-cast count (Rograkh 0 + Kediss 1R cast
  from command, then Jeska 2R → exactly 2 LOYALTY counters; Partner
  presence retained).
- Zero-loyalty entry dies to state-based actions (CR 704.5i): no
  commander casts → Jeska cast → graveyard, never on bf. (Initial test
  design wrongly expected survival; rules-corrected.)

## Blocked (BRIDGE_DEFECT filed, tests disabled with blockers)

- Jeska loyalty abilities ([0] AddCounter, [-X] SubCounter) classify
  COMPLEX_COST (CostPutCounter/CostRemoveCounter unrepresented): any
  priority frame offering them parks UNSUPPORTED, so activation is
  unreachable via bridge. Needs a loyalty-cost DecisionFrame surface
  (production bridge feature + requal) — out of S3 scope.
- Sim seam cannot drive the required combat deterministically (AI owns
  attacks), so the triple-damage replacement has no second seam.
- Disabled: testJeskaZeroAbilityTriplesDamage,
  testJeskaUltimateXDamage (blocker R11a-1, never PASS by assumption).

## Harness repairs (mine, rules-correct)

- Cast Rograkh/Kediss/Jeska back-to-back in ONE main phase (empty full
  rounds strand sorcery-speed casts); REPLACEMENT_ORDER/CONFIRM answered
  (Jeska ETB replacement); uniform 5-Mountain mana margin.

## Disposition

CARD_08: PARTIAL (entry/SBA proven; activations blocked on loyalty-cost
surface). Successor: bridge loyalty surface OR engine-direct proof.
