# Workstream Contract — R16 Forge Six-Player Successor (2026-09-20)

## Objective

Bounded 6P successor: determine whether the Forge engine + bridge can
support six-player Commander execution without weakening 2–5P
correctness. If yes: widen the provider gate 2–5 → 2–6 (production
change, minimal), qualify lifecycle + actual-card multiplayer behavior
+ hidden-info + determinism at 6P, move 7+ fail-closed boundary, full
regression. If no: prove the technical gate with evidence and keep
fail-closed (bounded stop with cause).

## Source Lock

- Base: R15 tip `4476a043aa06e768ba2122f528d564dca38b687c` (2–5P PASS).
- This branch: `wsr16/forge-six-player-20260920`.
- This worktree: `/home/moeen/code/ws-r16-forge-six-player-20260920`.
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- Engine 6P capability probe (native game creation/start at 6 seats).
- Gate widening (MIN/MAX + caps + contract docs) ONLY if engine proves
  capable; otherwise the gate stays and the probe seals the boundary.
- 6P lifecycle (construct/start/priority/terminate/shutdown, fresh +
  in-JVM), fail-closed 7+ (and 0/1 retained).
- 6P actual-card spot (multi-defender combat split + Kediss fan-out to
  five others), hidden-info spot (canary matrix), twin determinism spot.
- Full sim/bridge regression (2–5P must stay green).
- Evidence (root cause, matrix delta, seal, handoff).

## Out of Scope

- 7P+ support (fail-closed by design, recorded).
- S1/S3/S4 re-claims beyond retention; FULL107; promotion; Lab/RSP.

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge worktrees read-only, never written (verified clean before/after).

## Dependencies

- R15 tip (2–5P established prerequisite); WS233 gates; R9–R13 harnesses.

## Hard Gates

- 6P must never weaken 2–5P correctness (full regression green).
- Rules Authority: engine owns legality/seats/RNG; no second Rules
  Engine; unsupported fail closed; UNKNOWN stays UNKNOWN.
- No weakening to pass; fail-before + root cause for any repair.

## Forbidden Shortcuts

Per AGENTS.md §2 plus: no fixture-deck duplication to fake six seats
(real six distinct handles required); no truncation to five; no
green-suite equality with 6P PASS.

## Evidence Requirements

Engine verdict (capable/blocked with proof) → gate decision →
per-capability dispositions → regression → seal.

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff.

## Stop Conditions

Stop only when: 6P qualified with green regression, or a genuine
technical/ownership gate is proven (engine cap, fixture bound) with
fail-closed preserved. Remediable failures are diagnostic.
