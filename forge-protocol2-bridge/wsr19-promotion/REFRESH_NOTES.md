# Refresh Notes — R18 → R19 promotion packet (2026-09-21)

## Delta vs `wsr18-promotion` (base `9c41bff2`, R18 dir untouched)

1. `lab_branches`: +2 entries (R19 six-player @ `107db189`, tree
   `821aee76`; R20 CI lane @ `0526a798`, tree `656108c1`) with
   evidence notes; older entries upgraded head-only → head+tree dicts
   (trees freshly read, heads identical to R18 record).
2. `wsr18/forge-promotion-packet-20260921`: pre-packet head
   (`f7a8c6c7`) → post-packet head `9c41bff2` + tree `8db82593`
   (legitimate: packet sealed at that commit; this refresh is based on it).
3. `ws231/...`: tree filled (`a5f35229...`, was absent in R18).
4. `wsr19/...`: placeholder entry (this workstream appends; seals on commit).
5. `lab_main` / `forge_master`: reverified unmoved (`aebcfda3` /
   `8a41f4a1`); note: local Lab `main` has PR-#168 merge (`b483263e`)
   but canonical `origin/main` is unmoved.
6. `merge_impact`: R18 numbers carried with stale-label provenance, NOT
   recomputed. `repin_impact`: extended to R9–R20, still NONE.
7. DECISION_PACKET §3 (new) + §7 fixed: Lab 6P parity exists on-branch
   (R18 said "Lab main still 4P-only"); §8 updated accordingly.

## Fresh reverify log (2026-09-21, `git rev-parse` per branch)

- 17/18 Forge branch HEADs byte-identical to R18 seal (full-SHA
  prefix match); all trees match recorded values.
- 1 legitimate advance: wsr18 pre→post-packet (above).
- No Forge `wsr19` branch on remote (heads listed; R19/R20 Lab-side).
- Engines: `versionCode 2.0.15` + `snapshotName -SNAPSHOT` (Forge pom);
  `xmage.version 1.4.61` (Lab engine-bridge pom). Unchanged.
- mage candidate `db134b97` carried (no new mage workstream; lineage
  per R18).

## Push-ready verification (same day, see STATE on commit)

- Worktrees clean; sealed HEADs unmoved; packet JSON valid; index complete.
- Verdict: VERIFIED-READY. Actual push requires explicit user approval.
