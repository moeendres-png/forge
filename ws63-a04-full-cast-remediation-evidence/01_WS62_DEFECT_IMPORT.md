# 01 — WS62 Defect Import (CODE_DERIVED from WS62 artifacts + journal forensics)

Evidence class: CODE_DERIVED (frames parsed from `WS62_A04_EVID.json`;
no behavior credit; `BEHAVIOR_CREDIT=0/107`).

## WS62 prereq disposition (imported)

- Engine-direct (moveToStack/moveToPlay with pre-set X=3): PASS, 7|8.
- Provider full-cast runtime (1282 frames, successor pin): NOT_REACHABLE —
  X=3 announced by value, 3 G paid natively, DS+HS present, spell resolves,
  0 `chooseSingleReplacementEffect` milestones, 0 replacement frames,
  Serpent 0/0 in graveyard. Provider synthesis count 0; provider repair
  forbidden. Blocker narrowed to full-cast cause/X propagation
  (`changeZone` equals-gate or payment-path X wiring).

## Independent journal verification (this workstream)

Parsed `WS62_A04_EVID.json` (1282 frames) directly:

- Frame kinds: `announce_x` x1 (seq 954, P1,
  `WS55:NUMRANGE:min=0:max=2147483647:announce=X:value=3`);
  `mana_payment` x3 (seq 955-957, three Forest `{T}: Add {G}` taps);
  all other frames `priority` PASS or unrelated upkeep/cleanup kinds.
- Cast: seq 953 P1 selects `ACT host=MINTED-32 Stonecoil Serpent`.
- Stack observations (P1 view): `#32` on stack at 954 and 958; stack empty
  at 962.
- Battlefield (P1 view) at 953/958/962: Forests + Hardened Scales `#8` +
  Doubling Season `#27` (P1-controlled; the other Doubling Seasons in ACT
  lists are hand cards, not battlefield permanents).
- Card `#32` trajectory across all 1282 frames: HAND (1-953) -> STACK
  (954-961) -> GRAVEYARD (962-1282). Never observed on the battlefield.
- Replacement-related frames (kind match or `REPL` in offered
  identities/options): **0 of 1282**.
- `failure_class` of the run itself: HARNESS (intent gap at
  `discardToMaximumHandSize`), per the evidence JSON.

## Forensic consequence (CODE_DERIVED)

The engine calls `chooseSingleReplacementEffect` even for a singleton
replacer list (proven by this workstream's instrumented runs: the entering
card's own etbCounter `Moved` replacement always produces a controller
call). Zero frames therefore entails an **empty `Moved` replacement list**
at `#32`'s Stack->Battlefield transition — not merely X=0 (X=0 still lists
the card's own replacement). Any candidate root cause must explain an
empty list, not just lost X.
