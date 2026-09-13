# WS87 IMPLEMENTATION

## Decision (adjudicated, XHIGH read-first)

Replace the `String` fingerprint with a typed validity model. Every required
input is gated: any read failure returns INVALID (null digest + failure
reason), never a hashed error marker. Equality/no-mutation proof requires
BOTH valid. Failures record UNKNOWN and never block gameplay. No wire,
Rules, visibility, or capability change.

## Production changes (forge-protocol2-bridge only)

1. `InternalAuditFingerprint.java` (rewritten, 143 → ~350 lines):
   - NEW `Fingerprint` value type: `{valid, digest, failure}` with
     `valid()` ctor gate (`[0-9a-f]{64}` only), `invalid()` requires reason,
     `toAuditString()` = digest when valid else `UNAVAILABLE:<failure>`
     (never hex), `sameValidIdentity(a,b)` = both valid && digest equal
     (null-safe false), `toString()` = audit string.
   - `ofGame(Game,BridgeSession):Fingerprint` (was `String`): null game →
     invalid `no-game`. Each required read — turn, phase, priority, active,
     timestamp, game-over, registry, player stats (id/life/poison/lost),
     Hand/Battlefield/Graveyard/Exile/Command (sorted multisets),
     Library (encounter order, no sort), stack (encounter order, no sort),
     SHA-256 — is individually try/caught to `invalid(<family>:<SimpleName>)`;
     nothing hashed on failure. Old `safeTurn/safePhase→"?"`,
     `internal-unreadable` hashing, `shaOrSentinel`, `internal-hash-error`
     paths removed. `"none"` (null player) and `"null"` (null phase) remain
     as explicit absent-value encodings inside still-guarded blocks.
   - 16 package-private test seams mirroring `StateProjection`:
     failTurn/Phase/Priority/Active/Timestamp/GameOver/Registry/PlayerStats/
     Hand/Battlefield/Graveyard/Exile/Command/Library/Stack/Sha +
     `clearTestSeams()`. Game untouched; flags checked inside fingerprint only.
   - Scope docs updated: invalid carries null digest + `UNAVAILABLE:*` audit
     that never satisfies the 64-hex wire gate.
2. `DecisionFrame.java`: `preStateHash:String` → `Fingerprint` (doc: invalid
   means UNKNOWN, never identity). No other semantic change.
3. `BridgeSession.java`:
   - `SubmitOutcome.pre/postStateHash:String` → `Fingerprint`; `applied()` /
     `rejected()` signatures updated; `rejected` still sets pre==post by
     construction but now typed (consumers must check `sameValidIdentity`).
   - `parkFrame`: `Fingerprint preHash=ofGame` (invalid → park with UNKNOWN,
     still audit + handoff.take).
   - `submit`: `Fingerprint preHash` captured AFTER consume/audit authority
     (order unchanged); failure → UNKNOWN pre, still audit/handoff/settle.
   - `waitForSettle`: pre/post `Fingerprint`; post capture failure → UNKNOWN
     post, still `applied` with Rules `executionOk` + revision rendezvous.
   - `currentHash():Fingerprint` (was String + `internal-unavailable`
     fallback): just `ofGame` (never throws) + defensive invalid fallback.
     Rejected paths keep original MALFORMED/WRONG_ACTOR/STALE/etc. codes —
     never PROJECTION_FAILED for fingerprint.
   - `frameDetails` / `settleDetails`: `Fingerprint` params; store
     `toAuditString()` (UNAVAILABLE on invalid, never valid identity) +
     `*_failure` reason fields on invalid. `submitDetails` still hash-free.
4. `BridgeEngine.java`, `StateProjection.java`, `ObservationDigest.java`:
   UNTOUCHED. Wire pre/post remain observation digests behind the 64-hex
   gate; fingerprint never enters wire path.

## Ordering preserved

Library + stack preserve encounter order (no sorting — explicit). Unordered
zones remain deterministic sorted multisets (documented bounded internal
identity). No CPL, Rules-Core, card, legality, redaction, capability,
engine/RNG pin change.

## Test changes

- `BridgeEngineTest.java` (7 sites): `String` → `Fingerprint`; every
  equality gated on `valid` first + `sameValidIdentity`; applied asserts
  check `pre/post.valid` + `digest` inequality (was bare String
  assertEquals/NotEquals).
- `HiddenInformationNoninterferenceTest.java`: `parkTestFrame` uses real
  `ofGame` Fingerprint (was `"test-pre"` dummy); library/timestamp/launched
  asserts gated on `valid` + `digest` comparison.
- NEW `InternalAuditFailClosedTest.java` (11 tests): per-zone (5) invalid;
  Library invalid; stack invalid; turn/phase/priority/active/timestamp/
  game-over/registry/player-stats/sha/no-game invalid; invalid-vs-valid and
  invalid-vs-invalid `sameValidIdentity==false`; rejected UNKNOWN (wrong-actor
  pre invalid, never PASS, retry still succeeds); valid determinism;
  Library reorder changes valid digest; stack content changes valid digest
  (order preserved, no sort); fingerprint failure does not block legal pass
  (applied true, observation digests still hex, audit UNAVAILABLE, no hashed
  markers); no internal identity reaches wire (wire hex, no UNAVAILABLE/
  internal-/pre_hash/post_hash/preStateHash, actor derivability + non-actor
  withheld).
- Deleted temporary `Ws87PreFixReproductionTest.java` after DIRECTLY_VERIFIED
  run (evidence in PRE_FIX_REPRODUCTION.md).

## What was NOT changed

Forge Rules-Core, cards, legal-action enumeration, projection redaction,
ObservationDigest, wire schema, capability flags, engine/RNG pins, CPL,
exports (still fail closed), audit retention (still internal-only). No second
Rules engine; no widening; no semantic replay.

## Reviewer follow-up

Fresh-context reviewer PARTIAL → must-fix (3 unproven seams) resolved:
`testTurnPhaseReaderFailuresAreInvalid` now covers `failGameOver`,
`failRegistry`, `failPlayerStats` at runtime. Full module re-run 84/84 PASS.
