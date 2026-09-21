# Admission Decision Packet — Forge Candidate (R19 refresh, input only)

**This packet is decision INPUT. It selects no provider, freezes no
architecture, merges nothing. Promotion readiness is not
production-provider selection.** Refresh of the R18 packet
(`wsr18-promotion` @ `9c41bff2`, untouched); delta: REFRESH_NOTES.md.

## 1. Candidate identity

Forge chain tip: `wsr19` on R18 `9c41bff28091` (refresh workstream,
appends only); Rules-Core/engine pin unchanged (reactor
`2.0.15-SNAPSHOT` reverified in pom); Lab XMage pin `1.4.61`
reverified in engine-bridge pom; mage source candidate `db134b97`
carried (no new mage workstream). No Forge R19 branch exists
(remote heads checked) — R19/R20 are Lab-side. Full identity table:
IDENTITIES.json (every SHA freshly reverified 2026-09-21).

## 2. S0–S4 evidence and limitations (carried from R18 seal)

- S0 PASS (lock/license/build at tip; R14/R17).
- S1 PASS (30 families + loyalty/scry/divided deltas; fallback,
  scoping, census, surface tests re-run green).
- S2 PASS (generic 2–6 gate in code; WS233 + R16 lifecycle/process;
  1P/7P+ fail closed).
- S3 PASS (29/29 itemized: WS234 14, R6 2, WS236 2, R8 1, R9 4,
  R10 3, R11 2, R12 1; retention suites green).
- S4 PASS (replay green; no RNG diffs in R12/R13/R16 production).
- Limitations: behavior credit 0 beyond cited runs; UNKNOWNs listed
  per seal (APNAP extras, marathon terminals, scry-N>7, non-source
  counter costs); construction/import alone never credited.
- No sealed verdict was re-run in R19 (no HEAD moved under any seal);
  no verdict upgraded.

## 3. New since R18 (Lab-side, branch-level, not merged)

- R19 Lab-6P (`cpl/xmage-six-player-20260921` @ `107db189`): gates
  2–6, Java bridge 155/155, Python 761, live 6P smoke 55 + full gate
  7284 PASS (winner seat 2, replay MATCH). Lab-side 6P parity now
  exists on-branch (was "Lab main still 4P-only" in R18 §7).
- R20 CI lane (`cpl/ci-cardinality-lane-20260921` @ `0526a798`):
  workflow runs bounded live smokes 2/3/5/6 + 7P fail-closed probe;
  lane tests 6/7 green (1 parked: lock.txt env-tier); suite delta
  24→19 FAILED with 0 new failures.
- Neither is merged; `origin/main` (Lab) and `master` (Forge)
  reverified unmoved.

## 4. Actual runtime versus seal-derived evidence (unchanged method)

- DIRECTLY_VERIFIED at tip: bridge 190→213 suites (R12→R16), sim 445,
  live 2–6P gates/twins/concessions/combat, retention predicates.
- SEAL-DERIVED (same ancestry, itemized rationale, never silent):
  WS234/R6/WS236/R8/R9/R10/R11 card verdicts; WS233 lifecycle matrices;
  WS218 tapes (as canary inputs). Each item names its seal; byte
  fidelity re-verified where hashed (retention predicates).
- No verdict was upgraded without its stated method.

## 5. Candidate-comparison requirements (NOT executed)

A Forge-vs-XMage selection would additionally require (none exist):
(a) a common denominator contract (FULL107 definition import);
(b) a same-deck same-seed cross-engine fixture protocol;
(c) independent Rules adjudication of divergences (Sol High);
(d) hidden-info/RNG/replay parity criteria per count;
(e) pilot-agnostic scoring (no pilot-strength conflation).

## 6. FULL107 readiness and outstanding work

- Status: NOT_RUN. Definition lives in the Lab WS47 contract
  (provider denominator 107); no Forge-local definition exists.
- Outstanding: definition import → 107-item mapping (59 items
  mappable today: 30 families + 29 cards; 48 undefined) → execution →
  adjudication. Estimated as its own qualification workstream.

## 7. Merge and repin impact

- Merge figures carried R18-sealed, NOT recomputed (no merges
  occurred since): Forge tip vs master = 306 files (+45842/−250);
  Lab successor vs main = 90 files (+58749/−383) — the Lab figure is
  now stale-label (chain extended by R19/R20 branches). Both need
  line-by-line review + CI + separate merge authorization. This packet
  is not merge approval.
- Repin: NONE (no pin moved in R9–R20 on either side; reverified).

## 8. Remaining authoritative decisions (Coordinator-owned)

Promotion selection; Architecture Freeze; merges; publication/push;
XMage SOS repin; CR/Release-Notes; stale-consumer scope; FULL107
execution; merging Lab 6P parity + CI lane to Lab main.
