# WS236-S1 Validation

## Identity / hygiene

- Branch `ws236-s1/forge-trigger-drain-family-20260916`, audit base
  `1f1393021c47` / `ad4a637b334` (HEAD+TREE verified, clean tree at lock;
  origin `ws236/forge-f4-spellcast-discriminator-20260916` verified identical
  SHA+TREE after fetch).
- Changes: 1 new sim test file (3 tests), 1 migrated test helper (+ comment),
  10 evidence files in `forge-protocol2-bridge/ws236-s1/`; zero production
  diffs; zero test-body diffs.

## Baseline (pre-correction, DIRECTLY_VERIFIED)

- Sim seam, legacy drain, strict CARD_17: Kaervek trigger queued
  (`hasSimultaneousStackEntries()=true`) but stranded — lives 20→20, entries
  still stranded post-drain, strict exact-2 assert FAILS (expected [2] found
  [0]; exit 1). Temporary diagnostic sealed in KAERVEK_OBSERVATIONS.json,
  then removed; shipped file holds only green tests.

## Root-cause proof (DIRECTLY_VERIFIED)

- Same WS236 HARNESS_DEFECT face: legacy `resolveStack + checkStateEffects`
  never calls `addAllTriggeredAbilitiesToStack()`; corrected drain orders via
  the engine path (AI target/ordering decisions, never harness-chosen).
- Strict pass-after: trigger on stack (host Kaervek, AI target opponent),
  post-drain p2 20→18, p1 untouched, Kaervek unmarked, stack empty, no
  stranded entries.
- Negatives: controller-cast queues nothing, lives unchanged; Veyran-doubled
  pair orders (2 triggers, no Kaervek trigger) and pumps +2/+2.

## Qualification (post-correction, DIRECTLY_VERIFIED)

- `Ws236S1KaervekDrainTest` 3/3 PASS.
- Retention: `Ws236F4SpellcastDiscriminatorTest` 5/5 PASS;
  `Ws234S3CardBehaviorTest` 21/21 enabled PASS (7 disabled NOT_RUN
  preserved). Total 29/29, 0 failures/errors, exit 0.
- Checkstyle 0 violations in all built modules (enforced in-build).
- Intermediate 29-run/1-failure (migrated game-over guard vs Syphon Mind
  decking) adjudicated FIXTURE_DEFECT artifact with before/after parity and
  re-run green — see ROOT_CAUSE.md.

## Not run (explicitly)

- Bridge suites incl. `WS234S3BridgeTest` (no causal path from sim-only
  changes), full bridge 161/161, sim-engine 71/71, FULL107: NOT_RUN.
- Cross-player APNAP ordering choice: NOT_TESTED (no in-scope event).

## Verdict

CARD_17 strict actual-card reprobe CLOSED (fail-before + pass-after with
engine-owned ordering); family-wide drain inventory enumerated and boundedly
migrated (1 affected caller; 2 same-form unaffected callers classified and
untouched). No ENGINE/BRIDGE/SCRIPT defect. No promotion / freeze / provider
claim.
