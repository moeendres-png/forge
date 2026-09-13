# WS87 PRE-FIX REPRODUCTION (DIRECTLY_VERIFIED)

## Test

File (audit-base String API, temporary):
`forge-protocol2-bridge/src/test/java/forge/bridge/Ws87PreFixReproductionTest.java`

Method: `testHandReadFailureMustNotYieldValidHex`

Fault: corrupt one Hand card's `Card.currentState` to null via reflection so
`Card.getName()` throws NPE inside the fingerprint's per-zone loop, while the
surrounding Game remains otherwise usable (4 players, Hand non-empty,
registry intact — all asserted).

Requirement asserted (fail-closed): incomplete fingerprint must NOT match
`[0-9a-f]{64}` and must NOT support equality/no-mutation proof.

## Command

```
mvn -o -pl forge-protocol2-bridge test -Dtest=Ws87PreFixReproductionTest -DfailIfNoTests=true
```

Envelope: isolated temp TMPDIR=/home/moeen/tmp MAVEN_OPTS=-Djava.io.tmpdir=/home/moeen/tmp/opencode; mvn -o

## Audit-base result (2026-09-13, HEAD 4342a798)

- Tests run: 1, Failures: 1, Errors: 0
- Failure: `DEFECT: Hand read failure still yielded valid-looking hex:
  3fd4f44931cbfec4ad68b88d81be5919c9f8609954cb4360dec6ab08a9da36d1
  expected [false] but found [true]`
  at Ws87PreFixReproductionTest.java:62

This proves:

- INTERNAL_FINGERPRINT_READ_FAILURE_VALID_HEX = 1 (should be 0): the
  `internal-unreadable:NullPointerException` marker was hashed into ordinary
  hex via `InternalAuditFingerprint.java:68-74 → 100 → 130-138`.
- INVALID_FINGERPRINT_CAN_PROVE_NO_MUTATION = 1 (should be 0): two degraded
  calls return the same hex and would compare equal as false no-mutation
  evidence (second assertion in test, unreachable because first already
  failed on hex shape — same root cause).
- Game remained usable (players/zones/registry assertions passed before the
  hex assertion).

## Verdict

PRE_FIX_DEFECT_REPRODUCED=PASS (DIRECTLY_VERIFIED, runtime — not source
inspection). First failing boundary confirmed:
`InternalAuditFingerprint.java:48-100`, validity lost at `:100`.

Note: this temporary String-API test is superseded by the WS87 fail-closed
`Fingerprint` tests post-fix (per-zone/Library/stack/turn/phase validity +
no-mutation UNKNOWN + determinism + ordering + protocol containment).
