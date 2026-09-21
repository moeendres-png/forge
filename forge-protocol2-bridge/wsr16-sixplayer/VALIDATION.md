# R16 Validation

## Identity / hygiene

- Branch `wsr16/forge-six-player-20260920`, base `4476a043` (R15 tip,
  clean at creation; all other Forge worktrees untouched — verified clean).
- Changes (owned surfaces only): BridgeEngine gate (3 lines + comment),
  WS233CardinalityTest negatives (6P→7P + rationale),
  WS233CardinalityProcessTest (caps 6 + pods + 1 test),
  WsR16SixPlayerFamilyTest (7 tests) + `forge-protocol2-bridge/
  wsr16-sixplayer/` evidence (7 files). Probes removed (zero refs).

## Qualification (DIRECTLY_VERIFIED)

- New: R16 family 7/7 (lifecycle, combat, trigger, concede, hidden,
  twin-match, twin-diverge).
- Fail-before: engine 6P probe (capable) + old 6P rejection (gate).
- Retention: WS233 negatives (7P), process suite incl. fresh 6P,
  R6–R15 + WS234/WS236 families, census/replay suites.
- Full suites: sim 445 + bridge 213/213, checkstyle 0, BUILD SUCCESS.
- No EXTERNALLY_RULE_VALIDATED beyond Oracle/CR derivation; nothing
  MODELED/SYNTHETIC.

## Explicitly NOT_RUN / UNKNOWN

- Full-length 6P real-deck games to natural terminals (bounded
  terminals proven; marathons NOT_RUN, same bound as 3P/5P).
- FULL107, promotion, Lab/RSP, Freeze, Provider.

## Verdict

6P SUPPORTED (bounded scope above) | 7P+ FAIL_CLOSED | 2–5P intact |
FULL107 NOT_RUN | ARCHITECTURE_FREEZE NOT_CLAIMED |
PRODUCTION_PROVIDER NOT_SELECTED.
