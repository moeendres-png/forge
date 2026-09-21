# WS59 Root-Cause Analysis (systemic, general — no card names)

## A04 two stacked layers
- (a) Base X linkage (CR 107.3k): stack->battlefield `copied` via copyCard loses
  castSA/castFrom (only stack path propagated them). ETB PutCounter CounterNum X
  (Count$xPaid) therefore reads 0 via c.getXManaCostPaid(). Additionally the Moved
  replacement populates the ETB table keyed by the pre-move stack object (same ID,
  different identity), while the battlefield copy is a different object; without
  ID-based remapping counters land on the detached object and are lost.
- (b) AddCounter routing (CR 616.1): ETB CounterMap path (GameAction:561,
  etb=true) skips ReplacementType.AddCounter entirely; Moved event only gathers
  Moved-type replacements with an initially empty CounterMap, so AddCounter DS/HS
  never match. Existing chooseSingleReplacementEffect (ReplacementHandler:220,
  layer Other) would yield the authoritative 2-order offer once the populated map
  is routed through AddCounter.

## C01 continuation boundary
- Stack-spell candidacy gap: getAllCandidates/hasCandidates/getNumCandidates consult
  only Players + Cards-in-tgtZone, never game.getStack() for SpellAbilityStackInstances.
  FoW TargetType Spell therefore has zero resolvable candidates by construction.
- Zone gating: SpellAbility.canTarget rejects stack host cards (Stack not in default
  [Battlefield] tgtZone) even when canTargetSpellAbility deems the stack spell
  targetable. CardUtil.getValidCardsToTarget enumerates only tgtZone cards, so Human
  and headless target selection never see the stack host proxy.
- Consequence: pre-cost setupTargets cannot reach the authoritative target callback;
  silent rollback leaves spell in hand with no cost_exile (hidden-zone pitch choice),
  life, exile, or counter consequence. Offer-side seams (variants, mana publication,
  exact binding) already proven; only target-engagement fails.

## G04 missing seam
- Decision offer absent: PlayerController has no concession method, so no offer set
  exists. Initiation-only API (Player.concede) is not externalizable as a Decision.
- Architecture: concession must be engine-owned (Rules Core alone determines Legal
  Actions), available at any time per 104.3a (not priority-gated), with native 800.4
  cleanup preserved. Provider may transport but never fabricate; orchestration
  direct-call proves orchestration, not a Decision seam.

All three are NATIVE_ENGINE_DEFECT, general engine semantics (replacement/ETB/X,
stack targeting/target-candidacy, concession action), not card-specific.
