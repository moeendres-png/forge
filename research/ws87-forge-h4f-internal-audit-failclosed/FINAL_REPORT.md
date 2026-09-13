# WS87 FINAL REPORT — FORGE H4F INTERNAL AUDIT FAIL-CLOSED HARDENING

## Source Lock

- Repository moeendres-png/forge, branch
  ws87/forge-h4f-internal-audit-failclosed-20260913, audit base
  4342a7981fd556b48c6555707ac53d8bceb14f4a (tree 262fb29f...), verified clean.
- Remote source ws82/forge-h4f-hidden-info-noninterference-20260913,
  WS82 validated head 2beaa6a5, H4F candidate base 4753bb7c.
- No master use, no repin, no CPL edits. Result: SOURCE_LOCK=PASS.

## Coordinator Finding Reproduced

InternalAuditFingerprint hashed `?` and `internal-unreadable:*` markers into
ordinary 64-hex; callers could not distinguish complete vs incomplete state;
two unreadable states could compare equal as false no-mutation evidence —
contradicting INTERNAL_AUDIT_FAILURE_FAILS_CLOSED=PASS.

## Read-First Findings

XHIGH adjudicator verdict FAIL (CODE_DERIVED, EVIDENCE_PIPELINE_DEFECT):
first failing boundary InternalAuditFingerprint.java:48-100, validity lost at
`:100`. Production never compares (tests only); production never authorizes on
fingerprints (actor/revision/option + observation digest govern); wire
pre/post are ObservationDigest (never fingerprint); smallest model is typed
`{valid,digest,failure}` (throw would elevate evidence to Rules denial;
bare non-hex String relies on undisciplined comparators). Full 10 answers in
ARCHITECTURE_ADJUDICATION.md. READ_FIRST_XHIGH_ADJUDICATION=PASS.

## Pre-Fix Reproduction

Temporary `Ws87PreFixReproductionTest` forced Hand `Card.getName()` NPE via
`currentState=null` while Game stayed usable (4 players, registry intact).
Audit-base result: `DEFECT: Hand read failure still yielded valid-looking hex:
3fd4f449...` (1 run, 1 failure). DIRECTLY_VERIFIED. PRE_FIX_DEFECT_REPRODUCED=PASS.
Superseded post-fix by `InternalAuditFailClosedTest` (evidence retained in
PRE_FIX_REPRODUCTION.md).

## Architecture Decision

Typed `Fingerprint` result (valid ⇒ 64-hex digest; invalid ⇒ null digest +
failure + `UNAVAILABLE:*` audit). Every required field (turn/phase/priority/
active/timestamp/game-over/registry/player-stats/Hand/Battlefield/Graveyard/
Exile/Command/Library/stack/SHA) returns invalid on failure — never hashed.
`sameValidIdentity` requires BOTH valid. Failures record UNKNOWN and never
block park/submit/settle; only the audit gate may fail. Library/stack stay
order-sensitive (no sort); unordered zones stay sorted multisets. No wire,
Rules, visibility, or capability change.

## Work Completed

Rewrote InternalAuditFingerprint to fail-closed Fingerprint + 16 seams;
DecisionFrame/SubmitOutcome/parks/captures/audit to Fingerprint + UNKNOWN;
validity-gated all BridgeEngineTest (7 sites) and
HiddenInformationNoninterferenceTest (5 sites) equalities; added 11-test
InternalAuditFailClosedTest; reviewer must-fix (game-over/registry/
player-stats runtime proof) resolved.

## Changes

- `forge-protocol2-bridge/src/main/java/forge/bridge/InternalAuditFingerprint.java`
- `forge-protocol2-bridge/src/main/java/forge/bridge/BridgeSession.java`
- `forge-protocol2-bridge/src/main/java/forge/bridge/DecisionFrame.java`
- `forge-protocol2-bridge/src/test/java/forge/bridge/BridgeEngineTest.java`
- `forge-protocol2-bridge/src/test/java/forge/bridge/HiddenInformationNoninterferenceTest.java`
- NEW `forge-protocol2-bridge/src/test/java/forge/bridge/InternalAuditFailClosedTest.java`
- `research/ws87-forge-h4f-internal-audit-failclosed/` (7 files)

## Internal Failure Tests

InternalAuditFailClosedTest 11/11 PASS: per-zone (5) invalid; Library
invalid; stack invalid; turn/phase/priority/active/timestamp/game-over/
registry/player-stats/sha/no-game invalid; invalid-vs-valid and
invalid-vs-invalid never prove identity; rejected UNKNOWN (never PASS, retry
succeeds); valid deterministic; Library reorder changes digest; stack content
changes digest (order preserved); fingerprint failure does not block legal
pass; no internal identity reaches wire.

## Privacy Regression Tests

- HiddenInformationNoninterferenceTest 14/14 PASS (A-C noninterference, D
  sensitivity, determinism, non-actor withheld, failure fail-closed, order
  split, timestamp isolation, same-name swap, derivability, canonical form,
  launched binding + digests, pre-capture fail-closed).
- BridgeEngineTest 25/25 PASS (lifecycle, starting-player, cast, targeting,
  hidden adversary, enumeration, projection, mana, dispatch, mulligan,
  callbacks, visibility, flashback, R9, diagnostics).
- Full forge-protocol2-bridge module 84/84 PASS (incl. Protocol 22,
  HeadlessGui 10, separate-process qualification incl. wire pre!=post).

## Protocol Regression Tests

- ProtocolTest 22/22 PASS; HeadlessGuiFailClosedTest 10/10 PASS;
  BridgeProtocolProcessTest 2/2 PASS (separate-process handshake/import/
  create/start/choice/keeps/priority/pass/negatives/state/exports/shutdown,
  stdout JSON purity, pinned engine SHA).
- Wire pre/post remain ObservationDigest hex behind 64-hex gate; no
  UNAVAILABLE/internal-/pre_hash/post_hash/preStateHash in responses; actor
  derivability + non-actor withheld re-proven.

## PASS / FAIL / UNKNOWN

- SOURCE_LOCK=PASS, READ_FIRST_XHIGH_ADJUDICATION=PASS,
  PRE_FIX_DEFECT_REPRODUCED=PASS,
  INTERNAL_FINGERPRINT_READ_FAILURE_VALID_HEX=0,
  INVALID_FINGERPRINT_CAN_PROVE_NO_MUTATION=0,
  INTERNAL_AUDIT_FAILURE_SEMANTICS=PASS,
  INTERNAL_AUDIT_FINGERPRINT=PASS_BOUNDED_SAME_PROCESS,
  OBSERVATION_DIGEST=PASS, PRINCIPAL_NONINTERFERENCE=PASS,
  OPPONENT_HAND_IDENTITY_NONINTERFERENCE=PASS,
  HIDDEN_LIBRARY_NONINTERFERENCE=PASS, FACE_DOWN_NONINTERFERENCE=PASS,
  PRIVATE_DATA_DEPENDENCY_IN_EXTERNAL_DIGEST=0,
  PROCESS_LOCAL_IDENTITY_IN_EXTERNAL_DIGEST=0,
  INTERNAL_AUDIT_IDENTITY_EXTERNAL_EXPOSURE=0,
  REVISION_ACTION_BINDING_PRESERVED=PASS, LEGAL_ACTION_SEMANTICS_CHANGED=NO,
  HIDDEN_INFORMATION_WIDENING=0, SECOND_RULES_ENGINE=0, CAPABILITY_CHANGE=0,
  ENGINE_RULES_PIN_CHANGE=0, BEHAVIOR_CREDIT_CHANGE=0,
  SEMANTIC_REPLAY_DIGEST=NOT_IMPLEMENTED, REMOTE_PERSISTENCE=PASS (after
  safe_push verification), WS87_SUCCESSOR_ACCEPTED=NO,
  ARCHITECTURE_FREEZE=NOT_CLAIMED, PRODUCTION_PROVIDER=NOT_SELECTED.

## Validated Technical Successor

Terminal commit on ws87/forge-h4f-internal-audit-failclosed-20260913 (see
safe_push verification; local == remote). WS82 base preserved; WS87 hardening
is bounded successor-hardening only.

## Remote Terminal Head

Filled after safe_push (local == remote verified).

## Remaining Blockers

None technical. Coordinator adjudicates successor acceptance separately.

## Outputs

research/ws87-forge-h4f-internal-audit-failclosed/: SOURCE_LOCK.md,
COORDINATOR_FINDING.md, ARCHITECTURE_ADJUDICATION.md, PRE_FIX_REPRODUCTION.md,
IMPLEMENTATION.md, VALIDATION.json, FINAL_REPORT.md.

## Dependencies Unblocked

INTERNAL_AUDIT_FAILURE_FAILS_CLOSED gate unblocked for coordinator successor
review; WS82 privacy/observation/binding evidence preserved.

## Exact Next Action

Coordinator successor acceptance/integration (no PR per workstream rules; do
not merge candidate branch; do not repin).
