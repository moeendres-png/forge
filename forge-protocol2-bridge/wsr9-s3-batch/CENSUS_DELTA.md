# R9 Census Delta — S3 denominator 29 (frozen WS231)

## Baseline at R8 tip (per wsr8 seal, verified in-tree)

- SUPPORTED: 19 (WS234 14 + R6 CARD_07/CARD_16 + WS236 Veyran/Harmonic + R8 CARD_14)
- PARTIAL: 10 (04, 08, 09, 11, 13, 15, 18, 22, 27, 29)

## Closed by R9 (all DIRECTLY_VERIFIED strict runtime tests)

| card | fixture | branch | proof |
|---|---|---|---|
| Shriekmaw | CARD_18 | Evoke 1B + sac + Fear | WsR9EvokeFearFamilyTest 5/5 (sim) |
| Bolt Bend | CARD_22 | ChangeTargets + ReduceCost | WsR9RetargetBridgeFamilyTest 5/5 (bridge) |
| Flare of Duplication | CARD_13 | sac-alt-cost + copy-new-targets | WsR9RetargetBridgeFamilyTest 5/5 (bridge) |
| Finale of Revelation | CARD_15 | X>=10 shuffle/draw/untap/exile | WsR9FinaleX10BridgeFamilyTest 2/2 (bridge) |

- SUPPORTED: 23. PARTIAL: 6 (04 Kediss redirect, 08 Jeska, 09 Magma Opus,
  11 Wear//Tear Fuse, 27 Path mana/scry, 29 Boseiju chapters/transform).
- MISSING: 0 (unchanged). No construction-only credit anywhere.

## Explicitly remaining PARTIAL (successor backlog, never silent)

- CARD_04 Kediss (redirect/combat), CARD_08 Jeska, CARD_09 Magma Opus
  (card-specific reuse; generic divided seam already WS217), CARD_11 Fuse
  combined, CARD_27 mana/scry, CARD_29 chapters/transform.
- Sim-seam retarget stays NOT_RUN (AI_DEFECT: ChangeTargetsAi CantPlayAi,
  chooseNewTargetsFor null) — bridge is the authoritative seam.
- 12-tapped-land untap framing UNSUPPORTED by bridge 128-cap design.
- No-max-hand-size static CODE_DERIVED (same SubAbility chain as proven
  untap/exile).
