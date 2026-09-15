# WS233 Regression Results

Command: `mvn -pl forge-protocol2-bridge -am -o test` (offline, pinned deps).

- Full bridge suite: 150 tests, 0 failures, 0 errors, 0 skipped (609.9 s).
  Baseline at pin: 139 executed. Delta: +11 (7 `WS233CardinalityTest` + 4
  `WS233CardinalityProcessTest`). No test deleted, weakened, or re-scoped
  except `BridgeEngineTest.testCreateValidation` pod-size probe rebound
  2-handle -> 1-handle (same error code; 2P is now legal by contract).
- 4P strength preserved or extended: all pre-existing 4P lifecycle,
  starting-player, principal-scoping, fallback, protocol-purity and
  replay tests pass unmodified.
- Checkstyle (`checkstyle:check`, informational): 2885 violations module-wide
  including untouched files (pre-existing; not an enforced gate — `mvn test`
  succeeds at base and post-change). Touched lines introduce no new violation
  category (new constants carry Javadoc; test files add zero flagged lines).
- S3 untouched: no card script, no Rules implementation, no S3 test modified
  (`git status` scope: bridge provider + bridge tests + ws233 evidence only).

Verdict: FULL_BRIDGE_REGRESSION PASS; existing-4P regression PASS.
