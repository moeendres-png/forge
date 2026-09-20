# Workstream Contract — R13 Bridge Choice-Mana + Scry Surface (2026-09-20)

## Objective

Close two production bridge gaps with reproduction (R10c): (1) tapping a
choice-mana source (Path of Ancestry) through the bridge consumes the tap
but produces zero mana, parks no choice, raises no error (silent drop —
correctness hazard for every complete-game run using such sources);
(2) scry arrangement unrepresented (arrangeForScry throws unsupported).
Then prove Path scry end-to-end via bridge. Scry framing needs a new
DecisionFrame surface only if the engine path requires it.

## Source Lock

- Base: R12 tip `adab6bb1e9a8f640b68231609e86fba488c13507` (S3 29/29).
- This branch: `wsr13/forge-choicemana-scry-surface-20260920`.
- This worktree: `/home/moeen/code/ws-r13-forge-choicemana-scry-surface-20260920`.
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- Fail-before probes with synchronized observations (pool/stack/frozen
  over time, no races): Path tap production; scry choice reachability.
- Minimal production repair(s) with root cause: tap-drop fix and/or
  scry-arrangement framing (new surface only if required, WS202-style).
- Path scry via bridge (shared-type positive + 2 negatives) reusing R10c
  sim proofs as oracle.
- Full bridge regression (187 tests) + sim retention + checkstyle.
- Evidence (root causes, regression, seal, handoff).

## Out of Scope

- Non-choice-mana behavior changes; S1/S2/S4 re-screen; FULL107;
  Lab/RSP; Freeze; Provider. New surfaces beyond scry/tap-drop.

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge worktrees read-only, never written (verified clean before/after).

## Dependencies

- R10c sim proofs + defect notes (provenance); R12 tip (base).

## Hard Gates

- Rules Authority: engine owns legality/costs/mana; bridge transports;
  no second Rules Engine; unsupported still fail closed WITH error
  (silent drops are defects, not design).
- No heuristic/default choices; X/amounts only via framed inputs.
- No weakening to pass; fail-before + pass-after required.

## Forbidden Shortcuts

Per AGENTS.md §2 plus: no auto-keep scry default; no pool fabrication;
no green-suite equality with Qualification PASS.

## Evidence Requirements

Synchronized fail-before (pool/stack/frozen traces) → minimal repair →
pass-after (Path scry green via bridge) → full regression → seal.

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff.

## Stop Conditions

Stop only when: tap-drop repaired + scry proven via bridge with green
regression, or a genuine terminal blocker is proven (engine-side choice
defect unfixable on owned surfaces → reproducible issue + PARTIAL with
cause). Remediable failures are diagnostic.
