# R11 Census Delta — S3 denominator 29 (frozen WS231)

## Baseline at R10 tip (verified in-tree)

- SUPPORTED: 26. PARTIAL: 3 (08, 11, 29).

## Closed by R11 (all DIRECTLY_VERIFIED strict runtime tests)

| card | fixture | branch | proof |
|---|---|---|---|
| Wear // Tear | CARD_11 | Fuse combined + half + fail-closed | WsR11FuseBridgeFamilyTest 3/3 |
| Boseiju // Branch | CARD_29 | 3 chapters + transform + P/T tracking | WsR11BoseijuBridgeFamilyTest 3/3 |
| Jeska, Thrice Reborn | CARD_08 | loyalty entry + SBA-zero (activations blocked) | WsR11JeskaBridgeFamilyTest 2/4 green + 2 disabled |

- SUPPORTED: 28. PARTIAL: 1 (CARD_08 Jeska activations — loyalty-cost
  DecisionFrame surface missing; 2 disabled tests with blocker R11a-1).
- MISSING: 0 (unchanged). No construction-only credit anywhere.

## S3 final statement

28/29 SUPPORTED with behavior; 1 PARTIAL with a named production-surface
blocker (bridge loyalty-cost framing). S1/S2/S4 retained per WS234/R8;
FULL107 NOT_RUN; no freeze/provider claims.
