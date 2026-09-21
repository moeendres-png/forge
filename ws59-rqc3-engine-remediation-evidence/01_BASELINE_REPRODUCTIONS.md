# WS59 Baseline Reproductions (before repair, at audit pin)

## A04 — DIRECTLY_VERIFIED (WS55R_A04_EVID.json, 1282 frames) + CODE_DERIVED
- Exact fixture assembled natively: Doubling Season + Hardened Scales on battlefield,
  Stonecoil Serpent X=3 announced by value and 3 G paid natively, spell resolved.
- Observed: 0 counters placed, Serpent 0/0 died via SBA, zero
  chooseSingleReplacementEffect CALLED milestones, no replacement_effect frame.
- Source at pin: GameAction.moveTo creates battlefield `copied` via copyCard without
  castSA/castFrom (only stack path sets them); ETB CounterMap applied via
  GameEntityCounterTable.replaceCounterEffect(etb=true) which SKIPS AddCounter
  ("ETB Counters are already handled in the Move Event"); Moved event gathers only
  Moved-type replacements with empty CounterMap, so AddCounter DS/HS never match.
- Classification: ENGINE_DEFECT (NATIVE_ENGINE_DEFECT). No provider repair conformant.

## C01 — DIRECTLY_VERIFIED (WS55R_C01_EVID.json, 837 frames) + CODE_DERIVED
- Corrected fixture: 5 Islands, FoW + blue Turn to Frog in hand, opposing Elves
  stack spell. Stages 1-5 PASS (both variants offered, pitch exactly bound d713/o3,
  engine ACCEPTED_CONTINUED). Stages 6-11 NOT_REACHED: zero target/cost_exile/confirm
  frames, spell in hand, life 40, exile empty, Elves resolved natively.
- Source at pin: TargetRestrictions.getAllCandidates enumerates Players + Cards in
  tgtZone only, never stack SpellAbilityStackInstances; FoW TargetType Spell has
  exactly one legal target (Elves stack spell) unresolvable by construction.
  SpellAbility.canTarget zone-gates stack host cards to Battlefield; CardUtil
  getValidCardsToTarget looks only in tgtZone (Battlefield). Pre-cost setupTargets
  therefore cannot reach the authoritative target/cost continuation.
- Classification: ENGINE_DEFECT (NATIVE_ENGINE_DEFECT). Provider filtering/parallel
  solver forbidden.

## G04 — CODE_DERIVED (source reads at pin)
- Authoritative API Player.concede() exists (no checks) with native SBA transition
  (checkGameOverCondition -> Game.onPlayerLost 800.4 cleanup), but initiation is
  GUI-only (IGameController/PlayerControllerHuman/GameMenu). PlayerController
  (forge-game Decision API, 52 methods) has NO concession method: no offer set exists
  to externalize, no current-principal action identity projectable.
- No conformant provider seam (standing CONCEDE pseudo-option fabricates an offered
  choice; orchestration direct-call injects outcome without offered action).
- AG-1 EXTERNALLY_RULE_VALIDATED (coordinator gates): CR 104.3a concession at any
  time, leave-game cleanup per 800.4 based on leaving, not cause.
- Classification: ENGINE_DEFECT (missing engine-native offered action).
