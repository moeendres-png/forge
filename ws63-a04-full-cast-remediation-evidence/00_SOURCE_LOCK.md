# 00 — Source Lock (WS63)

Evidence class: DIRECTLY_VERIFIED (git plumbing).

- Repository: `moeendres-png/forge`
- Worktree: `/home/moeen/code/ws63-forge-a04-full-cast-remediation`
- Branch: `ws63/forge-a04-full-cast-remediation-20260911`
- Production audit base (HEAD at work start, verified `git rev-parse`):
  - `FORGE_BASE_HEAD=a9a95db6662c2d28814390a9c0c2f986e39aa8b4`
  - `FORGE_BASE_TREE=2c18327f79e330f2ed167067166ffd42d61b0849`
- Remote source lineage (authorized read-only fetch ref):
  - `ws59/forge-rqc3-engine-remediation-20260911`, required tip
    `85f03824229cbfc0a93bdf85d3554a92b5351884` (enforced at push time).
- WS62 authority head (evidence only, CPL lineage): `63930f13e8308c8a1d17fff5fd5a82c62ef5519d`.
- Working tree was clean at `a9a95db` before edits (only untracked
  `.foundry/metrics.jsonl` and the empty evidence dir present).
- Authority directory read in full: `/tmp/ws63-a04-authority-20260911/`
  (`WS62_A04_PREREQ.json`, `WS62_A04_EVID.json`, `WS62_FINAL_REPORT.md`,
  `ws62_breadth_runner.py`, `ws62_provider_overlay.py`,
  `COORDINATOR_A04_RULES_GATE.md`), plus `WS62_ADAPTER_AUDIT.json` and
  `WS62_BUILD_RECEIPT.json`.
- Rules authority: official Comprehensive Rules effective 2026-08-07;
  relevant rule CR 107.3m (EXTERNALLY_RULE_VALIDATED via the Coordinator
  gate). Current CR 107.3k concerns activated-ability costs, not ETB-X.
