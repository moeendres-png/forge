# WS87 ARCHITECTURE ADJUDICATION (XHIGH, read-first, no production edits)

Method: read-first source inspection + Foundry adjudicator at XHIGH
(CODE_DERIVED). No production edits before this file. `git status` clean at
adjudication (HEAD 4342a798, tree 262fb29f).

## 1. Which read failures can currently be hidden inside a 64-hex fingerprint?

Hidden (embedded in preimage, then SHA-256 hashed at
InternalAuditFingerprint.java:100 via 130-138 into indistinguishable
`[0-9a-f]{64}`):

- InternalAuditFingerprint.java:106-112 `safeTurn` — catch→`"?"` (110),
  appended at 48.
- InternalAuditFingerprint.java:114-121 `safePhase` — catch→`"?"` (119),
  appended at 49.
- InternalAuditFingerprint.java:68-74 unordered zones
  (Hand/Battlefield/Graveyard/Exile/Command) — per-zone
  catch→`internal-unreadable:<SimpleName>` (73), sorted (75), appended (76).
- InternalAuditFingerprint.java:80-86 ordered Library — same pattern (85),
  appended (87).
- InternalAuditFingerprint.java:92-98 `game.getStack()` — catch→same (97),
  appended (99).

Any one yields valid-looking hex equal to another equally-degraded state when
exception simple-names coincide — false no-mutation evidence.

Visible (returned directly as non-hex, distinguishable — NOT hidden):

- InternalAuditFingerprint.java:42-44 `game==null` → `internal-no-game`.
- InternalAuditFingerprint.java:101-103 outer catch → `internal-hash-error`
  (covers getPriorityPlayer/getPlayerTurn (50-51), getTimestamp (52),
  isGameOver (53), registryPlayers (54-56), playerIdOf/idOf (58,123-128),
  getLife/getPoisonCounters/hasLost (59-61)).
- InternalAuditFingerprint.java:130-142 `shaOrSentinel` catch →
  `internal-sha-unavailable` (140).
- BridgeSession.java:570-576 `currentHash` catch → `internal-unavailable`
  (574) — effectively dead (ofGame never throws), defense-in-depth only.

Class doc 35-37 promises sentinels + fail-closed qualification, but 48-100
hashes `?` and `internal-unreadable:*` before return — doc contradicts code.

## 2. Which callsites ever compare fingerprints?

Production code NEVER compares. All comparisons are tests + implicit
equality-by-construction:

- BridgeEngineTest.java:153 (4 rejected submits 132-152), :295 (starting
  frame 284-295), :412 (unsupported targeting 392-413), :498 (enumeration
  fault 493-498), :571 (nonzero-mana 566-571), :659 (dispatch validation
  613-660), :686 (mulligan missing-keep 679-686): `assertEquals(ofGame,hash)`.
- HiddenInformationNoninterferenceTest.java:294-306 library reorder
  `assertNotEquals`, :324-335 timestamp `assertNotEquals`, :471-475 launched
  rejected `assertEquals(ofGame,auditBefore)`, :486
  `assertNotEquals(outcome.postStateHash,outcome.preStateHash)`.
- BridgeSession.java:91-93 `SubmitOutcome.rejected` sets pre==post==pre by
  construction; waitForSettle 530-532/534-536 reuse preHash for both slots —
  implicit "no mutation" claim without `equals`.

## 3. Which callsites merely record them?

- BridgeSession.java:363 `preHash=ofGame` → 364-365 `new DecisionFrame` →
  DecisionFrame.java:79/85/93 store. Park-time snapshot only.
- BridgeSession.java:500 `preHash=ofGame` → 505 `waitForSettle` → 516-517
  params → 522/540 `postHash=ofGame` → 525-526/542-544
  `SubmitOutcome.applied(pre,post)` storing in 61-62/73-79.
- BridgeSession.java:605 `frameDetails` puts `pre_hash`; audited at 372.
- BridgeSession.java:622-629 `settleDetails` puts `pre_hash/post_hash`;
  audited at 524/530/535/541.
- BridgeSession.java:612-620 `submitDetails` carries NO hash (correct).
- Rejected-submit `currentHash()` values
  (432/436/440/444/447/453/456/462/466/470/474/479/483/494/498) flow into
  `rejected` pre slot only — recorded, never authorized on.

## 4. Does any production action authorization depend on them?

NO. Submit validation order BridgeSession.java:421-505: syntactic
(430-445) → lifecycle (446-457) → frame null (458-463) → actor (464-467) →
revision (468-471) → SUPPORTED (472-475) → option lookup (476-480) →
actionType (481-484) → observation digest capture (490-495,
PROJECTION_FAILED, engine untouched, option unconsumed) → consume (496-499)
→ preHash=ofGame (500, AFTER all authorization) → audit (501) → handoff
(503) → waitForSettle (505). Fingerprint failure cannot reject or block
handoff; ofGame never throws.

Wire proof BridgeEngine.java:756-809: rejected 758-762 returns
fail(errorCode,message) with NO hash fields; applied 764-809 emits
780-781 `outcome.pre/postObservationDigest` (observation digests, NOT
`pre/postStateHash` — zero readers of StateHash in BridgeEngine), gated by
773-774 `isHexDigest`. Authority = Forge Rules Core execution +
DecisionFrame actor/revision/option binding.

## 5. Smallest explicit validity model?

Bare String insufficient (degraded-hex equality AND direct-sentinel equality
both false-PASS). Smallest closing model: typed result reusing `require`
pattern internally:

```java
record Fingerprint(boolean valid, String digest, String failure)
 // valid ⇒ digest matches [0-9a-f]{64}, non-null; !valid ⇒ digest==null, failure!=null
```

failure enumerates no-game | turn/phase/priority/active/timestamp/
player-stats/zone:<Zone>/library/stack/registry/hash-error/sha-unavailable +
cause excerpt (stderr/audit only, never wire). Low-level readers throw
(BridgeProjectionException-style, consistent with
StateProjection.java:58-77 and ObservationDigest.java:66-70), caught ONCE in
ofGame → !valid. Callers store Fingerprint, never String.

## 6. Throw vs typed result vs non-hex marker + metadata?

Adjudicated: RETURN TYPED RESULT; do NOT throw to callers; do NOT keep bare
non-hex String.

- ObservationDigest throws (ObservationDigest.java:50-70/119-131) because it
  is a WIRE contract (BridgeEngine 773-777, BridgeSession 493-494 must fail
  PROJECTION_FAILED). Correct there.
- StateProjection.require throws (58-77) — required observation fields have
  no default. Correct there.
- Fingerprint is opposite: NOT Rules authority, NOT pilot-visible, NOT replay
  (InternalAuditFingerprint 16-33, upheld). Throwing ofGame would force
  try/catch at 5 capture sites (BridgeSession 363/500/522/540/572); any miss
  escalates engineering evidence into gameplay denial — the elevation Q8
  forbids.
- Bare non-hex marker + sidecar relies on every comparator remembering
  isHexDigest first; current suite proves that discipline fails (Q2).
- Typed result: deterministic validity checks without sprawl
  (`assertTrue(fp.valid())`), require-consistent inside, park/submit/settle
  non-blocking (record UNKNOWN, proceed). Minimal diff: 1 value type +
  ofGame signature + 4 capture sites + 2 record fields + comparison gates.

## 7. Rejected-submission no-mutation evidence when capture unavailable?

Trinary: EQUAL_VALID (both valid && digest equal) → no-mutation PASS
(bounded same-process); NOT_EQUAL_VALID → mutation detected (FAIL for
rejected submits); EITHER_INVALID → UNKNOWN: SubmitOutcome/AuditEvent record
`UNKNOWN`/`UNAVAILABLE:<failure>` + reason; qualification assertions must
fail closed (must NOT pass; fail or report UNKNOWN requiring rerun).
Current BridgeEngineTest (153/295/412/498/571/659/686) and
HiddenInformationNoninterferenceTest (471-475) violate this — two
internal-hash-error sentinels or two degraded hexes compare equal as false
proof. Repair gates every assertion on valid first.

## 8. Where may/may not fingerprint failure block?

Fail-closed = never represent/consume incomplete fingerprint as valid
identity evidence — NOT auto-aborting gameplay. Boundary (BridgeSession):

- MUST NOT block: parkFrame 358-383 (park with UNKNOWN pre, still audit +
  handoff.take); submit 421-505 (best-effort capture after consume; failure
  → UNKNOWN pre, still audit/handoff/waitForSettle); waitForSettle 516-555
  post 522/540 (failure → UNKNOWN post, still applied with executionOk from
  Rules + revision rendezvous); all currentHash rejected paths 432-498
  (failure → UNKNOWN pre, still reject with original MALFORMED/WRONG_ACTOR/
  STALE code, never PROJECTION_FAILED for fingerprint); shutdown/terminal.
- MAY block/fail: ONLY qualification/audit gate: frameDetails/settleDetails
  record UNKNOWN + failure; INTERNAL_AUDIT_FAILURE_FAILS_CLOSED gate FAILs on
  UNKNOWN where PASS claimed; BridgeEngine wire gate 773-777 unaffected
  (observation failure still PROJECTION_FAILED; fingerprint null/UNKNOWN must
  never trigger it).
- Only StateProjection.observationDigest failure (490-495) may reject submit.

## 9. Fault seams proving every failure class?

Existing seams (NONE cover fingerprint): StateProjection
36 failRequiredReadsForTests, 39-41 failManaPool/CommanderDamage/Casts,
48 projectionCauseForTests (observation-only); ExternalPlayerController 94
enumerationFaultForTests; BridgeEngine 48 dispatchFaultForTests.

New seams required (package-private volatile flags in
InternalAuditFingerprint, mirroring StateProjection; Game untouched, cleared
in finally): failTurn/failPhase/failPriority/failActive/failTimestamp/
failPlayerStats/failRegistry/failHand/failBattlefield/failGraveyard/
failExile/failCommand/failLibrary/failStack/failSha. Null-game needs no seam.
Each throws distinctive RuntimeException before real read → !valid +
UNKNOWN propagation — never degraded hex. Flags (not mocks): Game/Player/
Zone/Stack are concrete engine classes (InternalAuditFingerprint 3-8
imports); breaking live Game conflates causality; in-fingerprint injection
isolates evidence pipeline while same Game recovers after clear.

Pre-fix red test (no production seam yet): corrupt one Card's `currentState`
to null via reflection → `getName` NPEs → per-zone catch path fires while
Game stays usable; assert ofGame still returns 64-hex (defect) and two
degraded states compare equal as false no-mutation. DIRECTLY_VERIFIED via
`mvn -o -pl forge-protocol2-bridge test -Dtest=...`.

## 10. Does any value leave Protocol-2?

NO. Wire pre/post_state_hash are observation digests (BridgeEngine 780-781
= outcome.pre/postObservationDigest; gate 773-774 isHexDigest →
PROJECTION_FAILED, never fabricated 767-769/803-808). StateProjection 175
actor state_hash via digestOf; 178 non-actor JsonNull; 191-197 throws, never
sentinel. Fingerprint never reaches wire: DecisionFrame.preStateHash only at
BridgeSession 605 into AuditEvent.details; SubmitOutcome.pre/postStateHash
have no reader in BridgeEngine; AuditEvent (96-106) is auditSnapshot-internal
(tests/stderr/file) — former export builder removed (BridgeEngine 812-818),
151-156/171-176 export→EVENT_LOG_UNSUPPORTED, 225-236 caps false.
Sentinel gate: internal-no-game/internal-hash-error/internal-sha-unavailable/
internal-unavailable/? all fail ObservationDigest 134-136 isHexDigest — but
DEGRADED 64-hex PASSES regex; containment holds ONLY because fingerprints
never enter wire path. Internal false-PASS remains open defect.

## Formal decision

- root_cause_class: EVIDENCE_PIPELINE_DEFECT
- first_failing_boundary:
  InternalAuditFingerprint.java:48-100 (degraded-preimage assembly), validity
  lost at 100 `return shaOrSentinel(sb.toString())` via 130-138.
- contract verdict: FAIL (CODE_DERIVED). WS82 VALIDATION.json
  INTERNAL_AUDIT_FAILURE_FAILS_CLOSED=PASS invalidated for this gate; other
  WS82 gates retain sealed status (not re-adjudicated).
- minimal_repair_surface:
  1. InternalAuditFingerprint.java:42-142 (ofGame→Fingerprint, remove
     catch-and-hash 68-100, keep 16-33 docs + validity contract, add Q9
     seams).
  2. BridgeSession.java:61-62/73-93 (SubmitOutcome→Fingerprint),
     363-365 (park), 500/522/540/570-576 (captures+currentHash),
     598-629 (frameDetails/settleDetails→UNKNOWN+failure on !valid).
  3. DecisionFrame.java:79/84-93 (preStateHash→Fingerprint).
  4. Tests: BridgeEngineTest
     129/153/158/281/295/390/412/493/498/566/571/613/659/679/686 and
     HiddenInformationNoninterferenceTest
     294/303/324/334/457/474/482-486/528-529 (gate every equality on valid);
     add per-family fault tests (Q9). No BridgeEngine 756-809,
     StateProjection, ObservationDigest, Rules-Core, or wire-schema change.
- repair_priority: HIGH (evidence-integrity P0; not gameplay outage, no
  Rules corruption, no pilot leak).
- Verdict: FAIL (adjudicated, CODE_DERIVED). READ_FIRST_XHIGH_ADJUDICATION=PASS.
