# CR104 controller harness repair — Lab #532

Source lock: Forge master `9bb4448c7c7514dadc93dca973362f630c2604ed`, TREE `892c88fe7eeb96a7ce4d35f50fc16d15eff638f8`.

The test-only `PlayerControllerForTests.playSpellAbilityNoStack` passed null to the real Rules Core execution path. `PlaySpellAbility.playAbility` now needs that controller when evaluating costs even for triggered effects. Pass the existing test controller to both pre-existing dispatch sites. No controller policy, card-name branch, AI policy, Rules Core, expectation or test enablement changes are introduced.

## Evidence

Unmodified CR104 suite: 11 executed, 9 PASS, 2 FAIL, 0 skipped. Both failing tests are the reported Near-Death Experience / Final Fortune scenarios, with `this.controller` null at the cost decision call. Before XML and normalized receipt are committed.

After the exact two-line patch: Java21 and Java17 each execute all 11 unmodified tests with 11 PASS / 0 FAIL / 0 ERROR / 0 SKIP. Original winner/turn assertions remain unchanged. Each receipt binds the full patch, source base, changed-file SHA256 and raw XML SHA256; compressed raw XML is committed. These are targeted local DIRECTLY_VERIFIED harness results, not a full-reactor PASS or trusted D17 qualification.

Reproduce under either JDK using JAVA_HOME and PATH for that JDK:

```
xvfb-run -a mvn -B -pl forge-gui-desktop -am -Dtest=ComprehensiveRulesSection104 -Dsurefire.failIfNoSpecifiedTests=false test
```

Before reproduction: use the source-lock commit. After: use this repair commit. Full hosted Java17/21 results and exact-head review must be read before merge. The remaining CardDb/DeckRecognizer failures (#531), explicit disabled104.3f (#503), full provenance coverage and trusted qualification are not made PASS by this bounded change.

Scratch logs outside the repository are reproducible/non-authoritative. The first capture helper assertion mismatched the quoted exception spelling and made no source mutation; its repeat still used unmodified source and supplied the committed BEFORE evidence. Only the subsequently patched runs are AFTER. No failed helper/run is promoted.

Global nonclaims: PRODUCTION_PROVIDER=NOT_SELECTED; ARCHITECTURE_FREEZE=NOT_CLAIMED; PRODUCTION_REPOSITORY=NOT_CREATED.
