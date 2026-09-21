# S1 Whole-Boundary Audit — WS227 Candidate Provider (verdict: PASS)

All file:line references are at seal pin
`8ff3e7a48271eaba847608701f4c284c20dcdf68`. Full per-callback/per-family
evidence is sealed in `S1_DECISION_FAMILY_MATRIX.json`,
`STOCK_REMOTE_REACHABILITY.json`, `FALLBACK_REACHABILITY.json`,
`PRINCIPAL_SCOPING_AUDIT.json`.

## 1. Production entrypoint trace (candidate path)

`BridgeMain.main` (`forge/bridge/BridgeMain.java:23`) → stdout capture
(`:24-25`) → `HeadlessBridgeGui.install()` (`:30`) → `FModel.initialize`
(`:32`) → `BridgeEngine.dispatch` (`BridgeEngine.java:113-180`) →
`createCommanderGame` (`:390`): `new BridgeSession` (`:456`), seed/scenario
bind (`:457-484`), per-seat `new BridgeLobbyPlayer` (`:485-491`), `new
GameRules(Commander)` + `new Match` (`:492-494`), `match.createGame()`
(`:497`), `session.attach(match, game)` (`:503`). `attach`
(`BridgeSession.java:182-193`) builds an immutable seat registry from
`game.getRegisteredPlayers()` (never display names). Each seat's
`BridgeLobbyPlayer.createIngamePlayer` installs `new
ExternalPlayerController(game, player, this, session)`
(`BridgeLobbyPlayer.java:23-28`; mind-slave `:31-33`). `session.launch()`
(`BridgeSession.java:286-313`) binds `MyRandom.bindSeed` then starts the game
on a dedicated thread (`launchStarter` `:319-328`, `runStarter` `:330-360`:
`BridgeUnsupportedDecision` → FAILED + failReason; `Throwable` → FAILED).

Dispatch: `ExternalPlayerController.chooseSpellAbilityToPlay` (`:192-265`)
enumerates from `player.getAllCards()` ∪
`player.getCardsActivatableInExternalZones(true)` filtered by
`card.getAllPossibleAbilities(player, true)` (`:469-497`), classifies complex
options (`classifyComplex` `:312-357`), parks PRIORITY SUPPORTED (`:238-239`)
or UNSUPPORTED (`:229-236`, enumeration-fault `:204-207`). Execution goes
through the real `PlaySpellAbility` pipeline with identity check
(`playChosenSpellAbility` `:267-293`). Submission (`BridgeSession.java:561-666`
enumerated, `:676-776` divided) enforces CLOSED/FAILED/OVER → NO_PENDING →
WRONG_ACTOR → STALE_REVISION → UNSUPPORTED_DECISION → MALFORMED →
UNKNOWN_OPTION → actionType mismatch → range check, then exactly-once
`option.consume()` + handoff. Settle (`:786-822`) applies or fails closed.

## 2. Rules authority (sole authority: Forge Core)

`ExternalPlayerController` extends `forge.game.player.PlayerController`
directly (`:97`) — not AI, not Human, not remote. It never computes legality:
enumeration uses engine-owned collections + `canPlay(true)` filtering;
`classifyComplex` only withholds non-representable options into UNSUPPORTED
parks; mana/combat/cost paths delegate to native pipelines
(`payCostDuringAbilityResolve`, `ManaPool.payManaCostFromPool`,
`DividedAllocationDecision.resolve` with native validation). The bridge
serializes Core-created options/constraints/context, principal-safe
observations, semantic fingerprints, legal-set digests, Core RNG coordinates.

## 3. Discretionary-callback classification (109 overrides inventoried)

- `EXTERNALLY_EXPOSED_AUTHORITATIVELY`: every represented discretionary
  callback parks a complete SUPPORTED frame (priority, mulligan keep/ship,
  starting-player over full turn order, mana taps, costs, targets, modes,
  numbers, colors, combat declarations, trigger/replacement/static/copy
  choices, search, hidden-zone, commander-move, concession-as-option,
  amount/divided allocation, confirms, binary).
- `FORCED_NONDISCRETIONARY`: singleton/empty forced returns (all `.get(0)`
  sites are `size==1`-guarded), audit-only reveals/notifies, no-op
  human-sync methods, engine-delegated executors, scripted `orderCosts`.
- `FAIL_CLOSED_UNSUPPORTED`: 30+ `throw unsupported(...)` sites (London tuck,
  starting hands, convoke/improvise, splice, dice/planes/vote, card names,
  protection, optional costs, etc.); declines (`confirmPayment → false`,
  `helpPayForAssistSpell → false`, mana shortfall → false,
  `MustTarget` failure → false) always roll the engine back with audit, never
  fabricate state; oversize sets always throw, never truncate
  (128/512/9/10/4 caps).
- `HISTORICAL_NOT_ON_CANDIDATE_PATH`: all stock-remote/AI/GUI defaults
  (§4, `STOCK_REMOTE_REACHABILITY.json`).
- `PROHIBITED_FALLBACK_REACHABLE`: **NONE** — zero overrides return a default
  when discretion exists (`FALLBACK_REACHABILITY.json`). `chooseBinary`
  explicitly ignores `defaultChoice` (`:3466-3468`); no randomness, no AI,
  no GUI default, no parent fallback, no silent pass, no bridge-side legality.
- `UNKNOWN`: none.

## 4. Stock remote path (verdict A — independent, old path unreachable)

Historical failure surface (all NOT on the candidate path):
`forge-ai/.../PlayerControllerAi.java` (first-option `get(0)`, `Aggregates.random`,
`getRandom().nextBoolean()`), `forge-gui/.../PlayerControllerHuman.java`
(first-or-null, `getGui().one/getChoices`, `autoPassUntilEndOfTurn`,
remembered-order fallbacks), `FServerManager.java` (AFK `cancelAll/input.stop`
silent skip, `convertToAI` internal-AI takeover, `findRemoteController →
PlayerControllerHuman`), `RemoteClientGuiGame.java` (bandwidth fallbacks).

The candidate imports none of `forge.ai`, `PlayerControllerHuman`, or
`forge.gamemodes.net.*` (`BridgeEngine.java:1-26`,
`BridgeSession.java:1-17`); instantiates only `ExternalPlayerController`
(`BridgeLobbyPlayer.java:26,32`); `HeadlessBridgeGui` choice-bearing methods
all `throw unsupportedInteraction` (`HeadlessBridgeGui.java:234-283`).
No wrapping, no delegation, no `dangerouslySetController`, no `ReplyPool`.
Verdict **A**: WS227 replaces the stock remote path with an independent
candidate provider path; the old path is not reachable from that provider.

## 5. Principal scoping

Per-actor redacted observation (`StateProjection.java:129-142` actor gating;
hand `:505-518` `<hidden>`; battlefield/exile `:520-591` dual-gate
`canBeShownTo && canFaceDownBeShownTo`; library count-only `:488-494`;
stack redaction `:681-742`; `bridgeMeta` actor-scoped `:191-218`;
execution-error binding `BridgeSession.java:216-233`; `WRONG_ACTOR` +
`withheld` non-actor responses `BridgeEngine.java:599-611,846-893`).
Null observer = public-only. No cross-principal leak in code.

## 6. Legacy note — COMBAT_ORDER

`COMBAT_ORDER` (Damage Assignment Order) is still parked as a discrete frame
for `orderBlockers/orderBlocker/orderAttackers`
(`ExternalPlayerController.java:2762-2852`, `DecisionFrame.java:39`):
`≤1 → return`, `>4 → throw`, else full permutation frame. This is
legacy/non-current under current Magic rules but is handled as an
externally-decided, fail-closed frame — not a silent default, not bridge-side
legality. Modern `COMBAT_DAMAGE` remains separate (`chooseCombatDamage`
`:1357`, even lone triples parked). S1-neutral; recorded so no future work
mistakes family-name existence for current-rules behavior proof.

## S1 verdict: PASS

Sole-Rules-authority design + legal-action surface inventory (30 families) +
fail-closed unsupported-path design + principal-scoped observation design are
all present at CODE_DERIVED minimum, and no PROVEN whole-boundary violation
exists on the production-reachable candidate path (verdict A, zero prohibited
fallbacks reachable). The historical S1/AF04 failure is superseded for the
proposed provider path — and only for that path.
