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
