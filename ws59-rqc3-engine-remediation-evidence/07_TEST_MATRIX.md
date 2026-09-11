# WS59 Test Matrix

## New regression (must pass)
- Ws59A04ReplacementOrderingTest
  - testExactFixtureSerpentX3DoublingSeasonHardenedScales (DIRECT)
  - testGenericOrderingNotCardNameSpecial (GENERIC: Menace/Constrictor/Walker)
  - testBaseEtbMaterializesWithoutReplacements (BASE)
- Ws59C01CostPitchTest
  - testExactFixtureForceOfWillPitchSeesStackSpell (DIRECT)
  - testGenericCounterspellSeesStackSpell (GENERIC: Cancel/Bears)
- Ws59G04ConcessionTest
  - testConcessionOfferedAtAnyTimeNotPriorityGated (DIRECT)
  - testConcessionTriggersNativeLeaveGameCleanup (CLEANUP 2P)
  - testMultiplayerConcessionPreserves800d4Cleanup (CLEANUP 3P + control)
  - testConcessionFailClosedWhenNotLegal (NEGATIVE)

## Relevant module/suite (impact-adjudicated)
- forge-game: AbilityKeyTest, ManaCostBeingPaidTest (existing, must still pass)
- forge-gui-desktop gamesimulationtests: ReplacementHandlerTest (existing ETB/Moved),
  ComprehensiveRulesSection104 (104.2/104.3 win/loss, must still pass)
- Full forge-gui-desktop gamesimulationtests only on failure broadening.

Historical WS55R journals retained as baseline (no reruns): WS55R_C01_EVID (837f),
WS55R_A04_EVID (1282f), replays 0-div, hidden-info audits. No behavior credit claimed.
