# WS234 Validation

## Identity / hygiene
- Branch `ws234/forge-s3-mechanic-partials-20260915`, audit base `e6e5083c7a2` / `c06cab05` (= HEAD at start, verified via rev-parse).
- Implementation `bc347e62255` (Cleave marker + Aftermath script + 3 test files, 1802 insertions).
- Worktree clean at seal (to be verified pre-publish).

## Baseline (pre-fix, DIRECTLY_VERIFIED)
- Wash Away dual-SA without AlternativeCost.Cleave (isCleave false); Find//Finality Finality without K:Aftermath (isAftermath false, zone Hand).
- S3 denominator re-derived at lock: 27 SUPPORTED (CODE_DERIVED) / 2 PARTIAL / 0 MISSING; 0/29 dedicated behavior; micro 13/17.

## Qualification (post-fix, DIRECTLY_VERIFIED)
- Ws234CleaveAftermathTest 5/5 PASS (variants, identity, costs, targeting, hand/grave, exile).
- Ws234S3CardBehaviorTest 20/20 enabled PASS (7 disabled NOT_RUN with blockers).
- WS234S3BridgeTest 11/11 enabled PASS (1 disabled Veyran UNKNOWN).
- Full bridge 161/161 PASS (150 historical +11 new, 635.8s).
- Sim engine 71/71 PASS (gamesimulationtests + game).

## Not run (explicitly)
- 7 sim trigger tests disabled NOT_RUN (harness gap, bridge covers 4/7, Veyran/Harmonic + Crawler draw PARTIAL).
- Veyran bridge disabled NOT_RUN (UNKNOWN adjudicated).
- FULL107 NOT_RUN. Lab recomputation not owned. 15 PARTIAL card branches NOT_RUN with blockers (see census).
- Fresh-process S3: NOT_RUN (in-JVM + full bridge process isolation retained via 161/161; S3 behavior is in-JVM engine-direct, same as historical sim).

## Verdict
Validation supports S3 partial closure (F1+F2 FULL, F6 IMPLEMENTED 17/17, 14/29 SUPPORTED with behavior) with S1/S2/S4 retained. No promotion/freeze claim.
