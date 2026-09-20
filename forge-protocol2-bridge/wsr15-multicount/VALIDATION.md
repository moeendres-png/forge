# R15 Validation

## Identity / hygiene

- Branch `wsr15/forge-multiplayer-conformance-20260920`, base `b8deae92`
  (R14 tip, clean at creation; all other Forge worktrees untouched —
  verified clean).
- Changes (owned surfaces only): 5 new test files
  (WsR15MulticountCombatTest, WsR15MulticountTriggerTest,
  WsR15ConcessionFamilyTest, WsR15HiddenInfoFamilyTest,
  WsR15DeterminismTwinTest) + `forge-protocol2-bridge/wsr15-multicount/`
  evidence (8 files). Zero production diffs; zero test-body diffs
  elsewhere. All probes removed (verified zero refs).

## Qualification (DIRECTLY_VERIFIED)

- New: 15/15 green (combat 3, trigger 3, concession 3, hidden 3, twins 3).
- Fail-before: 2P-concede terminal shape probed before asserting
  (gameOver + sole survivor, then strict).
- Retention: WS233 lifecycle/cardinality/process (re-run green in R14),
  R6–R13 families, WS227 replay (all in full-suite counts below).
- Full suites: sim 445 + bridge 205/205, checkstyle 0, BUILD SUCCESS.
- No EXTERNALLY_RULE_VALIDATED beyond Oracle/CR derivation; nothing
  MODELED/SYNTHETIC.

## Explicitly NOT_RUN / UNKNOWN

- 6P (separate successor, only now unblocked).
- Complete games to terminal at 3P/5P with real decks (bounded
  concession/combat/trigger terminals proven; full-length games NOT_RUN).
- FULL107, promotion, Lab/RSP, Freeze, Provider.

## Verdict

2P/3P/4P/5P terminal dispositions PASS (matrix) | 1P/6P FAIL_CLOSED |
FULL107 NOT_RUN | ARCHITECTURE_FREEZE NOT_CLAIMED |
PRODUCTION_PROVIDER NOT_SELECTED.
