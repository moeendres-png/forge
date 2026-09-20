# R15 Disposition Matrix — 2–5P Multiplayer Conformance (Forge tip)

Grade: DIRECTLY_VERIFIED = live runtime on the tip; RETAINED = sealed
predecessor run on this ancestry with no impact trigger.

| Count | Code support | Lifecycle | Actual-card runtime | Terminal/continuation behavior (concession-driven) | Multiplayer rules | Hidden info | Determinism + replay |
|---|---|---|---|---|---|---|---|
| 2P | generic 2–5 gate (DIRECTLY_VERIFIED, code read + fail-closed negatives) | WS233 re-run green (RETAINED) | combat 2×2 + Kediss trigger (NEW 6 tests) | concede terminal: game over, sole survivor (NEW) | combat damage + triggers (NEW) | N×N canary matrix (NEW) | record/replay twin (RETAINED WS233) |
| 3P | generic gate | WS233 re-run green | combat split + Kediss fan-out (NEW) | concede leave + cleanup + continue (NEW) | multi-defender combat + fan-out (NEW) | N×N canary matrix (NEW) | record/replay twin + divergence (NEW 2 tests) |
| 4P | generic gate | WS233 + R9–R13 suites green | S3 29/29 + families (RETAINED R6–R13) | Lab gates: dual-terminal 4P (RETAINED successor-integration) | combat/trigger/divided/concession (RETAINED) | adversary matrix (RETAINED BridgeEngineTest) | WS218 tapes + WS227 tests (RETAINED) |
| 5P | generic gate | WS233 re-run green | combat split + Kediss fan-out + untouched seats (NEW) | concede leave + cleanup + continue (NEW) | multi-defender combat + fan-out (NEW) | N×N canary matrix (NEW) | record/replay twin (RETAINED WS233) |
| 1P/6P | fail-closed gate (DIRECTLY_VERIFIED negatives) | n/a (never constructed) | NOT_RUN | NOT_RUN | n/a | n/a | NOT_RUN |

Terminal dispositions: 2P/3P/4P/5P PASS (all cells above PASS or
explicitly RETAINED-PASS); 1P/6P FAIL_CLOSED (by design); nothing
UNKNOWN except FULL107-adjacent cross-candidate items (out of scope).
