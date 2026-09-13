# WS89 COMPATIBILITY ADJUSTMENTS — bridge-only, mechanical, no Rules logic

All adjustments are inside `forge-protocol2-bridge/` (plus the single root-pom module line, documented separately). No `forge-game/`, `forge-gui-desktop/`, or other Rules-Core production file was touched. `GameAction.java` / `PhaseHandler.java` blobs verified identical to `aa5c00aa...` (CLEAN_RULES_CORE_PRESERVED = PASS).

## 0. Root module registration (not a bridge-file diff; required project wiring)

- File: `pom.xml` (clean lineage content preserved; exactly 1 line added after `<module>forge-gui-desktop</module>`):
  `+ <module>forge-protocol2-bridge</module>`
- Prohibited alternative (NOT done): copying the H4F root `pom.xml` wholesale, which would have regressed `${revision}`→`2.0.14`, `versionCode` 2.0.15→2.0.14, `tag` HEAD→`forge-2.0.14`.
- Effect: bridge joins the clean reactor as `2.0.15-SNAPSHOT` like every other child. No behavior change.

## 1. `forge-protocol2-bridge/pom.xml` — parent coordinate (1 line)

- Before (H4F lineage): `<version>2.0.14</version>` in `<parent>`.
- After: `<version>${revision}</version>`.
- Why: clean reactor parent version is `${revision}` (`2.0.15-SNAPSHOT`); hardcoded `2.0.14` fails reactor/parent resolution offline and online. Mirrors `forge-game/pom.xml` at `bc29afd`.
- Semantics: build coordinates only. No legality, visibility, cost, targeting, priority, combat, or continuous/replacement semantics touched. Legal-action semantics unchanged: YES (unchanged). Visibility semantics unchanged: YES.

## 2. `ExternalPlayerController.java` — combat-damage controller boundary (mechanical API update)

- Root cause: `forge-game/.../game/player/PlayerController.java` on the clean lineage REMOVED the deprecated abstract `assignCombatDamage(Card, CardCollectionView, CardCollectionView, int, GameEntity, boolean)` (present at `a37a865:110`, absent at `bc29afd`) and ADDED concrete `chooseCombatDamage(CombatDamageDecisionView)` / `chooseAmountDistribution(AmountDistributionDecisionView)` (both fail-closed `IllegalStateException` in base). `divideShield` remains abstract (bridge override retained).
- Change:
  - DELETED the 5-line `@Override assignCombatDamage(...)` block (lines 652–656 of transplanted file), which no longer overrides anything (`javac` "method does not override" break).
  - ADDED two explicit fail-closed overrides (bridge-only, preserving `BridgeUnsupportedDecision` taxonomy instead of relying on base `IllegalStateException`):
    - `chooseCombatDamage(CombatDamageDecisionView) → throw unsupported("chooseCombatDamage", "combat damage assignment is not represented")`
    - `chooseAmountDistribution(AmountDistributionDecisionView) → throw unsupported("chooseAmountDistribution", "amount distribution is not represented")`
  - ADDED 4 imports: `forge.game.combat.CombatDamageDecisionView`, `forge.game.combat.CombatDamageSelection`, `forge.game.player.AmountDistributionDecisionView`, `forge.game.player.AmountDistributionSelection`.
- Semantics: old path (combat-damage callback → `BridgeUnsupportedDecision` abort, no AI/default/random/first-option) → new paths (same fail-closed abort). No new legal action enumerated; no priority/mulligan/cost/targeting path touched; `StateProjection` / `ObservationDigest` / `InternalAuditFingerprint` untouched. Legal-action semantics unchanged: YES. Visibility semantics unchanged: YES. Rules-Core edits required: NO.
- Alternatives considered: relying on base-class `IllegalStateException` without explicit overrides compiles but changes error taxonomy (`FORGE_CONTROLLER_*_UNSUPPORTED` vs `BridgeUnsupportedDecision`); explicit overrides preferred for identical fail-closed semantics.

## 3. `BridgeProtocolProcessTest.java` — engine-pin truthfulness (1 line, test-only)

- Before: `private static final String ENGINE_SHA = "a37a865a53280dd8ad6fad3384d69611e8c5a42f";`
- After: `private static final String ENGINE_SHA = "aa5c00aa32dfd40e213f223f8fd400c43daabb24";`
- Why: the constant drives the child `FORGE_ENGINE_SHA` env in the separate-process qualification; shipping the stale historical pin would misreport engine identity. Dual-identity contract requires the bridge to report the accepted Rules-Core authority `aa5c00aa...`, never `a37a865a...` after integration.
- Semantics: test fixture only; no production legality/visibility change.

## 4. `Ws89EngineIdentityTest.java` — NEW integration-specific test (3 @Test; legitimate count change 84 → 87)

- Purpose: runtime proof of the dual-identity contract (required by WS89 contract §INTEGRATION-SPECIFIC TEST).
- Contents: `RULES_CORE_AUTHORITY = aa5c00aa...` vs `HISTORICAL_H4F_PIN = a37a865a...` are distinct valid hex; with `forge.engine.sha` sysprop set to the authority, `VersionInfo.engineCommit()/engineCommitIfValid()` return it with source `sysprop:forge.engine.sha`; bridge never reports the historical pin as authority.
- Semantics: asserts identity plumbing only; constructs no legality, invents no choices, touches no visibility.

## 5. Intentionally NOT changed

- `VersionInfo.BRIDGE_VERSION` (`2.0.14-ws-a1d-h4f`) / `RELEASE` (`2.0.14`): stale historical bridge-release labels retained byte-identical; engine authority flows via `FORGE_ENGINE_SHA`/`forge.engine.sha`/filtered `bridge.properties` (verified filtered `target/classes/bridge.properties`: `engine.commit=aa5c00aa...`, `bridge.version=2.0.15-SNAPSHOT`). Hygiene-only; no semantic effect.
- `WS-A1D-H4F-STATE.md`: historical bridge state doc retained byte-identical (records old `AUDIT_BASE_SHA a37a865a...` as history, not as forward authority).
- `StateProjection.java:527` (`for (Player : game.getPlayers())`) and `InternalAuditFingerprint.java:217` (`new ArrayList<>(game.getPlayers())`): return type `PlayerCollection extends FCollection` unchanged — no edit needed.
- `divideShield` override: retained (still abstract at `bc29afd:123`).
- Every other bridge file (34/37 base paths): BYTE_IDENTICAL.

## Verdicts

- `UNEXPLAINED_BRIDGE_DIFFS` = 0.
- `LEGAL_ACTION_SEMANTICS_CHANGED` = NO.
- Visibility semantics changed = NO.
- Rules-Core edits required = NO (Architecture Authority Gate NOT triggered).
- `CAPABILITY_CHANGE` = 0; `BEHAVIOR_CREDIT_CHANGE` = 0; `SECOND_RULES_ENGINE` = 0.
- `SEMANTIC_REPLAY_DIGEST` = NOT_IMPLEMENTED (no replay code added; grep empty).
