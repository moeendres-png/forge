# WS233 RNG Impact

- `forge-core/.../MyRandom.java` untouched; `BridgeSession.launch` seed
  binding untouched; no bridge-side RNG added; no prediction.
- Player-count parameterization does not touch shuffle/dice call sites or
  coordinates: seeds bind per session exactly as before (seeded-create test in
  `testCreateValidation` still passes; replay tests bind explicit seeds).
- State/checkpoint digests (`StateHash`, `SemanticReplay` digests) iterate the
  dynamic registry; no count-indexed coordinate exists, so no digest skew by N
  (2P/5P replay digest equality is live proof).
- Single-flight seeded-execution discipline unchanged (sequential sessions only).

Verdict: S4 RNG claim intact; replay/RNG regression RERUN green (stronger than
retention).
