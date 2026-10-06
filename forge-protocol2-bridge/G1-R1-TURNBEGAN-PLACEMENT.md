# G1-R1: TurnBegan scenario-permanent placement (Commander-Lab #561)

Status: **implemented on the qualification bridge lineage; Lab re-pin and
requalification NOT performed here.**
`PRODUCTION_PROVIDER = NOT_SELECTED` · `ARCHITECTURE_FREEZE = NOT_CLAIMED` ·
`PRODUCTION_REPOSITORY = NOT_CREATED`

## Authority

- Coordinator decision record: Commander-Lab #561 comment `6005365186`,
  **G1-R1 APPROVED WITH CONDITIONS C1-C6**.
- Rules sources cited by that ruling: CR 302.6, CR 508.1a, CR 103.6a, plus the
  XMage `BEGIN_TURN` precedent in
  `docs/af07_final_closure_20261002/PLACEMENT_POINT_ADJUDICATION.md`
  (Commander-Lab repository).
- Base source identity: Forge `ee37e4a52d99401ba57fba7ca516ac01f1981161`
  (tree `b26c59365bb88f898a2ec7e7a7e337bdf7d4573e`), the exact current
  `secondary_engine.bridge_source.commit`. Rules-Core candidate identity
  (`bb0a740d2bef725194798383c2452213ecdd0b37`) is untouched and remains a
  separate identity.

## Recorded divergence (C6)

This placement point is a **qualification-bridge behavior, not unmodified
Forge-native game-start behavior**. Forge's own game-start permanents
(`Player.initVariantsZones`) plus the `playerTurn.getTurn() > 0` guard in
`PhaseHandler` remain summoning-sick on turn one. Only scenario-placed
permanents are admitted to the first-turn readiness sweep, and only under the
accepted ruling above. No Rules-Core behavior is changed to hide the
distinction.

## Exact before/after bridge behavior

| Surface | Before (ee37e4a5) | After (this branch) |
|---|---|---|
| Battlefield placement point | `startGameHook`, i.e. `PhaseHandler.setupFirstTurn` after the readiness sweep (`PhaseHandler:183-187`) and after the untap step (`onPhaseBegin`, `:1018`) | turn-one `GameEventTurnBegan` subscriber (`PhaseHandler:180`), before the readiness sweep and before the untap step |
| Requested tapped state | applied at placement (`card.setTapped(true)` in the hook) | placed untapped at TurnBegan; applied silently by `Card.setTapped` in the retained post-untap hook (`GameEventCardTapped` never fires for it) |
| Active seat's placed creature on turn 1 | stayed summoning-sick; the engine parks **no** declare-attackers frame (no legal attacker) | controlled since that turn began (native readiness sweep); legal attacker; declare-attackers frame parks |
| Non-active seats' placed creatures | summoning-sick until their own turn begins | unchanged (still summoning-sick until their own turn begins; `isFirstTurnControlled()==true`) |
| Counters | added once, `fireEvents=false`, at placement | added once, `fireEvents=false`, at TurnBegan placement; never re-applied. The bridge-fixed property is exactly one application; native `addCounterInternal` clamps or drops per engine rules, and exactness is checkpoint-verified by the Lab |
| Hands / life / commander damage | retained hook, after untap | unchanged; still the retained hook |
| Subscriber failure handling | n/a (no subscriber) | subscriber catches `Throwable`, records it on the session; one-shot latch keyed to the intended game and turn 1; duplicate and missing invocation fail closed; the retained hook throws unless bootstrap ran exactly once and succeeded |
| Hands-off rules | native | native (all legality, SBAs, triggers, casts, combat stay in the Rules Core) |

## Changed decision/frame surfaces

Mandated by the ruling (must be adjudicated by the later Lab requalification;
no historical row is called PASS here):

- **New turn-one frames**: the active seat's scenario creatures now make
  turn-one declare-attackers frames reachable where the external controller
  previously parked none (it returns silently when no legal attacker exists).
  Downstream combat frames (blockers, combat damage) become reachable only if
  the pilot actually declares attacks.
- **New turn-one options**: `{T}` activations of the active seat's scenario
  permanents (for example mana dorks) are now engine-offered; before, summoning
  sickness gated them.
- **Phase-trigger ordering**: scenario permanents now exist before the untap
  step and before the untap-phase `TriggerType.Phase` run, so any phase/begin
  of turn triggers they carry can be part of the turn-one event history.
- **Untap-step interactions**: because placement now precedes
  `PhaseHandler`'s `onPhaseBegin` for the untap step, untap-step static and
  replacement effects can now see the scenario permanents (none are tapped
  when placed, so no untap action is fabricated for them). This is the same
  divergence class as the trigger ordering and is for the Lab requalification
  to diff.

Preserved:

- The retained hook's `givePriorityToPlayer` frame: the first priority frame is
  still parked for the active player at the first-turn untap step, with the
  same offered options as the legacy placement point (asserted A/B in
  `G1R1TurnBeganBootstrapTest#initialPriorityDecisionFramePreserved`). The
  test additionally asserts the absolute frame kind, actor and step, so a
  removed or moved frame is caught even if both compared runs regressed; the A/B
  guards against an option change introduced only by the new placement path.
- Terminal facts and all non-combat offered frames are unchanged by the bridge;
  any downstream difference is a consequence of now-legal turn-one combat and
  is for the Lab requalification to adjudicate.

## Changed native state/readback surfaces

- `StateProjection` `players[].zones.battlefield_details[]` gains
  `controlled_since_turn_began` = `!Card.isFirstTurnControlled()` (raw
  summoning-sickness, CR 302.6), never `hasSickness()` (which folds in haste).
  Additive field; emitted for every battlefield entry, redacted ones included
  because control history is public.
- Zone-change event timing for scenario placements moves from the retained
  hook to the TurnBegan dispatch; requested tapped state produces no
  `GameEventCardTapped` in either implementation, and the approved point never
  produces a fabricated untap (`tapped_at_begin` control).
- No other projected field changes.

## Exact tests

- `G1R1TurnBeganBootstrapTest` (new): C4(a)(b)(c) real-engine controls; C3
  readback; C1 fail-closed controls (`never`, `throw`, `double`, wrong game,
  wrong turn, hook refusal); C2 placement-point mutants (`late`,
  `tapped_at_begin`, `counters_twice`, `sick_active`, `ready_all`,
  `ready_casts`); C5 first-frame A/B.
- `WS202SeparateProcessTest#testPipeTurnOneScenarioReadiness` (new):
  end-to-end over Protocol-2 JSONL — turn-one attacker offered, projected
  `controlled_since_turn_began` true for the active seat and false for a
  non-active seat.
- `WS202ExecutableSurfaceTest#testBootstrapStateProof` (updated to the split
  placement/hook API).
- `WS216SeparateProcessTest` (updated: incidental turn-one combat is declined
  by the engine-offered empty declaration; stale placement-timing comments
  corrected).

## Historical Forge PASS rows potentially invalidated (NOT re-labelled PASS)

From sealed epoch `ab357d1772c3-8698ff38979c`, the 17 Forge PASS rows; the 13
`FORGE_SCENARIO_BOOTSTRAP_OBLIGATION` rows are directly affected, the
`PLAYER_COUNT_*` rows and `PILOT_MULLIGAN` only see the additive readback field:

`PLAYER_COUNT_2P`, `PLAYER_COUNT_3P`, `PLAYER_COUNT_4P`, `PLAYER_COUNT_5P`,
`PILOT_MULLIGAN`, `WS05-MP-ELIM-OWNED-3`, `WS05-MP-ELIM-PRIO-3`,
`WS05-MP-ELIM-5`, `WS05-CMD-ZONE-GY-YES`, `WS05-CMD-ZONE-GY-NO`,
`WS05-CMD-ZONE-EXILE-YES`, `WS05-CMD-ZONE-EXILE-NO`, `WS05-CMD-ZONE-HAND-YES`,
`WS05-CMD-ZONE-HAND-NO`, `WS05-CMD-DMG-SPLIT`, `WS05-CMD-PARTNER-DMG`,
`WS05-CMD-PARTNER-ZONE`.

These rows keep no current credit until the separate Lab workstream re-pins
`config/rules_engines.json`, runs PB-03 on the new exact head, diffs frames,
offered options and terminal facts, and adjudicates every difference.

## Required follow-up in the separate Lab workstream (not performed here)

The Lab lane's pinned-source assertions and unobservable-dimension classification
must be re-derived against this new bridge source; they encode the old timing:

- `_HOOK_ASSERTIONS` in
  `src/commander_lab/qualification/current_boundary/forge_scenario_lane.py`
  currently requires `ScenarioBootstrap.apply(...)` and
  `capturedMatch.startGame(capturedGame, () -> {`, which no longer exist. The
  new fragments are `ScenarioBootstrap.placeBattlefield`, `installScenarioBootstrap`,
  `requireScenarioBootstrapCompleted` and `ScenarioBootstrap.applyPostUntap`
  (the `setScenarioPlan` fragment is unchanged).
- `_UNOBSERVABLE_RECORD_DIMENSIONS["controlled_since_turn_began"]` (and its
  `DIMENSION_UNOBSERVABLE` finding at the semantic-object loop) must become a
  verified comparison against `battlefield_details[].controlled_since_turn_began`
  (false for non-active-seat objects, true for the turn-one active seat's),
  failing closed on a mismatch.
- The stale placement-timing rationale comments (`forge_scenario_lane.py`
  around the combat-step and temporal-checkpoint classification) must be
  updated.
- PB-03 on the exact new head plus a sealed epoch is the only credit source.

