# D21 terminal experiment result — NO SAFE REDUCTION DEMONSTRATED

## Source Lock

Authoritative Forge master throughout terminal adjudication:

- SHA: `ca1d57d19f9c91d6107d0ce9fb22741ac856e948`
- TREE: `ce092ee296f60dc7108e16aaa252559f4781af69`

Tested D21 shadow head:

- SHA: `b4494cab769ce88dcedf0f24819d5745055ed346`
- TREE: `e256b23f07a3346edd1b018c2a56fbb85e3b448a`
- Shadow run: `37297975898`
- Unmodified normal Test-build run at the same D21 head: `37297975886`

Master did not drift during the terminal experiment.

## Experiment Design

Production `.github/workflows/test-build.yaml` was never edited. It remains the
authoritative Java 17 + Java 21 matrix.

The shadow experiment injects two test-only sources from `.github/d21` into
the ephemeral runner checkout:

- `D21SecurityManagerCompatibilityRedControlTest`
- `D21SecurityManagerChild`

No production Java source is changed. No Rules semantics are changed.

The compatibility action is executed in a child JVM. JDK 17 accepts dynamic
SecurityManager installation with the property unset; JDK 21 rejects it by
default. The parent TestNG test turns that real child-JVM incompatibility into a
normal test failure.

A first weaker no-op control using `System.setSecurityManager(null)` was
correctly rejected by the experiment because it did not fail on JDK 21. That
negative result was superseded by the non-null child-JVM control above.

## Compatibility Failure Classes

The experiment starts from these failure mechanisms:

1. JDK API/runtime behavior change.
2. Reflection/module-access behavior.
3. Compiler/bytecode/classpath behavior.
4. TestNG/Surefire/JVM discovery/lifecycle behavior.
5. Locale/charset/filesystem behavior.
6. Concurrency/runtime scheduling behavior.
7. Headless/AWT/JVM integration behavior.

Forge currently compiles for Java 17 but executes on Java 17 and Java 21, and
its Maven/Surefire configuration opens numerous JDK modules/packages. Those
surfaces are not presently covered by a proven static impact router.

## Red Controls

### Baseline leg — PASS

Shadow job `D21 baseline red control Java 17`:

- 1 test
- 1 pass
- 0 fail
- 0 error
- 0 skip
- measured job command duration: 41 s
- Maven total: 38.720 s

### Full Java 21 — PASS sensitivity

Shadow job `D21 full Java 21 with red control`:

- full suite: 724 tests
- 69 failures
- 0 errors
- 6 skipped
- the pre-existing full suite has 723 tests / 68 failures / 6 skipped
- the exact additional failure is the D21 red control
- failure signature contains `UnsupportedOperationException`
- measured full command duration: 116 s
- Maven total: 1:54

Therefore the full Java 21 battery detects the injected incompatibility.

### Reduced Java 21 — PASS sensitivity

The reduced battery's red-control invocation:

- 1 test
- 1 failure
- 0 errors
- 0 skipped
- exact failure is the D21 red control
- failure signature contains `UnsupportedOperationException`
- Maven total: 23.736 s

Therefore the proposed reduced battery detects this one injected JDK-specific
failure mechanism.

## Full Battery Results

Source-equivalent no-red normal matrix evidence at current production tree:

Latest D21-head normal run `37297975886`:

- Java 17: 723 tests / 68 failures / 0 errors / 6 skipped; Maven total 3:05.
- Java 21: 723 tests / 68 failures / 0 errors / 6 skipped; Maven total 2:51.
- D20 provenance integrity: PASS.
- D20 baseline / coverage / build / tests / overall: FAIL, honestly preserving
  the current product/oracle debt.

Additional source-equivalent timing points already produced on the same
production TREE:

- Java 17: 2:26 and 4:07.
- Java 21: 3:10 and 3:07.

Observed three-run full timing ranges:

- Java 17: 146–247 s; mean about 192.7 s.
- Java 21: 171–190 s; mean about 182.7 s.

The full Java17 and Java21 real failure multiplicity remains identical at 68 on
the locked source. Issue #531 owns classification/repair and remains separate
from D21.

## Reduced Battery Results

No-red reduced candidate contains:

- `forge.d24.D24ExecutionGuardTest`
- `forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection103`
- `forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection104`

Three Java21 repetitions:

- each: 18 tests / 0 failures / 0 errors / 0 skipped
- measured end-to-end command duration range: 60–67 s
- mean: 62.67 s

The earlier superseded run independently measured 47–79 s with the same 18
tests, also all green.

## Outcome Comparison

Red-control sensitivity:
- baseline Java17: PASS
- full Java21: PASS
- reduced Java21: PASS

Real-outcome equivalence:
- full Java21 no-red: 723 / 68 fail / 6 skip
- reduced Java21 no-red: 18 / 0 fail / 0 skip

Therefore full/reduced real outcomes are not equivalent. The reduced set omits
real currently failing surfaces. Those failures are presently identical across
JDK17 and JDK21, but the D21 contract does not permit treating that fact as a
future-proof compatibility routing proof.

Only one JDK-specific red-control mechanism has been demonstrated. Reflection,
module access, bytecode/classpath, locale/charset/filesystem, concurrency and
headless/AWT mechanisms do not have independent red controls or a validated
impact map. Equivalence is therefore not demonstrated.

## Timing / Savings

Using the three no-red full Java21 observations:

- full Java21 mean: about 182.7 s
- reduced Java21 mean: 62.67 s
- observed mean saving: about 120 s per Java21 leg
- observed reduction: about 65.7%

Using only the latest same-D21-head normal run:

- full Java21: 171 s
- reduced Java21 mean: 62.67 s
- saving: about 108 s
- reduction: about 63%

The speedup is material. It is not sufficient to compensate for missing
semantic/routing proof.

Added complexity would include:
- a second compatibility workflow or conditional secondary-JDK path;
- deterministic source-to-compatibility-surface routing;
- maintenance of JDK-sensitive surface ownership;
- adversarial controls for every routing class;
- ongoing drift/requalification whenever Maven/JDK/test-framework topology
  changes.

## Routing Contract

No production reduction router is qualified.

Fail-closed contract retained:

- production Java change => FULL
- Maven/POM/dependency/plugin/toolchain change => FULL
- reflection/module/classloader/serialization/security change => FULL
- locale/charset/filesystem/concurrency/headless lifecycle change => FULL
- TestNG/Surefire/discovery change => FULL
- unclassified change => FULL
- `UNKNOWN => FULL`

This means the only currently defensible router produces no useful
production-code reduction.

## Production Workflow Changes

NONE.

`.github/workflows/test-build.yaml` remains unchanged with Java 17 + Java 21.

The D21 workflow and fixtures exist only on the unmerged experiment branch and
are not authoritative production CI.

## Review State

Outcome A's independent-review gate was not entered because the reduction
failed earlier qualification gates. No independent-review credit is claimed.

## Decision

`NO_SAFE_REDUCTION_DEMONSTRATED`.

The shadow experiment successfully proves that a realistic JDK17-to-21 runtime
incompatibility can be injected and detected by both full and reduced
batteries. It does **not** prove that the reduced battery covers the declared
compatibility failure surface, nor that full/reduced real outcomes are
equivalent.

The correct terminal action is to preserve the complete production matrix and
close D21 as Outcome B. A future successor may reopen the reduction question
only with materially new evidence such as multiple independent red controls
plus a deterministic, validated impact map.

## Terminal State

`D21_IMPLEMENTATION = COMPLETE`

`D21_RED_CONTROL_SENSITIVITY = PASS`

`D21_FULL_REDUCED_EQUIVALENCE = FAIL`

`D21_REDUCTION_QUALIFIED = NO`

`FULL_JAVA17_PLUS_21 = PRESERVED`

`D21_OVERALL = COMPLETE`

`PRODUCTION_PROVIDER = NOT_SELECTED`

`ARCHITECTURE_FREEZE = NOT_CLAIMED`

`PRODUCTION_REPOSITORY = NOT_CREATED`
