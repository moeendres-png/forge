# WSR20 Identity Adjudication (Gate 0)

No provider ranking at Gate 0. No silent rewrite of historical provenance.

## 1. Immutable denominator identity

Frozen FULL107 v1.0.5 denominator: Lab branch
`ws47/successor-contract-v1.0.5-freeze@5a2e4f462fd45bba25f2271153212aab9faf09f5`,
schema `full107-denominator-mapping-1.0.0`, materialization
`commander-lab.semantic-fixture-materialization/1.0.5`, exactly 107 items.
Definition bytes verified read-only; a local definition binding is persisted
as `FULL107_DEFINITION_BINDING.json` (import, not rewrite).

## 2. Historical FULL107 mapping provenance

`FULL107_IDENTITY_BINDING.json` (historical, immutable) records:

- XMage `db134b9737e951367d65ef5806ad986319cc73ab` (maven 1.4.61,
  protocol 2.0.0), lineage base `069762bc` (post-PR207/PR210 main)
- Forge `a37a865a53280dd8ad6fad3384d69611e8c5a42f`
- `production_provider: null`, `provider_decision: NO_PROVIDER_READY`

This file is historical provenance only. It is NOT edited.

## 3. Current XMage evidence authority

Lab canonical main `fa315da3` pins XMage `b19596980f2734496ea1896504253e1bdd2756dd`
(primary, compatibility-fork-unreleased) with reconciled runtime authority
`593326713faeddb8c90df2fdc5e5bafbe1fccf1b` (RESIDUAL_CLOSURE_L1_L7 = PASS,
mapping DIRECT 15 / SUPPORTING 13 / UNKNOWN 54 / NOT_RUN_BLOCKED 25).
XMage has moved `db134b97` → `b1959698` since the historical binding; the
historical record above is unaffected by this fact.

## 4. Current Forge candidate-under-test identity

- HEAD `ef958ee91ac6c9ce0152189f2654bf6e05abf273`, TREE `fc3387bf…`
  (master incl. R9–R19, reactor `2.0.15-SNAPSHOT`).
- Verified: Lab-pinned Rules-Core `a37a865a` IS an ancestor of the candidate
  (`git merge-base --is-ancestor` → true). The candidate therefore contains
  the Lab-pinned Rules-Core lineage plus the moeendres-png bridge/campaign
  stack (R9–R19, WS202–WS236 sucessors).
- All Forge evidence in this workstream binds to this exact HEAD/TREE unless
  a per-item entry states otherwise.

## 5. Current Commander-Lab Forge production/differential pin

Lab canonical main `config/rules_engines.json`: secondary Forge
`forge-2.0.14` @ `a37a865a` (Card-Forge upstream), status PARTIAL
("candidate provider; PARTIAL real evidence, not production-selected",
`production_ready: false`), materialized bridge source `4753bb7c`
(moeendres-png/forge) on rules-core base `a37a865a`,
`provider_decision: NO_PROVIDER_READY`.

The Lab Forge pin is intentionally older than the candidate under test.
Current Forge master is NOT an already-selected or already-repinned
Commander-Lab provider. This workstream changes no pin.

## Adjudication outcome

Gate 0 COMPLETE: five identities distinguished, historical provenance preserved,
candidate bound to exact HEAD/TREE, no ranking, no freeze, no selection.
