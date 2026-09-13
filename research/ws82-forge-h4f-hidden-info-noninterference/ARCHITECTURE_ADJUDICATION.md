# WS82 ARCHITECTURE ADJUDICATION (XHIGH, read-first, no edits)

Method: read-first source inspection + Foundry adjudicator at XHIGH (CODE_DERIVED).
No production edits before this file. `git status` clean at adjudication.

## Callsite inventory (verified by grep + file reads)

| # | Location | Computation / disposition |
|---|---|---|
| I1 | StateHash.java:26 `ofGame` | turn/phase/priority/active/timestamp/over + per-player life/poison/lost + Hand/Battlefield/Graveyard/Exile/Command/Library as `identityHashCode:name` sorted + stack `sourceName@activatingPlayer` sorted → sha256. Mixed hidden+public+process-local. |
| I2 | StateHash.java:103 `sha256` | hex SHA-256 or `"sha-unavailable"`. Also used for deckHash (separate domain). |
| I3 | StateProjection.java:164-173 `bridgeMeta` | `state_hash=ofGame` catch→`"unavailable"`; non-actor→null. EXTERNAL via get_game_state. |
| I4 | BridgeEngine.java:541 `getGameState` | carrier for I3. EXTERNAL. |
| I5 | BridgeEngine.java:767-768 `submitResponse` | `decision.pre_state_hash/post_state_hash=outcome.pre/post` (ofGame values). EXTERNAL on all applied submit/pass/mulligan. |
| I6 | BridgeEngine.java:760 `submitResponse` | `payload.state=gameState(session,null)` public-only — proves I5 carries more than accompanying state. |
| I7 | BridgeEngine.java:790 `submitResponse` | `payload.bridge=bridgeMeta(session,submitterId)` — null when submitter≠next actor, but I5 hashes remain. |
| I8 | BridgeSession.java:350 `parkFrame` | `preHash=ofGame` → DecisionFrame.preStateHash. INTERNAL. |
| I9 | BridgeSession.java:475,496,513 `submit/waitForSettle` | pre/post via ofGame → SubmitOutcome.applied. INTERNAL until I5 exposes. |
| I10 | BridgeSession.java:418-473,529-535 `currentHash` | ofGame catch→`"unavailable"` → rejected outcomes. INTERNAL ONLY — rejected path uses BridgeProtocol.fail which serializes no hash fields (BridgeProtocol.java:145-169). |
| I11 | BridgeSession.java:557-588 audit details | pre_hash/post_hash in AuditEvent. INTERNAL ONLY — exports disabled (BridgeEngine.java:151-159,171-176,794-800). |
| I12 | DecisionFrame.java:78,92 / BridgeSession.java:60-61 | carriers, internal until I5. |
| I13 | BridgeEngine.java:351 `importDeck` deckHash | sha256 of own deck list. EXTERNAL but separate domain, not game hidden state. |
| I14 | BridgeEngine.java:571-574 `getLegalActions` | no hash. Correct negative inventory. |
| I15 | Tests (BridgeEngineTest, BridgeProtocolProcessTest:387-388) | same-JVM ofGame equality/inequality as mutation evidence. Test-only audit semantics. |

## Answers

1. External responses containing state_hash: (a) `get_game_state.payload.bridge.state_hash` (actor-only, else null); (b) `submit_action/pass_priority/resolve_mulligan.payload.decision.pre_state_hash/post_state_hash` (always on applied). `get_legal_actions` carries none; rejected submits carry none; exports carry nothing.
2. state_hash NOT authoritative. BridgeSession.java:408-480 validates actor_id/optionId/actionType/revision + lifecycle/status/consume before computing any hash and without reading any client hash. BridgeEngine pre-checks never parse a hash. Revision/action binding authoritative independently. Must preserve.
3. Tests relying on StateHash as internal mutation evidence: BridgeEngineTest testLifecyclePassAndShutdown (:129,:153,:158), testStartingPlayerExternalChoice (:281,:295), testConstructedUnsupportedTargeting (:390,:412), testEnumerationFailureFailsClosed (:493,:498), testNonzeroManaMakesUnsupported (:566,:571), testDispatchFieldValidation (:613,:659), testMulliganMissingKeepRejected (:679,:686); BridgeProtocolProcessTest wire inequality (:387-388). All same-JVM audit semantics; none proves cross-principal noninterference.
4. Private fields influencing actor-visible metadata (via I3/I5): opponent Hand names; all Library names; face-down Battlefield/Exile true identity + hidden stack sources (raw getName vs gated projection); game timestamp; System.identityHashCode. Projection redacts to `<hidden>`/`<face-down>`/`[]`/`<face-down spell>` (StateProjection.java:345-354,356-385,475-483,510-536) but hash covers raw values.
5. System.identityHashCode influences EXTERNAL (StateHash.java:52 → I3/I5). Not merely internal. Breaks cross-process determinism and derivability.
6. Incorrectly sorted for claimed identity: Library (StateHash.java:57) and Stack (:71). Order is semantic (draw order, LIFO). Sorting collides permutations and misses reorder mutations. Hand/Battlefield/Graveyard/Exile/Command as sorted multisets acceptable.
7. Principal-visible digest CANNOT be derived from sanitized projection today (NO): needs identityHashCodes (never emitted), opponent hand names (`<hidden>`), library contents (`[]`), face-down true names (markers), timestamp (absent). Digest is not a function of observation — the defining defect.
8. Current StateHash MUST be split/renamed: ObservationDigest (pure function of canonical sanitized projection, principal-scoped, deterministic) vs AuditDigest/InternalAuditFingerprint (bridge-local, may include hidden+identityHashCode+timestamp+ordered zones/stack, internal-only). Single name conflates incompatible contracts.
9. Failure behavior: observation → fail closed (throw → PROJECTION_FAILED/INTERNAL_ERROR, no hash field, never sentinels "no-game"/"unreadable"/"hash-error"/"sha-unavailable"/"unavailable"/"?"); internal audit → namespaced sentinel allowed internally but wire gate must assert ^[0-9a-f]{64}$ before any emit; MessageDigest failure on observable path → INTERNAL_ERROR, never "sha-unavailable" as digest. Current bridgeMeta catch-emits-string (StateProjection.java:167-168) is the live D-violation; currentHash fallback safe only because I10 drops it.
10. Replay claim: NONE justified and none made (correct). replay_supported=false, seeds rejected, exports disabled, point digest not a log. SEMANTIC_REPLAY_DIGEST=NOT_IMPLEMENTED.
11. Smallest repair with no second visibility/Rules model: cease emitting ofGame on I3/I5; compute emitted digests strictly from already-sanctioned canonical projection bytes for the entitled principal (no new fields, no redaction change, no engine change); confine ofGame (renamed, Library/Stack order-preserving) to I8-I11 with 64-hex wire gate. Surface: StateHash.java, StateProjection.java:151-178, BridgeEngine.java:751-792. BridgeSession internal shape unchanged. Q3 tests migrate to renamed audit identity.
12. Tests needed: (1) hidden-varying/observation-fixed hash equality (opponent hand, library, face-down); (2) derivability (recompute from sanitized bytes); (3) process-local invariance; (4) order-sensitivity split (library/stack reorder → observation unchanged, audit changed); (5) timestamp isolation; (6) sentinel absence + fail-closed; (7) cross-principal confinement + hash-free legal_actions + closed exports.

## Concerns

- A (side channel): FAIL — hidden multiset → opaque sha256 on I3/I5; submitter learns post covering opponent hidden cards even when bridge.state_hash==null. Actor-scoping narrows but does not remove.
- B (process-local): FAIL — identityHashCode → wire.
- C (ordering): FAIL — Library+Stack sorted, permutation collisions break mutation-evidence claim.
- D (sentinels): FAIL — wire leakage via "unavailable" + ofGame sentinels as if digests; only I10 path safe.

## Identity taxonomy (ruling)

- OBSERVATION: principal-scoped per-observer; inputs exactly canonical bytes of that principal's sanitized gameState+bridgeMeta-minus-hash; deterministic cross-process; no identityHashCode/timestamp/hidden names; failure throws; wire as bridge.state_hash + decision.pre/post (per-principal or withheld).
- INTERNAL_AUDIT: bridge-local whole-game; may include hidden zones, ordered Library+Stack, timestamp, identityHashCode; process-local allowed, order-sensitive; internal sentinels allowed, never serialized without 64-hex gate; AuditEvent/DecisionFrame/SubmitOutcome carriers only.
- REPLAY: none. No claim; exports stay disabled.

## Formal decision

- root_cause_class: PROVIDER_ADAPTER_DEFECT
- first_failing_boundary: StateHash.ofGame zone/stack loop (StateHash.java:46-72) crossing at bridgeMeta.state_hash (StateProjection.java:166) and submitResponse pre/post (BridgeEngine.java:767-768)
- contract verdict: FAIL
- minimal_repair_surface: StateHash.java:23-115, StateProjection.java:151-178, BridgeEngine.java:751-792 (+ tests)
- repair_priority: HIGH
- Verdict: FAIL (adjudicated, CODE_DERIVED)
