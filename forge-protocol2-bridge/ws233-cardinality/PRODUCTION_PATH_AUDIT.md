# WS233 Production Path Audit

Derived from source (not assumed from the WS231 report).

## Changed (BridgeEngine.java only)

- `MIN_PLAYERS`/`MAX_PLAYERS` constants (2/5).
- Admission gate `< MIN || > MAX` (same `PLAYER_COUNT_UNSUPPORTED` family;
  rejection precedes session creation, deck lookup, and scenario parsing —
  no partial game leaks).
- `pod`/`players` allocation + construction + seat-response loops bound to
  `handles.size()`; `player_count` likewise.
- `getCapabilities`: `max_players` 5, added `min_players` 2.

## Audited, unchanged (parametric by construction)

- `BridgeSession` registry/identity/frames/submit/settle/shutdown.
- `BridgeLobbyPlayer`/`ExternalPlayerController` (incl. Core-owned
  `chooseStartingPlayer` over turn order).
- `StateProjection` players/stack/winners/bridge-meta; `SemanticReplay`
  digests/outcomes/tape contract; `StateHash`; `ScenarioBootstrap`;
  `BridgeMain` JSONL framing; error-code taxonomy (`BridgeErrors`).
- Forge engine `Game`/`Match`/`GameRules` roster handling (no count cap found;
  `isMultiplayer = size > 2` is engine Rules authority, not provider logic).

## Error-ordering preserved

engine-started -> game_id/format/seed -> count gate -> unknown-handle ->
injection guards. 4-handle + unknown handle still `UNKNOWN_DECK_HANDLE`.

## No per-count branches

One generic path; no create2/3/4/5PlayerGame duplication. Duplicate handles
in one request are passed through as given (no truncation/fill/fabrication);
each import mints a distinct handle UUID.
