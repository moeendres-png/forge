# WS233 Turn-Order / Starting-Player Audit

- Turn order stays Forge-owned: `ExternalPlayerController.chooseStartingPlayer`
  enumerates `getPlayersInTurnOrder()` (untouched); bridge only transports the
  authoritative candidate set with native bindings.
- Starting-player legal domain == exactly the N-player pod at every count
  (2/3/4/5 options asserted live, in-JVM and fresh-process).
- No first-seat default: every qualification chooses the LAST seat
  (`pN`, never `p1`), and the engine starts with it (`active_player_id == pN`
  asserted over the pipe; `getPlayerTurn` identity asserted in-JVM at 4P base
  pattern, last-seat choice at all WS233 counts).
- Revision still parks until an explicit external submit (no auto-advance;
  wrong-actor/stale/missing-revision negatives pass at every lifecycle).
- Turn order contains exactly the live roster (`getPlayersInTurnOrder().size()
  == N` asserted post-start, pre-loss, at every count).

No APNAP/elimination/Commander-damage logic added to the provider (out of
scope; correctness beyond construction belongs to S3/S5).
