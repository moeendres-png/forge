# 07 — Regression Matrix (DIRECTLY_VERIFIED)

Evidence class: DIRECTLY_VERIFIED (`mvn -o` TestNG; reactor `BUILD
SUCCESS`). Production delta is behavior-neutral (04), so this matrix is
confirming, not repairing.

## Terminal-validation scope (final HEAD, clean tree)

Validated production+test commit (terminal validation ran on this exact
clean HEAD):
- `VALIDATED_HEAD=3347084d222266fb55fc9490e42ae80663589b09`
- tree `ace5f2219b435865006910d69dbd533ad37b7d1a`

- `Ws63A04NaturalFullCastTest`: 11/11 PASS (exact x6 configurations +
  static entry + pool payment + loop game + 4P + rich history + rounds +
  multi + base + generic + negative).
- `Ws59A04ReplacementOrderingTest`: 3/3 PASS (direct 7|8, generic >base,
  base =3) — no 15/double-application regression.
- `Ws59C01CostPitchTest`: 2/2 PASS — stack-spell targeting/candidacy and
  authoritative SA binding intact (C01 production files byte-identical,
  untouched).
- `Ws59G04ConcessionTest`: 4/4 PASS — engine-native concession seam
  intact (G04 production files byte-identical, untouched).
- `ReplacementHandlerTest` + `GameSimulationTest`: 74/74 PASS —
  replacement-handler semantics and general game simulation green.

Combined: 20/20 targeted + 74/74 neighbors, 0 failures, 0 errors.

## Impact adjudication (C01/G04)

Touched production surfaces: one comment-only line in `AbilityUtils`
(xCount comment) and dead-code removal in `GameAction.changeZone` (block
proven unreachable). C01 surfaces (`SpellAbility` proxy,
`TargetRestrictions`, `CardUtil`, `PlayerControllerHuman`) and G04
surfaces (`PlayerController.canConcede/concede`, `GameAction.concede`)
are byte-identical to the audit base; no excessive re-runs performed, but
the C01/G04 suites above re-pass regardless.

## Pre-WS59 / ablation runs (diagnostic, not credit)

- Both WS59 A04 blocks disabled: natural 2/2 + direct 3/3 green.
- Full pre-WS59 engine (6 files at `HEAD~1`): direct 3/3 green.
- These runs prove insensitivity, not repair; they are recorded in 03.
