# WS236 Validation

## Identity / hygiene

- Branch `ws236/forge-f4-spellcast-discriminator-20260916`, audit base
  `9a1e3fe94975` / `d1cb6ce47d` (HEAD+TREE verified, clean tree at lock).
- Changes: 2 new test files only (7 tests), 11 evidence files in
  `forge-protocol2-bridge/ws236-f4/`; zero production diffs; zero
  existing-test diffs.

## Baseline (pre-correction, DIRECTLY_VERIFIED)

- Sim seam, legacy drain: Veyran 2/2→2/2, Harmonic 1/3→1/3, Lumimancer
  0/1→0/1 (all FAIL); stack spell-only post-cast; collection matches
  (probe-run2/6).
- Bridge seam, priority/mana-only drain: Veyran 2/2→2/2 with Divination
  unresolved behind unanswered TRIGGER_ORDER; Harmonic 1/3→2/4 PASS
  (single trigger needs no ordering) (probe-run11/14/15).

## Root-cause proof (DIRECTLY_VERIFIED)

- `hasSimultaneousStackEntries()=true` post-cast in sim (engine dispatched).
- Manual engine ordering → 5-stack incl. 4 Veyran triggers; drain →
  power=6 (doubling + Pump sound; diagnostic since removed, probe-run8).
- Strict Kaervek under legacy drain FAILS 20→20 (Opponent filter also
  stranded; diagnostic since removed, probe-run9).
- Bridge `activationsThisTurn=2` with spell stalled (probe-run14).

## Qualification (post-correction, DIRECTLY_VERIFIED)

- `Ws236F4SpellcastDiscriminatorTest` 5/5 PASS (probe-run16/17).
- `WS236F4BridgeTest` 2/2 PASS (probe-run15/18).
- Retention: `Ws234S3CardBehaviorTest` 21/21 enabled PASS;
  `WS234S3BridgeTest` 11/11 enabled PASS. Totals 26/26 sim, 13/13 bridge,
  0 failures/errors.
- Checkstyle 0 violations in both modules.

## Not run (explicitly)

- Full bridge 161/161, sim-engine 71/71, FULL107: NOT_RUN (no decision
  value; see IMPACT_ADJUDICATION.md). 7 WS234 sim + 1 WS234 bridge disabled
  NOT_RUN preserved untouched. Lab recomputation not owned.

## Verdict

F4 You-filtered SpellCast-family CLOSED for Veyran/Harmonic with
actual-card behavior on both seams; root cause HARNESS_DEFECT boundedly
corrected in new tests only. No ENGINE/BRIDGE/SCRIPT defect. No promotion /
freeze / provider claim.
