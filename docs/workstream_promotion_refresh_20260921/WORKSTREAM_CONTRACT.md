# Workstream Contract — R19 Promotion Refresh + Push-Ready (2026-09-21)

## Objective

Refresh the R18 promotion decision packet with R15–R19 + Lab-6P
identities (add Lab R19 six-player + R20 CI lane; reverify Forge
R9–R18 SHAs fresh; update stale 6P-parity statement), then run
push-ready verification (clean trees, seals-at-HEAD, evidence index).
No selection, no freeze, no merge, no push.

## Source Lock

- Base: Forge R18 tip `9c41bff280910c143ab16134f4a8539108d1f220`.
- This branch: `wsr19/forge-promotion-refresh-20260921`.
- This worktree: `/home/moeen/code/ws-r19-forge-promotion-refresh-20260921`.
- Repo `moeendres-png/forge`. Lab repo read-only (identity reads only).
- No push/merge/rebase. No engine/pin changes.

## In Scope

- New dir `forge-protocol2-bridge/wsr19-promotion/`: refreshed
  IDENTITIES.json + DECISION_PACKET.md + REFRESH_NOTES.md (delta vs R18).
  R18 dir untouched (seal preserved).
- Fresh reverification: Forge branch HEADs/trees (R9–R18); Lab R19
  (`107db189`) + R20 (`0526a798`) HEADs/trees; engines/pins unchanged.
- Push-ready verification: all known worktrees clean; all sealed HEADs
  unmoved; packet JSON valid; evidence index complete.
- Scoped local commit + §13 handoff.

## Out of Scope

- Any promotion selection, Freeze, Provider, merges, pushes,
  publication, CR/Release-Notes, FULL107 execution, repins, reruns of
  sealed suites (seals stand at unmoved HEADs), R18 seal edits.

## Ownership

Single writer: this session on this branch/worktree only. All other
Forge/Lab worktrees read-only. Lab worktrees: identity reads only.

## Hard Gates

- Every SHA in the refreshed packet freshly reverified at refresh
  time; no carry-over without reverify (carry-over marked with R18
  provenance only if byte-identical reverify passes — else fail closed).
- Packet remains decision INPUT (selects nothing, freezes nothing).
- No verdict upgrades; limitations carried forward + new R19/R20 notes.
- Push-ready means VERIFIED-READY only; actual push needs explicit
  user approval (withheld by default).

## Forbidden Shortcuts

Per policy plus: no silent seal extension to new HEADs; no merge-impact
number reuse without recompute-or-seal-label; no editing R18 files.

## Evidence Requirements

Reverify log + refreshed packet diff + push-ready checklist results.

## Stop Conditions

Complete when packet refreshed + push-ready verified + committed, or a
genuine terminal blocker (authority conflict → park + escalate).
