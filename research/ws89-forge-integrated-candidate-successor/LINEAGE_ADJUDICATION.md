# WS89 LINEAGE ADJUDICATION — XHIGH READ-FIRST (persisted before any production edit)

Method: READ-ONLY. No edits, writes, commits, or compiler/test executions during adjudication.
Provenance tiers: DIRECTLY_VERIFIED = observed git/file output; CODE_DERIVED = source inference without runtime; RUNTIME = fresh execution (none claimed here).
Adjudicator: Foundry adjudicator at XHIGH (session `ses_f65959740ffeTTEWB99WDp9S1J`), plus operator DIRECTLY_VERIFIED spot-checks (module lists, pom versions, PlayerController combat methods, bridge.properties, VersionInfo). Persisted before any transplant/edit.

## A. Exact clean successor diff — DIRECTLY_VERIFIED

`a9a95db..aa5c00`: exactly 4 files (548 insertions, 3 deletions):
- `forge-game/src/main/java/forge/game/GameAction.java` — removed `FCollectionView` import; `game.getPlayers()` → `Lists.newArrayList(game.getPlayers())` with CR 104.3a snapshot comment.
- `forge-game/src/main/java/forge/game/phase/PhaseHandler.java:411` — same snapshot hardening in turn loop.
- `H01CloneHumilityTest.java` (+274), `Ws76ImmediateConcessionTest.java` (+263) — new oracles.

`aa5c00..bc29afd`: evidence/state only (`clean-ws76-successor-evidence/EVIDENCE_SEAL.md`, `WORKSTREAM_STATE.yaml`). Zero production files. Prior seal attests H01 3/3, WS76 5/5, combined+regression 18/18 — prior runtime evidence only, not integrated credit.

## B. Exact H4F/WS82/WS87 bridge diff — DIRECTLY_VERIFIED

- `4753bb7^..4753bb7` (H4F merge, 35 files / 8429 insertions): entire `forge-protocol2-bridge/` (pom.xml, WS-A1D-H4F-STATE.md, 18 main java, bridge.properties, 5 test + support, 9 decks) + root `pom.xml` exactly 1 line (`+ <module>forge-protocol2-bridge</module>`).
- `4753bb7..17ddc26` (24 files, 2651+/173-): bridge evolution (BridgeEngine/Session/DecisionFrame/StateProjection modified; InternalAuditFingerprint new 340 lines; ObservationDigest new 137 lines; StateHash deleted 116 lines; BridgeEngineTest modified; HiddenInformationNoninterferenceTest new 552; InternalAuditFailClosedTest new 461) + `research/ws82-*` + `research/ws87-*` (14 files, excluded from transplant — historical evidence only).

## C. Root pom — DIRECTLY_VERIFIED (operator re-verified)

- Clean `bc29afd`: 11 modules, no bridge. H4F `17ddc26`: 12 modules (same 11 + bridge after `forge-gui-desktop` line 72). `a37a865`: 11 modules.
- Drift `bc29afd..17ddc26` to EXCLUDE: `<version>${revision}</version>`→`2.0.14`; `versionCode` 2.0.15→2.0.14; `<tag>HEAD</tag>`→`forge-2.0.14`. Only the single module line is in scope.
- Java: both `maven.compiler.release 17`. Child convention differs (clean `${revision}` vs H4F hardcoded `2.0.14`) — source of pom compile block below.

## D. Bridge pom + dependencies — DIRECTLY_VERIFIED

`forge-protocol2-bridge/pom.xml` at `17ddc26`: parent `forge:forge:2.0.14` (stale vs clean `${revision}`/2.0.15-SNAPSHOT — compile block b); artifactId `forge-protocol2-bridge`; deps `forge-core`, `forge-game`, `forge-gui` (`${project.version}`), `gson 2.13.2`, `testng 7.10.2 (test)`. `forge.engine.sha` = `${env.FORGE_ENGINE_SHA}` (operator re-verified). TestNG version matches clean `forge-game` test scope.

## E. Bridge Forge-API surface — DIRECTLY_VERIFIED via `^import` grep at 17ddc26

BridgeEngine (StaticData/CardDb/CardPool/Deck/Game/GameRules/GameType/Match/RegisteredPlayer/PaperCard/gson); BridgeSession (Game/Match/Player); DecisionFrame (Player/SpellAbility); ExternalPlayerController (~45 Forge imports incl. GameActionUtil — NOT GameAction — Cost*, Player/Controller, SpellAbility, CardView, etc.); BridgeLobbyPlayer (LobbyPlayer/Game/IGameEntitiesFactory/Player/PlayerController); BridgeMain (FModel); HeadlessBridgeGui (HostedMatch/GuiBase/...); InternalAuditFingerprint (Game/Card/PhaseType/Player/StackInstance/ZoneType); StateProjection (MagicColor/Game/GameOutcome/Card/CardView/PhaseHandler/PhaseType/Player/PlayerView/StackInstance/ZoneType); BridgeCostDecisionMaker (Cost*); ObservationDigest/BridgeErrors/BridgeProtocol/exceptions (no Forge rules imports). No `import forge.game.GameAction` anywhere in bridge (only `GameActionUtil` + doc mentions of `rollbackAbility`/`PhaseHandler.mainLoopStep`).

## F. GameAction/PhaseHandler a37a865 vs aa5c00 — DIRECTLY_VERIFIED

- `GameAction`: +52/-1 (`a37a865..aa5c00`): CR 107.3k X-propagation, ETB remap, leavesPlay command change, new `concede(Player)` (FORGE_CONCESSION_NOT_LEGAL guard → concede → checkGameOverCondition) + `aa5c00` snapshot hardening. All present at `bc29afd` (concede ~line 1953).
- `PhaseHandler`: `a37a865..a9a95db` empty; `a9a95db..aa5c00` only snapshot hunk. Bridge-used methods (`getPhase/getTurn/getPlayerTurn/getPriorityPlayer/startFirstTurn`) identical signatures at both commits.
- Component roles (from headers/code): BridgeEngine = Protocol-2 dispatch + lifecycle w/ F3 engine-identity gate; BridgeSession = per-game state + actor/revision/option binding; ExternalPlayerController = interception boundary, fail-closed `BridgeUnsupportedDecision` on all discretionary callbacks; StateProjection = principal-scoped redaction (opponent hands `"<hidden>"`, libraries empty, face-down `"<face-down>"` unless `canBeShownTo/canFaceDownBeShownTo`; null observer = public-only; required reads throw, no defaults); ObservationDigest = SHA-256 hex of already-sanitized projection + metadata (no visibility/legality decisions, never second engine); InternalAuditFingerprint = privileged same-process mutation evidence (may see hidden zones; never wire-serialized; WS87 valid-vs-invalid(null digest) fail-closed); VersionInfo + bridge.properties = truthful identity (sysprop → env → build-filtered property; malformed fails closed; `BRIDGE_VERSION=2.0.14-ws-a1d-h4f`, `RELEASE=2.0.14` stale strings = hygiene only).
- Bridge tests at `17ddc26` (DIRECTLY_VERIFIED): BridgeEngineTest 1318 lines / 25 @Test; ProtocolTest 289/22; HeadlessGuiFailClosedTest 128/10; HiddenInformationNoninterferenceTest 552/14; InternalAuditFailClosedTest 461/11; BridgeProtocolProcessTest 456/2 (pins `ENGINE_SHA=a37a865...` — stale, must update); BridgeTestSupport 378/0; 9 deck JSONs. Total 84 @Test.

## Numbered answers

1. **Pure additive bridge surfaces — PASS (DIRECTLY_VERIFIED).** Entire `forge-protocol2-bridge/` tree at `17ddc26` (37 paths). Zero such paths at `bc29afd`. `research/ws82-*`, `research/ws87-*` excluded. No production file outside bridge + 1 root-pom line added by H4F merge.
2. **Root-project change required — PASS (DIRECTLY_VERIFIED).** Exactly one line in clean `pom.xml` after `<module>forge-gui-desktop</module>`: `+ <module>forge-protocol2-bridge</module>`. Wholesale H4F root-pom copy prohibited.
3. **Bridge APIs changed a37a865 → clean — PASS.** ONE breaking removal: `PlayerController.assignCombatDamage(Card,CardCollectionView,CardCollectionView,int,GameEntity,boolean)` abstract (`a37a865:110` present; absent at `bc29afd` — operator re-verified path `forge-game/.../game/player/PlayerController.java`). FOUR additive concretes (backward-compatible): `chooseCombatDamage`, `chooseAmountDistribution`, `canConcede`, `concede`. All other bridge-used symbols stable (`Game.getPlayers/getPlayersInTurnOrder/getPhaseHandler`, `GameActionUtil`, `CardView.canBeShownTo/canFaceDownBeShownTo/getAlternateState`, `PhaseHandler` getters, `FCollectionView`, `GuiBase/IGuiGame`, `Cost*`, `LobbyPlayer/FModel/StaticData/CardDb/Deck/Match/...` — empty diff). Removed `CardView` ctor/`updateBackSide`/`getBackSideName`/`getMayPlayPlayers` unused by bridge (zero grep hits). Behavior-only changes (GameAction X/ETB/leavesPlay/snapshot/concede; PhaseHandler snapshot; Card facedown/morph; SpellAbility proxy-targeting; CostAdjustment guard; Player library copy) need runtime proof, no compile impact.
4. **Can bridge compile unchanged? — FAIL (CODE_DERIVED, high confidence).** No: (a) `ExternalPlayerController.java:653-655` `@Override assignCombatDamage` overrides nothing → javac "method does not override" error; (b) bridge `pom.xml:9` parent `2.0.14` vs clean reactor `${revision}` → Maven resolution failure. All other sources signature-compatible. Fresh compiler proof still required.
5. **Smallest bridge-only adaptation — PASS (CODE_DERIVED proposal, mechanical only).** (i) `forge-protocol2-bridge/pom.xml:9`: `2.0.14` → `${revision}` (1 line, mirrors `forge-game/pom.xml` at clean). (ii) `ExternalPlayerController.java:652-656`: DELETE 5-line `assignCombatDamage` override; ADD two explicit fail-closed overrides (bridge-only, imports from `forge.game.combat.*`): `chooseCombatDamage(CombatDamageDecisionView)` → throw unsupported; `chooseAmountDistribution(AmountDistributionDecisionView)` → throw unsupported (preferred over relying on base `IllegalStateException` to preserve `BridgeUnsupportedDecision` taxonomy). `divideShield` override stays (still abstract at `bc29afd:123`). `StateProjection:527` and `InternalAudit:217` `game.getPlayers()` loops need no change (`PlayerCollection extends FCollection`, return type unchanged). (iii) `BridgeProtocolProcessTest.java:29`: `ENGINE_SHA a37a865...` → new authority (truthfulness; stale pin must not ship). Optional hygiene: `VersionInfo.BRIDGE_VERSION/RELEASE` strings — no semantic effect, bump only if policy demands. Every other bridge file: zero changes.
6. **Legal-action semantics altered? — PASS (CODE_DERIVED).** No. Old path (combat-damage callback → `BridgeUnsupportedDecision` abort, no AI/default) → new paths (same fail-closed abort). No new legal action, no priority/mulligan/cost/targeting touch, no GameAction/PhaseHandler/enumeration touch. Requires fresh runtime confirmation (UNKNOWN until rerun).
7. **Visibility semantics altered? — PASS (CODE_DERIVED).** No. Adaptation touches only Maven coordinates + combat-damage controller boundary. StateProjection/ObservationDigest/InternalAuditFingerprint untouched; bridge-used `CardView` signatures stable.
8. **Rules-Core edits required? — PASS.** No. `GameAction`/`PhaseHandler` at `bc29afd` already provide everything bridge needs. Bridge-only fix suffices; Rules-Core immutability holds.
9. **Rules-Core authority — PASS (DIRECTLY_VERIFIED).** Report `aa5c00aa32dfd40e213f223f8fd400c43daabb24` as RULES_CORE_AUTHORITY (exact tested technical commit). `bc29afd` is terminal + evidence (does not replace validation credit). `a37a865a` retained ONLY as historical H4F pin/fixture, never forward authority. (Note: `a9a95db` owns WS59 A04/C01/G04 seams per `git log -S "public void concede"`; `aa5c00` owns snapshot + H01 oracle; `bc29afd` seals both.)
10. **Bridge-source identity — UNKNOWN (DIRECTLY_VERIFIED absence).** No WS89 transplant commit exists yet (`git log --all --grep=WS89` empty). Prescription: first commit atop `bc29afd` that (i) copies `forge-protocol2-bridge/` from `17ddc26` excl. research, (ii) adds single root-pom module line, (iii) applies only Q5 adaptations. Its SHA becomes bridge-source identity. No SHA fabricated here.
11. **Evidence survival — PASS (matrix; runtime UNKNOWN until rerun).** Clean corpus (H01 3/3, WS76 5/5, combined+regression 18/18, in-scope checkstyle — seal at `bc29afd`) SURVIVES transplant per se (no Rules-Core delta if Q8 holds; still requires fresh rerun per WS89 contract as integrated-candidate proof). Bridge corpus (25+22+10+14+11+2 = 84 @Test) DOES NOT SURVIVE — fresh runtime required (new engine behavior + controller boundary + reactor coordinates + stale ENGINE_SHA pin). Full107/broad suites NOT_RUN/NOT CLAIMED both lineages.
12. **Architecture Authority Gate? — PASS (no gate triggered).** Gate condition (Rules-Core edit needed / ambiguous MTG policy / provider selection / freeze / evidence-semantics change) absent. Contingent STOP: if implementer must touch `GameAction`/`PhaseHandler`, weaken fail-closed paths, or redefine Rules-authority boundary → STOP + raise to Sol High. Current recommendation forbids those.

## API compatibility table (bridge-used symbols)

| Symbol | Change | Impact |
|---|---|---|
| `PlayerController.assignCombatDamage(...)` abstract | REMOVED | BREAKS `ExternalPlayerController:653` |
| `PlayerController.chooseCombatDamage / chooseAmountDistribution / canConcede / concede` | ADDED concrete | Compatible; recommend explicit bridge overrides for first two |
| All other `PlayerController` abstracts | Unchanged | Compatible |
| `Game.getPlayers/getPlayersInTurnOrder/getPhaseHandler/getStack/isGameOver/getOutcome/onPlayerLost` | Unchanged | Compatible |
| `GameActionUtil.getOptionalCostValues/rollbackAbility` | Additive only | Compatible |
| `PhaseHandler.getPhase/getTurn/getPlayerTurn/getPriorityPlayer/startFirstTurn` | Unchanged | Compatible |
| `CardView.canBeShownTo/canFaceDownBeShownTo/getAlternateState` | Unchanged | Compatible |
| `CardView` ctor/updateBackSide/getBackSideName/getMayPlayPlayers | Removed/changed | Unused by bridge → no impact |
| `Card` facedown/morph, `SpellAbility` proxy-targeting, `CostAdjustment` guard, `Player` library copy | Behavior-only | No compile impact; runtime proof required |
| `FCollectionView`, `GuiBase/IGuiGame`, `Cost*`, `LobbyPlayer/FModel/StaticData/CardDb/Deck/...` | Unchanged | Compatible |
| Maven parent `2.0.14` vs `${revision}` | Coordinate drift | BREAKS reactor join; 1-line bridge-pom fix |

## Formal fields

- `root_cause_class` = `PROVIDER_ADAPTER_DEFECT` (bridge stale vs evolved engine; not ENGINE_DEFECT — removal of deprecated `assignCombatDamage` + additive hardened boundaries is legitimate).
- `first_failing_boundary` = `ExternalPlayerController.java:653 (@Override assignCombatDamage)` + `forge-protocol2-bridge/pom.xml:9 (parent 2.0.14 vs ${revision})`. Rejected hypothesis (CardView removals break bridge): zero bridge usages (DIRECTLY_VERIFIED grep).
- Contract verdict: unchanged transplant FAIL (CODE_DERIVED); adapted transplant CONDITIONAL GO.
- `minimal_repair_surface` = 2 required files (pom 1 line; ExternalPlayerController delete 1 override + add 2 fail-closed overrides) + 1 truthfulness test constant (ENGINE_SHA).
- `repair_priority` = P0 compiler blocks first; P1 ENGINE_SHA + version hygiene with runtime.
- `recommended_execution_tier` = bounded implementer atop `bc29afd` (no rebase/merge, no Rules-Core write); then `mvn -o -pl forge-protocol2-bridge -am` + full bridge suite + process test; fresh H01/WS76 per WS89 contract.
- `validation_corpus` = all 84 bridge @Test on new reactor/engine with `FORGE_ENGINE_SHA=<new authority>` + reactor build proving no other module regressed.
- `full107_or_broad_run_readiness` = NOT READY.

## GO / NO-GO

- GO for transplant WITH Q5 bridge-only adaptations atop `bc29afd`, Rules-Core immutable.
- NO-GO for verbatim unchanged transplant (fails Q4).
- NO-GO for any qualification/credit claim until fresh bridge build + runtime green on WS89 commit.
- Architecture Authority Gate: NOT TRIGGERED.

Top-level verdict: PARTIAL (10× PASS, 1× FAIL Q4 unchanged-compile, 1× UNKNOWN Q10 future SHA; all runtime beyond prior seals UNKNOWN pending fresh proof; no authority gate).
