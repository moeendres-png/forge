# WS233 Cardinality Design

## Contract

- `MIN_PLAYERS = 2`, `MAX_PLAYERS = 5`, supported set exactly `{2,3,4,5}`.
- 0P/1P/6P+ fail closed with `PLAYER_COUNT_UNSUPPORTED` before any
  session/game object leaks (no partial session in `sessions` map).
- Error ordering preserved: engine-started -> game_id/format/seed checks ->
  count gate -> unknown-handle check -> injection checks. 4-handle requests
  with an unknown handle still report `UNKNOWN_DECK_HANDLE`.
- No truncation, no auto-fill, no handle duplication, no fabricated seats:
  exactly the given handles become exactly the seats, in order.

## Production delta (one generic path, no per-count branches)

`BridgeEngine.java` only:

- `MIN_PLAYERS`/`MAX_PLAYERS` constants on the engine.
- Gate: `handles.size() < MIN || > MAX` -> same typed error, message
  "this bridge qualifies two to five players; got N".
- `pod`/`players` allocation + construction/seat loops bound to
  `handles.size()`; `player_count` + `seats` response bound to it.
- `getCapabilities`: `max_players` 4 -> 5, add `min_players` 2.

Everything downstream (session registry, controllers, projection, digests,
outcomes, starting-player enumeration, scenario bootstrap, shutdown) is
already roster-parametric per the census: no change, no second rules logic.

## Test support delta

- New `deck5.json` (Isamaru simple shell, distinct `h4f-simple-5` id; 1+99=100).
- `BridgeTestSupport.importPod(engine, n)` over `deck1..deck5,deck-targeted`
  (distinct handle UUIDs per import); `importPod(engine)` keeps 4P.
- `BridgeTestSupport.buildConstructedGame(gameId, n)`; 1-arg keeps 4P.
- `BridgeEngineTest.testCreateValidation`: 2-handle rejection -> 1-handle
  rejection (same code); 4P asserts untouched.
- New `WS233CardinalityTest` (in-JVM matrix 2/3/4/5 + negatives 0/1/6 +
  no-leak + 2P/5P in-process semantic record/replay).
- New `WS233CardinalityProcessTest` (fresh JVM per 2/3/4/5 over BridgeMain
  JSONL: import/create/start/starting-player/keeps/priority/shutdown).

## Non-goals (scope guard)

No S3 card/Rules touch; no Lab write; no RSP/topology; no APNAP/Commander
semantics in the bridge; no replay schema change (additive fields only,
contract `semantic-replay-tape/1.0.0` preserved); no 4P weakening.
