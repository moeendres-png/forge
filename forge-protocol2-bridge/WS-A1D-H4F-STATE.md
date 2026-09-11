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
   post-state. No Lab polling of internals, no AI, no defaults, no auto-pass.
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
 - [x] Module scaffold + root pom module entry
 - [ ] BridgeMain / Protocol / HeadlessBridgeGui / VersionInfo
 - [ ] ExternalPlayerController / BridgeCostDecisionMaker / classifier
 - [ ] BridgeEngine dispatch (20 messages + 3 compat aliases) / sessions / projection / audit
 - [ ] Fixture decks (simple-only + targeted-bolt negative)
 - [ ] Tests: protocol unit, in-JVM engine, separate-process JSONL
 - [ ] Evidence + handoff

 ## Validated substantive head
 - TBD (updated per commit)

 ## Exact next action
 - Implement bridge sources, build the reactor slice, run tests.
