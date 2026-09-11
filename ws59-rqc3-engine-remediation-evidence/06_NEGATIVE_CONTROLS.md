# WS59 Negative Controls (fail closed, no weakening)

- A04: base ETB without replacements asserts exactly 3 (no doubling leakage);
  generic test asserts >base (not exact 7/8, so order-independent); no manual counters.
- C01: exact binding asserts single-match Elves SpellAbility (not Card proxy);
  generic Cancel test proves stack candidacy without pitch/alternate-cost.
  No blue-color determination, hand filtering, alternate-cost solving, target-set
  construction, life/exile injection in tests.
- G04: double-concede and concede-on-lost both throw FORGE_CONCESSION_NOT_LEGAL;
  canConcede false when lost/game-over; concession never priority-gated (p1 concedes
  on p2 turn). No GUI default, no AI fallback, no silent skip.
- All new controllers in tests use engine authority (canTargetSpellAbility,
  getAllPossibleAbilities) verbatim; no first/random/default fallback in production
  paths (PlayerControllerForTests first-pick is test-only determinism for replacement
  ordering, not production legality; production AI path unchanged).

No denominators, assertions, or expected semantics weakened to obtain green.
