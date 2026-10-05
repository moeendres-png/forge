# D21 — Java 21 compatibility battery shadow experiment

Source lock:

- Forge master: `ca1d57d19f9c91d6107d0ce9fb22741ac856e948`
- TREE: `ce092ee296f60dc7108e16aaa252559f4781af69`
- Production `.github/workflows/test-build.yaml` is intentionally unchanged.
- Authoritative matrix remains Java 17 + Java 21.

## Experimental question

What minimum reduced Java 21 battery could still detect the classes of JDK-specific regressions for which the full Java 17 + Java 21 matrix exists?

Selection starts from failure mechanisms, not fast or representative tests.

## Compatibility failure classes

1. JDK API/runtime behavior changes between baseline and secondary JDK.
2. Reflection/module-access changes; Forge/Surefire uses multiple `--add-opens`.
3. Compiler/bytecode/classpath assumptions; production compiles for release 17 while tests execute on both JDKs.
4. TestNG/Surefire/JVM lifecycle and discovery behavior; D24 proved discovery can be wrong while a workflow appears green.
5. Locale/charset/filesystem differences.
6. Concurrency/runtime scheduling differences.
7. Headless/AWT/JVM integration differences.

## Mandatory red control

The final injected fixture launches an isolated child JVM and calls `System.setSecurityManager(new SecurityManager())`, exercising a real dynamic SecurityManager installation rather than inspecting the runtime version.

Documented JDK behavior:
- JDK 17: with `java.security.manager` unset, dynamic SecurityManager installation is allowed.
- JDK 21: with that property unset, a non-null `System.setSecurityManager(...)` call throws `UnsupportedOperationException` unless the JVM starts with `-Djava.security.manager=allow`.

An earlier weaker `System.setSecurityManager(null)` probe was rejected because Java 21 can treat that no-op as successful; it receives no sensitivity credit.

The fixture remains under `.github/d21`; the shadow workflow copies it into the test tree only on the ephemeral runner. No production source is changed.

## Candidate reduced battery

Measurement-only Java 21 candidate:
- dependency reactor required by `forge-gui-desktop`;
- `forge.d24.D24ExecutionGuardTest`;
- `forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection103`;
- `forge.gamesimulationtests.comprehensiverules.ComprehensiveRulesSection104`;
- injected D21 red-control test.

The no-red subset is repeated three times for timing variance. Red-control sensitivity is tested separately.

## Routing contract under evaluation

Until a deterministic impact map proves coverage of every JDK-sensitive surface:
- any production Java change => FULL Java 17 + 21;
- any Maven/POM/dependency/plugin/toolchain change => FULL;
- reflection/module/classloader/serialization/security change => FULL;
- locale/charset/filesystem/concurrency/headless lifecycle change => FULL;
- test-framework/discovery change => FULL;
- unclassified or ambiguous change => FULL;
- `UNKNOWN => FULL`.

The experiment can therefore prove sensitivity without proving a useful production reduction.

## Qualification rule

Reduction is qualified only if all are true:
1. JDK 17 baseline passes the injected control.
2. Full JDK 21 catches it.
3. Reduced JDK 21 catches it.
4. Relevant full/reduced real outcomes are equivalent on the declared surface.
5. Deterministic routing covers all failure classes and fails closed.
6. Savings justify added workflow/routing complexity.
7. Independent review is clean.

Otherwise: `NO_SAFE_REDUCTION_DEMONSTRATED` and production Java17+21 remains.
