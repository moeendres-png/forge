# Workstream Contract — R12 Forge Loyalty-Cost Surface (2026-09-20)

## Objective

Represent planeswalker loyalty costs (CostPutCounter/CostRemoveCounter,
from-source forced shapes) in the Protocol-2 bridge so loyalty abilities
become playable through engine-offered frames, then enable R11a's two
disabled Jeska activation tests and close S3 to 29/29 SUPPORTED.

## Source Lock

- Base: R11 tip `fafbbf1d050e185daeb7e5f8d147f291b923dcdf` (S3 28/29).
- This branch: `wsr12/forge-loyalty-cost-surface-20260920`.
- This worktree: `/home/moeen/code/ws-r12-forge-loyalty-cost-surface-20260920`.
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- Production (minimal, semantics-preserving): from-source forced paths
  in `BridgeCostDecisionMaker.visit(CostPutCounter/CostRemoveCounter)`
  mirroring HumanCostDecision (no confirms; submit = intent; anything
  else returns null → rollback), plus the two classifier allowlist lines
  in `ExternalPlayerController.classifyComplex` with comments.
- X announcement for loyalty X flows through the existing X_ANNOUNCE
  surface (no new frame kinds).
- Enable R11a's 2 disabled tests (remove `enabled=false`) + verify green.
- Full bridge regression (185 tests) + sim retention + checkstyle.
- Evidence (root cause, S3 29/29 statement, seal, handoff).

## Out of Scope

- Non-from-source counter costs (card-selection shapes stay COMPLEX_COST
  → null → rollback, unchanged).
- New DecisionFrame kinds; confirm dialogs; scry/Path surfaces;
  S1/S2/S4 re-screen; FULL107; Lab/RSP; Freeze; Provider.

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge worktrees read-only, never written (verified clean before/after).

## Dependencies

- R11 tip + Jeska seals (provenance: blocker R11a-1 text).
- HumanCostDecision semantics (reference only, forge-gui module).

## Hard Gates

- Rules Authority: engine owns legality/costs; bridge transports only;
  no second Rules Engine; unsupported shapes still fail closed
  (null → rollback, audited).
- No heuristic/X-default: X comes only from framed X_ANNOUNCE.
- No weakening to pass; fail-before (disabled tests red-by-blocker at
  base) + pass-after on this branch.
- R11a tests enabled ONLY when green; else stay disabled.

## Forbidden Shortcuts

Per AGENTS.md §2 plus: no confirm-bypass beyond submit-as-intent (WS202
precedent: pilot pre-decides); no silent partial payment; no green-suite
equality with Qualification PASS.

## Evidence Requirements

Fail-before (blocker reproduced on base) → production diff (minimal) →
pass-after (Jeska activations green) → full regression counts →
S3 29/29 statement → seal → handoff.

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff.

## Stop Conditions

Stop only when: loyalty surface green with Jeska activations passing and
full regression sealed (S3 29/29), or a genuine terminal blocker is
proven (e.g., X-announcement unreachable for loyalty SA → PARTIAL stays
with cause). Remediable failures are diagnostic.
