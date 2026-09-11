# 02 — Baseline Full-Cast Reproduction (DIRECTLY_VERIFIED test runs)

Evidence class: DIRECTLY_VERIFIED (`mvn -o` TestNG runs on the audit-base
engine; full outputs in surefire reports).

## Compliant baseline vehicle

`forge-gui-desktop/src/test/java/forge/gamesimulationtests/ws63/Ws63A04NaturalFullCastTest.java`
(scripted player choice only for the X value inside the engine-offered
range and observation of replacement calls; all legality, payment,
targeting, ordering, and application stay in production engine/AI code).
The suite complies with every Milestone A prohibition:

- never calls `GameAction.moveToStack`/`moveToPlay` for the cast creature;
- never calls `setXManaCostPaid` directly;
- never injects counters, replacement outcomes, targets, or results;
- never bypasses cost payment (Forests tapped natively per test);
- DS/HS placed as fixture setup only.

## Baseline result (before any production change)

Eleven natural-cast configurations, all executed pre-fix at `a9a95db`:

| # | Configuration | X announce | Payment | Stack | Replacement reached | Result |
|---|---|---|---|---|---|---|
| 1 | exact, instance entry | 3 (1 call) | 3 tapped | yes | yes, max 2 contesting | 7\|8, battlefield |
| 2 | exact, static Human/provider entry | 3 | 3 tapped | yes | yes, max 2 | 7\|8 |
| 3 | exact, real priority-loop game | 3 | 3 tapped | yes | 3 calls, max 2 | 7\|8 |
| 4 | exact, Human-shape pool payment | 3 | pool consumed | yes | max 2 | 7\|8 |
| 5 | exact, 4-player game | 3 | 3 tapped | yes | max 2 | 7\|8 |
| 6 | exact, drawn card + resolved doublers + rounds | 3 | 3 tapped | yes | max 2 | 7\|8 |
| 7 | exact, SBAs + last-state copies before resolve | 3 | 3 tapped | yes | max 2 | 7\|8 |
| 8 | exact, 2xDS + 2xHS board | 3 | paid | yes | 5 calls, max 4 | 14 |
| 9 | base, no replacements | 3 | paid | yes | n/a | exactly 3 |
| 10 | generic Walker X=2 + Menace/Constrictor | 2 | paid | yes | yes | >2 |
| 11 | negative, non-cast entry (fail-closed) | n/a | n/a | n/a | n/a | 0 counters, graveyard via SBA |

Lineage recorded per run (object/card/SA ids, `==` identity relations,
castSA linkage, chosen/paid X, cause identity, zones): stack SA X=3,
`saHost==stackCard`, card X paid 3, `isUnlinkedFromCastSA=false` in every
natural configuration (temporary engine diagnostics, since removed).

## Material finding

The specified WS62 material failure (empty replacement list, 0/0
graveyard) **did not reproduce** through any compliant Forge-native
pipeline: the ETB replacement path recovers X=3 and both replacements
contest in every configuration. The failing-baseline half of Milestone A
is therefore UNKNOWN/absent; the passing-pipeline half is DIRECTLY_VERIFIED
(11/11 green pre-fix and post-fix).
