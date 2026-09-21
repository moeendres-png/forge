# Workstream Contract — R14 Forge Admission Re-Screen (2026-09-20)

## Objective

Re-adjudicate the Forge candidate admission stages (S0–S4, WS231 contract)
at the R13 tip, where S2's fixed-four gate (replaced by WS233's generic
2–5 gate) and S3's denominator (29/29 SUPPORTED via R9–R13) changed verdict
inputs. Produce fresh stage verdicts + terminal fields as THE handover
qualification statement. No promotion claim (Coordinator decides).

## Source Lock

- Base: R13 tip `520f7d8503982fdbecd003d4939fe1ae11d6e48a` (S3 29/29,
  loyalty + scry surfaces in).
- This branch: `wsr14/forge-admission-rescreen-20260920`.
- This worktree: `/home/moeen/code/ws-r14-forge-admission-rescreen-20260920`.
- Donor contract (read-only): `forge-protocol2-bridge/ws231-admission/`
  (S0 PASS, S1 PASS, S2 FAIL, S3 FAIL, S4 PASS at WS227 pin).
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- S0: source lock, license files, build+checkstyle green at tip.
- S1: 30-family matrix re-verified (loyalty/scry/divided deltas noted);
  fallback-reachability + principal-scoping + census tests re-run.
- S2: generic 2–5 gate code + WS233 lifecycle evidence + fresh 2P/5P
  construct/start/terminate spot runs (existing tests preferred).
- S3: 29/29 census compiled from R9–R13 seals (no behavior re-run;
  retention via green suites).
- S4: replay/RNG retention tests re-run.
- New evidence namespace `forge-protocol2-bridge/wsr14-admission/`
  (never edit ws231-admission/ in place).
- Repair scope: if the re-screen proves a defect on owned surfaces,
  fix minimal + requalify (else record PARTIAL/FAIL honestly).

## Out of Scope

- FULL107 (cross-candidate denominator; Coordinator tier) → NOT_RUN.
- S5 (out of scope, per WS231). Promotion/provider selection/merges.
- New behavior tests beyond retention spots (S3 stays sealed).

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge worktrees read-only, never written (verified clean before/after).

## Dependencies

- R13 tip + seals; WS231 contract (criteria); WS233 cardinality evidence.

## Hard Gates

- Rules Authority preserved; no second Rules Engine; unsupported fail
  closed; UNKNOWN stays UNKNOWN; no inherited PASS without re-run
  (S3 compiled from sealed R9–R13 runs on this ancestry, itemized).
- No weakening to pass; every verdict cites evidence.
- Verdicts adjudicate the CANDIDATE, never select a provider.

## Forbidden Shortcuts

Per AGENTS.md §2 plus: no donor-verdict relabeling; no green-suite
equality with stage PASS (per-stage evidence required); no S5 claims.

## Evidence Requirements

Per stage: criteria ref → method (re-run/compiled/adjudicated) →
observed → verdict. Terminal fields explicit. Handoff with the exact
next authority action (Coordinator promotion decision).

## Persistence

Per validated milestone: scoped validation, state update, focused local
commit. End with §13-style handoff.

## Stop Conditions

Stop only when: all 5 stages adjudicated with sealed evidence, or a
genuine terminal blocker is proven (unrepairable defect → FAIL with
cause; authority gate). Remediable failures are diagnostic.
