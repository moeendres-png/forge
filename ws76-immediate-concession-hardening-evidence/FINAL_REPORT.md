# WS76 — Forge Immediate-Concession Engine Hardening — Final Report

## Source Lock

See `SOURCE_LOCK.md`. Validated head: `2a49cef3c4078577d3b47cc0d3139c9f93a2bc7f`
(fix + tests; clean-head verified). Accepted pin unchanged:
`a9a95db6662c2d28814390a9c0c2f986e39aa8b4` (no repin claimed).

## Work Completed

1. **Phase 1 — engine-direct reproduction**: new `Ws76ImmediateConcessionTest`
   (5 tests, zero provider frames) drives synchronous native-seam concession
   from inside the production CLEANUP `autoPassCancel` sweep. Pre-fix: 4/5
   fail — CME at `PhaseHandler.onPhaseBegin:411` (4P/5P/coherence) and silent
   sweep truncation (2P). Priority control passes throughout.
2. **Phase 2 — root cause**: `ENGINE_DEFECT` — live `ingamePlayers`
   fail-fast iterator vs synchronous structural removal in `Game.onPlayerLost`
   across a controller-decision boundary. Full chain + audit in `ROOT_CAUSE.md`.
3. **Phase 3 — systemic fix**: snapshot traversal at the two engine sites
   crossing a synchronous decision boundary with the live list
   (`PhaseHandler:411`, `GameAction.checkGameOverCondition`). No deferral, no
   CME swallowing, no card-name logic, no provider changes.
4. **Phase 4 — multiplayer controls**: immediate leave/counts, native 800.4a
   (owned leave; stolen return), no further decisions to the departed, turn/
   priority coherence to the next turn — 2P/4P/5P green.
5. **Phase 5 — WS67 regression**: 8/8 green, assertions unmodified.
6. **Phase 6 — broader regression**: full reactor 397 run / 0 fail / 6 skipped
   (pre-existing); checkstyle 0 violations.

## New Findings

- F1. Same defect, two symptoms: CME when the leaver is not last in sweep
  order; silent skipping of all later sweep steps when the leaver is at
  index 0 (`cursor == size`). The 2P skip also drops the survivor's autopass
  reset — the snapshot fixes both.
- F2. The engine's priority machinery already tolerates active-player loss
  (counter-driven loop + `PhaseHandler:1058-1064` handoff); only
  iterator-based sweeps across decision boundaries were unsafe.
- F3. `GameAction.checkGameOverCondition` aliased the live list across
  replacement-handler callbacks before removing — same latent hazard class,
  hardened with the same one-line snapshot.

## Changes

See `CHANGES.md` (2 engine files + 1 test file; validated head `2a49cef`).

## Tests / Evidence

- `prefix-failure-report.txt/xml` (pre-fix 4 failures incl. CME stacks)
- `postfix-ws76-report.txt` (post-fix 5/5)
- `cleanhead-18of18-report.xml` (18/18 from committed `2a49cef`)
- Full-reactor console summary: 397 run, 0 failures, 0 errors, 6 skipped.

## PASS / FAIL / UNKNOWN

**WS76_ENGINE_CONCESSION_HARDENING=PASS** — immediate synchronous concession
is safe engine-direct in and outside live sweeps; WS67 remains green.

## Remaining Blockers

- None in-scope. `ComprehensiveRulesSection104` not runnable in this
  container (pre-existing TestNG/PowerMock discovery condition).
- `/tmp` tmpfs full (other workstreams' checkouts) — evidence kept in-worktree.

## Outputs

`ws76-immediate-concession-hardening-evidence/`: `SOURCE_LOCK.md`,
`PRE_FIX_REPRODUCTION.md`, `ROOT_CAUSE.md`, `CHANGES.md`,
`REGRESSION_MATRIX.md`, `FINAL_REPORT.md` (this file),
`WORKSTREAM_STATE.yaml`, pre/post/clean-head reports.

## Dependencies Unblocked

- WS68's `ENGINE_DEFECT` packet (sweep re-entrancy CME) is remediated at the
  engine level; single-phase synchronous concession transport is now viable
  without deferral (requalification still owned by a future wave).

## Exact Next Action

Coordinator: adjudicate WS76 against this evidence; schedule any
G04-concession transport re-entry and successor-candidate promotion
separately. Do not start Full107, Freeze, or provider selection from WS76.

---
WS76_ENGINE_CONCESSION_HARDENING=PASS
PRE_FIX_REPRO=PASS
ROOT_CAUSE=ENGINE_DEFECT
IMMEDIATE_CONCESSION=PASS
LIVE_ITERATION_SAFETY=PASS
MULTIPLAYER_LEAVE_CLEANUP=PASS
FORGE_SUCCESSOR_CANDIDATE=2a49cef3c4078577d3b47cc0d3139c9f93a2bc7f
PRIOR_SUCCESSOR_CANDIDATE=22e7f17befee8fcce0684fa6d006f29afdb5c280
ACCEPTED_FORGE_PIN=a9a95db6662c2d28814390a9c0c2f986e39aa8b4
SUCCESSOR_ACCEPTED=NO
BEHAVIOR_CREDIT=0/107
FULL107=NOT_RUN
ARCHITECTURE_FREEZE=NOT_CLAIMED
PRODUCTION_PROVIDER=NOT_SELECTED
