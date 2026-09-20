# R13 Final Handoff — Bridge Choice-Mana + Scry Surface (Path via Bridge)

## Source Lock

- Repo `moeendres-png/forge`, branch
  `wsr13/forge-choicemana-scry-surface-20260920`, base `adab6bb1`
  (R12 tip).
- Worktree `/home/moeen/code/ws-r13-forge-choicemana-scry-surface-20260920`.

## Work Completed

- Diagnosed Path scry silence: bridge pool payments discarded spent
  mana (payingMana empty post-payment); spent watchers never fired.
- Production (additive-only): record spent mana on the SA (AI mirror);
  scry-arrangement bottom-subset framing via GENERIC_SELECTION (2^N,
  128-cap, canonical order, always framed).
- Path bridge family 3/3 green (shared scry + 2 negatives).
- Full sim/bridge regression green, checkstyle 0.
- Evidence `forge-protocol2-bridge/wsr13-choicemana-scry/` (7 files).

## New Findings

- R13SYNC proved choice-mana production works; the gap was purely
  spent-recording. Earlier "tap-drop" readings were poll races +
  misattributed ManaCost arithmetic ({2}{R} Song, {R} overpay).
- Scry-1 frames exactly 2 options (keep/bottom); no auto-defaults.
- One background full-suite attempt died mid-run without output
  (environmental flake); foreground rerun clean 13:49.

## Changes

- MODIFY: ExternalPlayerController.java (payingMana recording,
  arrangeForScry, docs).
- ADD: WsR13PathBridgeFamilyTest.java (3 tests) + evidence dir.
- MODIFY: nothing else. No rules/engine changes.

## Tests / Evidence

- DIRECTLY_VERIFIED: Path 3/3 + full suites (sim 445, bridge 190/190).
- Nothing MODELED/SYNTHETIC.

## PASS / FAIL / UNKNOWN

- Tap-drop repaired | scry surface CLOSED | Path bridge SUPPORTED |
  S3 29/29 retained FULL | FULL107 NOT_RUN |
  ARCHITECTURE_FREEZE NOT_CLAIMED | PRODUCTION_PROVIDER NOT_SELECTED.

## Remaining Blockers

1. Publication push (canonical safe_push + authorization; not attempted).
2. Scry-N > 7 equivalent (128-cap; fail-closed by design).

## Outputs

`forge-protocol2-bridge/wsr13-choicemana-scry/`: WORKSTREAM_CONTRACT.md,
STATE.md, R13_SPENT_SCRY_ROOT_CAUSE.md, VALIDATION.md,
EVIDENCE_SEAL.json, FINAL_HANDOFF.md, CAMPAIGN_CHECKPOINT.md.

## Dependencies Unblocked

- Every bridge pool payment now records spent mana (spent-trigger and
  mana-hook correctness for all cards).
- Scry cards are bridge-playable (bounded N).

## Exact Next Action

Commit package → verify HEAD → hand Coordinator (publication +
Lab-side successors). Campaign continues (fresh ownership).
