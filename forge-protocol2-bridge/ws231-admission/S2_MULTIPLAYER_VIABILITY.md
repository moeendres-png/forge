# S2 Commander / Multiplayer Structural Viability (CODE_DERIVED, separated from correctness)

S2 verdict is FAIL on cardinality (fixed-four provider gate), but
Commander/multiplayer structural viability is inventoried separately per the
contract ("S2 FAIL only if cardinalities themselves unconstructible"; Partner
init blocks are S3-relevant gaps). Viability here is structural only:
constructibility ≠ correctness; no behavior credit is claimed.

## Commander constructs (all roster-generic, none hardcode 4)

- `GameType.Commander` exists (`forge-game/.../GameType.java:36`).
- `RegisteredPlayer.forCommander` binds commanders + startingLife 40
  (`RegisteredPlayer.java:134-139`; variant-layered 154-157, CR 903.7 cited).
- Bridge import enforces Commander shape: 1–2 commanders, exactly 100 cards
  (`BridgeEngine.java:310-318`); real `DeckSection.Commander` + main pools via
  `CardDb` (`:329-346`); starting-life injection rejected (`:446-451`); all
  players life==40 asserted from projection
  (`BridgeEngineTest.testLifecyclePassAndShutdown:200-205`).
- Command zone native: `Player.java:77` zone set incl. `ZoneType.Command`;
  `getCommanders` (`:2736`), `addCommanderDamage` (`:2853-2854`), native
  registration/life (`:2948-2987`); commander tax (`CostAdjustment.java:57-67`);
  commander-cast-from-Command (`MagicStack.java:390`); command-zone
  movement/effects (`GameAction.java:723-738,1874,2458`).
- Bridge is command-zone aware: `ScenarioBootstrap.takeCommander` (`:481-498`),
  `findCommander` (`:446-474`), commander-damage seeding (`:410-432`).
- Partner mechanics script-encoded + hook-backed (Ishai/Rograkh/Esior/Kediss/
  Jeska `K:Partner`; `keyword/Partner.java:3`); commander-cast counting
  (`AbilityUtils.java:2346,3289`); color-identity mana (`Mana.java:125`,
  `CardProperty.java:712-740`); commander-damage redirect (Kediss script +
  `DamageAllEffect`). Commander flows have exactly one non-mechanic execution
  test (Rograkh cast vehicle) — recorded as S3 gap family F3, not an S2 block.

## Multiplayer constructs (generic over roster size)

- `Game` constructor takes `Iterable<RegisteredPlayer>`, per-player teams, no
  count check (`Game.java:315-365`); `getPlayers` (`:396-398`),
  `getPlayersInTurnOrder` direction-aware (`:407-422`),
  `getRegisteredPlayers` survives elimination (`:428-430`, the session
  registry basis); `isMultiplayer = size > 2` (`:866`).
- `Match.createGame` (`Match.java:72-74`), `startGame` (`:80-92`) →
  `GameAction.startGame` (`:2376-2436`): dice/chooser
  (`determineFirstTurnPlayer :2438-2496` → externalized `chooseStartingPlayer`),
  per-player draws/mulligan (`:2390-2406`), `startFirstTurn` (`:2426-2427`,
  the `ScenarioBootstrap.apply` injection point).
- Session registry (`BridgeSession.java:182-193`), turn order, state
  projection (`StateProjection.winnersState :691-706` iterates
  `game.getPlayers()`), terminal outcomes (`SemanticReplay.terminalOutcomes
  :807-864` iterates `registryPlayers()`, dynamic seat sort) are all dynamic.
- Known limits (not S2 blocks, recorded): mulligan-ship path aborts loudly
  (`testShipAbortsLoudly`; `tuckCardsViaMulligan` unsupported); pod-level
  partner lifecycle explicitly unqualified (`BridgeEngine.java:261`); seeded
  determinism single-flight only.

## Viability verdict: VIABLE_STRUCTURALLY (CODE_DERIVED)

Commander/multiplayer semantics are structurally viable through the candidate
path at 4P; engine paths are roster-generic. Correctness beyond construction
is unqualified and belongs to S3/S5, not S2.
