# WS87 COORDINATOR FINDING (verbatim scope)

InternalAuditFingerprint currently catches some required read failures.

Examples include:

- per-zone reads producing internal-unreadable:<ExceptionClass>
- Library reads producing internal-unreadable:<ExceptionClass>
- stack reads producing internal-unreadable:<ExceptionClass>
- safeTurn returning "?"
- safePhase returning "?"
- top-level fallback values such as internal-hash-error

Some of those markers are inserted into the fingerprint preimage and then
SHA-256 hashed.

The caller therefore receives an ordinary 64-hex string.

A caller cannot know from that result that part of the authoritative
internal state was unreadable.

Two unreadable states may therefore compare equal and be mistaken for
evidence that nothing mutated.

That contradicts the WS82 hard gate:

INTERNAL_AUDIT_FAILURE_FAILS_CLOSED = PASS

WS82 technical successor acceptance remains NO until this is corrected.

## Semantic distinction (binding)

InternalAuditFingerprint is NOT Rules authority. It is NOT a pilot-visible
identity. It is NOT semantic replay. It is bounded same-process engineering
evidence.

Therefore "fail closed" does NOT automatically mean aborting a legal Magic
action merely because an optional engineering fingerprint failed.

It means: a failed or incomplete fingerprint must NEVER be represented or
consumed as valid evidence of state identity or no mutation.

Gameplay authority remains Forge Rules Core + DecisionFrame
actor/revision/option binding.

## Target property

A complete valid fingerprint and an incomplete fingerprint must be
unambiguously distinguishable.

Hard property: INTERNAL_FINGERPRINT_READ_FAILURE_VALID_HEX = 0
And: INVALID_FINGERPRINT_CAN_PROVE_NO_MUTATION = 0

If any required input cannot be read, the system must explicitly classify the
fingerprint as unavailable/invalid. Do not hash the error marker into a valid
fingerprint.
