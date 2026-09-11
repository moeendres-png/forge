# WS-A1D-H4F — Forge Protocol-2 Bridge Workstream State

 Ownership: Forge-side bridge implementation only. Lab is read-only. No manifest edits.
 No push authorized. Local commits only.

 ## Source lock
 - Forge repo worktree: /home/moeen/code/ws-a1d-h4f-forge-protocol2-bridge
 - Branch: architecture/ws-a1d-h4f-forge-protocol2-bridge-20260911
 - AUDIT_BASE_SHA: a37a865a53280dd8ad6fad3384d69611e8c5a42f (verified `git rev-parse HEAD`)
 - AUDIT_BASE_TREE: 4471ff068dd23127fc5878bdffa0c0e6de8e6c28 (verified)
 - Lab authority: origin/main = 950d6fd6f7ec2b7f1835d2ed744e2c8d146d4e39 (verified via
   `git ls-remote`; local Lab checkout is on a research branch ahead of main — untouched).
 - Lab protocol contract engine_runtime.py at 950d6fd6 fetched read-only via raw GitHub;
   message surface identical to local copy. No Lab mutation performed.
 - Java: OpenJDK 21.0.12 (build targets 17 per parent pom). Maven 3.9.12. No mvnw.
 - Xvfb: NOT installed, NOT needed (bridge uses a no-Swing IGuiBase stub; engine path
   is Match-direct like SimulateMatch, no desktop Main/Singletons).
 - Reactor: parent forge:2.0.14 + new leaf module forge-protocol2-bridge
   (deps: forge-core, forge-game, forge-gui, gson; test: testng). No forge-ai dep
   (structural F1 guarantee). No forge-gui-desktop dep (no Swing).

 ## Phase 0 — done
 Clean tree at pin. Card data ships in-repo (forge-gui/res, 54k files, tracked).

 ## Phase 1 — native boundary findings (all DIRECTLY_VERIFIED at pin)
 - Narrowest interception: `forge.game.player.PlayerController` (abstract, ~75 methods).
 - Priority: `PhaseHandler.mainLoopStep` calls `chooseSpellAbilityToPlay()` (null = pass),
   then `playChosenSpellAbility(sa)` per SA. No separate pass method.
 - Execution: `PlaySpellAbility.playSpellAbility(controller, player, sa)` with real
   rollback (`GameActionUtil.rollbackAbility`) on failure. No resolve() shortcut used.
 - `AvailableActions` (forge-ai) is heuristic/timeout-based — MUST NOT and DOES NOT back
   legal-action enumeration. Enumeration primitive is per-card
   `Card.getAllPossibleAbilities(player, true)` (= same call the human UI makes per click)
   filtered by engine-truthful `SpellAbility.canPlay(true)` (zone/timing/restriction/cost
   pre-checks in Spell/AbilityActivated/LandAbility overrides).
 - Observation gate: `CardView.canBeShownTo(PlayerView)` (hand only to controller,
   library hidden, face-down gated, mayLook overrides). `PlayerView.getHand()` of
   opponents still returns real objects in-process — bridge MUST filter via canBeShownTo.
 - Hidden zones: no per-player sanitized GameView exists; projection is bridge-side.
 - RNG: global `forge.util.MyRandom` static; no per-Game seed. seed_supported=false.
 - State revision: no global per-mutation revision on Game; bridge mints its own
   monotonic frame revisions + canonical state hashes.
 - Construction: `FModel.initialize(null,null)` + `GameRules(Commander)` +
   `RegisteredPlayer.forCommander(deck)` + `Match(rules, players, title)` +
   `match.createGame()` + `match.startGame(game)` on a dedicated thread.
 - Mulligan default rule: London. First-mulligan-free in multiplayer. Ship path needs
   tuck selection (unsupported -> explicit abort). Keep path is binary external choice.
 - Starting player: engine dice-rolls the chooser, chooser picks any player (multiplayer).

 ## Phase 2 — architecture (separate process, JSONL, no second rules engine)
 - New leaf module `forge-protocol2-bridge`, GPL side only. stdin/stdout JSONL
   Protocol 2.0.0, one request -> one response. stdout = responses only; all diagnostics
   (incl. Forge logs) -> stderr/files.
 - Engine runs on a dedicated game thread per session. Controller callbacks rendezvous
   with the protocol thread via parked DecisionFrames (revision-bound, single-use
   opaque UUID option IDs, native SpellAbility bindings retained bridge-side).
 - `get_legal_actions` reports the currently parked frame (or explicit
   no_pending_decision/unsupported metadata). `submit_action`/`pass_priority`/
   `resolve_mulligan` validate session/actor/revision/option BEFORE touching the engine,
   deliver the selection, then wait for the next park (or terminal) to answer with
   post-state. Compat aliases with identical verified semantics: create_game (commander
   request), get_state, shutdown, get_event_log. No Lab polling of internals, no AI,
   no defaults, no auto-pass.
 - F5 completeness: offered set = {pass} + every canPlay(true) SA across actor zones
   (Hand/Battlefield/Command/Graveyard/Exile/Library). Structural classifier marks a
   frame UNSUPPORTED (zero options, explicit reason) if ANY candidate needs targeting,
   modes, X/announce, optional costs, or non-forced cost parts. Never a partial list.
 - Executor: real `PlaySpellAbility` pipeline. `BridgeCostDecisionMaker` mirrors ONLY
   provably-forced visits (self-tap/untap/add-mana numbers, mana-from-pool); every other
   cost visit declines (null -> engine rollback -> execution error, session survives).
   Mana paid ONLY from floating pool via engine `ManaPool.payManaCostFromPool`
   (test-probe then deduct; uncovered -> execution failure, no partial state).
   Every other controller choice callback throws BridgeUnsupportedDecision (loud abort,
   never a silent/auto decision). confirmPayment declines (false).
 - Principal projection bridge-side via `canBeShownTo`; opponent hands -> "<hidden>" x N
   (count is public info), libraries -> [] (order hidden), face-down -> "<face-down>"
   unless shown. Default observer (absent) redacts all hands.
 - Rejected: network service (unnecessary), embedding Lab Python (out of scope),
   porting rules (forbidden), using PlayerControllerAi (F1), AvailableActions (heuristic).

 ## Implementation status
 - [x] Module scaffold + root pom module entry (1-line tracked diff; all else additive)
 - [x] BridgeMain / Protocol / HeadlessBridgeGui / VersionInfo (F2 stdout redirect, F3 identity)
 - [x] ExternalPlayerController / BridgeCostDecisionMaker / classifier (F1/F5/F7)
 - [x] BridgeEngine dispatch (20 messages + 4 compat aliases) / sessions / projection / audit
 - [x] Fixture decks (45 scripts manually verified; simple-only + Swords negative)
 - [x] Tests: 12 protocol unit + 8 engine + 1 separate-process = 21 green, full reactor green
 - [x] Published 48d6e50fd339 (REMOTE_REVIEW_01 then returned FAIL_REMEDIATION_REQUIRED)
 - [x] Remediation 01 (R1-R7, commit 98e538ed33): see below; 38/38 green, reactor green

 ## Remote review 01 — FAIL_REMEDIATION_REQUIRED (remediated, publication-gated)

 Coordinator found publication valid and architecture viable; PR blocked on R1-R7.
 H4B Forge remains PARTIAL. No H4B PASS written. Previous 21/21 kept as historical
 evidence only for unaffected surfaces; changed surfaces re-qualified below.

 - R1 enumeration atomicity: any zone/card native-enumeration failure now throws
   BridgeNativeEnumerationException and parks an UNSUPPORTED frame with reason
   NATIVE_ENUMERATION_FAILED and zero options (no catch-and-continue, no partial set).
   Seam: package-private static fault flag, production-unreachable.
 - R2 mandatory binding: session submit rejects null/empty actor, option, action type
   and null revision (MALFORMED_REQUEST) before all other checks; submit_action,
   pass_priority and resolve_mulligan require explicit actor/revision (and keep);
   the `keep=true` default is removed.
 - R3 principal scoping: gameState includes legal_actions only for the frame actor;
   get_legal_actions requires actor_id and rejects non-actors with WRONG_ACTOR;
   shownName obeys canBeShownTo AND canFaceDownBeShownTo literally, with a true-name
   path (native alternate state, then paper identity) for authorized face-down views.
 - R4 no fabricated defaults: required reads go through require() and throw
   BridgeProjectionException (pre-first-turn null phase keeps a documented structural
   "beginning" mapping; optional dicts with Lab defaults stay all-or-nothing {});
   get_game_state and submit responses convert failure to PROJECTION_FAILED.
 - R5 starting authority: create rejects starting_player_seat
   (STARTING_PLAYER_SEAT_UNSUPPORTED) and starting_life (STARTING_LIFE_UNSUPPORTED,
   even 40); RegisteredPlayer.forCommander establishes canonical 40 life (read back
   from state, T9); chooseStartingPlayer parks a STARTING_PLAYER frame for Forge's
   dice/rules-selected chooser with the complete turn-order player set, opaque IDs,
   retained native Player bindings, actor+revision submission, no default.
 - R6 zero-mana-only execution: nonzero CostPartMana (via getManaCostFor().isZero(),
   with the engine "no cost" sentinel accepted) forces MANA_PAYMENT_CHOICE for the
   whole frame; choice mana outputs (Any/Combo/Special/Chosen via native
   AbilityManaPart structure) force MANA_OUTPUT_CHOICE; fixed-output taps remain
   (Plains tap proven: pool W==1, no controller choice); payManaCost declines
   nonzero and vacuously accepts ZERO/no-cost; applyManaToCost passes only paid
   balances. No bridge call path to payManaCostFromPool/CostPayment.getMana remains.
 - R7 fail-closed protocol/identity: protocol_version required and 2.0.0, request_id
   non-empty, contradictory canonical/compat aliases rejected at parse;
   start_engine and all gameplay handlers require a valid 40-hex identity
   (sysprop forge.engine.sha, then FORGE_ENGINE_SHA env, then build property;
   present-but-malformed fails closed) with ENGINE_IDENTITY_UNAVAILABLE otherwise.
   Only get_provider_version/get_capabilities (observability) and shutdown_engine
   stay ungated. No SHA is hardcoded in Java.
 - Capabilities unchanged-false: legal_actions_supported and
   action_submission_supported remain false; notes now say zero-mana-only.

 ## Source-derived conclusions (CODE_DERIVED until runtime-exercised)

 - PhaseHandler.isSkippingPhase skips DRAW only on turn 1 with exactly 2 players:
   4-player games (incl. all fixtures) always draw turn 1.
 - ManaEffect.resolve calls specifyManaCombo/chooseColor only for combo/any/special
   mana; fixed output resolves with no controller choice (notifyOfValue only).
 - CostPayment.getMana returns the single best-weighted pool option automatically and
   only calls chooseManaFromPool on ties (the removed R6 defect).
 - AbilityManaPart.isAnyMana/isComboMana/isSpecialMana + "Chosen" output exactly mark
   choice-bearing mana (verified against ManaEffect branches).
 - CardView.canBeShownTo is the zone gate (battlefield visible to all) while
   canFaceDownBeShownTo is the face gate (controller/may-look/mindslave, else only
   Battlefield/Stack/Sideboard-to-controller); the GUI combines both
   (AbstractGuiGame alternate-state rule). Engine Card.getName() is blank while
   face-down; the true name lives in the alternate state/paper identity.
 - ManaCost ZERO/isZero()/isNoCost(): `{0}` isZero()==true; land "no cost" sentinel
   isNoCost()==true (both payment-free); nonzero costs are neither.
 - GameAction.startGame order: determineFirstTurnPlayer (dice + chooser) runs before
   MulliganService, so STARTING_PLAYER always precedes mulligan frames.

 ## Remediation validation (DIRECTLY_VERIFIED, this workstream)

 - `mvn -pl forge-protocol2-bridge -am test`: BUILD SUCCESS.
 - ProtocolTest 19/19 (T1 envelope incl. alias conflicts; T2 identity incl. missing
   identity fail-closed and build-property fallback via shadow resource).
 - BridgeEngineTest 17/17 (T3 enumeration fault; T4 Memnite retained; T5 Grizzly
   MANA_PAYMENT_CHOICE + Plains tap; T6 actor/revision negatives per kind incl.
   STARTING_PLAYER; T7 no-defaults incl. missing keep; T8 external p3 choice then
   Forge starts with p3; T9 seat/life rejection + life==40 read; T10 adversary incl.
   option-ID/label absence + null observer; T11 face-down/may-look gates; T12
   forced projection failure; T13 callback throws).
 - BridgeProtocolProcessTest 2/2 (T14 full pipe with starting choice, keeps,
   revision-bound pass, negatives, principal scoping, shutdown; DISPLAY unset,
   headless, exact FORGE_ENGINE_SHA=a37a865a...).
 - Total: 38/38 bridge + forge-game 3/3. Changed-surface evidence re-qualified;
   unaffected surfaces (import validation, lifecycle shape, targeting fail-closed,
   rollback execution) preserved and still green.
 - Remaining unsupported (unchanged): targets, modes, X, combat, trigger ordering,
   tuck, concede, replay, RNG control, general mulligan, scenario injection,
   partners, non-4P pods, nonzero-mana execution, choice mana outputs.
 - H4B_FORGE_RECOMMENDATION = PARTIAL. RULES_BEHAVIOR_CREDIT_CHANGE = 0.
   PRODUCTION_PROVIDER = NOT SELECTED. ARCHITECTURE_FREEZE = NOT CLAIMED.

 ## Validated substantive head
 - Remediation substantive: 98e538ed336ddd254a4e6055280b4c814bf647ec
   (this file updated separately; see HEAD for the state-only checkpoint)
 - Prior: 6e91c3403a9 (tests), 48d6e50fd33 (published + state)
