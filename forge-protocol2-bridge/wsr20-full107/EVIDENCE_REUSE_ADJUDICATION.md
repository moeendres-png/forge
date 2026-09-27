# WSR20 Evidence Reuse Adjudication

Rule: EXACT_REUSE only on exact source/contract/fixture/evidence semantics.
Otherwise IMPACT_REQUALIFICATION_REQUIRED / SUPPORTING_ONLY / HISTORICAL_ONLY
/ UNKNOWN. A green historical workflow is never automatically current
FULL107 evidence. Old 29-card/30-family denominator never translates to
FULL107 PASS.

## 1. R9–R19 campaign seals → EXACT_REUSE (family level)

- Source: candidate HEAD `ef958ee91ac` IS the R19-merged master; seals bind
  to identical bytes (no production-code delta between seal time and now).
- Re-verified tonight at candidate source:
  `mvn -pl forge-protocol2-bridge -am -Dcheckstyle.skip=true test` →
  bridge **213/213 PASS, 0 fail/error/skip** (~837s), BUILD SUCCESS
  (Parent/Core/Game/AI/Gui/Bridge). Supersedes retention claims.
- Covers: S0 source/license/build; S1 30 families + loyalty/scry/divided
  deltas; S2 2–6P lifecycle + 1P/7P fail-closed; S3 29/29 actual-card
  (WS234 14, R6 2, WS236 2, R8 1, R9 4, R10 3, R11 2, R12 1); S4 replay.
- R19 FULL107=NOT_RUN statement preserved: no seal claims FULL107.
- Disposition: EXACT_REUSE for family/mechanism-level evidence;
  fixture-corresponding FULL107 DIRECT still requires per-item mapping
  (this workstream), never inherited from S3/S1 counts.

## 2. wsc2 common-denominator packet → EXACT_REUSE (30 rows)

- Local branch `wsc2/forge-xmage-common-denominator-20260923`
  @ `b24ce36520ad`, child of audit base, docs-only
  (`forge-protocol2-bridge/wsc2-denominator-20260923/`, +3695/−0).
- Same frozen denominator import (`5a2e4f46`, materialization 1.0.5, 107).
- Same Forge HEAD `ef958ee91ac`/tree `fc3387bf`; suite cited 213/213,
  re-verified identical tonight.
- ADOPTED: 23 Forge PASS rows (evidence pointers in FORGE_RESULTS.json)
  → DIRECTLY_VERIFIED; 7 NEGATIVE FAIL_CLOSED rows → TECHNICALLY_CONFORMANT.
- NOT adopted: wsc2 XMage column (provisional, db134b97-era mapping
  4/9/59/35). Replaced with current canonical Lab mapping
  (DIRECT 15 / SUPPORTING 13 / UNKNOWN 54 / NOT_RUN_BLOCKED 25 @59332671).
- wsc2 KNOWN_UNSUPPORTED (no starting-state injection seam, 0P/1P/7P+,
  unrepresented callbacks, export_event_log) → retained as blocking
  reasons where still true; re-proven where WSR20 executes.

## 3. wsc2a real-deck e2e → EXACT_REUSE (supporting, actual-card)

- Local branch `wsc2a/forge-real-deck-e2e-20260923` @ `4c427063cf2`
  (+ Phase A `8f0e2b23`), children of audit base.
- ZERO production-code delta vs `ef958ee91ac` (test files + 4 vendored
  real decks + evidence JSON only) → evidence binds to candidate bytes.
- Adopted as SUPPORTING_ONLY (not fixture-corresponding, pass-only pilot
  policy): 4 real Commander decks parse/import/construction (333/333
  identities), 240 externally-decided decisions (smoke 40 + extended 200,
  0 unsupported, 0 fallbacks), same-seed twin determinism (40/40),
  hidden-info opening/extended (own-hand exact, foes `<hidden>`),
  21 decision-family dispositions.
- Actual-card runtime weight for CARD_02-adjacent and multiplayer claims.

## 4. Historical-only (no behavior credit)

- FULL107_IDENTITY_BINDING.json (db134b97/a37a865a provenance).
- Pre-R19 engine-remediation branches (ws59–ws93 chain): ancestors of
  candidate; provenance only.
- Lab wsc2-era mapping (4/9/59/35): superseded by canonical 15/13/54/25.
- muse-ws48/ws48 Lab probe tooling: Lab-owned; results consumed via Lab
  mapping only, code not ported (RELEVANT BUT SEPARATE).

## 5. Impact requalification required (WSR20 new execution)

77 wsc2-UNKNOWN fixtures: HIDDEN 01–19+sentinel (partial), MICRO 17
(partial), PILOT CHOICE/PILE/MULTI_AMOUNT/TRIGGER_ORDER,
WS05-MP/CMD rest. New `WsR20*` tests (actual-card, engine-authoritative,
no fallback) close what is executable; genuine capability blocks
(no injection seam, London-tuck, controlled-player, look-audience,
paired-partner damage totals) stay NOT_RUN_BLOCKED/UNKNOWN with explicit
blocking reason + next action.
