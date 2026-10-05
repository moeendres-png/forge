# D22 bounded remediation — historical disabled CR104 scenario

Work/Codex owns Lab#503; separate from C12/D17, D20, D24 migration, provider selection.
Source base: ca1d57d19f9c91d6107d0ce9fb22741ac856e948, TREE ce092ee296f60dc7108e16aaa252559f4781af69.
Branch: hardening/d22-simultaneous-win-loss-20261005.

## Rules adjudication

Official current source: https://media.wizards.com/2026/downloads/MagicCompRules%2020260925.txt
(2026-09-25, linked by https://magic.wizards.com/en/rules, inspected 2026-10-05).
104.3f still requires a simultaneous winning/losing player to lose. However, this
historical test combines damage replacements with resulting draws: 120.4b and
121.7 order replacements and replacement-caused draws. Unpayable Nefarious Lich
replacement loses before the later Laboratory Maniac draw replacement.
The historical method name stays for evidence continuity; it is NOT an isolated
simultaneous104.3f obligation. No Rules Core defect was demonstrated here.
No new fabricated simultaneous-win/loss card or manual terminal injection was added.

## Findings and changes

1. Enabling the existing test alone failed: Lifelink's Aura type was removed by
   subtype cleanup because mocked FModel skipped normal type-data initialization.
   BaseGameSimulationTest now loads the real source type list normally.
2. The activation helper added an ability to the stack without paying any costs.
   It now verifies engine legality and uses normal PlaySpellAbility activation.
   The fixture adds one actual Swamp for the Wumpus's black mana cost.
3. The card-database localization mock only supported zero format arguments.
   Real game prompts now use normal English resources with inherited singleton
   cleanup. Lang is initialized before any threaded card loading.
4. The contrasting payable-Lich route formerly delegated its card choice to AI.
   An explicit ChooseZoneCardAction now resolves the declared choice only within
   engine offers, requires unique identity and the exact zone route, fails closed
   when absent, and never generates legality. This is a test-only route.
5. Original winner and final-turn assertions remain intact; the historical test
   is enabled. D24's explicit ratchet removes exactly this one disabled entry and
   increases enabled declarations160→161/runtime268→269, preserving two disabled
   performance benchmarks. Rules Core, cards, POMs and workflows are unchanged.

## Controls and reproduction

Run from repository root with Java17 or Java21:

```sh
xvfb-run -a mvn -B -pl forge-gui-desktop -am -Dtest=ComprehensiveRulesSection104,LichDamageReplacementTest,D24ExecutionGuardTest -Dsurefire.failIfNoSpecifiedTests=false clean test
```

The four Lich controls check unpaid activation denial, real paid mana consumption,
the expected loss with an empty graveyard, the opposite Maniac win with a declared
exilable Forest, and missing scripted zone-choice denial. Winner and turn checks
execute against real Rules Core results. Construction remains synthetic fixture
setup, not a full game/startup/Commander qualification.

At initial persistence: local Java21 targeted21/21 PASS; Java17 and mutation controls
NOT_RUN, hosted exact-head runtime/review UNKNOWN. Do not merge from this interim state.
Before-fix XML and diagnostic JSON are committed; diagnostic evidence grants no
trusted candidate credit. Final receipts and GitHub PR checkpoints supersede this
interim status explicitly. A green workflow is not provider qualification.

Global nonclaims: PRODUCTION_PROVIDER=NOT_SELECTED; ARCHITECTURE_FREEZE=NOT_CLAIMED;
PRODUCTION_REPOSITORY=NOT_CREATED. C12/D17 remain reserved to Sol owners.

## Validated local checkpoint (supersedes initial interim local status)

Source95dce6de1bb3aebabfcbdba8aa30122fed8883d7/TREEe53d01264edd0fa798c6a5ec0483a582306b03a9,
clean code, Java17 and21 each21/21 PASS, no failures/errors/skips.
AFTER-JAVA17/21.json bind exact source/tree, full command, JDK, timestamps and raw
XML hashes. Source-bound negative-control patches and XML are also committed:
- RED-UNPAID-ACTIVATION:21 execute,3 fail; unpaid activation no longer throws and
  both paid routes fail the real-Swamp-tapped check.
- RED-AI-ZONE-FALLBACK:21 execute,2 fail; missing explicit selection no longer
  throws, and unconsumed script fails the positive game's actions-exhausted check.
- RED-WRONG-WINNER:21 execute,1 fail; actual Player2 defeats expected Player1.
All mutants were restored byte-for-byte. They are expected FAIL controls, not
passing qualification runs. An initial unpaid mutant left an unused import and
was rejected by Checkstyle before tests; that disposable failure is intentionally
not credited or preserved as a successful semantic control.

Diagnostic limitation: normal real localization exposes two existing
GameLogFormatter poison-event subscriber exceptions in synthetic source-less
initial poison-counter setup (CR104 tests). Game outcomes/turns remain checked
independently; poison event/log correctness is UNKNOWN, not qualified by this
workstream. Production replay/event qualification is not claimed. This source-null
construction-only log behavior must not be silently promoted to Rules behavior.
The newly enabled Lich paths and their controls do not use synthetic poison.

DraftPR: https://github.com/moeendres-png/forge/pull/27 . Hosted/review remain pending;
current exact source95dce PR Java21 run37285286622/job111682623208 has completed:
desktop728 execute/68FAIL/6SKIP; provenance integrity PASS, upstream/downstream
coverage FAIL retained. Artifact11335025526, ZIPsha256
3bf676b646088ce79a14ac13e55759f01b5a93af4a9a30fecf840550a6723baa.
Full independent two-JDK raw-report readback is the exact next action, then review,
fresh base/head/writer lock and bounded merge admission only if no new regressions.

## Review repair (supersedes c08 interim admission)

Independent exact-head review found three P2s, accepted:
- initialize source type definitions before eagerly parsing/caching cards;
- scope real localization to Lich controls, preserving the old poison fixtures;
- retain unqualified104.3f semantic debt even though its historical fixture runs.

New construction-only multiword-type control uses actual The Tenth Doctor
and requires Time Lord to be parsed intact. D24 now prints the separate
CR104.3f NOT_RUN/UNKNOWN obligation and PARTIAL Rules coverage in all runs.
RULE_OBLIGATIONS.json preserves the same distinction. Lab#503 remains OPEN;
this PR repairs the historical fixture but does not terminate its broader rule
obligation. No simulated terminal result or fake simultaneous card was added.
Old c08/95 dual-JDK receipts are historical supporting evidence only after this
relevant fixture/obligation change. New exact-head receipts/review are required.
