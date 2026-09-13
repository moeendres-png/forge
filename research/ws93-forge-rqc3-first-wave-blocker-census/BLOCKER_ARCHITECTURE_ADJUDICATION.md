# WS93 Blocker Architecture Adjudication (read-first, XHIGH→HIGH throughput)

Source truth: WS89 integrated candidate HEAD `09e5df4` (bridge `ede2b98`, engine `aa5c00a`).
Authority: WS90 corrected 15-slot pack + 20-kind decision requirements (read-only).

## Bridge decision surface (pinned source)

- Only externally parked frames: `PRIORITY`, `MULLIGAN` (binary keep/ship), `STARTING_PLAYER`.
- `PRIORITY` offers `pass` + candidates passing `ExternalPlayerController.classifyComplex == null`.
- `classifyComplex` blockers: `TARGETING`, `MODAL` (Charm), `ANNOUNCE`, `X_VALUE`,
  `OPTIONAL_COST`, `MANA_PAYMENT_CHOICE` (any nonzero mana), `MANA_OUTPUT_CHOICE`,
  `COMPLEX_COST:*`.
- All other native callbacks fail closed via `BridgeUnsupportedDecision`
  (targets, modes, numbers, colors, combat declare, damage assignment, zone-change
  selection, replacement/static choice, optional costs, confirms).
- Protocol: `select_targets`, `choose_modes`, `order_triggers`, `concede` return
  explicit unsupported (fail-closed). `get_capabilities` advertises
  `target_selection_supported=false, mode_selection_supported=false,
  trigger_order_supported=false, concede_supported=false,
  legal_actions_supported=false, action_submission_supported=false`.
- Commander: real zero-mana targetless command-zone casts ARE offered (proven by
  existing `testCommanderCastCountOnStack`, not re-run here). Commander
  zone-movement choice is a confirm/replacement path → unsupported.
- G04: WS59/WS76 engine-native concession exists, but bridge `CONCEDE` is
  unsupported and the controller exposes no leave-game callback → engine
  concession is NOT_REACHED via bridge (protocol blocker, not engine defect).

## 20-kind carrier classification

| kind | carrier status |
|---|---|
| pass | SUPPORTED (pass_priority / priority pass option; mulligan keep/ship binary) |
| cast | PARTIAL (offered only if targetless + nonmodal + X-free + zero-mana + simple cost; Commander zero-mana cast included) |
| activate | PARTIAL (same priority gate as cast, `activate_ability`) |
| mana source | PARTIAL (fixed-output mana abilities only; choice-output → MANA_OUTPUT_CHOICE) |
| X | BLOCKED (X_VALUE; chooseNumber unsupported) |
| mana payment | BLOCKED unless provably zero (MANA_PAYMENT_CHOICE; nonzero declined → rollback) |
| targets | BLOCKED (TARGETING + chooseTargetsFor/chooseTarget unsupported + select_targets unsupported) |
| alternate cost | BLOCKED (getAbilityToPlay multi-variant + chooseOptionalCosts unsupported) |
| hidden-zone selection | BLOCKED (chooseCardsForCost / zone-change selection unsupported) |
| modes | BLOCKED (MODAL + chooseModeForAbility + choose_modes unsupported) |
| replacement ordering | BLOCKED (chooseSingleReplacementEffect unsupported) |
| trigger ordering | BLOCKED (orderSimultaneousSa multi + order_triggers unsupported) |
| attackers / defender per attacker | BLOCKED (declareAttackers unsupported) |
| blockers | BLOCKED (declareBlockers unsupported) |
| combat damage assignment | BLOCKED (chooseCombatDamage unsupported) |
| search | BLOCKED (zone-change selection unsupported) |
| Commander movement | BLOCKED (confirm/replacement path unsupported; observation of count/damage is visible, movement choice is not) |
| concession | BLOCKED at protocol (concede unsupported; no controller leave-game callback) |
| copy choices | BLOCKED (entity/face/state selection unsupported) |
| trigger ordering | BLOCKED (see above) |

Epistemics: protocol-unsupported rows are RUNTIME_VERIFIED by the census harness
(real `BridgeEngine.dispatch`). Classifier rows are CODE_DERIVED from pinned
source + callback-override inventory; per-card reachability is NOT claimed beyond
the first-blocker mapping. NOT_REACHED is not PASS. Callback existence is not
runtime verification.
