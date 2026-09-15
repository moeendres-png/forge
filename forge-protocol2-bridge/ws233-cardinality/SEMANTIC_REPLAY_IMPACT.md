# WS233 Semantic-Replay Impact

- Replay surface (`SemanticReplay`, `WS227ReplayChild`, tape contract)
  untouched; no replay schema change.
- Existing 4P record/replay RERUN post-change inside the full green suite
  (`WS227SemanticReplayTest`, `WS227SeparateProcessTest.testFreshJvmRecordReplay`):
  PASS, so the S4 claim survives the provider diff by execution, not by
  retention predicates.
- New variable-player replay evidence: seeded in-process record + independent
  exactly-once replay at 2P and 5P with equal `public_state_digest`
  (`REPLAY_RUNTIME_RESULTS.json`). No injection, no fuzzy matching, no
  first-equivalent fallback (resolveExactlyOnce MISSING/AMBIGUOUS fail-closed
  paths untouched).

Verdict: S4 PASS stands on rerun evidence; variable-player replay proven at
the 2P/5P bounds.
