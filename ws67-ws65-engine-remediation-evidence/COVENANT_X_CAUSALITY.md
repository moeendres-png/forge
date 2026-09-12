# Fire Covenant Non-Mana X — Causality

Packet: `WS65-RP-COVENANT-XMAUL-01` (blocks RQ-C3-G04 loss path).
WS65 classification: ENGINE_RULES_DEFECT as *leading hypothesis* (X-life billed
as mana-X; Fireball mana-X control correct). WS65 symptom: Fire Covenant X=39
(life) with zero targets never completes mana payment — 12 taps then rollback.
Hypothesis: announced life-X leaks into the mana bill (`{1}{R}+{39}=41`).

## Engine-direct reproducer (actual cards, no provider)

`.../ws67/Ws67EngineRemediationTest.java`

- `testFireCovenantNonManaXPaidInLifeOnly`: Fire Covenant
  (`Cost$ 1 B R PayLife<X>`, `SVar:X = Count$xPaid`; printed mana `{1}{B}{R}`,
  no mana-X) in hand; X=5 announced via scripted `announceRequirements`;
  Swamp + 2x Mountain; opponent Runeclaw Bear targeted with full X=5 divided
  allocation via scripted `chooseTargetsFor` (engine-native target legality +
  allocation APIs). Full `PlaySpellAbility.playSpellAbility` path, native AI
  mana payment. Asserts: cast succeeds (no rollback), stack spell records
  `XManaCostPaid=5`, exactly 5 life paid (20→15), resolution to graveyard,
  Bear destroyed by lethal (post-SBA).
- `testFireballManaXControl`: Fireball X=5 (genuine mana-X) on the same engine
  path; 7x Mountain; asserts cast/stack/resolution, Bear dies, **no life paid**.

Result on accepted pin `a9a95db` (pre-fix head): **both PASS**.
Result on validated head `22e7f17`: **both PASS** (unaffected by the
Clone/Humility fix; regression suite confirms).

## Inspected engine accounting (why no mana-X leak exists)

- Announcement: `announceValuesLikeX` (`PlaySpellAbility.java:737-784`) records
  announced life-X via `ability.setXManaCostPaid(value)`; CostPayLife amount
  resolves through `Count$xPaid`; `AiCostDecision.visit(CostPayLife)`
  (`forge-ai/.../AiCostDecision.java:377-383`) pays exactly that life.
- Mana bill: human/provider path `PlaySpellAbility.payManaCost`
  (`PlaySpellAbility.java:453-469`) only converts announced X into mana when
  `CostPartMana.getAmountOfX() > 0`; else `toPay.setXManaCostPaid(...)` computes
  `xPaid * cntX` (`ManaCostBeingPaid.java:240-251`) and Covenant's printed
  `{1}{B}{R}` has `cntX == 0` → contributes zero. Native AI path
  `ComputerUtilMana.calculateManaCost`
  (`forge-ai/.../ComputerUtilMana.java:1213-1269`) only adds X-mana under
  `getXcounter() > 0 || extraMana > 0` (line 1238) — false for Covenant.
  Mana bill is exactly the printed `{1}{B}{R}`; life-X never becomes generic
  mana on the engine path.

## Classification

**COVENANT_ROOT_CAUSE=OTHER** (provider-transport billing: the `{39}` generic
shard exists only in provider-side bill computation, not in engine X-cost
accounting). Engine-direct PASS with legal life-X announced, mana bill limited
to the printed component, life reflecting X, and normal resolution disproves
the ENGINE_DEFECT hypothesis. **COVENANT_ENGINE_FIX=NOT_REQUIRED. No Forge
patch made for this packet** (generic X-cost accounting is already correct:
non-mana X does not become generic mana).

No Fire-Covenant-name special cases introduced or used. Control: genuine
mana-X spell (Fireball) behaves identically correctly on the same path.
