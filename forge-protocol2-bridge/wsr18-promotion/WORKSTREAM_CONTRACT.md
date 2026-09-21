# Workstream Contract — R18 Forge Promotion Packet (2026-09-21)

## Objective

Prepare the complete admission decision packet for the Forge candidate
(S3 29/29, S0–S4 PASS, 2–6P, CI qualified) WITHOUT declaring promotion:
source/engine identities, S0–S4 evidence and limitations, runtime vs
seal-derived accounting, candidate-comparison requirements, FULL107
readiness, merge/repin impact, remaining authoritative decisions.

## Source Lock

- Base: R17 tip `f7a8c6c702a6807f1ce13241bf424c80097c5153`.
- This branch: `wsr18/forge-promotion-packet-20260921`.
- This worktree: `/home/moeen/code/ws-r18-forge-promotion-packet-20260921`.
- Repo `moeendres-png/forge`. No Lab/mage edits. No push/merge.

## In Scope

- Exact identities: Lab/Forge/mage HEADs+TREEs for every workstream
  branch in the campaign chain + engine pins (Maven + source commits,
  lineage-verified).
- S0–S4 evidence pointers + per-stage limitations (from R14/R17 seals).
- Runtime-vs-seal accounting: which verdicts rest on re-runs at tip
  vs inherited seals (itemized; no silent inheritance).
- Candidate-comparison requirements: what a Forge-vs-XMate decision
  would require (protocol definition, NOT execution).
- FULL107 readiness: definition trace + outstanding work list.
- Merge impact (branch→main deltas per workstream) and repin impact
  (none: pin unchanged throughout R9–R17).
- Remaining authoritative decisions list (Coordinator-owned).
- Evidence dir `forge-protocol2-bridge/wsr18-promotion/` (8 files).

## Out of Scope

- Declaring Forge the production provider (explicitly forbidden).
- Merging, pushing, PR creation, engine repin, FULL107 execution.
- New tests/code (read-only assembly; verification commands only).

## Ownership

Single writer: this session on this branch/worktree only. All other
worktrees read-only (verified clean before/after; this WS writes only
its evidence dir).

## Dependencies

- R9–R17 seals; R14 admission; Lab successor seals (read-only refs).

## Hard Gates

- No provider selection language anywhere (packet is input, not decision).
- UNKNOWN stays UNKNOWN; seal-derived verdicts labeled as such with
  per-item rationale (never upgraded silently).
- Every identity freshly verified (rev-parse), never copied from memory.

## Forbidden Shortcuts

Per AGENTS.md §2 plus: no promotion by implication ("ready" ≠
"selected"); no FULL107 arithmetic without definition; no merge
dry-run presented as merge approval.

## Evidence Requirements

Machine-readable identities JSON + human decision packet + limitations
+ outstanding-work lists. All SHAs freshly verified at seal time.

## Persistence

Scoped validation, state update, focused local commit. End with §13
handoff + campaign checkpoint.

## Stop Conditions

Stop when the packet is complete and committed, or a genuine blocker
appears (unverifiable identity → escalate, do not fabricate).
