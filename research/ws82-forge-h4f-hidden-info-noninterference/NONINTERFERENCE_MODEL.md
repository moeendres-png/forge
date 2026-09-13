# WS82 NONINTERFERENCE MODEL

Authority: H4F principal-scoped projection (StateProjection.gameState + bridgeMeta + legalActions/decisionSummary). No independent MTG visibility engine.

## Observational equivalence

Two engine states S1, S2 are observationally equivalent for principal P iff:
- `StateProjection.gameState(session_P_view(S1), P)` byte-equals `gameState(...S2..., P)` after canonical JSON serialization, AND
- `bridgeMeta` minus `state_hash` equals (revision, pending_decision summary, session_status, bound execution error), AND
- `legal_actions` array equals (actor-scoped: empty for non-actor).

The exact sanitized envelope H4F emits is the authority. No reinterpretation inside hashing.

## Required property (deterministic projection)

Same authorized observation => byte/semantic-equivalent external envelope, INCLUDING any exposed digest.
Changing data outside P's observation (opponent hand identities, hidden library contents/order, unauthorized face-down true names, process-local object identity, engine timestamp) MUST NOT change P-visible bytes. Hash changes are observable.

## What is legitimately visible (do not redact to pass)

- Public hand counts (as `<hidden>` placeholders, count preserved).
- Visible battlefield/exile/stack data per native gates (canBeShownTo + canFaceDownBeShownTo).
- Own hand names to owner; granted face-down true names to authorized observer.
- Actor's legal actions/decision summary/revision to actor only; -1/null to non-actor.
- Life/poison/loss/phase/turn/active/priority/seats/mana/commander fields as projected.
- event_sequence (audit count) — public liveness, not card identity.

## Digest derivation rule

Observation digest = SHA-256 over deterministic canonicalization of the sanitized principal-visible representation (gameState + actor-scoped decision metadata), excluding the digest field itself (no recursion). Object keys sorted; arrays preserve semantic order; hidden content already removed by projection; no Rules/visibility logic inside hash utility.

## Internal audit (separate)

Privileged fingerprint may inspect live state for same-process no-mutation proofs. Never serialized. Name/document scope truthfully. Order-sensitive (Library/Stack preserve order). Fail closed on read failure.

## Replay

SEMANTIC_REPLAY_DIGEST = NOT_IMPLEMENTED. Not claimed, not built here.

Gate: PRINCIPAL_NONINTERFERENCE = PASS requires Pairs A-D (see PRE_FIX_REPRODUCTION.md) with entire principal-visible envelope identical under hidden variation and digest changing on visible change.
