# WS233 Principal-Scoping Impact (S1 retained PASS)

Patch touches only count bounds/loops/capabilities in `BridgeEngine`; no
observation, redaction, identity or framing code changed.

Runtime proof per count (in-JVM lifecycle + fresh-process state read):

- Principal map holds exactly N identities (`p1..pN`), distinct, registry-bound;
  no phantom `p4`/`p5` (N=2 shows no `p3+`; N=5 shows no `p6`).
- No display names, no raw UUIDs on the wire (seat metadata `forge-pN` lobby
  names only, as at base).
- Owner hand visible; opponent hands `<hidden>` x count; libraries `[]` +
  public size; unknown observer -> `WRONG_ACTOR`.
- Non-actor `get_legal_actions` -> `WRONG_ACTOR`; next-decision withheld
  semantics untouched.

Verdict: PRINCIPAL_SCOPED retained (DIRECTLY_VERIFIED at 2/3/4/5P).
