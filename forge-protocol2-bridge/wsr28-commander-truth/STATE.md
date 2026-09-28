# WSR28/WSR30 Forge Provider Truth — Durable State

Bounded state artifact for the Forge candidate-remediation branch. Not a
qualification report; the Lab owns the FULL107/provider evidence.

## Source lock

| Item | Value |
| --- | --- |
| Repository | `moeendres-png/forge` |
| Branch | `wsr28/commander-legality-and-scoping-20260928` |
| Base (master) | `ef958ee91ac6c9ce0152189f2654bf6e05abf273` |
| AF03 production | `615b6922701` (Commander legality delegates to `DeckFormat`) |
| AF03 tests | `f88d837697f13031feddb954337be35dd4927c4c` (`Wsr28CommanderLegalityTest`) |
| PB-06/PB-08 repair | `dac21f81a9d` (requester binding + creation seed acknowledgement) |

## Completed at this head

- WSR30 / PB-06 requester binding:
  - `StateProjection.playerState` marks `is_actor` from the validated observer
    context only; `StateProjection.gameState` echoes the validated
    `observer_player_id`. The public view marks nobody. The marker is never
    inferred from which seat holds visible cards, and principal metadata stays
    seat-scoped (`p1`..`p6` tokens).
  - Reproduced gap: `HIDDEN_INFO_FORGE.json` recorded
    `SCOPING_NOT_ESTABLISHED_ACTOR_MARKING_ABSENT` with
    `distinct_state_views: 4` and `actor_marked: 0/4`.
- WSR30 / PB-08 creation seed acknowledgement:
  - `create_commander_game` installs the requested seed and acknowledges from
    `MyRandom.getRootSeed()/isExplicitSeed()` in `payload.rng`
    (`explicit_seed`, `rules_seed`). Rejected or divergent readback fails
    closed with `SEED_UNSUPPORTED` and registers no session.
  - Unseeded launches clear a stale explicit binding so an uncontrolled run
    cannot silently inherit a seed and still claim `UNCONTROLLED_ENGINE_RNG`.
- Tests: `Wsr30RequesterBindingTest` (7) + `Wsr30SeedAcknowledgementTest` (6).
  - Fail-before (production change surgically removed): requester binding
    6/7 fail; seed acknowledgement 4/6 fail; stale-binding control 1/1 fail;
    the untouched negative control passes.
  - Pass-after: 13/13.
- Impacted regression: full `forge-protocol2-bridge` suite 301 tests,
  0 failures, 0 errors, 0 skipped (was 288 before this workstream).
- No `forge-core` / `forge-game` Rules source changed by WSR30; the repairs are
  bridge-local, like the PB-05 provenance repair.

## Known open items (not claimed as done)

- Forge PR #4 remains a DRAFT for the older wsr24 branch; the wsr28 branch
  needs its own Draft PR. Do not merge either to `master`.
- Lab-side consumption and evidence regeneration are not done at this head.
- The Lab's `START-2` observation path still requests `{"actor": ...}` and
  reads `hand_count`/`library_count`, while the Forge projection exposes
  `zones.hand` and `zones.library_size` and expects `observer_player_id`.
  `WS05-CMD-START-2` is UNKNOWN for Forge (and XMage) for that reason.
- XMage generic B4-D lane remains not principal-scoped (Lab-owned repair).

## Exact next action

Publish this exact head (normal push of the owned branch), open the wsr28
Draft PR, then consume `dac21f81a9d` into the Lab source lock and regenerate
only the impacted evidence surfaces (AF03, AF05/PB-06, AF09/PB-08,
provider blockers, readiness packet).
