# WS233 Fallback-Reachability Impact (S1 retained PASS)

Diff inventory: `BridgeEngine` count gate/loops/capabilities; test fixtures +
new tests; one stale comment. No controller, framing, submission, projection
or error-mapping code touched.

- No new option enumeration, no default selection, no randomness, no AI
  reference, no GUI default, no parent-class fallback, no silent skip, no
  legality reconstruction, no outcome injection, no requested-option filtering.
- The six `> 4` completeness caps in `ExternalPlayerController` are card-count
  bounds (permutations/shield/attackers/blockers/orderings), unchanged and
  unreached by the count patch (5P lifecycles parked no such frame).
- Starting-player path still parks the full Core-enumerated set with no
  default (last-seat choice honored at every count; revision still parked
  until explicit submit).
- Full suite green includes all S1 fail-closed regression tests
  (`HeadlessGuiFailClosedTest`, negative-control blocks in lifecycle/starting
  tests, event-export closure).

Verdict: PROHIBITED_FALLBACKS_REACHABLE = 0 retained; WHOLE_BOUNDARY_FAIL_CLOSED
retained (CODE_DERIVED + DIRECTLY_VERIFIED regression).
