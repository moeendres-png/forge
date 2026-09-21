# Workstream Contract — R15 Forge 2–5P Multiplayer Conformance (2026-09-20)

## Objective

Establish terminal evidence dispositions for all required cardinalities
(2P/3P/4P/5P) on actual-card runtime execution: multiplayer combat,
cross-player triggers, concession/elimination terminals, hidden-info
correctness, and seed determinism — on the R14 tip, reusing WS233
lifecycle gates (retained) and R9–R13 card patterns.

## Source Lock

- Base: R14 tip `b8deae929db3c2551420c5744eb8a2306c8c5869` (S0–S4 PASS).
- This branch: `wsr15/forge-multiplayer-conformance-20260920`.
- This worktree: `/home/moeen/code/ws-r15-forge-multiplayer-conformance-20260920`.
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

Per count 2P/3P/5P (4P retained from R9–R13 + WS233, re-run only on
impact — none expected; new files only):
- Multiplayer combat (multi-defender attacks, blocking, damage
  accounting) with actual cards.
- Cross-player triggers (commander-damage fan-out, e.g. Kediss family
  at N players).
- Concession terminals (concede submit → clean leave + owned-object
  cleanup per CR 800.4; game continues iff ≥2 remain).
- Hidden-info negatives per count (no cross-principal leaks in
  observations/frames/evidence).
- Same-seed twin determinism per count (seed binding surface exists).
- Terminal disposition matrix (PASS/FAIL/UNKNOWN + evidence per cell).

## Out of Scope

- 6P (separate successor, only after 2–5P established here).
- New engine/Rules semantics (repairs only with fail-before proof).
- FULL107, promotion, Lab/RSP, Freeze, Provider.

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge worktrees read-only, never written (verified clean before/after).

## Dependencies

- R14 tip + WS233 gates (lifecycle/process); R9–R13 card harnesses;
  seed-binding + concede + redaction surfaces (in-tree).

## Hard Gates

- Rules Authority: engine owns legality/combat/triggers/zones/RNG; no
  second Rules Engine; unsupported fail closed; no first/random/
  default/AI/GUI/silent-skip; UNKNOWN stays UNKNOWN.
- Actual-card runtime only (no construction credit); complete-game
  honesty (terminals proven, bounded runs labeled).
- No weakening to pass; every repair needs fail-before + root cause.

## Forbidden Shortcuts

Per AGENTS.md §2 plus: no requested-option filtering; no manual outcome
injection; no sa.resolve() substitutes; no green-suite equality with
conformance PASS.

## Evidence Requirements

Per count × capability: expected → observed (DIRECTLY_VERIFIED runs) →
disposition. Fail-before where repaired. Regression counts. Seal.

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff.

## Stop Conditions

Stop only when: every required cardinality has terminal dispositions
for every in-scope capability, or a genuine terminal blocker is proven
(reproducible issue + disposition with cause). Remediable failures are
diagnostic. Do not stop after one repaired count.
