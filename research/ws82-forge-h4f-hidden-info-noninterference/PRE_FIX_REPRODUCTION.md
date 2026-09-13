# WS82 PRE-FIX REPRODUCTION (audit base 4753bb7c72e)

Test: forge-protocol2-bridge/src/test/java/forge/bridge/HiddenInformationNoninterferenceTest.java
Run: mvn -o -pl forge-protocol2-bridge test -Dtest=HiddenInformationNoninterferenceTest
Env: TMPDIR=/home/moeen/tmp MAVEN_OPTS=-Djava.io.tmpdir=/home/moeen/tmp/opencode, JDK 21, offline.
Result: Tests run: 9, Failures: 6, Errors: 0 (DIRECTLY_VERIFIED runtime).

Real engine, real cards, unlaunched constructed games; only DecisionFrame parking
bypassed via reflection (projection + digest paths fully real).

## Failures (privacy defects, all with byte-identical principal projection)

1. testOpponentHandIdentityNoninterference (Pair A): p2 hand Serra Angel->Grizzly Bears
   (same count). gameState(p1) identical; bridgeMeta-minus-hash identical;
   state_hash bb702d.. vs cf7672.. DIFFER. => opponent-hand side channel CONFIRMED.
2. testHiddenLibraryIdentityNoninterference (Pair B): p2 library 5xPlains->5xIsland.
   gameState(p1) identical (libraries always []); state_hash 31a37f.. vs 63a071.. DIFFER.
   => hidden-library side channel CONFIRMED.
3. testUnauthorizedFaceDownNoninterference (Pair C): p1 battlefield face-down
   Grizzly Bears->Squire (order-preserved), observer p2 unauthorized.
   gameState(p2) identical (`<face-down>`); state_hash 0d68b4.. vs 7b6231.. DIFFER.
   => face-down side channel CONFIRMED (name and/or identityHashCode).
4. testDigestFailureNotFabricated (Concern D): with failRequiredReadsForTests set,
   bridgeMeta(p1) returned state_hash 29d8c0.. instead of throwing. => fabricated
   plausible digest on projection failure CONFIRMED.
5. testInternalAuditLibraryOrderBlindness (Concern C): reorder top library card to
   bottom; StateHash.ofGame identical (95469b.. == 95469b..). => order-only mutation
   invisible to internal audit CONFIRMED.
6. testProcessLocalIdentityInfluencesStateHash (Concern B): two identically built
   games (same names/counts, no frames so no UUIDs); gameState equal;
   StateHash differs. => process-local identity in externally exposed digest CONFIRMED.

## Passes (controls/guards, hold pre- and post-fix)

- testVisibleBattlefieldChangeSensitivity (Pair D): visible +1 Plains changes
  projection AND digest (not constant).
- testDigestDeterminismSameState: same session projected twice identical.
- testNonActorDigestWithheld: non-actor/public bridgeMeta state_hash null, revision -1.

Gate: PRE_FIX_HIDDEN_INFO_LEAK_REPRODUCED=PASS.
