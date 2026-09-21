# WS236 Root Cause

## Verdict: `HARNESS_DEFECT` (engine sound, no production change)

The Forge Rules Core is **sound** for the entire F4 path. Runtime-proven on
both seams with actual cards and engine-generated triggers/outcomes:

- Dispatch: `MagicStack.add` fires `SpellCast` (waiting list) and
  `SpellCastOrCopy` (immediate) with `Activator` always set.
- Filtering: `ValidActivatingPlayer$ You` matches by controller identity;
  `ValidCard` / `ValidCause` / `ValidActivator` / `ValidMode` all match
  (collection query returns exactly `[Veyran#SpellCastOrCopy]` pre- and
  post-cast, on both seams; suppression flags false).
- Doubling: `StaticAbilityPanharmonicon` yields trigAmt 2 for Veyran's own
  magecraft (`activationsThisTurn=2`), for Veyran-over-Lumimancer, and for
  Harmonic-over-Lumimancer (Wizard).
- Ordering machinery + resolution + Pump: `addAllTriggeredAbilitiesToStack`
  → stack → resolve → +1/+1 / +2/+2 / +4/+4 exactly as Oracle text requires.
- Card script: Veyran's `TrigPump` (no explicit `Defined$`) pumps Self via
  the engine default — no script defect, no script change.

## The two defect faces (both harness/driver, both bounded-corrected)

1. **Sim seam** (`SimulationTest`-family drain pattern): the legacy
   `resolveStack + checkStateEffects` loop never invokes the engine's
   `addAllTriggeredAbilitiesToStack()`, stranding CR 603.3b simultaneous
   entries for **every** cast-trigger filter — strict Kaervek
   (Opponent-filtered) fails 20→20 under the legacy drain too. The
   "You-specific" framing is refuted.
2. **Bridge seam driver**: the disabled WS234 Veyran probe's drain answered
   only PRIORITY/MANA frames; Veyran's doubled pair correctly parks
   `TRIGGER_ORDER` (complete permutation set, fail-closed design), stalling
   that drain with Divination unresolved. The bridge production code behaved
   correctly throughout.

## Remediation (completed, bounded)

- New sim regression (`Ws236F4SpellcastDiscriminatorTest`, 5 tests): drain
  loop performs the engine-owned ordering step before each resolution —
  test-only, trigger-family-general, zero production semantics touched,
  zero manual outcomes.
- New bridge regression (`WS236F4BridgeTest`, 2 tests): drain answers the
  engine-parked `TRIGGER_ORDER` from engine-offered options.
- No engine fix (none needed). No script fix (none needed). No card-name
  hacks. No second Rules engine. WS234 sealed files untouched.

Machine-readable: `ROOT_CAUSE.json` (this directory).
