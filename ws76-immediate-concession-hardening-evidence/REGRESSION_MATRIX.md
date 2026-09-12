# WS76 — Regression Matrix

Validated head: `2a49cef3c4078577d3b47cc0d3139c9f93a2bc7f`
(all runs `mvn -o`, offline; checkstyle bound to `validate`, 0 violations
in every run below unless noted).

| Suite / scope | Result | Evidence |
|---|---|---|
| WS76 new: `Ws76ImmediateConcessionTest` (5 tests: priority control, 2P/4P/5P sweep concede, coherence) | **5/5 PASS** post-fix (4/5 failed pre-fix) | `postfix-ws76-report.txt`, `cleanhead-18of18-report.xml` |
| WS59 G04 seam: `Ws59G04ConcessionTest` (4 tests, native seam/800.4) | **4/4 PASS** | `cleanhead-18of18-report.xml` |
| WS67 targeted: `Ws67EngineRemediationTest` (8 tests, Clone/Humility + controls) | **8/8 PASS**, assertions unmodified | `cleanhead-18of18-report.xml` |
| `ReplacementHandlerTest` | **1/1 PASS** | `cleanhead-18of18-report.xml` |
| Clean-head trio re-run (WS76+WS59+WS67+Replacement, from committed `2a49cef`) | **18/18 PASS** | `cleanhead-18of18-report.xml` |
| Full reactor (`forge-core`, `forge-game`, `forge-ai`, `forge-gui`, `forge-gui-desktop`) | **397 run, 0 failures, 0 errors, 6 skipped** (skips pre-existing), BUILD SUCCESS | console summary in FINAL_REPORT |
| Checkstyle (`validate`, incl. test sources) | **0 violations** | build logs |
| `ComprehensiveRulesSection104` | NOT_RUNNABLE_IN_ENV (0 tests discovered under this surefire/TestNG-PowerMock setup; pre-existing discovery condition, unrelated to change) | noted, not claimed |

Environment note: `/tmp` (7.9G tmpfs, shared with other workstreams'
read-only checkouts) is full; surefire/TestNG emitted "No space left on
device" warnings while flushing reports. All result XML/TXT used above were
written to the worktree (`target/surefire-reports`) and verified complete;
test outcomes themselves are unaffected.
