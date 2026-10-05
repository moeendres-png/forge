# #531 source-bound failure adjudication — interim census

Owner Work/Codex, branch hardening/carddb-deckrecognizer-adjudication-20261005.
Baseca1d57d19f9c91d6107d0ce9fb22741ac856e948/TREEce092ee296f60dc7108e16aaa252559f4781af69.
This branch contains research evidence only; no assertions/cards/Rules/CI changed.

FAILURE_CENSUS.json independently verifies both #532 hosted artifacts:
run37282465496, artifacts11332288925(Java17)/11332189053(Java21), exact SHA/TREE,
ZIP/raw XML hashes, identical class/method/failure messages. All68 failing
invocations represent32 underlying methods:18 inherited CardDb methods repeated
in3 classes, plus1 lazy fixture,1 edition collection and12 DeckRecognizer methods.
Each row records declaring source, hash, verified line, current error message and
all inherited runtime classes. Class/method multiplicity is never collapsed into
fake coverage. Candidate-controlled artifacts have no trusted D17 credit.

Classification currently UNKNOWN_PENDING_SOURCE_ADJUDICATION for all32 methods.
No current output is accepted as a new expected oracle merely because it exists.
Current findings to investigate (CODE_DERIVED, not final adjudication):
- latest-print expectations are historical hard-coded P30T/PLIST etc.; current
  source data adds FDN/SLC and canonically renames PLIST→PLST/MPS_AKH→MP2;
- CardDb.tryGetCard explicitly falls back when a requested edition lookup fails;
  several historical null assertions may assert a different API contract;
- DeckRecognizer.getMagicColor calls MagicColor.Color.fromName, which accepts
  full English color names, not short symbol characters; short-symbol parsing
  failures require a genuine product-adjudication check, not localized-expected
  text changes;
- CardEdition.UNKNOWN is constructed with scryfallCode unset, while its getter
  dereferences that field; known sentinel ??? also falls outside the set regex;
- lazy Ainok expectation and preferred-art indices need source-level review.

Exact next action: inspect every32 source method and relevant source editions,
classify supported card-data evolution vs contract drift vs genuine product/
fixture defects, retain UNKNOWN where unresolved. Then make bounded changes
with before/after/red controls. Do not mix D22/Forge27 or D17 into this branch.
Issue#531 remains OPEN. Full coverage and JDK reduction remain unqualified.
PRODUCTION_PROVIDER=NOT_SELECTED; ARCHITECTURE_FREEZE=NOT_CLAIMED.

## Independent-fixture finding (implementation validation pending)

Isolated DeckRecognizerTest on exact50218 source executes84 but fails49, not
the hosted full-suite12: Lang was uninitialized before CardStorageReader's
parallel parse; Spider-UK variant parsing aborted its batch and left an incomplete
database. The full-suite ordering had hidden the prerequisite. This is a genuine
fixture/harness defect. CardMockTestCase now initializes normal source language
and type data before eager parsing, with no card filtering or expectations edits.
This is fixture construction, not Rules behavior proof. Repeat the isolated
original84 tests before the symbol product repair; do not credit the mistakenly
named unchanged after-run as an after-fix result (it was another49-failure baseline).

## First narrow product repair (validation pending)

Five of32 methods are now supported GENUINE_DECK_RECOGNIZER_BEHAVIOR_DEFECT:
the short lettersW/U/B/R/G/C were passed to Color.fromName, which handles full
English names only. That null becomes the multicolor label. Original84-method
Java21 before run reproduces12 failures; five are short/hybrid/mixed/repeated
mana-symbol assertions. Map those six existing symbols explicitly in the deck
text parser; keep full-name/multicolor handling and every test assertion intact.
This parser formats deck text, not mana payment/legality or Rules decisions.
No other27 classifications are promoted. No Rules Core/cards/fixtures/workflows
or data expectation changes. Fresh after/negative evidence and review pending.

The first short-code-only repair770944 left two failures: accepted British
`Colourless` was also passed to the American/full-name-only enum conversion.
That diagnostic run84/9 is not final after evidence. The parser now maps that
already accepted spelling to the same existing COLORLESS enum; no assertions
or grammar broadened. Five-method after/negative controls must rerun on this head.
