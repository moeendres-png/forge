# WS67 — Forge WS65 Engine Remediation — Final Report

- Branch: `ws67/forge-ws65-engine-remediation-20260912`
- Accepted Forge pin (only engine pin): `a9a95db6662c2d28814390a9c0c2f986e39aa8b4` / tree `2c18327f79e330f2ed167067166ffd42d61b0849`
- Validated head (successor candidate only): `22e7f17befee8fcce0684fa6d006f29afdb5c280`
- Authority: `/tmp/ws67-ws65-engine-authority-20260912/` (WS65 terminal + 3 remediation packets + RQ-C3 scenario authority)
- Rules authority: current official/RQ-C3 (CR 613 layers, CR 614.12 pre-entry assessment). Reference parity never used as proof.

## Work completed

1. Built engine-direct actual-card reproducers for all three packets (new
   `Ws67EngineRemediationTest`, 8 tests): engine Game + engine AI controllers +
   native mana payment; no provider/CPL code on the path.
2. Packet 1 (Ghalta reduced-cost commander cast): engine-direct PASS (exact
   reduced bill, stack placement, 12/12 resolution, commander-tax accounting).
   Classified PROVIDER_TRANSPORT_DEFECT. No Forge patch (per contract).
3. Packet 2 (Fire Covenant non-mana X): engine-direct PASS (X=5 announced, mana
   bill exactly printed `{1}{B}{R}`, life 20→15, normal resolution; Fireball
   mana-X control identical-correct). Classified OTHER (provider-side bill
   computation). No Forge patch (per contract).
4. Packet 3 (Clone under Humility): engine-direct FAIL reproduced (optional
   ETB-copy never engaged: 0 confirm calls; Clone enters blank). Exact systemic
   engine cause proven: the CR-614.12 future-state pass lets battlefield
   ability-removal (layer 6) strip the entering permanent's own ETB
   replacements (copy is layer 1) from the candidacy view
   (`ReplacementHandler` → `checkStaticAbilities` → `CardTraitChanges.
   applyReplacementEffect` removal). Systemic fix in `ReplacementHandler`
   only (snapshot + candidacy union, all existing filters preserved, no
   card-name branches). Post-fix all 8 PASS, including second copy-effect
   control (Phantasmal Image) and the decisive Humility-removal 2/2 layer proof.
5. Regression: full `forge-gui-desktop` module 392 run / 0 fail / 0 errors
   (6 environmental network skips); `forge-game` 3/3; checkstyle validation
   PASS both modules. Single-fix workstream: no cost↔replacement interaction
   to adjudicate.

## Changes

See `CHANGES.md`. Two files: one production fix (`ReplacementHandler.java`
+39/−1), one new test file (8 tests). No provider/CPL edits. No weakened
assertions. No card-name special cases.

## Tests / Evidence

See `REGRESSION_MATRIX.md` and the three `*_CAUSALITY.md` files.
Pre-fix: WS67 8 run / 2 fail (exactly the under-Humility copy tests).
Post-fix (validated head `22e7f17`): WS67 8/8 PASS; full module 392/0/0/6-skip.

## PASS / FAIL / UNKNOWN

**WS67_ENGINE_REMEDIATION=PASS** (all three packets causally closed:
provider-transport, other/provider-side, engine-defect-repaired).

## Remaining blockers

- Ghalta + Covenant provider-side repairs are out of scope for this
  workstream (provider/CPL code frozen here); downstream provider work must
  requalify G02/G03/G04 behind their own gates.
- Successor candidate `22e7f17` is NOT the accepted pin; acceptance requires
  separate successor requalification. `SUCCESSOR_ACCEPTED=NO`.
- FULL107 NOT_RUN. Architecture freeze NOT claimed. Production provider NOT
  selected. Behavior credit 0/107.

## Outputs

`ws67-ws65-engine-remediation-evidence/` (this report + SOURCE_LOCK,
3 causality docs, CHANGES, REGRESSION_MATRIX, WORKSTREAM_STATE).

## Dependencies unblocked

- RQ-C3-H01 (Clone under Humility) is now engine-capable; targeted
  re-execution of the H01 provider scenario can proceed on the successor
  candidate after provider requalification.
- Ghalta/Covenant packets have definitive engine-vs-provider causality,
  directing repair effort to the provider side.

## Exact next action

Coordinator: accept `22e7f17` as FORGE_SUCCESSOR_CANDIDATE (accepted pin stays
`a9a95db`); schedule provider-side repair + requalification for the
Ghalta-entry and Covenant-X transports and H01 re-execution; do NOT start
Full107, Freeze, or provider selection from WS67.

---

WS67_ENGINE_REMEDIATION=PASS

GHALTA_ROOT_CAUSE=PROVIDER_TRANSPORT_DEFECT
GHALTA_ENGINE_FIX_REQUIRED=NO

COVENANT_ROOT_CAUSE=OTHER
COVENANT_ENGINE_FIX=NOT_REQUIRED

CLONE_HUMILITY_ROOT_CAUSE=ENGINE_DEFECT
CLONE_HUMILITY_ENGINE_FIX=PASS

FORGE_SUCCESSOR_CANDIDATE=22e7f17befee8fcce0684fa6d006f29afdb5c280
ACCEPTED_FORGE_PIN=a9a95db6662c2d28814390a9c0c2f986e39aa8b4
SUCCESSOR_ACCEPTED=NO
BEHAVIOR_CREDIT=0/107
FULL107=NOT_RUN
ARCHITECTURE_FREEZE=NOT_CLAIMED
PRODUCTION_PROVIDER=NOT_SELECTED
