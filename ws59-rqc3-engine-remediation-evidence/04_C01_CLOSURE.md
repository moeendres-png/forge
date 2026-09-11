# WS59 C01 Closure

## General repairs (forge-game, no card names, no provider work)
1. `TargetRestrictions.hasCandidates`: also consult game.getStack() via engine's own
   canTargetSpellAbility when SA declares TargetType (stack objects), independent of TgtZone.
2. `TargetRestrictions.getNumCandidates`: count stack via canTargetSpellAbility when
   tgtZone contains Stack OR SA declares TargetType (avoids double-count).
3. `TargetRestrictions.getAllCandidates`: include host cards of targetable stack spells
   as candidate proxies when SA declares TargetType (checked via canTargetSpellAbility;
   self excluded per 115.5). Only enumerates what the engine deems targetable.
4. `SpellAbility.canTarget`: general spell-stack exception — a Stack-zone host card
   whose spell is targetable via canTargetSpellAbility passes the TgtZone gate for
   TargetType effects. Helper isStackSpellProxyTargetable (ID-aware for copies).
   Non-TargetType effects (e.g. Bolt vs stack creature) still zone-gated.
5. `CardUtil.getValidCardsToTarget`: also include stack host cards whose spell is
   targetable via canTargetSpellAbility when SA declares TargetType (self excluded).
   Relies on engine authority, no legality reconstruction.

## Preserved native semantics
- Alternate-cost selection: engine-enumerated via Card.getAllPossibleAbilities /
  GameActionUtil.getAlternativeCosts (both variants side-by-side, exact binding).
- Hidden-zone selection: pitch card choice remains via engine-native chooseCardsForCost
  (principal-only projection); life/exile/counter consequences native.
- No filtering, no parallel solver, no outcome injection, no fallback.

## Tests
- Ws59C01CostPitchTest.testExactFixtureForceOfWillPitchSeesStackSpell: Elves on stack,
  FoW + Frog in hand, asserts both variants offered, hasCandidates/getNum/getAll/
  getValid/canTargetSpellAbility/canTarget(host) all see Elves, and authoritative
  binding of the stack spell (target is SpellAbility, not Card).
- testGenericCounterspellSeesStackSpell: Cancel vs Grizzly Bears spell (different names).

Evidence class: DIRECTLY_VERIFIED once new tests pass.
