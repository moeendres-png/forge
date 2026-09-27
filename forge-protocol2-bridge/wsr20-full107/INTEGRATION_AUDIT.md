# WSR20 Local / Remote Integration Audit (read-only, 2026-09-26/27)

Amendment gate: preserve WSR20, audit project-wide for duplicable/publishable
work, adjudicate impact, resume. No repo was mutated during the audit except
the authorized WSR20 worktree (its own workstream commits). No push/merge/
rebase/reset/clean/checkout/delete/config anywhere.

Scope: /home/moeen/code (== ~/code). 9 main repos, ~220 worktrees inventoried
via worktree list + branch -vv + status counts + rev-list + branch --contains.
Remote authority re-verified: Lab main fa315da3, Forge master ef958ee91ac,
Mage master ff09f542, PR #206 OPEN head 80abaae6 MERGEABLE-but-stale.

## WSR20 checkpoint at audit start (preserved, not restarted)

- Worktree /home/moeen/code/ws-forge-full107-cdq-20260926, branch
  wsr20/forge-full107-common-denominator-20260926, HEAD 0df50be5 (Gate 0 docs),
  tree clean, +1 local commit over audit base ef958ee91ac/fc3387bf.
- Phase: reuse adjudication + mapping analysis done; full bridge suite running.
- Remote wsr20 branch unchanged (ef958ee91ac) throughout: NO SOURCE_DRIFT.

## Special search: publisher remediation newer than PR #206

- Complete ws241 lineage traced in commander-playtest-lab: ws-p b12b21fd ->
  205b3fb6 -> ca804a19 -> bf504a91 -> 88c1388a -> 0b6649ed (ws241-C final) ->
  cff7dbb3 (r7-g3) -> 80abaae6 (ws241 close = PR #206 head) -> b048d682
  (cpl/r10 integration onto R7).
- tools/foundry/safe_push.py is BYTE-IDENTICAL (800 lines, 40 tests) between
  80abaae6 and b048d682: r10 carries the hardening forward, strengthens nothing.
- `git log --all -- tools/foundry/safe_push.py`: newest touches are aa4b9619 /
  9810d0f7 (FULL107 residual topics on Lab main lineage) which GUT the file
  (534 lines on fa315da3, `_push_matches_fetch` ABSENT).
- Control-plane commits 9abd85c5/6baa24d4 contain safe_push.py WITHOUT the
  hardening. Current Lab main confirmed missing pushurl/pushInsteadOf/
  effective-target/--no-follow-tags/_push_matches_fetch.
- CONCLUSION: no newer local remediation exists. Strongest candidate remains
  PR #206 content (local branch ws241/safe-push-effective-target-20260916 @
  80abaae6, clean worktree ws-csn-r9-ws241-independent-review-20260917).
  b048d682 is the byte-identical-safe_push reintegration vehicle (plus
  WS196/WS198/WS199 stack). Main REGRESSED (hardening deleted, not moved).

## Impact adjudication for WSR20

A. DIRECTLY REUSABLE (adopted, provenance verified, same audit-base ancestry):
- R9-R19 campaign seals (candidate IS R19-merged master; re-verified 213/213).
- wsc2 common-denominator packet (b24ce365, docs-only, same denominator
  import 5a2e4f46, same Forge HEAD): 23 PASS + 7 FAIL_CLOSED rows adopted
  with evidence pointers; provisional XMage column REPLACED with canonical
  15/13/54/25 @59332671.
- wsc2a real-deck e2e (4c427063 + 8f0e2b23, ZERO production delta vs
  ef958ee91ac): 4 real decks, 240 decisions, twin determinism, hidden-info
  adopted as supporting actual-card evidence.
B. RELEVANT BUT SEPARATE (recorded, not mutated, not merged into WSR20):
- Publisher chain (#206 + b048d682): Coordinator integration prerequisite.
- muse-ws48/ws48 Lab probe tooling (esior/warstorm checkers, eliminations):
  Lab-owned; results consumed via Lab mapping only.
- Mage branches, control-plane extractions, ws50 decision-sequence,
  camp/rg-closure, astra/capsule/review worktrees.
C. SUPERSEDED / IRRELEVANT: pre-R19 forge remediation chain (ancestors of
  candidate), stale research branches, superseded Lab mappings (4/9/59/35).

## Condensed worktree table (nontrivial only; ~170 clean synced worktrees omitted)

Repo | Worktree | Branch | HEAD | Dirty | Remote | PR | Unique local work | Classification | WSR20 relevance | Next action
forge | ws-forge-full107-cdq-20260926 | wsr20/...-20260926 | 0df50be5+ | clean | Y synced+1 | none | Gate 0 + R20 tests + mapping | INCOMPLETE (active) | self | complete + publish via authorized path
forge | ws-c2-provider-denominator-20260923 | wsc2/... | b24ce365 | clean | N (local-only) | none | denominator packet (23/7/77) | HISTORICAL_KEEP/REUSED | A: rows adopted | keep branch; Coordinator merge decision
forge | ws-forge-real-deck-e2e-20260923 | wsc2a/... | 4c427063 | clean | N (local-only) | none | real-deck e2e + tests | HISTORICAL_KEEP/REUSED | A: evidence adopted | keep; optionally port tests later
forge | forge | master | 8a41f4a1 | clean | Y (behind 94) | - | none (local master drift) | SOURCE_DRIFT (local only) | none | do not touch; canonical is origin/master ef958ee91ac
forge | ws82/ws87/ws89/ws93 | retired chain | * | clean | Y/local | - | pre-R19 seals (ancestors) | HISTORICAL_KEEP | C | none
commander-playtest-lab | ws-csn-r9-ws241-independent-review-20260917 | wsr9/ws241 | 80abaae6 | clean | N (content == PR #206) | #206 OPEN | PR #206 bytes | REINTEGRATE_ON_CURRENT_BASE | B: prerequisite | Coordinator reintegration (do NOT merge as-is)
commander-playtest-lab | (branch cpl/r10-...) | cpl/r10-final-... | b048d682 | clean | N (local-only) | none | R7+WS196/198/199 + identical safe_push | REVALIDATE_THEN_PUBLISH | B | revalidate on current main, then publish
commander-playtest-lab | commander-playtest-lab-muse-ws48 | muse/ws48-... | 895eca21 | 1 untracked | Y (a57/b18) | none | behavior checkers + probes | RELEVANT-SEPARATE | B (results via mapping) | none in WSR20
commander-playtest-lab | ws-rg-closure-20260925 | camp/rg-closure | ca8950e5 | clean | N (a12) | none | RG closure handoff | RELEVANT-SEPARATE | B | none in WSR20
commander-playtest-lab | ws-l6-rg05 | sol/rg05-... | 7c1fb770 | 1M+1? | Y (behind 10) | none | active probe edits | DIRTY_RECOVERY (foreign) | none | do not touch
commander-playtest-lab | ws50-forge-decision-sequence-slice | ws50/... | e636e705 | 51 untracked | Y | none | sequence evidence | DIRTY_RECOVERY (foreign) | B (separate) | do not touch
mage-ws33 | xmage-ws49-baseline | DETACHED 0c1f455e | 0c1f455e | 7M | - | none | engine source edits | DIRTY_RECOVERY (foreign) | B (separate) | do not touch
mage-ws33 | mage-rg06b | sol/rg06b-... | c893e6f8 | clean | Y synced | none | morph suppression | synced | B | none
cpl-detached | 15bedf1a (ws-l6-rg05-clean) | DETACHED | 15bedf1a | clean | - | none | L6 final docs | HISTORICAL_KEEP | none | none
cpl-detached | 80d1b4fd, 269ee109 | DETACHED | * | clean | - | none | ref snapshots | HISTORICAL_KEEP | none | none

## Required report fields

PUBLISHER_STRONGEST_CANDIDATE = ws241/safe-push-effective-target-20260916 @
80abaae66065c25b507e1d0eb49c838764fa61db (== PR #206 head; worktree
ws-csn-r9-ws241-independent-review-20260917; safe_push.py 800 lines/40 tests).
No stronger newer local remediation exists (main + control-plane verified
without the hardening).

UNPUBLISHED_VALIDATED_WORK = 6 items: cpl/r10 b048d682 (revalidation
evidence in-commit); forge wsc2 b24ce365; forge wsc2a 4c427063 + 8f0e2b23;
cpl muse-ws48 895eca21 (a57); cpl camp/rg-closure ca8950e5 (a12);
forge wsr20 this workstream (on completion).

DIRTY_RECOVERY_ITEMS = 5 material: xmage-ws49-baseline (7 modified engine
files); cpl ws50 (51 untracked evidence); cpl ws49-canonical (1 modified
probe); cpl ws-l6-rg05 (1 modified test + 1 untracked); cpl main worktree
(Datenpaket_19_09 untracked). Plus ~15 trivial single-untracked (javac args,
metrics, inventories). All foreign ownership: NO TOUCH.

REMOTE_PRS_TO_CLOSE_AS_SUPERSEDED = none directed (pre-R19 chain PR state not
authoritatively enumerated; Coordinator to adjudicate).

REMOTE_PRS_REQUIRING_REINTEGRATION = PR #206
(moeendres-png/commander-playtest-lab, OPEN, MERGEABLE-but-stale; must NOT
merge as-is; reintegrate via b048d682 vehicle or fresh port onto fa315da3).

WSR20_REUSED_EXISTING_WORK = R9-R19 seals (re-verified 213/213); wsc2 23 PASS
+ 7 FAIL_CLOSED rows (evidence pointers kept); wsc2a real-deck evidence
(supporting); WS233/WS227/R15/R16/WS202/WS216/WS217 suites (executed).

WSR20_DUPLICATE_WORK_AVOIDED = denominator-packet rebuild (adopted wsc2);
real-deck e2e re-execution (adopted wsc2a); XMage-evidence regeneration (uses
canonical Lab mapping read-only); publisher remediation (untouched, separate).
