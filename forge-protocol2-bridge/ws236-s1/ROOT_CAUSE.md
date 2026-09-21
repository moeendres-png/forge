# WS236-S1 Root Cause (addendum — WS236 verdict confirmed, not revised)

Verdict: `HARNESS_DEFECT` (WS236 face #1, same cause, one affected caller
migrated). Engine, card scripts, bridge production: `NO_DEFECT`.

## Confirmation

The WS236 discriminator proved the Forge Rules Core sound for the whole
cast-trigger lifecycle (dispatch, filter matching, Panharmonicon doubling,
`addAllTriggeredAbilitiesToStack` ordering machinery, resolution, Pump) and
localized the defect to the sim-seam legacy drain loop, which strands CR
603.3b simultaneous entries for every cast-trigger filter. S1 re-proves the
same face with the strict CARD_17 probe: under the legacy drain the Kaervek
trigger is queued (`hasSimultaneousStackEntries()=true`) yet never reaches
the stack (lives 20→20, entries stranded post-drain); with the engine-owned
ordering step it queues, orders (AI-chosen legal target through the engine
path), resolves, and deals exactly MV=2 (p2 20→18).

## Repair (test-only, smallest systemic)

- NEW `Ws236S1KaervekDrainTest` (3 behavior tests): strict opponent-cast,
  controller-cast negative, Veyran-doubled + Kaervek-silent same-controller
  multi-trigger. Own corrected drain with fail-closed guards.
- MIGRATED `Ws234S3CardBehaviorTest.drainStack` (the one affected shared
  helper): engine ordering per iteration, simultaneous-aware loop, terminal
  ordering, fail-closed guard/stranded asserts. Test bodies untouched;
  denominators unchanged (21 enabled, 7 disabled NOT_RUN preserved).
- UNTOUCHED: `Ws234CleaveAftermathTest` inline loop and
  `H01CloneHumilityTest` single resolve (no trigger sources on board —
  classified UNAFFECTED, minimal diff); WS236 file (sealed reference);
  all production Rules Core, scripts, bridge, CPL, RSP, pins.

## Classified non-defect (FIXTURE_DEFECT artifact, pre-existing)

`testCard20SyphonMind` decks its drawing player (empty-deck fixture +
704.5b), ending the game mid-drain. Identical under the legacy drain (its
loop also exited on `isGameOver`); the test's own assertions pass before and
after. A fail-closed game-over guard added during migration was therefore
removed with documented rationale; game-over exit stays pre-existing harness
behavior and is never treated as success.

## Not tested (explicit)

Cross-player APNAP ordering choice between simultaneous triggers of
different controllers (no 2-player single-cast event in scope produces one
with these cards); bridge seam (no causal path from sim-only changes —
NOT_RUN with reason); full 161/71/FULL107 (NOT_RUN per contract).
