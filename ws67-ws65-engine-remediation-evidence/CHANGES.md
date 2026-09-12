# Changes (executable only; evidence descendants excluded)

Commit `22e7f17befee8fcce0684fa6d006f29afdb5c280` on
`ws67/forge-ws65-engine-remediation-20260912` (parent: accepted pin `a9a95db`):

1. `forge-game/src/main/java/forge/game/replacement/ReplacementHandler.java`
   (+39/−1)
   - New helper `getEntryCandidateReplacements` (lines 80-93).
   - Snapshot of an entering permanent's own replacement effects before the
     CR-614.12 future-state continuous-effect pass (line 128); union into
     candidacy for the entering card only (line 169).
   - Host-card re-homing for snapshotted effects (lines 192-194).
   - Systemic: no card-name, keyword-name, or controller-specific branches.
     Shared cost/payment infrastructure untouched (single-fix workstream: no
     cost↔replacement interaction to adjudicate beyond noting the fix touches
     only the replacement candidacy view).

2. `forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws67/Ws67EngineRemediationTest.java`
   (new, 8 tests)
   - Ghalta reduced-cost commander cast + hand control + unreduced commander
     control (packet 1 reproducers; all PASS pre- and post-fix).
   - Fire Covenant non-mana X + Fireball mana-X control (packet 2 reproducers
     + control; all PASS pre- and post-fix).
   - Clone under Humility + no-Humility Clone control + Phantasmal Image under
     Humility control (packet 3 reproducers; Humility tests FAIL pre-fix,
     all PASS post-fix).
   - Engine-direct harness: engine Game + engine AI controllers + native mana
     payment via real land abilities; only X-announcement values and
     copy/target selections scripted through a delegating controller.
     No provider/CPL code on the path. Actual cards throughout.

No other production files changed. No test assertions weakened at any point
(initial finder/SBA issues were test-harness artifacts fixed by using the
engine's own MayPlay route, zone re-lookup by name/copy-flag, and SBA
processing — engine behavior was never adjusted to suit the tests).
