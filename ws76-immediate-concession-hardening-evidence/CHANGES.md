# WS76 — Changes (systemic engine repair)

Validated head: `2a49cef3c4078577d3b47cc0d3139c9f93a2bc7f`
(tree `a42dae88d89697026dc82ed3cff4376503deeb7a`)

## Production fix (2 files, +11/−3)

1. `forge-game/src/main/java/forge/game/phase/PhaseHandler.java`
   - CLEANUP `autoPassCancel` sweep now iterates
     `Lists.newArrayList(game.getPlayers())` (snapshot; same order).
   - Player leaves the game before the concession API returns
     (`onPlayerLost` still synchronous inside `concede()`); callers need no
     priority window; remaining players are all still consulted.

2. `forge-game/src/main/java/forge/game/GameAction.java`
   - `checkGameOverCondition()` snapshots the in-game players once
     (`Lists.newArrayList(game.getPlayers())`) instead of aliasing the live
     list across loss checks, replacement-handler callbacks, and removals.
   - Removed the now-unused `FCollectionView` import (checkstyle-clean).

Semantics preserved: immediate removal, native 800.4 cleanup, existing
priority-handoff logic, and all read-side uses of `getPlayers()` unchanged.

## Regression test (1 file, new)

`forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws76/Ws76ImmediateConcessionTest.java`
(5 tests; engine-direct via engine AI controllers + production `PhaseHandler`;
card names are fixtures only):

- `testPriorityConcede2PControl` — baseline immediate concession.
- `testSyncConcedeDuringCleanupSweep2P/4P/5P` — synchronous sweep-context
  concession; immediate leave, counts, no skipped sweep steps; 4P adds native
  800.4a assertions (owned objects leave; others-owned control returns).
- `testNoFurtherDecisionsAndCoherentTurnAfterSweepConcede4P` — turn advances
  to a remaining player; departed player receives no priority or sweep
  consultations; game continues.

## Not changed

CPL, provider overlay, RQ-C3 fixtures, WS67 evidence/code, accepted-pin
declarations, GUI/AI decision logic, `Game.getPlayers()` live-view contract.
WS67 assertions unmodified.
