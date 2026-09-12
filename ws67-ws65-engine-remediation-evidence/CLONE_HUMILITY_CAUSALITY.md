# Clone Under Humility — Causality

Packet: `WS65-RP-CLONE-HUMILITY-01` (blocks RQ-C3-H01).
WS65 classification: ENGINE_RULES_DEFECT as leading hypothesis (optional
ETB-copy never engages under Humility; no-Humility WCOPY control proves
transport sound). WS65 symptom: Clone resolving under Humility produces zero
confirm/choice/milestone frames across 2 casts.

## Engine-direct reproducer (actual cards, no provider)

`.../ws67/Ws67EngineRemediationTest.java`

- `testCloneUnderHumilityKeepsCopyDecisionAndAppliesLayers`: Humility +
  opponent Runeclaw Bear on battlefield; Clone in hand, 4x Island; copy choice
  scripted to Runeclaw Bear; full cast path, native payment. Asserts: stack
  placement; post-resolution second Bear copy on battlefield (copies take the
  copied name); `confirmReplacementEffect` asked ≥1 (optional ETB-copy engaged
  at the correct pre-entry point); copy choice offered ≥1; 1/1 under Humility;
  decisive layer proof — destroy Humility → copy survives as 2/2 (a blank 0/0
  would die to SBA 704.5f).
- `testCloneCopyWithoutHumilityControl`: no-Humility copy control — second Bear
  copy, 2/2, confirm engaged.
- `testSecondCopyEffectUnderHumilityControl`: Phantasmal Image (same Optional
  ETB-copy mechanic, different card) under Humility — second Bear copy, 1/1,
  confirm engaged.

Result on accepted pin `a9a95db` (pre-fix head): **Clone-Humility and
Image-Humility FAIL** (1 Bear only; `confirmReplacementCalls == 0`; Clone
enters as blank under name "Clone"); **no-Humility control PASSES**.
Humility presence is the differentiator — defect reproduced engine-direct.

## Exact systemic engine cause (proven, no names)

Chain on the accepted pin:

1. Clone's copy ability is the intrinsic keyword `ETBReplacement:Copy:DBCopy:Optional`
   (`clone.txt`; parsed `CardFactoryUtil.java:2595-2606` → `Optional$ True`,
   `ReplacementLayer.Copy`).
2. On Stack→Battlefield, `GameAction.moveTo` runs `ReplacementType.Moved`
   (`GameAction.java:348`); `ReplacementHandler.getReplacementList`
   builds a future-state LKI and applies battlefield continuous effects
   (`ReplacementHandler.java:84-96`, `checkStaticAbilities(..., preList)`).
3. Humility (`humility.txt`: `RemoveAllAbilities$ True`) becomes
   `removeAbilities = e -> true` (`StaticAbilityContinuous.java:326-328`),
   applied via `addChangedCardTraits`
   (`StaticAbilityContinuous.java:853-858`) to the entering Clone's LKI.
4. Candidacy gathering reads `c.getReplacementEffects()`
   (`ReplacementHandler.java:169`), which routes through
   `CardState.getReplacementEffects` (`CardState.java:735-749`) →
   `updateReplacementEffects` → `CardTraitChanges.applyReplacementEffect`
   (`CardTraitChanges.java:105-111`), where the ability-removal predicate
   deletes the intrinsic ETB-copy replacement from the returned view.
5. Result: zero candidate replacers from the entering permanent →
   `confirmReplacementEffect` never called → copy never engages →
   Clone enters as a blank 0/0 (1/1 under Humility).

Rules authority (RQ-C3, official CR): CR 613 layer order — copy effects apply
in layer 1, ability-removal in layer 6, P/T-setting in 7b, timestamp-independent
across layers; CR 614.12 — "as/enters" replacements are assessed at the
pre-entry point before the permanent is subject to battlefield continuous
effects that would remove the very replacement being assessed. Stripping the
ETB-copy candidacy via layer-6 removal during the 614.12 future-state
computation is therefore an engine legality error, not a provider gap.

## Fix (systemic subsystem correction, validated head `22e7f17`)

`forge-game/.../replacement/ReplacementHandler.java` only:

- Snapshot the entering permanent's own replacement effects *before* the
  future-state continuous-effect pass (line 128) and union them back into
  candidacy for the entering card only (helper
  `getEntryCandidateReplacements`, lines 80-93; loop line 169). All existing
  mode/zones/requirements/canReplace/layer/hasRun filters still apply; every
  other card and event sees byte-identical behavior (snapshot empty).
- Re-home snapshotted effects to the real card post-check (lines 192-194),
  mirroring the existing host fixup.

No Clone/Humility-name special cases. Post-fix: **all 8 WS67 tests PASS**,
including both Humility tests (confirm ≥1, choice ≥1, 1/1 under Humility,
2/2 Bear-copy after Humility leaves) and the second copy-effect control.

**CLONE_HUMILITY_ROOT_CAUSE=ENGINE_DEFECT. CLONE_HUMILITY_ENGINE_FIX=PASS.**
