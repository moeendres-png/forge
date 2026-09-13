# WS89 VALIDATION — fresh integrated-candidate runtime (DIRECTLY_VERIFIED)

All runs: offline Maven (`-o`), JDK 21, `TMPDIR=/home/moeen/tmp`, `MAVEN_OPTS=-Djava.io.tmpdir=/home/moeen/tmp/opencode`, `FORGE_ENGINE_SHA=aa5c00aa32dfd40e213f223f8fd400c43daabb24` + `-Dforge.engine.sha=aa5c00aa32dfd40e213f223f8fd400c43daabb24`. No historical PASS promoted.

## Identities

- `CLEAN_BASE` = `a9a95db6662c2d28814390a9c0c2f986e39aa8b4`
- `RULES_CORE_AUTHORITY` = `aa5c00aa32dfd40e213f223f8fd400c43daabb24`
- `CLEAN_TERMINAL_START` = `bc29afd8409cace599bfa7e95c8af8df0120df89`
- `H4F_BRIDGE_SOURCE` = `17ddc26f9bf2702bf0befe542a363f5fd87474e4`
- `BRIDGE_SOURCE_SUCCESSOR` (technical) = `ede2b987f938e7da045121903ce961e7532dbc90`
- Remote terminal head: filled after safe_push (evidence commit on top of technical).

## 1. Bridge compilation — PASS

- Command: `mvn -o -pl forge-protocol2-bridge -am -DskipTests -Dcheckstyle.skip=true compile -Djava.io.tmpdir=/home/moeen/tmp/opencode`
- Result: BUILD SUCCESS. Reactor 6/6 (Parent, Core, Game, AI, Gui, Bridge). Bridge: 18 sources compiled. Only note: deprecation warning on `ExternalPlayerController.java` (retained `divideShield` override of deprecated boundary) — no error.
- Proves Q4/Q5: bridge-only compat suffices; zero Rules-Core edits.

## 2. Full bridge-module tests — 87/87 PASS

- Command: `mvn -o -pl forge-protocol2-bridge -am -Dcheckstyle.skip=true test -Djava.io.tmpdir=/home/moeen/tmp/opencode -Dforge.engine.sha=aa5c00aa32dfd40e213f223f8fd400c43daabb24`
- Result: `Tests run: 87, Failures: 0, Errors: 0, Skipped: 0` (TestSuite).
- Per class (testng-results.xml): BridgeEngineTest 25, ProtocolTest 22, HeadlessGuiFailClosedTest 10, HiddenInformationNoninterferenceTest 14, InternalAuditFailClosedTest 11, BridgeProtocolProcessTest 2, Ws89EngineIdentityTest 3 (new). Baseline was 84/84 at WS87; +3 is the legitimate WS89 integration-specific identity test (explained, not a regression).
- Required six preserved: HiddenInformationNoninterferenceTest ✓, InternalAuditFailClosedTest ✓, BridgeEngineTest ✓, ProtocolTest ✓, HeadlessGuiFailClosedTest ✓, BridgeProtocolProcessTest ✓ (all present and green).

## 3. Privacy gates — all PASS (fresh)

- `PRINCIPAL_NONINTERFERENCE` = PASS (adversary + sensitivity + swap-invisibility tests green).
- `OPPONENT_HAND_IDENTITY_NONINTERFERENCE` = PASS (`testOpponentHandIdentityNoninterference`).
- `HIDDEN_LIBRARY_NONINTERFERENCE` = PASS (`testHiddenLibraryIdentityNoninterference`).
- `FACE_DOWN_NONINTERFERENCE` = PASS (`testUnauthorizedFaceDownNoninterference`).
- `PRIVATE_DATA_DEPENDENCY_IN_EXTERNAL_DIGEST` = 0, `PROCESS_LOCAL_IDENTITY_IN_EXTERNAL_DIGEST` = 0 (digest hashes sanitized projection only; `testNoInternalIdentityReachesExternalResponses` PASS), `NON_ACTOR_DIGEST_EXPOSURE` = 0 (`testNonActorDigestWithheld` PASS).
- `OBSERVATION_DIGEST` = PASS (canonical-form + derivable + determinism tests green).

## 4. Internal audit gates — all PASS (fresh, bounded)

- `INTERNAL_AUDIT_FAILURE_SEMANTICS` = PASS (`testFingerprintFailureDoesNotBlockGameplay`, `testRejectedNoMutationRefusesInvalidFingerprints`, per-zone/stack/library/turn-phase failure-is-invalid tests green; audit failure never blocks gameplay / never becomes Rules denial).
- `INTERNAL_FINGERPRINT_READ_FAILURE_VALID_HEX` = 0, `INVALID_FINGERPRINT_CAN_PROVE_NO_MUTATION` = 0 (`testInvalidCannotCompareAsValidIdentity`, `testRejectedNoMutationRefusesInvalidFingerprints` green; invalid evidence UNKNOWN, never PASS).
- `INTERNAL_AUDIT_FINGERPRINT` = PASS_BOUNDED_SAME_PROCESS (`testValidFingerprintDeterministicInOneProcess` green; cross-process identity NOT claimed).

## 5. Legal-action / decision boundary — preserved

- `REVISION_ACTION_BINDING_PRESERVED` = PASS (`testRevisionBindingAndObservationDigests` + lifecycle/pass/shutdown tests green).
- `LEGAL_ACTION_SEMANTICS_CHANGED` = NO (compat is fail-closed taxonomy-preserving; `testCapabilitiesAreConservative`, `testUnsupportedCallbacksThrow`, `testEnumerationFailureFailsClosed` green).
- No first-option / random / default yes-no / AI fallback / fabricated answer / GUI default / requested-option-filtering legality (negative-control + fail-closed tests green).
- `H4B_FORGE_RECOMMENDATION` = PARTIAL (unchanged; no qualification change here).
- `CAPABILITY_CHANGE` = 0, `BEHAVIOR_CREDIT_CHANGE` = 0, `SECOND_RULES_ENGINE` = 0.

## 6. Clean Rules tests — fresh on integrated candidate — all PASS

- H01: `mvn -o -pl forge-gui-desktop -am test -Dtest=H01CloneHumilityTest -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true ...` → `Tests run: 3, Failures: 0, Errors: 0` (`testHumilityFirstNoCopyThenDiesAfterHumilityLeaves` = HUMILITY_FIRST PASS; `testCloneFirstCopyPersistsThroughHumility` = CLONE_FIRST PASS; `testNoHumilityControl` = NO_HUMILITY PASS).
- WS76: `-Dtest=Ws76ImmediateConcessionTest` → `Tests run: 5, Failures: 0, Errors: 0` (priority 2P control, sync-concede 2P/4P/5P sweep, 4P post-leave coherence — all PASS) → `IMMEDIATE_CONCESSION` = PASS.
- Regression battery: `-Dtest='Ws59A04ReplacementOrderingTest,Ws59C01CostPitchTest,Ws59G04ConcessionTest,ReplacementHandlerTest,H01CloneHumilityTest,Ws76ImmediateConcessionTest'` → `Tests run: 18, Failures: 0, Errors: 0` (3+2+4+1+3+5) → bounded clean regression PASS.

## 7. Rules-Core preservation — PASS

- `git rev-parse aa5c00aa:<path>` vs `git hash-object <path>` identical for both `GameAction.java` and `PhaseHandler.java` on the integrated HEAD → `CLEAN_RULES_CORE_PRESERVED` = PASS, `GAMEACTION_BLOB_PRESERVED` = PASS, `PHASEHANDLER_BLOB_PRESERVED` = PASS.
- Staged-name check at technical commit: only `forge-protocol2-bridge/*` + root `pom.xml`; zero Rules-Core files.

## 8. Engine-identity handshake — PASS

- Filtered `forge-protocol2-bridge/target/classes/bridge.properties`: `bridge.artifact=forge-protocol2-bridge`, `bridge.version=2.0.15-SNAPSHOT`, `engine.commit=aa5c00aa32dfd40e213f223f8fd400c43daabb24` (built with `FORGE_ENGINE_SHA=aa5c00aa...`).
- `Ws89EngineIdentityTest` 3/3 + `testBuildPropertyIdentityFallback` + `testSeparateProcessQualification` green.

## 9. Not run / not claimed

- `SEMANTIC_REPLAY_DIGEST` = NOT_IMPLEMENTED (no replay code; grep empty; `InternalAuditFingerprint` is not replay identity).
- `FULL107_BEHAVIOR` = NOT_RUN. `ARCHITECTURE_FREEZE` = NOT_CLAIMED. `PRODUCTION_PROVIDER` = NOT_SELECTED.
- Checkstyle: `checkstyle:check` executed with no violations in all reactor builds above (`-Dcheckstyle.skip=true` was set for test runs per WS89 build strategy to isolate runtime proof; the earlier `compile` reactor also ran checkstyle clean; repo-wide `validate` without `-am` cannot resolve `${revision}` offline — reactor-usage artifact, not a violation).
