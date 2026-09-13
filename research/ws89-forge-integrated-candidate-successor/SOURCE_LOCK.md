# WS89 SOURCE LOCK — FORGE UNIFIED INTEGRATED CANDIDATE SUCCESSOR

Provenance: DIRECTLY_VERIFIED via `git rev-parse`, `git cat-file -t`, `git diff --name-only`, `git show`, `git ls-tree`, `git grep` on 2026-09-13. No production edits performed at lock time.

## 1. Working base (integration start)

- `HEAD` = `bc29afd8409cace599bfa7e95c8af8df0120df89`
- `branch` = `ws89/forge-integrated-candidate-successor-20260913`
- `tracking` = `origin/ws77/forge-clean-ws76-successor-20260912`
- `git status` at lock: clean tree on correct branch (verified before any edit)

## 2. Clean Rules-Core successor lock — PASS

- `CLEAN_BASE` = `a9a95db6662c2d28814390a9c0c2f986e39aa8b4` (exact base)
- `CLEAN_TECH` = `aa5c00aa32dfd40e213f223f8fd400c43daabb24` (accepted technical validation commit)
- `CLEAN_TERMINAL` = `bc29afd8409cace599bfa7e95c8af8df0120df89` (terminal evidence head, integration start)
- All three objects verified present (`git cat-file -t` = commit).
- Delta `a9a95db..aa5c00` verified exactly 4 files:
  - `forge-game/src/main/java/forge/game/GameAction.java`
  - `forge-game/src/main/java/forge/game/phase/PhaseHandler.java`
  - `forge-gui-desktop/src/test/java/forge/gamesimulationtests/h01/H01CloneHumilityTest.java`
  - `forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws76/Ws76ImmediateConcessionTest.java`
- Delta `aa5c00..bc29afd` verified evidence/state only:
  - `clean-ws76-successor-evidence/EVIDENCE_SEAL.md`
  - `clean-ws76-successor-evidence/WORKSTREAM_STATE.yaml`
- Accepted bounded claims (not broadened): corrected H01 behavior = PASS; immediate concession = PASS.

## 3. H4F bridge successor lock — PASS

- `H4F_BRIDGE_HEAD` = `17ddc26f9bf2702bf0befe542a363f5fd87474e4` (accepted bridge/privacy/audit head, verified present)
- `H4F_BASE` = `4753bb7c72ea60d653121e0bab989077b4009f9c` (historical H4F integrated base, verified present)
- `H4F_HISTORICAL_AUTHORITY` = `a37a865a53280dd8ad6fad3384d69611e8c5a42f` (historical Rules-Core authority, verified present; retained ONLY as historical pin, never as forward authority after integration)
- `17ddc26` contained by `ws87/forge-h4f-internal-audit-failclosed-20260913` (local + remote).
- Bridge tree at `17ddc26`: 37 paths under `forge-protocol2-bridge/` (18 main java + pom.xml + bridge.properties + WS-A1D-H4F-STATE.md + 7 test java + 9 deck JSONs). `StateHash.java` absent (superseded by ObservationDigest + InternalAuditFingerprint).
- Bridge tree at `bc29afd`: 0 paths (clean has no bridge — verified via `git ls-tree`).
- Accepted bridge claims carried forward as re-verification targets (not as integrated PASS): noninterference PASS, ObservationDigest PASS, InternalAuditFingerprint PASS_BOUNDED_SAME_PROCESS, audit failure semantics PASS, revision/action binding PASS, legal-action semantics unchanged, capability change 0, semantic replay NOT_IMPLEMENTED.

## 4. Divergence point

- Lineages diverge at `a37a865a...` (verified common conceptual base per contract; clean successor contains substantially newer Forge lineage; H4F branch contains Protocol-2 bridge lineage).

## 5. Identities (dual-identity contract)

- `RULES_CORE_AUTHORITY` = `aa5c00aa32dfd40e213f223f8fd400c43daabb24` (exact tested technical commit carrying accepted clean production changes; `bc29afd` adds evidence only and does not replace validation credit).
- `BRIDGE_SOURCE_IDENTITY` = future freshly validated WS89 integrated technical commit (UNKNOWN at lock; `git log --all --grep=WS89` empty — no fabrication).
- `FORGE_ENGINE_SHA` for bridge build = `aa5c00aa32dfd40e213f223f8fd400c43daabb24` (accepted Rules-Core identity where bridge build expects engine SHA).
- After integration: do NOT keep reporting `a37a865a` as authority; do NOT call WS89 bridge-source commit the Rules-Core commit.

## 6. Integration method locked

- Start from `bc29afd` (preserves accepted clean successor + evidence).
- Transplant exact contents of `forge-protocol2-bridge/` from `17ddc26`.
- Do NOT transplant old H4F root pom.xml wholesale (would regress `${revision}`→`2.0.14`, `versionCode` 2.0.15→2.0.14, `tag` HEAD→`forge-2.0.14` — all verified as drift to exclude).
- Add ONLY single module-registration line to clean root pom.xml: `<module>forge-protocol2-bridge</module>` after `forge-gui-desktop`.
- Do NOT copy WS82/WS87 research directories; create new WS89 integration evidence.
- Rules-Core `GameAction.java` / `PhaseHandler.java` immutable; no Forge Rules-Core production edit unless bridge demonstrably cannot integrate otherwise + not solvable at adapter boundary + explicit Architecture Authority Gate raised (STOP with evidence if gate appears).

## 7. Hard-gate snapshot at lock

- `SOURCE_LOCK` = PASS
- `CLEAN_SUCCESSOR_LOCK` = PASS
- `H4F_SUCCESSOR_LOCK` = PASS
- `WHOLESALE_H4F_MERGE` = NO
- `RULES_CORE_IDENTITY` = `aa5c00aa32dfd40e213f223f8fd400c43daabb24`
- `READ_FIRST_XHIGH_ADJUDICATION` = PASS (adjudication invoked before any production edit; persisted in `LINEAGE_ADJUDICATION.md`)
- All runtime gates = UNKNOWN pending fresh integrated proof (no historical PASS promoted).
