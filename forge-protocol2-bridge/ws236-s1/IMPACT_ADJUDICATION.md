# WS236-S1 Impact Adjudication

## Code impact: one migrated helper + one new test file

- Production sources modified: **NONE** (engine, card scripts, bridge-main,
  CPL, RSP, pins all untouched — the engine was proven sound, so no repair
  was warranted or performed).
- Existing tests modified: **ONE HELPER ONLY** —
  `Ws234S3CardBehaviorTest.drainStack` (engine-owned ordering + fail-closed
  guards) plus a 5-line comment addendum. All 28 test bodies byte-identical;
  denominators unchanged (21 enabled, 7 disabled NOT_RUN preserved).
- Added: `forge-gui-desktop/.../gamesimulationtests/ws236s1/
  Ws236S1KaervekDrainTest.java` (3 strict behavior tests) and this
  `forge-protocol2-bridge/ws236-s1/` evidence package.

## Test-impact decision (conservative)

Positive decision value only for the causal surface: the new S1 file, the
migrated WS234 file (all its callers re-run: 21/21 green), and the
trigger-family WS236 discriminator (5/5 green). Bridge suites have no causal
path from sim-only changes (real priority flow, no manual drain) and are
explicitly NOT_RUN, not re-run for reassurance. Full 161/71 and FULL107 are
NOT_RUN per contract.

## Retained gates

S1 whole-boundary, S2 cardinality, S4 replay/RNG, fallback-reachable 0,
principal scoping: all **NO_IMPACT** — no code on those paths was touched.
S3: CARD_17 sim reprobe is now STRICT PASS; all other S3 verdicts/bytes
unchanged. Sealed WS234-S3 and WS236-F4 packages preserved byte-identical;
proof is `git status`/`git diff --stat` (2 test files only, zero main-source
diffs).

## Evidence-quality notes (recorded, not silently repaired)

- WS234 `testCard17` keeps its non-strict `<=` assert (still passing, now
  under the corrected drain); strictness lives in the new S1 test.
- `testCard20SyphonMind` decks its drawing player (empty-deck fixture,
  pre-existing under both drains); classified FIXTURE_DEFECT artifact.
- Cross-player APNAP ordering choice: explicitly NOT_TESTED (no in-scope
  2-player single-cast event produces one with these cards).

Machine-readable: `IMPACT_ADJUDICATION.json` (this directory).
