# WS59R Test Matrix

## New/strengthened regression (must pass)
- Ws59A04ReplacementOrderingTest (unchanged)
  - testExactFixtureSerpentX3DoublingSeasonHardenedScales (DIRECT)
  - testGenericOrderingNotCardNameSpecial (GENERIC: Menace/Constrictor/Walker)
  - testBaseEtbMaterializesWithoutReplacements (BASE)
- Ws59C01CostPitchTest (WS59R non-bypass rewrite)
  - testExactFixtureForceOfWillPitchAuthoritativeDecision (DIRECT, native callback)
  - testGenericCounterspellAuthoritativeDecision (GENERIC: Cancel/Bears, native callback)
- Ws59G04ConcessionTest (unchanged)
  - testConcessionOfferedAtAnyTimeNotPriorityGated (DIRECT)
  - testConcessionTriggersNativeLeaveGameCleanup (CLEANUP 2P)
  - testMultiplayerConcessionPreserves800d4Cleanup (CLEANUP 3P + control)
  - testConcessionFailClosedWhenNotLegal (NEGATIVE)

## Relevant module/suite (impact-adjudicated)
- forge-game: AbilityKeyTest, ManaCostBeingPaidTest (existing, must still pass)
- forge-gui-desktop gamesimulationtests: ReplacementHandlerTest (existing ETB/Moved)
- Full forge-gui-desktop gamesimulationtests only on failure broadening.
- A04/G04 production semantics byte-identical to a9a95db: prior evidence survives;
  no requalification beyond the runs above (impact adjudication).

Historical WS55R journals retained as baseline (no reruns): WS55R_C01_EVID (837f),
WS55R_A04_EVID (1282f), replays 0-div, hidden-info audits. No behavior credit claimed.
