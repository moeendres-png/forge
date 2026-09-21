# R17 Validation

## Identity / hygiene

- Branch `wsr17/forge-ci-qualification-20260920`, base `7af7b322`
  (R16 tip, clean at creation; all other Forge worktrees untouched —
  verified clean).
- Changes: evidence dir only (`forge-protocol2-bridge/wsr17-ci-qual/`,
  6 files). Zero production diffs; zero test diffs; zero CI-file diffs.

## Qualification (DIRECTLY_VERIFIED)

- Full root `mvn clean test`, Java 21: BUILD SUCCESS (18:49). sim 445
  (0 fail, 6 pre-existing skips), bridge 213/213, checkstyle 0.
- Full root `mvn clean test`, Java 17: BUILD SUCCESS. sim 445,
  bridge 213/213. (Mirrors the CI matrix exactly.)
- WS236 Java-21 CI red: unreproducible on current tip (both JDKs
  green); no repair indicated; nothing weakened/skipped/omitted.

## Explicitly NOT_RUN / UNKNOWN

- CI runs themselves (no remote actions taken; push-gated).
- FULL107, promotion, Lab/RSP, Freeze, Provider.

## Verdict

CI configuration QUALIFIED (coherent + green on both matrix JDKs) |
WS236 red UNREPRODUCIBLE (documented, not repaired) |
FULL107 NOT_RUN | ARCHITECTURE_FREEZE NOT_CLAIMED |
PRODUCTION_PROVIDER NOT_SELECTED.
