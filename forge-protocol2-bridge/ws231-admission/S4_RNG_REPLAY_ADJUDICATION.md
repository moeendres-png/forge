# S4 RNG / Clean-Process Replay Adjudication (verdict: PASS by exact-pin retention)

## Rule applied

S4 PASS iff Rules RNG attribution design AND a bounded clean-process
semantic-replay runtime proof (minimum one tape + independent clean-process
replay, WS218-Tape-v1 shape) exist. Design without runtime proof ⇒ FAIL.
Slot/in-process-only replay ⇒ FAIL.

## Retention decision

WS231 makes **zero** material changes to provider/Core/replay paths
(`git status --porcelain` empty; HEAD == seal pin; provider/Core trees
verified equal). Per the WS231 brief, the 139-test suite is therefore NOT
rerun for reassurance. Retention predicates are sealed in
`S4_REPLAY_RETENTION.json`, and the current-pin WS227 runtime evidence is
used. Impact adjudication (test-impact skill): no changed surface exists, so
no dependent PASS is invalidated; nothing requires requalification.

## Retained runtime proof (exact pin 8ff3e7a4 / eb87b31 / d52e890)

- **Rules RNG attribution (design + runtime):** Core-owned
  `MyRandom.bindSeed` installs `CountingRandom`, resets the call counter,
  stores rootSeed/explicit (`forge-core/.../MyRandom.java:37-159`);
  `BridgeSession.launch` uses `bindSeed`; bridge holds no RNG
  (`testRulesRngBindingIsCoreOwned`).
- **Call coordinates:** call counts + root-seed comparison; extra RNG call
  diverges `callsBefore`, seed mismatch diverges root (fail closed).
- **Fresh-process record:** `WS227SeparateProcessTest.testFreshJvmRecordReplay`
  record child exit 0, RECORD_PASS, steps=3, bearDead=true.
- **Fresh-process replay:** same test, independent replay child exit 0,
  REPLAY_PASS, steps=3, bearDead=true, same manifest/seed, exactly-once,
  coordinate comparison.
- **No state injection:** manifest/seed + native submits only.
- **Process isolation:** two fresh child JVMs, FORGE_ENGINE_SHA bound, one
  game per process, wrong SHA fails closed.
- **Semantic option resolution:** exact fingerprint match; MISSING →
  fail closed; AMBIGUOUS (duplicate semantics) → fail closed, never
  first-match.
- **Drift matrix (all fail closed, no mutation):** legal-set digest mismatch
  aborts before submit; RNG drift; state drift (tampered post diverges);
  stale revision; wrong actor (outsider learns only code); malformed replay
  (null/empty fingerprint, null allocations, value+allocations exclusive).
- **Tape shape:** `semantic-replay-tape/1.0.0` over
  `forge-semantic-replay/1.0.0`; divided allocation replay (Arc 2+1 fresh-JVM
  + in-process, Seedcore 3+1 counters); terminal-outcome seat maps;
  checkpoint digests per step.
- **Human/GUI regression:** `HeadlessGuiFailClosedTest` + full bridge suite
  PASS; no Human path touched.

## Verdict: S4 PASS

Exact neutral admission definition satisfied via retained bounded
clean-process semantic-replay runtime proof at the exact current candidate
identity — not merely "replay code exists".
