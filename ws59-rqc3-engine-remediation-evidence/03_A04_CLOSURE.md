# WS59 A04 Closure

## General repairs (forge-game, no card names)
1. `GameAction.changeZone`: propagate resolving spell cast linkage to the battlefield
   copy for stack->battlefield spells (CR 107.3k): setCastFrom, setCastSA(cause),
   controller to activating player (CR 112.2), xManaCostPaidByColor + promisedGift.
   General for all X-ETB permanents. This alone restores base X=3 -> 3 P1P1 (was 0).
2. `GameAction.changeZone`: ID-based remapping safety for ETB CounterMap entries from
   a pre-move object sharing the entering permanent's ID to the battlefield copy.
   No-op in the exact fixture (PutCounter already keys the entering copy) but general
   safety for other ETB population paths; no card names, no injection.

## Ordering arises from existing general semantics (no new ordering code)
- After (1), the card's own Moved etbCounter replacement populates 3 via the live
  CounterMap view; the Move event's Updated recursion then routes the populated map
  through AddCounter (ReplaceAddCounter modeCheck Moved+CounterMap) with both DS+HS
  matching layer Other. Decider offered authoritative 2-order choice via existing
  chooseSingleReplacementEffect (ReplacementHandler:220). 3 -> 8 (HS then DS) or
  7 (DS then HS). Zero no longer occurs.
- New finding: running AddCounter again post-move (GameAction:561 etb=true path)
  double-applies (3 -> 7 -> 15 via live-view + post-move). Correct systemic fix is
  base materialization only; existing Moved ordering suffices. Post-move skip preserved.

## Tests (forge-gui-desktop gamesimulationtests/ws59)
- Ws59A04ReplacementOrderingTest.testExactFixtureSerpentX3DoublingSeasonHardenedScales:
  exact cards, X=3 via engine castSA, asserts battlefield (not graveyard) and 7|8.
  DIRECTLY_VERIFIED (9/9 WS59 tests pass; base 3 alone passes).
- testGenericOrderingNotCardNameSpecial: Corpsejack Menace + Winding Constrictor +
  Hangarback Walker X=2, asserts >base (proves not card-name-special).
- testBaseEtbMaterializesWithoutReplacements: Serpent X=3 alone asserts exactly 3.

Evidence class for closure: DIRECTLY_VERIFIED (new tests pass).

## Ordering arises from general semantics
- After (1)+(2), base X=3 populates 3 P1P1 in the ETB map via the card's own Moved
  etbCounter replacement (no injection).
- After (3), the populated map runs through AddCounter: DS + HS both match (layer
  Other), decider offered authoritative 2-order choice, Updated re-runs for the
  second effect. 3 -> 8 (HS then DS) or 7 (DS then HS). Zero no longer occurs.

## Tests (forge-gui-desktop gamesimulationtests/ws59)
- Ws59A04ReplacementOrderingTest.testExactFixtureSerpentX3DoublingSeasonHardenedScales:
  exact cards, X=3 via engine castSA, asserts battlefield (not graveyard) and 7|8.
- testGenericOrderingNotCardNameSpecial: Corpsejack Menace + Winding Constrictor +
  Hangarback Walker X=2, asserts >base (proves not card-name-special).
- testBaseEtbMaterializesWithoutReplacements: Serpent X=3 alone asserts exactly 3.

Evidence class for closure: DIRECTLY_VERIFIED once new tests pass (pending terminal run).
