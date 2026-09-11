# WS59R Final Report — terminal

## Source Lock
- Repository: moeendres-png/forge
- Worktree: /home/moeen/code/ws59-forge-rqc3-engine-remediation
- Branch: ws59/forge-rqc3-engine-remediation-20260911
- Audit base: 66caae16015bd403bc0a52fa6689afb5508f74d0
- Audit-base remote source: foundry/ws45-v104-observation-remediation
- Pre-remediation terminal HEAD: 10c342e452db9e04177f64d04b0b415edc84426e
- Validated engine successor (production): a9a95db6662c2d28814390a9c0c2f986e39aa8b4
- Engine successor tree: 2c18327f79e330f2ed167067166ffd42d61b0849
- WS59R tested remediation HEAD: c8f30969fd002b06b5e962afac67d801cf090977
- WS59R remediation tree: 9427d4c610fc307fba15e1e62970a8c1b83c07bd
- validated_head (WS59R): c8f30969fd002b06b5e962afac67d801cf090977
- Production files (forge-game/src/main, forge-gui/src/main) byte-identical between
  a9a95db and c8f30969fd0; FORGE_SUCCESSOR_HEAD preserved as a9a95db.

## A04 disposition — PASS (DIRECTLY_VERIFIED)
- Exact Serpent X=3 + Doubling Season + Hardened Scales enters battlefield with 7|8
  via general Moved/ETB + AddCounter ordering (existing chooseSingleReplacementEffect);
  base X linkage restored by 107.3k cast propagation. Generic Menace/Constrictor/Walker
  and base-alone =3 also pass. No card names, no injection.

## C01 boundary disposition
- A. variant enumeration — DIRECTLY_VERIFIED (both FoW variants engine-enumerated;
  structural CostExile+CostPayLife vs CostPartMana identity; no display strings).
- B. alternate-cost selection — DIRECTLY_VERIFIED (exact pitch object from offered list).
- C. hidden-zone pitch selection — TECHNICALLY_CONFORMANT / NOT_RUN (Exile-from-Hand
  + blue Frog preserved structurally; choice callback deferred to First-Wave).
- D. target decision callback — DIRECTLY_VERIFIED (native PlayerController seam reached
  via SpellAbility.setupTargets; harness flag).
- E. target binding — DIRECTLY_VERIFIED (single-match stack SpellAbility becomes actual
  target; getFirstTargetedSpell identity; no manual injection in test methods).
- F. payment/life/exile — NOT_RUN (deferred to First-Wave behavior).
- G. resolution/counter outcome — NOT_RUN (deferred to First-Wave behavior).
- No provider filtering/solver; no string heuristics; no fallback.

## G04 disposition — PASS (DIRECTLY_VERIFIED)
- Engine-native canConcede/concede (104.3a any-time, not priority-gated) with native
  800.4 cleanup; 2-player game-over, 3-player stolen-control correction, fail-closed
  double-concede. Prior evidence survives (production identical).

## Exact tests (terminal validation, clean HEAD c8f30969fd0)
- Ws59A04ReplacementOrderingTest (3): exact 7|8, generic >base, base =3
- Ws59C01CostPitchTest (2, WS59R non-bypass): exact FoW authoritative decision,
  generic Cancel authoritative decision
- Ws59G04ConcessionTest (4): any-time, 2P cleanup, 3P 800.4, fail-closed
- ReplacementHandlerTest (1), AbilityKeyTest (2), ManaCostBeingPaidTest (1)
- Result: forge-game 3/3 PASS; forge-gui-desktop 10/10 PASS (9 WS59 + ReplacementHandler)

## Evidence classes
- DIRECTLY_VERIFIED: A04 full, C01 A/B/D/E, G04 full (runtime above).
- TECHNICALLY_CONFORMANT: C01 C preserved (structural costs + blue/hand presence).
- NOT_RUN: C01 F/G (First-Wave behavior scope). UNKNOWN never PASS.

## Credit / scope
- BEHAVIOR_CREDIT=0/107 (engine remediation alone awards no scenario behavior credit).
- ARCHITECTURE_FREEZE=NOT_CLAIMED. PRODUCTION_PROVIDER=NOT_SELECTED.
- No CPL/provider changes. No A04/G04 production semantic changes in WS59R.

## Remote
- Target branch pre-exists at 10c342e452db9e04177f64d04b0b415edc84426e; fast-forward only.
