# WS89 INTEGRATION ARCHITECTURE — one explicit integrated candidate

## Objective

Clean accepted Rules Core + accepted bridge module + only compatibility adjustments strictly required by the newer clean lineage = one explicit integrated candidate. No wholesale H4F merge, no rebase, no old root-pom copy, no WS82/WS87 research-directory copy.

## Base

- Start: `bc29afd8409cace599bfa7e95c8af8df0120df89` (clean successor terminal + evidence). Preserves accepted clean Rules-Core successor plus its evidence.
- Transplant source: `forge-protocol2-bridge/` exact contents from `17ddc26f9bf2702bf0befe542a363f5fd87474e4` (37 paths; verified 37/37 BYTE_IDENTICAL immediately after `git checkout 17ddc26 -- forge-protocol2-bridge` via `git hash-object` vs `git rev-parse <base>:<path>`).
- Root wiring: +1 module line in clean `pom.xml` (position after `forge-gui-desktop`, matching H4F ordering). All other clean root-pom content preserved (`${revision}`, `versionCode 2.0.15`, `tag HEAD`).

## Authority boundaries (unchanged by integration)

- Rules authority remains Forge. The bridge is a GPL-side adapter/controller boundary over the real pinned engine: all legality, costs, targeting, priority, combat, continuous/replacement semantics stay in Forge (`GameAction`, `PhaseHandler`, `GameActionUtil`, `Cost*`, `SpellAbility`, `Card`, `Player`, `Combat`).
- `ExternalPlayerController`: native interception boundary. Priority/mulligan/starting-player rendezvous via parked `DecisionFrame`s; every other discretionary callback throws `BridgeUnsupportedDecision` (fail-closed; no AI, no first-option, no random, no default yes/no, no GUI default, no requested-option filtering as parallel legality). Combat-damage boundary updated mechanically (Q5) with identical fail-closed taxonomy.
- `BridgeSession`: per-game state, actor/revision/option binding, audit trail; protocol thread never holds monitor while waiting. Revision/action binding preserved (proven by `testRevisionBindingAndObservationDigests` + lifecycle tests, fresh 87/87).
- `StateProjection`: principal-scoped redaction ONLY. Opponent hands `"<hidden>"`, libraries empty, face-down `"<face-down>"` unless `CardView.canBeShownTo/canFaceDownBeShownTo`; null observer = public-only; required reads throw `BridgeProjectionException` (no defaults). Untouched by compat.
- `ObservationDigest`: SHA-256 hex over already-sanitized projection + metadata (sorted keys, canonical form). Performs no visibility/legality decisions; never a second engine. Untouched by compat.
- `InternalAuditFingerprint`: privileged same-process mutation evidence (may see hidden zones/identity hashes); never wire-serialized. WS87 fail-closed semantics (`valid` vs `invalid(null digest)`; equality requires both valid; invalid evidence is UNKNOWN, never PASS; audit failure never becomes Rules denial). Untouched by compat.
- `BridgeEngine` F3 gate: requires 40-hex engine identity; observability only without it. Build/run use `FORGE_ENGINE_SHA=aa5c00aa...` (verified filtered `bridge.properties`: `engine.commit=aa5c00aa...`).
- Unsupported decisions fail closed. `H4B_FORGE_RECOMMENDATION` remains PARTIAL (no qualification change here).

## Privacy posture (preserved)

- Principal noninterference + opponent-hand-identity + hidden-library + face-down noninterference, all fresh PASS (14/14 `HiddenInformationNoninterferenceTest`).
- `PRIVATE_DATA_DEPENDENCY_IN_EXTERNAL_DIGEST=0`, `PROCESS_LOCAL_IDENTITY_IN_EXTERNAL_DIGEST=0`, `NON_ACTOR_DIGEST_EXPOSURE=0` (by construction — digest hashes sanitized output only — plus `testNonActorDigestWithheld`, `testNoInternalIdentityReachesExternalResponses` PASS).
- `OBSERVATION_DIGEST=PASS` (canonical-form/derivability/determinism tests PASS).
- No visibility broadened for compatibility (compat touches Maven coordinates + combat-damage controller boundary only).

## Capabilities (bounded, unchanged)

- `legal_actions_supported` / `action_submission_supported` NOT widened (`testCapabilitiesAreConservative` PASS). `CAPABILITY_CHANGE=0`. No broad capability credit. No second rules engine (`SECOND_RULES_ENGINE=0`).
- Semantic replay NOT implemented (`SEMANTIC_REPLAY_DIGEST=NOT_IMPLEMENTED`; `InternalAuditFingerprint` is not replay identity).

## Identities at runtime

- `RULES_CORE_AUTHORITY=aa5c00aa32dfd40e213f223f8fd400c43daabb24` (exact tested technical commit).
- `BRIDGE_SOURCE_SUCCESSOR=<fresh WS89 validated technical commit>` (filled at commit time; evidence commit follows).
- Historical `a37a865a...` retained ONLY as superseded pin inside `WS-A1D-H4F-STATE.md` history text and as the negative control in `Ws89EngineIdentityTest`; never reported as current authority.
- `VersionInfo`/`bridge.properties`/runtime handshake verified: with `FORGE_ENGINE_SHA=aa5c00aa...`, filtered `bridge.properties` carries `engine.commit=aa5c00aa...`; `Ws89EngineIdentityTest` (3/3) + `testBuildPropertyIdentityFallback` + `testSeparateProcessQualification` PASS.

## What was NOT done (explicit non-goals)

- No Forge master merge; no PR; no CPL update; no `candidate/forge-2.0.14-h4f-integration-20260912` move.
- No Full107 behavior run (`FULL107_BEHAVIOR=NOT_RUN`); no Architecture Freeze claim; no Production Provider selection.
- No `research/ws82-*` / `ws87-*` copy; new WS89 evidence created instead (this directory).
