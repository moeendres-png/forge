# WS236 Impact Adjudication

## Code impact: none beyond two new test files

- Production sources modified: **NONE** (engine, scripts, bridge-main all
  untouched — the engine was proven sound, so no repair was warranted).
- Existing tests modified: **NONE** (WS234 files byte-identical).
- Added: `forge-gui-desktop/.../gamesimulationtests/ws236/
  Ws236F4SpellcastDiscriminatorTest.java` (5 tests) and
  `forge-protocol2-bridge/.../forge/bridge/WS236F4BridgeTest.java` (2 tests).

## Test-impact decision (conservative)

Positive decision value only for the new files plus the family-adjacent
WS234 suites that share the trigger-family seams. Full 161/161 bridge and
71/71 sim-engine re-runs would re-prove untouched paths with ~zero marginal
information; explicitly NOT_RUN per validation economy (no reassurance
reruns). FULL107 NOT_RUN per contract.

## Retained gates

S1 whole-boundary, S2 cardinality (2P–5P PASS, 1P/6P fail-closed), S4
replay/RNG, fallback-reachable 0, principal scoping: all **NO_IMPACT** —
no code on those paths was touched, and the family suites covering them
re-ran green.

## Evidence-quality finding (recorded, not silently repaired)

WS234 `testCard17` (Kaervek sim) asserts `life <= before` — non-strict, so
it passes whether or not the trigger resolves. The WS236 strict variant
under the legacy drain fails (20→20), proving the vacuity; the bridge
strict Kaervek proof is unaffected. Sealed WS234 bytes are preserved
untouched; successor WS236-S1 (see FINAL_HANDOFF.md) charters owning
CARD_17 re-probe outside WS236 scope.

Machine-readable: `IMPACT_ADJUDICATION.json` (this directory).
