# R17 CI Failure Analysis — WS236 Java-21 Test-build red

## CI history (read-only gh survey, 2026-09-20)

- `moeendres-png/forge` Test-build (full `mvn clean test`, Java 17+21):
  GREEN on ws231/ws233/ws234/ws227/ws217; WS236 branch
  (`ws236/forge-f4-spellcast-discriminator-20260916`, run 35039212911):
  Java 17 SUCCESS, Java 21 FAILURE.
- Run logs expired (empty via API); root cause from CI logs UNKNOWN.

## Local reproduction on R16 tip (contains WS236 + all successors)

- Full root `mvn clean test`, Java 21: BUILD SUCCESS (18:49).
  sim 445 (0 fail, 6 pre-existing skips), bridge 213/213,
  checkstyle 0 in all modules.
- Full root `mvn clean test`, Java 17: running (background, this WS).

## Classification

- WS236's sealed tests (F4 discriminator 5/5) pass consistently on
  Java 21 in every scoped and full run since; no WS236-attributable
  failure reproduces on current tip.
- Disposition: UNREPRODUCIBLE on R16 tip (full builds green). No repair
  indicated; nothing weakened, skipped, or omitted. If the WS236 red
  recurs on push, the CI log (fresh, unexpired) becomes the new
  fail-before — record here, do not pre-repair a ghost.

## Config verdict

- `test-build.yaml` (full `mvn clean test`, Java 17+21, Xvfb) already
  qualifies sim + bridge + checkstyle with gating exit codes. No
  workflow change required: the configuration is coherent with the
  current tree (module names, Java versions, no skipped R-family
  suites). No CI file modified in R17.
