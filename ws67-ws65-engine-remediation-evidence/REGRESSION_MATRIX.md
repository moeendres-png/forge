# Regression Matrix (validated head `22e7f17`, clean committed code)

Command pattern (offline):
`mvn -pl <module> [-am] -o test [-Dtest=...] [-Dsurefire.failIfNoSpecifiedTests=false]`
Checkstyle: project `checkstyle-validation` execution via `mvn -o validate`
(both modules: BUILD SUCCESS).

| Suite | Selector | Result |
|---|---|---|
| WS67 engine-direct reproducers | `Ws67EngineRemediationTest` (8 tests) | 8 run, 0 fail, 0 errors |
| Relevant Forge regression (simulation + AI) | `forge.gamesimulationtests.**`, `forge.ai.simulation.**`, `forge.ai.**` | 317 run, 0 fail, 0 errors |
| forge-game unit tests | full `forge-game` module | 3 run, 0 fail, 0 errors |
| Full `forge-gui-desktop` module (incl. all above) | full module, `-am` reactor | 392 run, 0 fail, 0 errors, 6 skipped |
| Checkstyle validation | `forge-game`, `forge-gui-desktop` | PASS |

The 6 skips are pre-existing network-dependent tests throwing TestNG
`SkipException` (`analyzeLog`, `runComprehensiveDeltaSyncTest`,
`runQuickDeltaSyncTest`, `testUnifiedHarnessLocalMode`,
`testConfigurableSequential`, `testConfigurableParallel`) — environmental,
unrelated to this change (no replacement/cost/AI surface).

Notable included suites (all green): `ReplacementHandlerTest`,
`comprehensiverules.*`, `ws59.*` (A04/C01/G04), `CloneOpponentCreatureAiTest`,
`Ws59A04ReplacementOrderingTest`, `ManaCostBeingPaidTest`,
`ManaRefundServiceTest`, `WS40CombatDamageCoreTest`, `CastFromOwnZoneTest`,
`GrantedCastTest`.

Pre-fix baseline (accepted pin, same selectors): WS67 8 run / 2 fail
(exactly the two under-Humility copy tests); all other suites green.
Post-fix: everything green — no regressions, fix strictly additive to
candidacy.

Interaction adjudication: single production fix confined to the replacement
candidacy view; cost/payment/timing code untouched → no shared-infrastructure
interaction to adjudicate. Cost packets (Ghalta, Covenant) required no engine
change, so no cross-fix interference exists.

FULL107: NOT_RUN (out of scope). Behavior credit: no new credit claimed
(0/107); these are remediation reproducers, not First-Wave behavior proofs.
