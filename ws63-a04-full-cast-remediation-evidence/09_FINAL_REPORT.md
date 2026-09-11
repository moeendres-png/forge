# 09 — Final Report (WS63 FORGE A04)

Branch: `ws63/forge-a04-full-cast-remediation-20260911`
Audit base: `a9a95db6662c2d28814390a9c0c2f986e39aa8b4` /
`2c18327f79e330f2ed167067166ffd42d61b0849`
Validated head (production+test commit, 94/94 terminal validation):
`3347084d222266fb55fc9490e42ae80663589b09` /
`ace5f2219b435865006910d69dbd533ad37b7d1a`
Terminal verdict: **FORGE_A04_FULL_CAST_FAIL_CLOSED** (fail-closed, bounded).

## Work completed

1. Imported the WS62 packet and independently verified the journal shape
   (1282 frames; X=3 at 954; 3 Forest taps; `#32` HAND->STACK->GY; DS+HS
   P1-controlled; 0 replacement frames). Established the forensic bar: the
   shape requires an EMPTY `Moved` replacement list, not just lost X (01).
2. Built the compliant Forge-side natural-cast suite (11 tests: exact x6
   pipeline variants + full priority-loop game + 4P + rich history +
   pool payment + rounds + multi-doubler + base + generic + negative).
   Result: 11/11 green pre- AND post-change — the specified material
   failure did not reproduce (02).
3. Traced the complete object/ability lineage with temporary engine
   diagnostics (since removed): X rides the casting SA through
   `moveToStack` + `addAndUnfreeze` LKI propagation; `unlinked=false`;
   both replacements contest (03).
4. Proved the WS59 Stack->Battlefield equals-gate block is structurally
   dead and that the direct A04 path was already green pre-WS59, via
   ablation and a coherent pre-WS59 engine run (03/R3-R4).
5. Applied the minimal honest production delta: removed the dead block
   (replaced with an invariant note) and corrected the live stale
   `107.3k` citation to current `107.3m` (04). No behavior change; no
   card names; no injection; no provider changes.
6. Regression matrix 20/20 targeted + 74/74 neighbors, all green (07).

## PASS / FAIL / UNKNOWN

- `FORGE_A04_FULL_CAST_REMEDIATION_PASS`: NOT CLAIMED (fail closed).
- `A04_NATURAL_FULL_CAST=FAIL` — exact blocker: the WS62 journal shape
  (empty `Moved` list, 0 replacement frames) is not reproducible through
  any Forge-native pipeline in this repository; no Forge-side defect was
  found to remediate. The Forge pipeline itself is proven correct
  (11/11 natural proofs, 7|8 with replacement reached).
- `CR_107_3M_SEMANTICS=PASS` (DIRECTLY_VERIFIED, 9 configurations).
- `A04_NATIVE_X_SELECTION=PASS`, `A04_NATIVE_PAYMENT=PASS`,
  `A04_ETB_REPLACEMENT_REACHED=PASS`, `A04_FINAL_COUNTERS=7_OR_8`,
  `A04_PERMANENT_X_GLOBAL_LEAK=NO`,
  `A04_DOUBLE_APPLICATION_REGRESSION=NO`, `PROVIDER_CHANGES=NO`.
- Unknowns: the engine-external cause of the WS62 0-frame run
  (provider-side input handling or un-replicated history) — explicitly
  UNKNOWN, not PASS.

## Remaining blockers

1. WS62-shape non-reproduction Forge-side (only blocker). Recommended
   follow-up OUTSIDE this repo (provider repair forbidden here): audit the
   vertical provider's `playChosenSpellAbility`/ACT-to-cast path and its
   mana/replacement callbacks against the 953-962 frame window, with
   engine-side object-identity logging at `PermanentEffect` resolution.

## Outputs / dependencies

- `ws63-a04-full-cast-remediation-evidence/` (00-09 + state).
- Production successor commit (code+tests) = validated head (below).
- `BEHAVIOR_CREDIT=0/107`; `FULL107=NOT_RUN`;
  `ARCHITECTURE_FREEZE=NOT_CLAIMED`; `PRODUCTION_PROVIDER=NOT_SELECTED`;
  `WS64_REQUALIFICATION_REQUIRED=YES`.
