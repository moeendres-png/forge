# WS236 F4 Input Reconstruction (from sealed WS234 bytes)

Reconstructed from `forge-protocol2-bridge/ws234-s3/` sealed package
(FINAL_HANDOFF.md, EVIDENCE_SEAL.json, S3_SYSTEMIC_GAP_MAP.json,
S3_ACTUAL_CARD_CENSUS.json, S3_DEDICATED_TEST_INVENTORY.json,
REGRESSION_RESULTS.json, VALIDATION.md) plus the exact current sources and
tests at the WS236 audit base. Nothing below is inferred from the WS236
prompt alone.

## The sealed gap

- Family F4 trigger-doubling: Veyran, Voice of Duality (CARD_05) and Harmonic
  Prodigy (CARD_06), both PARTIAL with evidence class UNKNOWN per
  adjudicator: "base You-filtered SpellCastOrCopy does not fire in either
  harness; doubling unproven".
- WS234 finding 3: direct AITest `Zone.add` does not reliably fire
  SpellCast/ETB/dies/draw trigger families; bridge constructed-game execution
  does exercise real engine paths (HARNESS_DEFECT, bridge proves engine).
- F4 therefore remained PARTIAL rather than PASS. All other 15 PARTIAL
  branches are out of WS236 scope.

## What "either harness" concretely meant (verified in current bytes)

Three born-disabled, never-runtime-observed probes, all created in
`bc347e62255`:

1. `Ws234S3CardBehaviorTest.testCard05VeyranMagecraftDoubled`
   (`enabled = false`): Veyran + Lightning Bolt via sim `castFromHand` +
   `drainStack`, expects +2 power (own-doubled magecraft).
2. `Ws234S3CardBehaviorTest.testCard06HarmonicProwess`
   (`enabled = false`): Harmonic + Lightning Bolt, expects +1 power.
3. `WS234S3BridgeTest.testVeyranDoubledMagecraftViaBridge`
   (`enabled = false`): Veyran + Divination via constructed game, expects
   >= +1 power.

No lifecycle observation (trigger collection, stack, activator identity,
resolution) exists anywhere in the sealed bytes for any of the three. The
Bolt-based sim probes additionally carry a target-selection confound
(Lightning Bolt needs an AI-picked target).

## Current-byte facts revalidated before use

- Veyran script (`veyran_voice_of_duality.txt`): `T:Mode$ SpellCastOrCopy |
  ValidCard$ Instant,Sorcery | ValidActivatingPlayer$ You |
  TriggerZones$ Battlefield | Execute$ TrigPump` where
  `TrigPump:DB$ Pump | NumAtt$ +1 | NumDef$ +1` (no explicit `Defined$`,
  engine default for non-targeting Pump is `Self` per
  `SpellAbilityEffect.getCards`, same outcome as Clever Lumimancer's explicit
  `Defined$ Self`); plus `S:Mode$ Panharmonicon |
  ValidMode$ SpellCast,SpellCopy,SpellCastOrCopy |
  ValidCard$ Permanent.YouCtrl | ValidCause$ Instant,Sorcery |
  ValidActivator$ You`.
- Harmonic script (`harmonic_prodigy.txt`): `K:Prowess` (engine-generated
  `Mode$ SpellCast | ValidCard$ Card.nonCreature |
  ValidActivatingPlayer$ You`, Pump Defined Self) plus `S:Mode$ Panharmonicon
  | ValidCard$ Shaman.YouCtrl,Wizard.Other+YouCtrl` (no ValidMode/ValidCause
  restriction).
- Engine dispatch (`MagicStack.add` → `push` → `runTrigger`): `SpellCast`
  with holdTrigger=true (waiting list), `SpellCastOrCopy` with
  holdTrigger=false (immediate `runWaitingTrigger` when unfrozen); both with
  `AbilityKey.Activator` always set. `TriggerSpellAbilityCastOrCopy.
  performTest` matches `You`/`Opponent` against the activator by controller
  identity (`Player.isValid`).
- Queued triggers land in the simultaneous-entry list
  (`addSimultaneousStackEntry`, CR 603.3b) and reach the real stack only via
  the engine's own `addAllTriggeredAbilitiesToStack()` (called from
  `PhaseHandler.passPriority`/`checkStateBasedEffects` and `GameState`).
  The sim `drainStack` pattern (`resolveStack` + `checkStateEffects`) never
  calls it. The bridge parks multi-trigger ordering as `TRIGGER_ORDER`
  (`ExternalPlayerController.orderSimultaneousSa`, complete permutation set,
  fail-closed above 4).
- Proven controls at base: Kaervek Opponent-filtered SpellCast passes in sim
  (`testCard17`, but note its `<=` non-strict assertion — see
  IMPACT_ADJUDICATION.md) and strictly via bridge; Ishai Opponent-filtered
  SpellCast passes via bridge. These prove the waiting-list half of the
  machinery, not the You-filtered half.

## Precise gap entering the discriminator

Whether the You-filtered SpellCast-family base trigger (+1) fires and
whether Panharmonicon doubling (+1 more) applies, for Veyran and Harmonic,
with actual cards, real casts, engine-generated triggers/outcomes, on each
seam — with the first divergence boundary observed, not inferred.

Status: **WS234_F4_GAP_RECONSTRUCTED = PASS**.
