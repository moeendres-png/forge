# 06 — Negative Controls (DIRECTLY_VERIFIED)

Evidence class: DIRECTLY_VERIFIED (runs in `Ws63A04NaturalFullCastTest`).

1. `testNegativeNonCastEntryHasZeroX`: Serpent put onto the battlefield with
   a null (non-spell) cause — i.e., no cast lineage — materializes 0
   counters and leaves for the graveyard under real state-based actions.
   PASS. Proves the fix surface does not leak the cast X value onto the
   permanent outside the CR 107.3m ETB context, and that inconsistent
   lineage fails closed (0, not an arbitrary X).
2. Base isolation (`...BaseSerpentX3WithoutReplacements`): X materializes
   as exactly 3 with no multipliers — multipliers, not materialization,
   produce 7|8. PASS.
3. No-double-application: exact 7|8 (never 15); doubled board exactly 14
   for base 3 with 2xDS + 2xHS. PASS.
4. Non-ETB xPaid queries still evaluate 0 by construction
   (`AbilityUtils.xCount` falls through past the trigger/replacement-ETB
   branches). CODE_DERIVED from the unchanged evaluation order.

`A04_PERMANENT_X_GLOBAL_LEAK=NO`.
