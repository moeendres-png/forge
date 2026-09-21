# WS231 Final Handoff — Forge Post-WS227 Admission Re-screen (COMPLETE pending publication)

## Source Lock

- Repo `moeendres-png/forge`, branch
  `ws231/forge-post-ws227-admission-rescreen-20260915`.
- Seal `8ff3e7a48271eaba847608701f4c284c20dcdf68` / `be5e3f3a…` (= HEAD).
- Provider `eb87b31759c2a9989a819f408c52b3da5c00301d` / `cb4e5dbd…`.
- Core `d52e890538dc0380d1b26d781349312e310b3a2b` / `302938b7f…`.
- Lab authority WS226 `fb156d2b4cf8c5c21d0c84e844a53160032c0992`, read-only.

## Work Completed

Re-screened the WS227 successor against neutral S0–S4 (S5 out of scope):
bound identities; audited the whole production candidate boundary
(entrypoint trace, 109 controller overrides classified, 30/30 decision
families dispositioned, stock-remote reachability proven A, zero prohibited
fallbacks, principal scoping verified); adjudicated 2P–5P constructibility
(4P PASS, 2P/3P/5P provider-rejected) with Commander/multiplayer viability
separated; ran the exact 29-card + 17-micro census with dedicated-test
inventory and systemic gap map (F0–F6); retained WS227 clean-process
replay/RNG runtime evidence under exact-pin predicates (no code changes);
published the 24-file package `forge-protocol2-bridge/ws231-admission/`.

## New Findings

1. WS227 supersedes the historical S1/AF04 failure FOR THE CANDIDATE PATH:
   independent provider path (verdict A), whole boundary fail-closed,
   S1 PASS. The old stock-remote failure remains true of the old path.
2. First terminal stage is S2 (not S3): the provider's unconditional
   `!=4` gate makes 2P/3P/5P unconstructible via the production entrypoint
   although engine/session/turn-order/projection paths are roster-generic.
3. S3 denominator: 27/29 SUPPORTED; Wash Away PARTIAL (Cleave approximation,
   no text-removal engine); Find//Finality PARTIAL (Aftermath uninvoked);
   0/29 dedicated behavior tests (F0 evidence void); micro 13/17.
4. S4 PASS by retention (fresh-JVM record+replay, tamper matrix, isolation).
5. Legacy COMBAT_ORDER still parked as an externally-decided fail-closed
   frame; modern COMBAT_DAMAGE separate. S1-neutral; do not mistake the
   family name for current-rules behavior.

## Changes

Evidence package only (24 files under
`forge-protocol2-bridge/ws231-admission/`). FORGE_CODE_MODIFIED = NO.
LAB_MODIFIED = NO.

## Tests / Evidence

- New DIRECTLY_VERIFIED census probes V2–V6 (scripts, test-grep, gate text,
  partials, protocol/license) — PASS as executed checks.
- Retained WS227 runtime (139 bridge tests, fresh-process record/replay,
  tamper/isolation matrix) at exact pin — S4 + S2-negative-probe basis.
- NOT_RUN: mvn re-execution, S5 campaign, new cardinality probes,
  checkstyle (all by policy, recorded in VALIDATION.md).

## PASS / FAIL / UNKNOWN

S0 PASS / S1 PASS / S2 FAIL / S3 FAIL / S4 PASS. FIRST_TERMINAL_STAGE S2,
PROVEN_GAP, ADMISSION_STATUS DO_NOT_PROMOTE_CURRENT_PIN. No UNKNOWN remains.

## Remaining Blockers

1. S2: parameterize provider seat count 2–5 + probes (one workstream, DAYS).
2. S3: F2 one-keyword Aftermath fix; Cleave decision; F0/F3/F4/F5 test
   families + 4 micro tests (follow-on workstreams; NO broad campaign here).
3. Future Lab recomputation must consume this package (see
   LAB_SUCCESSOR_SPEC.json); WS226 standing untouched.

## Outputs

`forge-protocol2-bridge/ws231-admission/`: SOURCE_LOCK.json/md,
INPUT_AUTHORITY_MATRIX.json, ADMISSION_CONTRACT_INTERPRETATION.md,
S0_SOURCE_LICENSE_BUILD.json, S1_WHOLE_BOUNDARY_AUDIT.md,
S1_DECISION_FAMILY_MATRIX.json, STOCK_REMOTE_REACHABILITY.json,
FALLBACK_REACHABILITY.json, PRINCIPAL_SCOPING_AUDIT.json,
S2_CARDINALITY_MATRIX.json, S2_MULTIPLAYER_VIABILITY.md,
S2_RUNTIME_RESULTS.json, S3_ACTUAL_CARD_CENSUS.json,
S3_MICRO_RULES_CENSUS.json, S3_DEDICATED_TEST_INVENTORY.json,
S3_SYSTEMIC_GAP_MAP.json, S4_REPLAY_RETENTION.json,
S4_RNG_REPLAY_ADJUDICATION.md, ADMISSION_STAGE_RESULTS.json,
TERMINAL_BLOCKER.json, FORGE_POST_WS227_STANDING.json,
LAB_SUCCESSOR_SPEC.json, VALIDATION.md, FINAL_HANDOFF.md.

## Dependencies Unblocked

Future Lab recomputation (S1/S4 inputs); WS-A seat-count remediation;
WS-B/C denominator remediation; WS230 informed (no scope taken).

## Exact Next Action

Commit package → canonical safe_push (dry-run then actual) → fetch +
HEAD/TREE equality + clean worktree → terminate with terminal fields.

Terminal fields: WS231_FORGE_POST_WS227_ADMISSION | SOURCE
8ff3e7a4/be5e3f3a | PROVIDER eb87b31/cb4e5dbd | CORE d52e890/302938b7f |
S0 PASS | S1 PASS | S2 FAIL | S3 FAIL | S4 PASS | FIRST_TERMINAL_STAGE S2 |
TERMINAL_BLOCKER_KIND PROVEN_GAP | ADMISSION_STATUS DO_NOT_PROMOTE_CURRENT_PIN |
STOCK_REMOTE_REACHABILITY NOT_REACHABLE(A) | WHOLE_BOUNDARY_FAIL_CLOSED YES |
DECISION_FAMILIES_TOTAL 30 | PROHIBITED_FALLBACKS_REACHABLE 0 |
2P FAIL | 3P FAIL | 4P PASS | 5P FAIL | FROZEN_CARDS_SUPPORTED 27 |
FROZEN_CARDS_PARTIAL 2 | FROZEN_CARDS_MISSING 0 | DEDICATED_BEHAVIOR_TESTS 0 |
MICRO_RULES_IMPLEMENTED 13 | MICRO_RULES_PARTIAL_OR_BLOCKED 4 |
RNG_REPLAY PASS(retained) | PROCESS_ISOLATION PASS(retained) |
FORGE_CODE_MODIFIED NO | LAB_MODIFIED NO | GLOBAL_BEHAVIOR_CREDIT_CHANGE 0 |
FULL107 NOT_RUN | RAW_GIT_PUSH_USED NO | ARCHITECTURE_FREEZE NOT_CLAIMED |
PRODUCTION_PROVIDER NOT_SELECTED.
