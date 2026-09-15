# WS231 Validation

No Forge code was modified (`git status --porcelain` empty before and after
writing the evidence package, which adds only
`forge-protocol2-bridge/ws231-admission/*`). Per the WS231 validation policy
(no provider code changes ⇒ avoid broad recompilation; run inexpensive
source/census validators first + tests needed to establish S2), validation is:

## Census / identity validators (executed, PASS)

- `V1 identities`: `git rev-parse HEAD` = seal `8ff3e7a4`; `HEAD^{tree}` =
  `be5e3f3a…`; provider tree `cb4e5dbd…`; Core tree `302938b7f…`; worktree
  clean. PASS.
- `V2 card-script census`: all 29 mapped `cardsfolder` scripts present.
  PASS (DIRECTLY_VERIFIED file census).
- `V3 dedicated-test grep`: `rg -li` per-card names across all four test
  roots → only Rograkh vehicle + 2 ban-list strings (both excluded with
  reasons). PASS (executed; result 0/29, sealed).
- `V4 provider gate`: `BridgeEngine.java:425-429` fixed-four text verified.
  PASS (S2 FAIL proven).
- `V5 partials`: Wash Away dual-ability text + no Cleave engine (display-only
  refs); Find//Finality missing `K:Aftermath` vs `dusk_dawn.txt` reference.
  PASS (both PARTIALs proven).
- `V6 protocol/license`: Protocol 2.0.0, bridge 2.0.14-ws-a1d-h4f, GPL-3.0
  text + POM name, 137 @Test methods counted (WS227 seal: 139 executed).
  PASS.
- `V7 S2 negative probe (retained runtime)`: `testCreateValidation` 2P
  rejection + 4P lifecycle + separate-process suites — retained WS227
  139-test PASS at exact pin (no code delta, retention predicates sealed).
  PASS (retained, not rerun).

## Not run (explicitly absent, by policy)

- Full `mvn` recompilation / bridge suite re-execution: NOT_RUN (no code
  changes; would be reassurance-only spend).
- S5 135-fixture campaign: NOT_RUN (out of scope).
- New 2P/3P/5P lifecycle probes: NOT_RUN (remediation deferred; S2 FAIL
  sealed instead).
- Checkstyle: NOT_RUN (no sources touched).

## Verdict

Validation supports the sealed adjudication: S0 PASS, S1 PASS, S2 FAIL, S3
FAIL, S4 PASS (retained). Missing evidence stays explicitly absent above —
nothing inferred.
