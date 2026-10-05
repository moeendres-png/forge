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

## Final adjudication (this branch head)

All 32 census methods are now classified; none is left UNKNOWN and no
expectation was copied from current output without a cited source.

- Fixture defect (1 class of failures): CardMockTestCase now initializes
  language/type data before the parallel card parse (above).
- Product defects, fixed with red-before controls:
  - DeckRecognizer short mana symbols and accepted `Colourless` (5 methods);
  - CardEdition.UNKNOWN/USER built without code defaults (sentinel getters
    dereferenced null; new control);
  - DeckRecognizer rejected real database names/collector numbers
    (`Continue?`, M19-185j, POR-57s, A-150e, a1_2007, 118†s, SLD star variants;
    new database grammar control, original collector-number form kept first);
  - CardDb.setPreferredArt stored the fallback print as the preferred art when
    the requested art did not exist (setPreferredArtForCard red before the fix).
- Upstream contract change, not oracle staleness: Forge 38da2046 (#8080) made
  tryGetCard's documented fallback live; affected methods assert exactly that
  fallback and every legacy-DB null assertion is kept.
- Card-data evolution, each expectation cited at the test: FDN, SLC, PLST/MP2
  codes, SOA, SLZ, PF26, SPG 128, PRM Island art fallback, derived missing-art
  index, a real FDN date instead of 2023-09-31.

D24ExecutionGuardTest denominators move 163/160 -> 165/162 for the two new
controls (the guard caught them; nothing relaxed).

Local validation (sandbox, C.UTF-8):
- targeted CardDb*/DeckRecognizerTest/CardEditionCollection*: 257 tests,
  0 failures on Java 17 (64 failing invocations before);
- full `-pl forge-gui-desktop,forge-protocol2-bridge -am` under xvfb on
  Java 21: 725 tests, 1 failure, 6 skipped. The failure is
  NetworkPlayIntegrationTest.testServerStartAndStop (BindException on port
  55556). It also fails in isolation, and an independent plain socket bind of
  55556 fails in this sandbox: the port is held by the sandbox. The branch
  touches no network code. ENVIRONMENTAL, not credited either way.
- Java 17 full run here cannot instantiate forge.PanelTest because the
  sandbox's JDK 17 is the headless package (no libawt_xawt). ENVIRONMENTAL.
- Hosted CI on the pushed head is the evidence of record; LOCAL_OBSERVED
  results above are not credit.

Out of scope and unchanged: Rules Core, card scripts, workflows, D17/D22.
Full coverage and JDK reduction remain unqualified.
PRODUCTION_PROVIDER=NOT_SELECTED; ARCHITECTURE_FREEZE=NOT_CLAIMED.

## Adversarial review (fresh context) and follow-up

No P1. P2 fixed: the widened collector-number form also read a trailing
quantity such as `x4` (and bare words such as `qty4`, `set2`, `p146`) as a
number; with the set-level fallback a `4x`-intended line imported one copy.
The second form now has to start with a digit or carry a non-alphanumeric
separator. New negative controls (`x4`, `x10`, `qty4`, `set2`, `p146`, and
full lines `1 Power Sink (TMP) x4`, `1 Lightning Bolt (M10) x4 *F*`) fail on
the previous regex and pass now; the whole-database grammar control still
passes, so no real collector number is rejected. Upper-case `X4` fits the
original upper-case form and was already accepted before #531 (unchanged).
P3 fixed: PRM lists 27 basic Island arts (comment said 30). P3 noted:
DeckUrlImportTextBuilder (Moxfield/Archidekt URL import) filters numbers with
the same regex, by design, so the parser accepts exactly what the importer
emits; real database numbers such as `380★☇` are now kept there too. P3
noted, no current case: setPreferredArt compares the main edition code, so
a print stored under a Code2 alias would be refused (fail-safe).

After the follow-up, Java 21 C.UTF-8 targeted CardDb*/DeckRecognizerTest/
CardEditionCollection*/D24ExecutionGuardTest: 262 tests, 0 failures.
