# WS59R C01 Closure — authoritative decision continuation (non-bypass)

## General repairs (forge-game, unchanged by WS59R; no card names, no provider work)
1. `TargetRestrictions.hasCandidates`: also consult game.getStack() via engine's own
   canTargetSpellAbility when SA declares TargetType (stack objects), independent of TgtZone.
2. `TargetRestrictions.getNumCandidates`: count stack via canTargetSpellAbility when
   tgtZone contains Stack OR SA declares TargetType (avoids double-count).
3. `TargetRestrictions.getAllCandidates`: include host cards of targetable stack spells
   as candidate proxies when SA declares TargetType (checked via canTargetSpellAbility;
   self excluded per 115.5). Only enumerates what the engine deems targetable.
4. `SpellAbility.canTarget`: general spell-stack exception — Stack-zone host cards whose
   spell is targetable via canTargetSpellAbility pass both the TargetType Card-validity
   check (owned by SpellAbility authority, not Card.isValid) and the TgtZone gate for
   TargetType effects. Helper isStackSpellProxyTargetable (current-zone, ID-aware).
   Non-TargetType effects remain zone-gated.
5. `CardUtil.getValidCardsToTarget`: also include stack host cards whose spell is
   targetable via canTargetSpellAbility when SA declares TargetType (self excluded,
   current-zone checks).

## Boundary disposition (WS55R_C01 packet stages)
- A. variant enumeration — DIRECTLY_VERIFIED: both FoW variants engine-enumerated via
  Card.getAllPossibleAbilities (no reconstruction); pitch identified structurally by
  CostExile + CostPayLife classes, normal by CostPartMana without them. No Cost.toString,
  no contains-string heuristics.
- B. alternate-cost selection — DIRECTLY_VERIFIED: exact pitch SpellAbility object from
  the offered list is used for setupTargets (identity, not reconstruction).
- C. hidden-zone pitch selection — TECHNICALLY_CONFORMANT / NOT_RUN: pitch Exile part
  structurally requires Hand (ZoneType enum); blue Frog present in Hand (isBlue + zone);
  actual pitch-choice callback deferred to First-Wave behavior. Never DIRECTLY_VERIFIED.
- D. target decision callback — DIRECTLY_VERIFIED: Ws59NativeTargetHarness (Forge
  PlayerController seam, extends test-suite controller, fail-closed elsewhere) records
  callbackReached via SpellAbility.setupTargets (the path that previously aborted).
- E. target binding — DIRECTLY_VERIFIED: harness enumerates ONLY via engine authority
  (stack + canTargetSpellAbility), requires exact single-match (fail closed), binds that
  SpellAbility; test asserts callbackReached, selected === stack spell, and
  getFirstTargetedSpell() === stack spell. No manual getTargets().add in test methods
  (sole addition lives inside the engine-invoked controller callback, as in Human/AI).
- F. payment/life/exile — NOT_RUN (deferred to First-Wave behavior).
- G. resolution/counter outcome — NOT_RUN (deferred to First-Wave behavior).

## Preserved native semantics
- No provider filtering/solver; no fallback; hidden-zone authority untouched.

## Tests (non-bypass)
- Ws59C01CostPitchTest.testExactFixtureForceOfWillPitchAuthoritativeDecision
- Ws59C01CostPitchTest.testGenericCounterspellAuthoritativeDecision (Cancel/Bears)
