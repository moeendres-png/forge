# WS236-S1 Final Handoff — Forge Simulation Trigger-Drain Requalification (COMPLETE pending publication)

## Source Lock

- Repo `moeendres-png/forge`, branch
  `ws236-s1/forge-trigger-drain-family-20260916`.
- Audit base `1f1393021c47f764bdf237fafac0133a758eced7` /
  `ad4a637b334c12a1c1301be726b27e6c9bcb0425` (HEAD+TREE verified, clean
  tree at lock; origin `ws236/forge-f4-spellcast-discriminator-20260916`
  verified identical SHA+TREE after fetch; single local writer).
  See `SOURCE_LOCK.json`.

## Objective

Strict actual-card CARD_17 Kaervek reprobe and bounded, systemic migration
of the SimulationTest-family legacy trigger-drain pattern, preserving engine
Rules ownership and independently qualified evidence.

## Work Completed

- Verified source lock, launcher context (writer, XHIGH,
  `opencode-go/muse-spark-1.3-contributor`), and single-writer ownership;
  read sealed WS236-F4 evidence and current sim sources.
- Machine-enumerated every repository `resolveStack` test site (4 files) and
  every `getStack` drain contact in `gamesimulationtests`; classified
  affected / unaffected / out-of-scope with dispositions
  (`DRAIN_INVENTORY.json`).
- Reproduced strict CARD_17 Kaervek fail-before under the legacy drain
  (lives 20→20, entries stranded, exact-2 assert FAIL; temporary diagnostic
  sealed then removed) and pass-after with Forge's own
  `addAllTriggeredAbilitiesToStack` priority-ordering path (p2 20→18, exact
  MV=2, AI target choice through the engine path, never harness-chosen).
- Added strict negative controls (controller-cast silence; Veyran-doubled +
  Kaervek-silent same-controller multi-trigger) and migrated the one
  affected shared helper (`Ws234S3CardBehaviorTest.drainStack`) with
  fail-closed guards; left same-form unaffected callers untouched.
- Qualified 29/29 sim tests green with checkstyle 0 violations; adjudicated
  bridge/161/71/FULL107 as NOT_RUN with causal reasons; sealed this 10-file
  evidence namespace.

## New Findings

- AI target policy (DIRECTLY_VERIFIED): the Kaervek trigger controller's AI
  deals the damage to the opposing player when no better target exists
  (lobby-name mapping documented in-test and in observations).
- Fixture artifact (FIXTURE_DEFECT, pre-existing): `testCard20SyphonMind`
  decks its drawing player (empty-deck fixture + 704.5b) under both drains;
  a migrated game-over guard was removed with rationale (never success).
- Cross-player APNAP ordering choice is explicitly NOT_TESTED (no in-scope
  2-player single-cast event produces one with these cards).

## Changes

- ADD `forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws236s1/
  Ws236S1KaervekDrainTest.java` (3 behavior tests).
- MODIFY `forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws234/
  Ws234S3CardBehaviorTest.java` (drain helper + comment only; bodies
  byte-identical).
- ADD `forge-protocol2-bridge/ws236-s1/` (10 evidence files, this package).
- MODIFY: nothing else (verified via `git status` / `git diff --stat`).

## Tests / Evidence

- DIRECTLY_VERIFIED: fail-before strict legacy (20→20 stranded, exit 1);
  pass-after strict (p2 20→18 exact-2, exit 0); negatives; multi-trigger;
  29/29 sim green; checkstyle 0 violations.
- CODE_DERIVED: Oracle MV readings, lobby-name mapping (runtime-confirmed),
  no-trigger-source classifications (read from card abilities present).
- TECHNICALLY_CONFORMANT: out-of-scope mechanism classifications
  (GameWrapper action flow, GameSimulator priority flow, bridge real flow).
- NOT_RUN (explicit): bridge suites, 161/161, 71/71, FULL107,
  cross-player APNAP choice.
- No EXTERNALLY_RULE_VALIDATED beyond Oracle text derivation; nothing
  MODELED/SYNTHETIC; no UNKNOWN left unlisted.

## PASS / FAIL / UNKNOWN

- WS236_S1_CARD17_STRICT = PASS (fail-before + pass-after, actual-card).
- DRAIN_FAMILY_MIGRATION = PASS (bounded: 1 migrated, 2 classified
  untouched, rest out-of-scope with reasons).
- ROOT_CAUSE = HARNESS_DEFECT (confirmed, test-only repair).
- FULL107 = NOT_RUN. ARCHITECTURE_FREEZE = NOT_CLAIMED.
  PRODUCTION_PROVIDER = NOT_SELECTED.

## Remaining Blockers

1. Publication push: local commit(s) ready; canonical safe_push dry-run then
   actual inside this writer session, fetch, and local/remote HEAD+TREE
   equality verification (no raw push).

## Outputs

`forge-protocol2-bridge/ws236-s1/`: SOURCE_LOCK.json,
DRAIN_INVENTORY.json, KAERVEK_OBSERVATIONS.json, ROOT_CAUSE.md,
REGRESSION_RESULTS.json, IMPACT_ADJUDICATION.json, IMPACT_ADJUDICATION.md,
VALIDATION.md, EVIDENCE_SEAL.json, FINAL_HANDOFF.md (this file).

## Dependencies Unblocked

- CARD_17 sim seam is now strict-qualified; Lab recomputation may consume
  the Kaervek strict proof alongside the WS236 F4 package.
- Every future trigger-family sim harness has the corrected drain shape plus
  a machine inventory with dispositions.

## Exact Next Action

Commit bounded S1 changes → validate exact committed HEAD (clean tree,
HEAD/TREE recorded) → canonical safe_push (dry-run DRY_RUN_OK, then actual)
→ fetch + remote equality verification → state COMPLETE → terminate.

Terminal fields: WS236_S1_CARD17_STRICT PASS | DRAIN_FAMILY_MIGRATION PASS |
ROOT_CAUSE HARNESS_DEFECT | FULL107 NOT_RUN | ARCHITECTURE_FREEZE
NOT_CLAIMED | PRODUCTION_PROVIDER NOT_SELECTED.
