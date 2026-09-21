# R9 Final Handoff — Forge S3 Partial Batch (R9a Evoke + R9b Retarget + R9c X10)

## Source Lock

- Repo `moeendres-png/forge`, branch
  `wsr9/forge-s3-partial-batch-20260919`, base `ca655ecd` (R8 tip).
- Worktree `/home/moeen/code/ws-r9-forge-s3-partial-batch-20260919`.

## Work Completed

- R9a Evoke/Fear (Shriekmaw/CARD_18): 5 strict sim tests green first run
  (TEST_GAP, no defect).
- R9b Retarget (Bolt Bend/CARD_22, Flare/CARD_13): sim AI proven
  incapable (AI_DEFECT preserved); closed via bridge 5/5 (framed choice,
  auto-bind, resolution retarget + damage, R-not-free cost, sac copy,
  decline fail-closed).
- R9c X>=10 (Finale/CARD_15): X_ANNOUNCE + 12-mana + shuffle/draw/untap/
  exile 2/2 green (Mox redesign honors the 128-cap bound).
- Retention + full sim/bridge regression green, checkstyle 0.
- Evidence `forge-protocol2-bridge/wsr9-s3-batch/` (9 files).

## New Findings

- CR 117.7a generic-only reduction ({3}{R}-3={R}): my "free" premise was
  wrong; engine + bridge correct (overpay tolerated, decline aborts).
- Bridge cast-target: single-candidate engine auto-bind; multi-candidate
  framed choice (both DIRECTLY_VERIFIED).
- Bridge MANA over-frames taps beyond exact cost (observability wart,
  follow-up; game-correct).
- Priority-round choreography rule for stacked-response tests (responder
  acts before a full pass round; caster retains priority for back-to-back
  casts); targeted mana answers required (Forest for G).
- 12-tapped-land untap = 1586 subsets > 128-cap → fail-closed by design.

## Changes

- ADD 3 test files (12 tests). ADD evidence dir (9 files).
- MODIFY: nothing else. No production/rules/script diffs.

## Tests / Evidence

- DIRECTLY_VERIFIED: 12 new strict + retention + full suites (counts in
  VALIDATION.md). No EXTERNALLY_RULE_VALIDATED beyond Oracle/CR text
  derivation; nothing MODELED/SYNTHETIC.

## PASS / FAIL / UNKNOWN

- R9a/R9b/R9c CLOSED | S3 10→6 PARTIAL (23/29 SUPPORTED) |
  FULL107 NOT_RUN | ARCHITECTURE_FREEZE NOT_CLAIMED |
  PRODUCTION_PROVIDER NOT_SELECTED.

## Remaining Blockers

1. 6 PARTIAL cards (04/08/09/11/27/29) with named harnesses — successor.
2. Sim-seam AI retarget inability (AI_DEFECT; bridge is the seam).
3. Publication push (canonical safe_push + authorization; not attempted).

## Outputs

`forge-protocol2-bridge/wsr9-s3-batch/`: WORKSTREAM_CONTRACT.md,
STATE.md, R9A/B/C_*_ROOT_CAUSE.md, CENSUS_DELTA.md, VALIDATION.md,
EVIDENCE_SEAL.json, FINAL_HANDOFF.md.

## Dependencies Unblocked

- S3 successor starts at 6 PARTIAL with exact harnesses (Fuse, redirect,
  Booth chapters/transform, mana/scry, Jeska, Magma-reuse).
- Lab recomputation may consume the R9 package (4 new SUPPORTED cards).

## Exact Next Action

Fill full-suite counts into VALIDATION.md → commit package → verify HEAD
→ hand Coordinator (publication decision + successor scope). Then continue
campaign per assignment (next workstream, fresh ownership).
