# WS59 Historical-Impact Note

- Source changes are confined to forge-game Rules Core + one forge-gui transport
  delegation (Human concede -> super) + new tests + WS59 evidence docs.
- Changed surfaces:
  - ETB/X materialization (GameAction.changeZone cast linkage + ID-remap safety):
    affects all stack->battlefield spell permanents with X-linked ETB replacements.
    Existing ReplacementHandlerTest (perpetual enters-tapped) rerun PASS (1/1);
    historical counter PASS retained only for rerun surfaces, rest NOT_RUN.
  - Stack targeting candidacy (TargetRestrictions, SpellAbility.canTarget, CardUtil):
    affects all TargetType stack effects (counterspells, Spellskite-like retargets,
    etc.) and generic target enumeration. No existing targeting suite green to retain
    (PowerMock Section104 NOT_RUN in this env); new C01 tests PASS (2/2).
  - Concession seam (PlayerController, GameAction, Human): additive concrete methods
    (no abstract breakage); existing concede paths preserved via delegation.
    New G04 tests PASS (4/4); Section104 NOT_RUN (PowerMock env gap, pre-existing).
- No pin change yet (still audit base until validated_head set).
- No provider/shared/CPL changes. No Architecture Freeze, no Production Provider.
- BEHAVIOR_CREDIT remains 0/107; engine remediation alone awards no RQ-C3 scenario credit.
