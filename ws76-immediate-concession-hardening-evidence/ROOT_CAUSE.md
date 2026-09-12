# WS76 — Root Cause

Classification: **`ENGINE_DEFECT`** (reproduced engine-direct; stack-verified).

## Exact call chain (proven)

1. `PhaseHandler.endTurnByEffect()` sets CLEANUP and calls `onPhaseBegin()`.
2. `PhaseHandler.java:411` — `for (Player player : game.getPlayers())`:
   `Game.getPlayers()` returns the **live** `ingamePlayers` collection
   (`Game.java:396-398`); `FCollection.iterator()` is the fail-fast
   `ArrayList` iterator (`FCollection.java:231-232`).
3. `player.getController().autoPassCancel()` — synchronous player-decision
   boundary. A conceding controller invokes the native seam
   `PlayerController.concede()` → `GameAction.concede()` →
   `Player.concede()` (outcome only) + `checkGameOverCondition()`.
4. `checkGameOverCondition()` → `Game.onPlayerLost(p)` →
   `Game.java:999` `ingamePlayers.remove(p)` — **structural modification of
   the list under iteration**.
5. Control returns to the sweep iterator:
   - 4P/5P (leaver not last): `ArrayList$Itr.next()` →
     `checkForComodification` → `ConcurrentModificationException`
     (stack in `prefix-failure-report.txt`).
   - 2P (leaver at index 0): removal shifts the survivor left so
     `cursor == size`; `hasNext()` is false, the loop exits "normally" and
     the survivor's `autoPassCancel` is **silently skipped** (assertion
     `expected [1] but found [0]`). Same defect, no exception.

## Collections / mutations / assumptions

- Iterated collection: live `Game.ingamePlayers` (`PlayerCollection` over
  fail-fast `ArrayList`).
- Responsible mutation: `Game.onPlayerLost` removal (correct 800.4 cleanup
  in itself; the Ws59 seam tests prove outcomes/800.4 are native-correct).
- Broken assumption: engine sweeps assume no structural player-list change
  can occur across a synchronous controller callback. CR 104.3a ("concede
  at any time") contradicts that assumption, so any synchronous immediate
  concession re-entering a live sweep breaks traversal.
- Leave-game cleanup sequence (`onPlayerLost`: 800.4a exile/return,
  triggers, stack purge, list removal) is native and untouched by this
  workstream; only the *traversal* of the live list across decision
  boundaries is repaired.

## Audit of other live-list traversals (decision-boundary rule)

Fixed (traverse a snapshot; same elements, same order, decoupled iterator):

- `PhaseHandler:411` cleanup `autoPassCancel` sweep — proven re-entrant
  decision boundary.
- `GameAction.checkGameOverCondition` — aliased the live list, then ran
  arbitrary game code (loss checks, replacement handlers that can consult
  controllers) and performed removals; second read-loop reused the alias.

Audited, intentionally untouched (no synchronous decision/concede can
originate inside; verified by read):

- `GameAction:2426` — iterates `getRegisteredPlayers()` (`allPlayers`),
  never structurally modified by loss; post-game concede is illegal anyway.
- `GameAction:2486` pre-game `awaitNextInput` loop — no decision inside;
  `chooseStartingPlayer` sits outside the loop.
- `Game.onPlayerLost:1003` post-removal loop — engine-internal
  `removeController`, no decision boundary.
- DRAW reset, extra-turn counts, `setHasPriority`, flashback-view loops —
  engine-internal setters only.
- Priority loop — counter-driven (no player-list iterator) with the
  explicit active-player-lost handoff (`PhaseHandler:1058-1064`).

No CME is caught/ignored anywhere; no deferred queue; no card-name logic;
no provider changes.
