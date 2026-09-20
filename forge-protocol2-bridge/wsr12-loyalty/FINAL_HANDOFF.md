# R12 Final Handoff — Forge Loyalty-Cost Surface (S3 29/29)

## Source Lock

- Repo `moeendres-png/forge`, branch
  `wsr12/forge-loyalty-cost-surface-20260920`, base `fafbbf1d` (R11 tip).
- Worktree `/home/moeen/code/ws-r12-forge-loyalty-cost-surface-20260920`.

## Work Completed

- Production (additive-only): from-source forced counter-cost visits
  in BridgeCostDecisionMaker (Human mirrors minus confirms) + 2-line
  classifier allowlist + doc. No new frame kinds; X via X_ANNOUNCE.
- Enabled R11a's 2 disabled Jeska tests: [0] triple 2→6, ultimate X=2
  to p2 with SBA-death payment proof. Family 4/4 green.
- Full sim/bridge regression green, checkstyle 0.
- Evidence `forge-protocol2-bridge/wsr12-loyalty/` (7 files).

## New Findings

- Loyalty labels carry cost text only (`(0)`/`(-X)`); picks must match
  those, not ability prose.
- Jeska-at-2-loyalty ultimate ends her (SBA) — payment proof, not failure.
- Sorcery-speed activations need held MAIN1 priorities (never pass p1
  past them); REPLACEMENT_ORDER/CONFIRM must be answered.

## Changes

- MODIFY: BridgeCostDecisionMaker.java (2 visits), 
  ExternalPlayerController.java (allowlist + doc).
- MODIFY: WsR11JeskaBridgeFamilyTest.java (2 enabled, tight drivers).
- ADD: evidence dir (7 files). Nothing else.

## Tests / Evidence

- DIRECTLY_VERIFIED: Jeska 4/4 + full suites (sim 445, bridge 187/187).
- Nothing MODELED/SYNTHETIC.

## PASS / FAIL / UNKNOWN

- Loyalty surface CLOSED | Jeska SUPPORTED | S3 29/29 SUPPORTED (FULL) |
  FULL107 NOT_RUN | ARCHITECTURE_FREEZE NOT_CLAIMED |
  PRODUCTION_PROVIDER NOT_SELECTED.

## Remaining Blockers

1. Publication push (canonical safe_push + authorization; not attempted).
2. Non-from-source counter costs (still fail-closed by design).

## Outputs

`forge-protocol2-bridge/wsr12-loyalty/`: WORKSTREAM_CONTRACT.md,
STATE.md, R12_LOYALTY_ROOT_CAUSE.md, VALIDATION.md, EVIDENCE_SEAL.json,
FINAL_HANDOFF.md, CAMPAIGN_CHECKPOINT.md.

## Dependencies Unblocked

- S3 FULL (29/29): the Forge actual-card denominator is closed.
- Any planeswalker loyalty ability is now bridge-playable (from-source).

## Exact Next Action

Commit package → verify HEAD → hand Coordinator (publication +
Lab-side successors). Campaign continues (fresh ownership).
