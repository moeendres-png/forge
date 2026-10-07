# #561 batch 6 B2b — checkpoint library and keyed Rules-RNG tape (Forge bridge)

Authority: Coordinator decision 6035022676 (item 2, C1, C2, C4–C8; C3 for credit) and
decision record 6005365186 (G4-K). Evidence class: LOCAL_OBSERVED until PB-03 on the exact
head plus a sealed epoch. Every row this serves (WS05 REPLAY_CLEAN_PROCESS,
REPLAY_DECISION_TAPE, REPLAY_STATE_HASHES, REPLAY_EVENT_TAPE, RNG_RULES_TAPE) stays
fail-closed UNKNOWN until requalified; REPLAY_EVENT_TAPE additionally needs G4-P.

## Request (`create_commander_game.request.checkpoint_materialization`)

The Lab record's own shape, as the XMage lane reads it (`XmageLosslessHiddenPlan`):

```json
{"deck_state": [{"player_id": "P1",
                 "library_template": {"card_identity": "Mountain", "count": 99},
                 "opening_hand_size": 7,
                 "checkpoint_hand": {"completeness": "COMPLETE",
                                     "template_card_identity": "Mountain", "template_count": 8},
                 "checkpoint_library": {"completeness": "COMPLETE_TOP_TO_BOTTOM",
                                        "runs": [{"semantic_id": "obj:replay-lib-0"}, "...",
                                                 {"card_identity": "Mountain", "count": 91}]}},
                {"player_id": "P2", "...": "checkpoint_hand template_count 7, no library"}],
 "semantic_objects": ["the record's objects; zone library needs zone_position, zone hand is added"]}
```

Player labels `P1`/`p1` both map to bridge principal `p1`. The imported deck of every
declared seat must be exactly `count` × template identity. Refusal codes (create time,
`game_creation_failed`, message `checkpoint materialization rejected: CODE [semantic or player id]`):
INVALID_DECK_STATE, DUPLICATE_SEMANTIC_OBJECT, PARTIAL_LIBRARY_REQUEST, UNKNOWN_LIBRARY_OBJECT,
LIBRARY_OBJECT_OWNER_MISMATCH, DUPLICATE_LIBRARY_OBJECT, LIBRARY_POSITION_MISMATCH,
INVALID_CARD_IDENTITY, LIBRARY_RUN_NOT_TEMPLATE, HAND_TEMPLATE_MISMATCH,
HAND_DECLARATION_REQUIRED, EXACT_HAND_AFTER_DRAW_UNSUPPORTED, LIBRARY_TEMPLATE_MISMATCH,
PARTIAL_HAND_REQUEST, UNKNOWN_DECK_PLAYER, HAND_SCRIPT_CONFLICT.
Runtime (session FAILED, `CHECKPOINT_MATERIALIZATION_REJECTED:CODE`): CHECKPOINT_SEAM_MISMATCH,
CHECKPOINT_SEAM_REENTERED, CHECKPOINT_SEAM_MISSED, DRAW_COUNT_MISMATCH, LIBRARY_COUNT_MISMATCH,
LIBRARY_TEMPLATE_MISMATCH, CHECKPOINT_NOT_ACTIVE_PLAYER, HAND_TEMPLATE_MISMATCH,
OBJECT_CREATION_FAILED, LIBRARY_OBJECT_MISSING, LIBRARY_POSITION_MISMATCH,
LIBRARY_DUPLICATE_OBJECT, LIBRARY_UNDECLARED_OBJECT, LIBRARY_ENGINE_CARD_MISSING,
LIBRARY_ORDER_MISMATCH, HAND_OBJECT_MISSING, HAND_COMPOSITION_MISMATCH,
CHECKPOINT_MATERIALIZATION_ERROR.

## Condition compliance

- **C1** Seam = the session's `GameEventTurnPhase` subscriber for turn 1 `MAIN1`
  (`PhaseHandler.advanceToNextPhase` fires it before `onPhaseBegin(MAIN1)` and before the
  first `MAIN1` priority). Never TurnBegan, never `applyPostUntap`. One-shot latch; every
  precondition is checked before any mutation: `getNumDrawnThisTurn()` equals the declared
  draws (`template_count − opening_hand_size`, must be 1 for a library seat); the library
  holds exactly the sum of the template runs (91 for the replay records, derived, not
  hard-coded) and only template cards; the seat is active.
- **C2** Only the declared objects are created (`Card.fromPaperCard`) and placed at their
  declared indices among the engine's own cards. A G1-R1 battlefield placement for a seat
  with a declared library is created, never taken out of that library (the record's
  arithmetic: 99 = 8 template hand + 91 template library; battlefield Mountains are extra).
- **C4** One native `Zone.setCards` per touched zone. No shuffle, draw or
  `GameEventCardChangeZone`; the only events are one `GameEventZone` `ComplexUpdate` for the
  library and one for the hand (a view refresh; G4-P keeps that class known-but-ignored, and
  the materialization lies before the first post-checkpoint frame, so 91→98 never reaches a
  tape). This batch does not implement G4-P.
- **C5** No materialization path references an object. `StateProjection.identityVisible` now
  hides every library card from every principal in `object_refs`, owner included, unless the
  engine's `mayPlayerLook` allows it (the library part of G4-C1; face-down handling stays with
  G4-P). Scope of the guarantee: **`object_refs` only.** The bridge controller has no
  `tempShowCards` override, so during a search or dig `mayPlayerLook` is false and those
  cards' `object_refs` are `hidden` too, while the option `label` and `source_name`
  (ExternalPlayerController's library-choice options) still name the card to the searching
  player. That is legal (CR 701.19: the searcher may look at the searched cards), but it is a
  wider-than-required hiding in `object_refs` and a name path outside it. **Follow-up for
  G4-C1 / G4-P (main writer):** per-event visibility aligned with `CardView.canBeShownTo`,
  including the searcher's temporary view. This batch adds no `tempShowCards` override.
- **C6** Engine-direct readback: object positions, membership (no undeclared extra, none
  dropped — covers `Zone.add`'s silent drop), identity order, hand composition. Mismatch =
  code only. Published only as `get_constructed_state.payload.checkpoint_materialization`:
  `{status, failure_code?, first_post_checkpoint_revision?, lossless_hidden_checks:
  {library_object, library_membership, library_order, hand_object, hand_composition}}`.
- **C7** The template hand is never scripted; only `zone: hand` semantic objects are added at
  the seam. A scenario `hands` entry for a declared hand is refused (HAND_SCRIPT_CONFLICT);
  any other hand shape is EXACT_HAND_AFTER_DRAW_UNSUPPORTED (the G2 gate).
- **C8** Upkeep/draw frames of turn 1 precede the seam. The orchestration report gives
  `first_post_checkpoint_revision`; the Lab must start every principal-scoped tape at that
  revision and drop earlier frames. A park guard refuses every decision after a failed or
  missed checkpoint, so no post-seam frame exists without a verified checkpoint.

## `get_rules_rng_tape` (G4-K)

Orchestration-only, refused with `orchestration_channel_not_enabled` without
`COMMANDER_LAB_ORCHESTRATION_KEY` (same handling as `get_constructed_state`). Payload:

```json
{"game_id": "...", "observation_scope": "orchestration_keyed_digests",
 "tape_schema": "forge-rules-rng-tape/2", "engine_state": "PARKED|CLEAN_TERMINAL|RUNNING|FAILED|CLOSED",
 "rules_seed_explicit": true, "rules_random_calls": 1234,
 "rules_rng_results": [{"operation": "LIBRARY_SHUFFLE", "stream": "library_shuffle:P1",
   "sequence": 0, "seat": 0, "before_lower_bound": 0, "after": 98, "library_size": 99,
   "result_digest": "<hex HMAC-SHA256>"}],
 "coordinate_semantics": {
   "before_lower_bound": "rules_rng_calls_at_last_engine_event_before_the_shuffle_lower_bound_not_exact",
   "after": "rules_rng_calls_when_the_shuffled_order_reached_the_controller_exact",
   "sequence": "global_engine_order_across_seats"},
 "privileged_state_digest": "<hex HMAC-SHA256>"}
```

Digest fields only when PARKED or CLEAN_TERMINAL. `result_digest` = HMAC over
`forge-rules-rng-tape/1, game_id, <id>, stream, <s>, seat, <n>, sequence, <n>,
library_size, <n>, permutation, <pre-shuffle index per post-shuffle position>…`.
`before_lower_bound` (schema /2; /1 called it `before`) is the call count at the last engine
event before the shuffle: a lower bound, never an exact call index (Forge has no pre-shuffle
hook without a Rules-Core change; nothing fires inside `Player.shuffle` before its RNG use).
`after` is exact: the count when the shuffled order reached the controller. The payload's
`coordinate_semantics` states both. **Divergence from XMage:** the XMage tape's `before` is
exact (`game.getRulesRandomCalls()` before the shuffle); a Lab consumer must not compare a
Forge `before_lower_bound` with an XMage `before` as the same quantity. Twin comparisons
(Forge against Forge) are unaffected: the bound is deterministic per seed.
`sequence` is one dense, zero-based counter in engine order **across all seats and
streams** (not per stream), the same convention as the XMage tape (`XmageRulesRngResultTape`:
`results.size()` over one per-game list); the per-seat order is recovered by filtering on
`stream`. Library shuffles only; coin and die values are not
recorded (they need a Rules-Core decision). Refusals: `rules_rng_tape_failed` with
`RULES_RNG_TAPE_POISONED:<cause>` or `RULES_RNG_TAPE_INCOMPLETE`.
Poison causes: SHUFFLE_SUBSCRIBER_ERROR, SHUFFLE_PLAYER_UNKNOWN, SHUFFLE_RESULT_UNREADABLE,
SHUFFLE_ORDER_NOT_A_PERMUTATION, RNG_COORDINATE_INCONSISTENT (after − before_lower_bound <
library_size − 1), SHUFFLE_WITHOUT_RESULT, SHUFFLE_SEAT_MISMATCH, RNG_OBSERVER_ERROR.

## Red tests and mutation-kill evidence (LOCAL_OBSERVED, implementation commit 9fab0856c2a)

Each mutant was applied to the committed tree, the named test run alone
(`mvn -o -pl forge-protocol2-bridge -am test -Dtest=...`), the file restored with
`git checkout --`, and `git diff --stat` confirmed clean afterwards.

| Mutant | Test (class#method) | Result |
|---|---|---|
| M1 seam at DRAW-step begin (before the draw) | B2bCheckpointLibraryTest#theMaterializationFollowsTheTurnOneDraw | RED (fails closed DRAW_COUNT_MISMATCH, no main frame) |
| M2 draw-count precondition removed | #theDrawCountPreconditionFailsClosed | RED (HAND_TEMPLATE_MISMATCH instead of DRAW_COUNT_MISMATCH) |
| M3 membership check removed | #anUndeclaredExtraLibraryObjectIsRefused | RED (LIBRARY_ORDER_MISMATCH instead of LIBRARY_UNDECLARED_OBJECT) |
| M4 position check removed | #aPositionMismatchIsACodeWithoutAName | RED (LIBRARY_ORDER_MISMATCH instead of LIBRARY_POSITION_MISMATCH) |
| M5 per-card Zone.remove/add instead of setCards | #theMaterializationFiresNoShuffleDrawOrZoneChangeEvent | RED (Removed/Added events in the seam window) |
| M6 key check removed from get_rules_rng_tape | B2bRulesRngTapeTest#anRngTapeWithoutAKeyIsRefused | RED |
| M7 a card name added to the payload | #thePayloadCarriesNoCardNames | RED |
| M8a–d game_id / sequence / seat / stream dropped from the HMAC input | #theHmacChangesWithGameIdStreamSeatOrSequence | RED ×4 |
| M9 shuffle-subscriber exception rethrown to Guava (swallowed) | #aSwallowedSubscriberExceptionPoisonsTheTape | RED (tape served as clean) |
| M10 result ignores the shuffled order | B2bRngTapeSeparateProcessTest | RED (seed + 1 gives the same digests) |

Review-fix reds (commit 6397682a111; offline build with `-Dcheckstyle.skip`):

| Mutant | Test | Result |
|---|---|---|
| M11 missed-seam park guard disabled | B2bCheckpointLibraryTest#aMissedSeamRefusesEveryLaterDecision | RED (a main-phase decision was offered) |
| M12 coordinate check removed | B2bRulesRngTapeTest#anImpossibleCoordinatePairPoisonsTheTape | RED |
| M13 seat check removed | B2bRulesRngTapeTest#aShuffleEventForAnotherSeatPoisonsTheTape | RED |
| M14 `before_lower_bound` key reverted to `before` | B2bRulesRngTapeTest#thePayloadCarriesNoCardNames | RED |
