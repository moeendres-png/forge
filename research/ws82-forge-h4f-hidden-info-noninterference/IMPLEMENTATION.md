# WS82 IMPLEMENTATION

## Decision (adjudicated, XHIGH read-first)

Split the conflated `StateHash.ofGame` into two identities; rewire external
surfaces to the observation identity; confine the privileged fingerprint
internally. No new visibility/Rules logic; projection redaction byte-identical.

## Production changes (forge-protocol2-bridge only)

1. NEW `ObservationDigest.java`: principal-visible identity. `digestOf(gameState,
   metaMinusHash)` = SHA-256 hex over canonical JSON (`{"state":..,"bridge":..}`;
   object keys sorted recursively, arrays order-preserved, nulls explicit).
   Throws `BridgeProjectionException` on any failure (never a sentinel);
   refuses preimages containing `state_hash` (no recursion). Imports only
   Gson + java.security: no engine reads, no Rules, no visibility decisions.
2. NEW `InternalAuditFingerprint.java` (hardened rename of `StateHash`):
   same-process mutation evidence only. Keeps hidden zones/timestamp/
   identityHashCode with truthful scope docs (NOT cross-process, NOT replay,
   NEVER serialized). Library + stack preserve encounter order (were sorted);
   unordered zones stay sorted multisets. Sentinels namespaced `internal-*`
   (never satisfy the 64-hex wire gate).
3. DELETE `StateHash.java` (zero remaining src/main references).
4. `StateProjection.java`: `bridgeMeta` actor `state_hash` =
   `ObservationDigest.digestOf(sanitized gameState, meta-minus-hash)`;
   non-actor stays null. NEW `bridgeMeta(session, observer, gameState)` reuses
   the emitted bytes (no recursion, no reinterpretation). NEW
   `observationDigest(session, observer)` for submit pre/post. No more
   `"unavailable"` fabrication: failures throw.
5. `BridgeSession.java`: `SubmitOutcome` gains `pre/postObservationDigest`
   (internal `pre/postStateHash` retained for audit). `submit()` captures the
   submitter pre digest AFTER actor/revision/option/actionType validation and
   BEFORE consume/audit/handoff (failure => rejected PROJECTION_FAILED,
   engine untouched, option unconsumed). `waitForSettle` captures post digest
   (null on failure). `currentHash()` sentinel => `internal-unavailable`.
   Binding remains hash-independent and authoritative.
6. `BridgeEngine.java`: `getGameState` projects once, shares bytes between
   state and bridge digest. `submitResponse` emits observation pre/post behind
   a 64-hex gate (else PROJECTION_FAILED, no fabrication); bridge guarded with
   PROJECTION_FAILED mapping. `deckHash` uses `sha256Hex` (fail-closed throw;
   separate own-deck domain, not game hidden state).
7. `DecisionFrame.java`: one-line scope doc on `preStateHash`. No semantic change.

## Test changes

- `BridgeEngineTest.java`: mechanical `StateHash.ofGame` =>
  `InternalAuditFingerprint.ofGame` (8 hunks, assertions identical).
- NEW `HiddenInformationNoninterferenceTest.java` (14 tests): Pairs A-C
  noninterference, Pair D sensitivity, determinism, non-actor withholding,
  failure fail-closed, internal order split (+observation stability),
  timestamp isolation, same-name object-swap invisibility, envelope
  derivability, canonical-form unit, launched revision-binding +
  observation-digest submit proofs, launched pre-capture failure fail-closed.

## What was NOT changed

Forge Rules-Core, card implementations, legal-action enumeration, projection
redaction, capability flags (`legal_actions_supported=false`,
`action_submission_supported=false`), engine/RNG pins, CPL (no edits),
exports (still fail closed), audit retention (still internal-only).

## Ordering notes (found while implementing, both fixed)

- Pre digest is captured before `option.consume()` so the legal-actions array
  in the preimage matches the actor envelope (consumed options are filtered).
- Post digest uses the submitter's post-settlement view (no pending decision
  when the next frame belongs to another actor): same principal, same
  preimage definition, still observation-only.
