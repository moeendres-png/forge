# D21 — Java17+21 compatibility battery shadow experiment

## Source Lock

- Forge master SHA: `ca1d57d19f9c91d6107d0ce9fb22741ac856e948`
- TREE: `ce092ee296f60dc7108e16aaa252559f4781af69`
- Branch: `hardening/d21-jdk21-shadow-20261005`
- Production `.github/workflows/test-build.yaml`: **UNCHANGED**
- #531, D22/#503/#532, D17: **READ ONLY / NO CO-EDIT**

## Experimental question

> What minimum reduced Java21 battery could still detect the class of JDK-specific regressions that the full Java17+21 matrix is intended to catch?

## Compatibility failure classes

1. JPMS/reflection/strong encapsulation.
2. Compiler/API/class-version boundary under Java17 release targeting.
3. JVM/process/headless lifecycle.
4. TestNG/Surefire discovery and inherited-test lifecycle.
5. Network/concurrency/runtime timing.
6. Locale/charset/filesystem defaults.
7. UNKNOWN => FULL JAVA21.

## Red control

`D21ModuleAccessRedControlTest` performs real reflective access to the private cached-string field of `java.net.URI`.
It never checks `Runtime.version()` and never throws intentionally. A shadow-only Maven profile grants the needed
`java.base/java.net` opening on Java17 only; Java21 intentionally lacks it.

Expected:
- full Java17: control PASS;
- full Java21: control FAIL with module-access denial;
- reduced Java21: same control FAIL.

The fixture/profile are experiment-only and must never merge to production.

## Reduced candidate

Selection is mechanism-derived and includes the red control, headless/process, network/concurrency,
D24 discovery/lifecycle surfaces, CR103/104, and every current failure-bearing #531 class.
The latter prevents a reduced run from becoming superficially green by omitting the 68 known failures.

A future production classifier must be deterministic; any path that cannot be confidently classified routes to
`FULL_JAVA21_BATTERY`.

## Baseline evidence

Source-equivalent Test build at TREE `ce092ee296f60dc7108e16aaa252559f4781af69`, run `37282465496`:
- Java21 Maven ~3:07; forge-gui-desktop ~1:58; TestNG suite ~105.3s.
- Java17 Maven ~4:07; forge-gui-desktop ~2:03; TestNG suite ~108.9s.
- both: 723 suite tests / 68 failures / 0 errors / 6 skipped; normalized 726 invocations / 652 PASS / 68 FAIL / 6 SKIP.
- 68 failures are #531 CardDb/DeckRecognizer debt and identical on Java17/21.
- 31 forge-protocol2-bridge test classes remain NOT_RUN because the authoritative reactor stops after desktop failure.

That incomplete downstream denominator is a hard qualification concern. Matching the current full battery is necessary
but not sufficient for production reduction.

## Completion rule

Reduction requires red-control sensitivity, relevant full/reduced outcome equivalence, deterministic routing,
UNKNOWN=>FULL, material repeated savings, and clean independent review.

Otherwise:
`NO_SAFE_REDUCTION_DEMONSTRATED`
`FULL_JAVA17_PLUS_21 = PRESERVED`
