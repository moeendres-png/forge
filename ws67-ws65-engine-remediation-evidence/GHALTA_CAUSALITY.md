# Ghalta Reduced-Cost Commander Cast — Causality

Packet: `WS65-RP-GHALTA_ENTRY-01` (blocks RQ-C3-G02/G03).
WS65 classification: UNKNOWN engine-vs-provider. WS65 symptom: cost-reduced
commander cast from the command zone through the qualified WS64 provider
transport — engine requests exactly the reduced cost (6 for Ghalta with 3x
Runeclaw Bear), all 6 mana submissions ACCEPTED_CONTINUED, then no
spell_cast/stack/resolution; Ghalta remains in the command zone; game continues.
Reproduced twice. Non-reduced Delina command-zone cast works on the same build.

## Engine-direct reproducer (actual cards, no provider)

`forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws67/Ws67EngineRemediationTest.java`

- `testGhaltaReducedCostCommanderCastFromCommandZone`: Ghalta, Primal Hunger
  (`{10}{G}{G}`, `ReduceCost X = total power you control`) as commander in the
  command zone (MayPlay cast option via the engine's own
  `GameActionUtil.getMayPlaySpellOptions` route, as GUI/AI casts use); 3x
  Runeclaw Bear on the battlefield (power 6 reduction online); 8x Forest;
  full `PlaySpellAbility.playSpellAbility` path with native AI mana payment
  through real land mana abilities (only the X/copy/target *values* are
  scripted via a delegating controller; every cost/payment/stack step is the
  production engine path). Asserts: cast reports success, stack non-empty,
  commander cast count 1, resolution onto the battlefield as 12/12.
- `testGhaltaReducedCostCastFromHandControl`: same reduction, hand zone
  (isolates command-zone vs reduction causality).
- `testUnreducedCommanderCastFromCommandZoneControl`: no reduction, command
  zone (Delina-style transport control).

Result on accepted pin `a9a95db` (pre-fix head): **all three PASS**.
Result on validated head `22e7f17`: **all three PASS** (unaffected by the
Clone/Humility fix; regression suite confirms).

## Inspected engine path

- `PlaySpellAbility.playAbility` (`forge-game/.../player/PlaySpellAbility.java:581-735`):
  cost computation honors the reduction; `payment.payCost(...)` succeeds with
  native payment; `payment.isFullyPaid()` true; `game.getStack().addAndUnfreeze`
  (line 727) places the spell; resolution completes.
- `CostPayment.isFullyPaid` (`forge-game/.../cost/CostPayment.java:114-116`) is
  entailed by `payCost` returning true over the same `adjustedCost` object
  (lines 136-176): the WS65-forensics "silent success-lie" branch
  (line 715 `if (isFree || payment.isFullyPaid())` falling through to
  `return true` without stack placement) is unreachable on the engine path —
  `payCost == true` implies `isFullyPaid == true`. No latent engine defect
  there; no patch warranted.
- Reduction accounting: `CostAdjustment` ReduceCost handling
  (`forge-game/.../cost/CostAdjustment.java:198-239`); commander tax via
  `getCommanderCast`/`incCommanderCast` (`Player.java:2869-2873`,
  `MagicStack.java:392`). Bill `{4}{G}{G}` paid exactly.

## Classification

**GHALTA_ROOT_CAUSE=PROVIDER_TRANSPORT_DEFECT.** Engine-direct PASS with exact
reduced mana payable, native payment, stack placement and eventual resolution
proves the Rules Core cost/payment/stack seam is sound for this packet. The
WS65 silent drop (payment accepted, no stack) lives in the provider mana
registration/post-payment transport, which this workstream is forbidden to
change. **GHALTA_ENGINE_FIX_REQUIRED=NO. No Forge patch made for this packet.**

No Ghalta-name special cases introduced or used.
