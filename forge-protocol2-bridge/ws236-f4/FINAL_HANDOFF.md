# WS236 Final Handoff — F4 You-Filtered SpellCast-Family Discriminator (COMPLETE pending publication)

## Source Lock

- Repo `moeendres-png/forge`, branch
  `ws236/forge-f4-spellcast-discriminator-20260916`.
- Audit base = WS234 terminal `9a1e3fe94975dba3e81a3079ad9a9d123c141775` /
  `d1cb6ce47db3472409dd61d1159777cc42998fec` (HEAD+TREE verified, clean
  tree at lock; no rebase, no refresh). See `SOURCE_LOCK.json/md`.

## Work Completed

- Reconstructed the WS234 F4 gap from sealed bytes: three born-disabled,
  never-runtime-observed probes (sim Veyran+Bolt, sim Harmonic+Bolt, bridge
  Veyran+Divination), adjudicated UNKNOWN with zero lifecycle observations.
- Designed and executed the smallest A/B discriminator around the
  SpellCast trigger lifecycle (collection → activator → waiting/priority →
  stack → identity → resolution → observable P/T), using target-free
  Divination on both seams plus Clever Lumimancer family controls and
  Veyran/Harmonic-double-other probes.
- Established the first divergence boundary: engine dispatch matches and
  queues (collection proof + simultaneous-queue proof + activations=2);
  each test seam omits the engine-owned ordering step
  (`addAllTriggeredAbilitiesToStack` / parked `TRIGGER_ORDER`).
- Classified root cause HARNESS_DEFECT with runtime evidence; refuted the
  You-filter engine semantic (collection matches; strict Kaervek under the
  legacy drain also fails 20→20, proving the strand is family-wide).
- Applied bounded test-only corrections in two NEW files (engine-owned
  ordering in the sim drain; answer engine-offered TRIGGER_ORDER in the
  bridge drain). Zero production diffs, zero existing-test diffs, zero
  card-name hacks, zero manual injections.
- Obtained actual-card-driven behavior evidence: Veyran +2/+2 (own-doubled)
  and Harmonic +1/+1 on BOTH seams; Veyran-doubles-other +4/+4 and
  Harmonic-doubles-other +4/+4 in sim.
- Sealed this 11-file evidence namespace; validated green; committed
  locally (push blocked by permission policy — see Remaining Blockers).

## F4 Gap Reconstruction

See `F4_INPUT_RECONSTRUCTION.md`. (PASS.)

## Discriminator

See `DISCRIMINATOR_PLAN.md`, `DISCRIMINATOR_OBSERVATIONS.json`. A/B with
Divination; controls Lumimancer/Harmonic/other-doubling; lifecycle
observations at collection, stack, simultaneous-queue, suppression,
ordering, resolution boundaries.

## First Divergence Boundary

ESTABLISHED: between engine dispatch (proven: queue + activations) and
stack ordering (omitted by each test seam). Not UNKNOWN.

## Root Cause

HARNESS_DEFECT — sim-seam legacy drain omits the engine's simultaneous
ordering step (family-wide, not You-specific); bridge-seam probe drain left
the correct TRIGGER_ORDER frame unanswered. Engine, bridge production, and
card scripts: NO_DEFECT. See `ROOT_CAUSE.json/md`.

## Remediation

Test-only, in new files (detailed above). No engine/script/bridge repair
warranted or performed. Family-general (any trigger), regression-tested.

## Actual-Card Evidence

`ACTUAL_CARD_RESULTS.json`: 7/7 PASS rows (Veyran ×2 seams, Harmonic ×2
seams, Lumimancer control, Veyran-other, Harmonic-other), full chain
actual card → real cast → Forge matching → authoritative instances →
ordering → stack → resolution → observable P/T + spell resolution.

## Changes

- ADD `forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws236/
  Ws236F4SpellcastDiscriminatorTest.java` (5 behavior tests).
- ADD `forge-protocol2-bridge/src/test/java/forge/bridge/WS236F4BridgeTest.
  java` (2 behavior tests).
- ADD `forge-protocol2-bridge/ws236-f4/` (11 evidence files, this package).
- MODIFY: nothing else (verified via `git status`/`git diff --stat`
  pre-commit).

## Tests / Evidence

- New: sim 5/5 PASS; bridge 2/2 PASS.
- Retention (family-adjacent, positive decision value only):
  Ws234S3CardBehaviorTest 21/21 enabled PASS (7 disabled NOT_RUN preserved);
  WS234S3BridgeTest 11/11 enabled PASS (1 disabled preserved, file
  untouched). Totals 26/26 sim, 13/13 bridge, 0 failures/errors.
- Checkstyle 0 violations (both modules).
- NOT_RUN: full 161/161, sim-engine 71/71, FULL107 (no decision value;
  see IMPACT_ADJUDICATION).
- No manual trigger/outcome injection; no card-name hack; no second Rules
  engine. See `REGRESSION_RESULTS.json`, `VALIDATION.md`.

## Impact Adjudication

S1/S2/S4/replay/RNG/fallback/scoping: NO_IMPACT (no production code
touched). WS234 evidence preserved byte-identical. Evidence-quality note
recorded (non-strict Kaervek sim assert passes vacuously; sealed bytes
untouched). See `IMPACT_ADJUDICATION.json/md`.

## PASS / FAIL / UNKNOWN

- WS236_F4_DISCRIMINATOR = COMPLETE.
- ROOT_CAUSE = HARNESS_DEFECT.
- VEYRAN = PASS. HARMONIC = PASS. ACTUAL_CARD_BEHAVIOR = PASS.
- F4 = PASS (Veyran/Harmonic You-filtered SpellCast-family base + doubling,
  own- and other-triggers, actual-card-driven on both seams).
- OTHER_15_PARTIAL_BRANCHES_TOUCHED = NO. FULL107 = NOT_RUN.
- RAW_GIT_PUSH_USED = NO. ARCHITECTURE_FREEZE = NOT_CLAIMED.
- PRODUCTION_PROVIDER = NOT_SELECTED.

## Remaining Blockers

1. Publication push: local commit(s) ready; `git push` is denied by the
   executor permission policy (no raw push). Coordinator/next session with
   push approval must run canonical safe_push (dry-run then actual), fetch,
   and verify local/remote HEAD+TREE equality.
2. Successor WS236-S1 (recommended, separate workstream): own CARD_17
   re-probe (strict Kaervek assert + corrected drain) and family-wide
   migration of the sim drain pattern; explicitly NOT done here to keep
   WS236 bounded and WS234 bytes sealed.

## Outputs

`forge-protocol2-bridge/ws236-f4/`: SOURCE_LOCK.json, SOURCE_LOCK.md,
F4_INPUT_RECONSTRUCTION.md, DISCRIMINATOR_PLAN.md,
DISCRIMINATOR_OBSERVATIONS.json, ROOT_CAUSE.json, ROOT_CAUSE.md,
ACTUAL_CARD_RESULTS.json, IMPACT_ADJUDICATION.json, IMPACT_ADJUDICATION.md,
REGRESSION_RESULTS.json, VALIDATION.md, EVIDENCE_SEAL.json,
FINAL_HANDOFF.md (this file).

## Dependencies Unblocked

- F4 branch no longer UNKNOWN: Lab recomputation may consume this package
  (Veyran/Harmonic SUPPORTED with behavior for the probed paths).
- The simultaneous-ordering sim pattern is documented for all future
  trigger-family harnesses (WS236-S1).

## Exact Next Action

Commit bounded WS236 changes → validate exact committed HEAD (clean
tree, HEAD/TREE recorded) → hand to push-approved session for canonical
safe_push + remote equality verification → terminate.

Terminal fields: WS236_F4_DISCRIMINATOR COMPLETE | ROOT_CAUSE
HARNESS_DEFECT | VEYRAN PASS | HARMONIC PASS | F4 PASS |
OTHER_15_PARTIAL_BRANCHES_TOUCHED NO | FULL107 NOT_RUN | RAW_GIT_PUSH_USED
NO | ARCHITECTURE_FREEZE NOT_CLAIMED | PRODUCTION_PROVIDER NOT_SELECTED.
