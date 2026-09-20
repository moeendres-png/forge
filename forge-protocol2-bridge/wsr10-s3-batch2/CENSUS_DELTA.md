# R10 Census Delta — S3 denominator 29 (frozen WS231)

## Baseline at R9 tip (verified in-tree)

- SUPPORTED: 23 (WS234 14 + R6 07/16 + WS236 Veyran/Harmonic + R8 14
  + R9a 18 + R9b 22/13 + R9c 15)
- PARTIAL: 6 (04, 08, 09, 11, 27, 29)

## Closed by R10 (all DIRECTLY_VERIFIED strict runtime tests)

| card | fixture | branch | proof |
|---|---|---|---|
| Kediss, Emberclaw Familiar | CARD_04 | multiplayer redirect | WsR10KedissBridgeFamilyTest 4/4 |
| Magma Opus | CARD_09 | divided 2+2/tap/token/draw + Treasure + fail-closed | WsR10MagmaBridgeFamilyTest 3/3 |
| Path of Ancestry | CARD_27 | mana + sharing-type scry + 2 negatives + ETB | WsR10PathSimFamilyTest 4/4 |

- SUPPORTED: 26. PARTIAL: 3 (08 Jeska, 11 Wear//Tear Fuse, 29 Boseiju).
- MISSING: 0 (unchanged). No construction-only credit anywhere.

## Explicitly remaining PARTIAL (R11 backlog, never silent)

- CARD_08 Jeska (planeswalker loyalty/abilities/ultimate).
- CARD_11 Wear//Tear Fuse combined.
- CARD_29 Boseiju chapters + transform.
- Bridge Path scry seam UNSUPPORTED (BRIDGE_DEFECT ×2 filed; sim seam
  authoritative for this card).
