# WS59 G04 Closure

## Engine-native seam (forge-game Rules Core; forge-gui transport preserved)
- `PlayerController.canConcede()`: true iff player in game and game not over.
  Not priority/phase/principal-gated (CR 104.3a at any time).
- `PlayerController.concede()`: authoritative action; fail-closed
  FORGE_CONCESSION_NOT_LEGAL when not legal; otherwise GameAction.concede.
- `GameAction.concede(Player)`: fail-closed when null/already-lost/game-over;
  otherwise player.concede() (no cantLose checks, per existing API) +
  checkGameOverCondition() -> Game.onPlayerLost native 800.4 cleanup.
- `PlayerControllerHuman.concede()`: now delegates to super.concede() (single
  authoritative path) preserving input-queue release on game over.

## Properties
- Engine exposes the legal concession action; provider may transport later but never
  fabricate (no standing CONCEDE pseudo-option, no orchestration direct-call as seam).
- Multiplayer leave-game cleanup preserved (face-down reveal, owner-objects cease,
  stolen permanents correction, stack prune, ingame->lost, monarch/initiative handoffs).

## Tests
- Ws59G04ConcessionTest.testConcessionOfferedAtAnyTimeNotPriorityGated: p1 concedes
  during p2 turn/priority via controller seam; asserts Conceded outcome, leave, and
  fail-closed second concede.
- testConcessionTriggersNativeLeaveGameCleanup: 2-player concede, asserts ingame/lost
  transition and unrelated permanent remains.
- testMultiplayerConcessionPreserves800d4Cleanup: 3-player, stolen Bears via temp
  control, middle concedes via seam, asserts control ends, two remain, game continues.
- testConcessionFailClosedWhenNotLegal: double-concede and GameAction.concede on lost
  both throw FORGE_CONCESSION_NOT_LEGAL.

AG-1 remains EXTERNALLY_RULE_VALIDATED (coordinator gates): 104.3a at any time,
800.4 based on leaving, not cause.

Evidence class: DIRECTLY_VERIFIED once new tests pass.
