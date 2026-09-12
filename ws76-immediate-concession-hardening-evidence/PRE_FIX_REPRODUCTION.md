# WS76 — Pre-Fix Reproduction (engine-direct, no provider code)

## Reproducer

`forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws76/Ws76ImmediateConcessionTest.java`

- Conceding decision: engine AI controller (`SyncConcedeController extends
  PlayerControllerAi`) whose `autoPassCancel()` calls the native Rules-Core
  seam `PlayerController.concede()` **synchronously** (once), exactly as an
  external concession arriving during the WS68 cleanup sweep would.
- Sweep driver: production `PhaseHandler.endTurnByEffect()` → CLEANUP
  `onPhaseBegin()` — the same live-list sweep WS68 implicated
  (`PhaseHandler.java:411` over `game.getPlayers()`).
- Controls: priority-adjacent concession (no enclosing iteration) in 2P;
  sweep-context concession in 2P / 4P / 5P; post-leave turn/priority coherence.

## Pre-fix result (audit base `22e7f17`, before any engine edit)

Command:

```text
mvn -o -pl forge-gui-desktop -am test -Dtest=Ws76ImmediateConcessionTest ...
Tests run: 5, Failures: 4, Errors: 0, Skipped: 0
```

Raw report: `prefix-failure-report.txt` / `prefix-failure-report.xml`.

| Test | Pre-fix outcome |
|------|-----------------|
| `testPriorityConcede2PControl` | PASS (baseline: no enclosing iteration) |
| `testSyncConcedeDuringCleanupSweep2P` | FAIL — `expected [1] but found [0]`: sweep exited silently, survivor's `autoPassCancel` skipped |
| `testSyncConcedeDuringCleanupSweep4P` | FAIL — `java.util.ConcurrentModificationException` |
| `testSyncConcedeDuringCleanupSweep5P` | FAIL — `java.util.ConcurrentModificationException` |
| `testNoFurtherDecisionsAndCoherentTurnAfterSweepConcede4P` | FAIL — `java.util.ConcurrentModificationException` |

## Failure signature (4P/5P/coherence)

```text
java.util.ConcurrentModificationException
    at java.base/java.util.ArrayList$Itr.checkForComodification(ArrayList.java:1095)
    at java.base/java.util.ArrayList$Itr.next(ArrayList.java:1049)
    at forge.game.phase.PhaseHandler.onPhaseBegin(PhaseHandler.java:411)
    at forge.game.phase.PhaseHandler.endTurnByEffect(PhaseHandler.java:1242)
```

This is the identical call chain WS68 reported from the provider path
(`PhaseHandler:411` → live `game.getPlayers()` → `Game.onPlayerLost`
removal), now proven engine-direct with zero CPL/provider frames.

## Verdict

`PRE_FIX_REPRO=PASS`: synchronous native-seam concession during the live
player sweep fails on the unpatched engine (CME for 4P/5P; silent sweep
truncation for 2P with the leaver at index 0).
