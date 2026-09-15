# WS233 Terminal / Cleanup Audit

- Rejected creations (0P/1P/6P/unknown-handle) leave no entry in the engine
  session map (asserted per rejection).
- `BridgeSession.shutdown(joinMillis)` path untouched; every qualified
  lifecycle (in-JVM primary + second same-engine game, fresh-process game)
  ends `isTerminal()` with `CLOSED` status; child processes exit 0 after
  `shutdown_engine`.
- Same deck handles reusable for a second game on the same engine with a
  parked first frame (no cross-session corruption at any count).
- `terminal_outcomes` projection iterates the immutable registry (dynamic seat
  sort); no fixed-four map exists in the shutdown/result path.

Verdict: clean termination + no cross-session contamination at 2/3/4/5P
(DIRECTLY_VERIFIED).
