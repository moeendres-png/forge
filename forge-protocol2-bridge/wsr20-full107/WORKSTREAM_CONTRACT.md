# WSR20 Forge FULL107 Common-Denominator Qualification — Workstream Contract

Primary workstream: FORGE-FULL107-CDQ-20260926.
Coordinator tracker: Commander-Lab Issue #251.

## Mission

Qualify the current Forge candidate against the exact frozen FULL107 v1.0.5
107-item provider denominator used by Commander Simulator Next, as the
decision-critical prerequisite for a later XMage-vs-Forge cross-engine
adjudication.

## Hard gates (unchanged for the whole workstream)

- Do NOT select a provider.
- Do NOT claim Architecture Freeze.
- Do NOT repin Commander-Lab.
- Do NOT modify Commander-Lab (read-only authority/input only).
- Do NOT create a new Rules Core.
- Do NOT merge Forge master.
- No force push. No rebase of published history. Fail closed on drift.
- One isolated worktree (`ws-forge-full107-cdq-20260926`), one writer.
- No other provider/architecture workstream in parallel.

## Source lock (audit base, verified 2026-09-26)

- Forge master: `ef958ee91ac6c9ce0152189f2654bf6e05abf273`
- Authorized branch `wsr20/forge-full107-common-denominator-20260926`
  == audit base at dispatch, no workstream commits.
- Audit base tree: `fc3387bf37aab19d780b2939a235309ed32b0492`
- Frozen FULL107 denominator: Lab branch
  `ws47/successor-contract-v1.0.5-freeze@5a2e4f462fd45bba25f2271153212aab9faf09f5`,
  materialization `commander-lab.semantic-fixture-materialization/1.0.5`,
  107 items.

## Method

1. Gate 0 identity/provenance reconciliation (no silent rewrite of history).
2. Reuse-first adjudication of R9–R19 evidence (EXACT_REUSE only on exact
   semantics; else IMPACT_REQUALIFICATION_REQUIRED / SUPPORTING_ONLY /
   HISTORICAL_ONLY / UNKNOWN). FULL107=NOT_RUN in R19: no translation of the
   29-card/30-family denominator into FULL107 PASS.
3. Exact 107-item Forge mapping, counts reconcile to 107.
4. Missing execution in priority order: rules authority/pilot boundary,
   forbidden-fallback negatives, hidden info, RNG/replay, micro rules,
   Commander, multiplayer (2–6P; 7P fail-closed).
5. Actual-card runtime preferred for behavioral items; repair engine/bridge
   gaps instead of card-name hacks; no second Rules engine in bridge/pilot.
6. Common-fixture successor packet for the later Lab-owned comparison
   (divergences stay UNKNOWN_PENDING_RULES_ADJUDICATION; no winner labels).
7. Smallest targeted loop → impacted regressions → full relevant suites;
   both Java matrix lanes if CI requires; no weakening of existing tests.

## Deliverables (all under forge-protocol2-bridge/wsr20-full107/)

WORKSTREAM_CONTRACT.md, SOURCE_LOCK.md, IDENTITY_ADJUDICATION.md,
FULL107_DEFINITION_BINDING.json, FULL107_FORGE_MAPPING.json,
EVIDENCE_REUSE_ADJUDICATION.md, EXECUTION_RESULTS.json,
HIDDEN_INFO_RESULTS.json, RNG_REPLAY_RESULTS.json, MULTIPLAYER_RESULTS.json,
COMMON_FIXTURE_SUCCESSOR_PACKET.json, VALIDATION.md, FINAL_HANDOFF.md.
