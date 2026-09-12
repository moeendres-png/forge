# WS76 — Source Lock

- Repository: `moeendres-png/forge`
- Remote: `https://github.com/moeendres-png/forge.git` (slug verified via `git remote -v`)
- Worktree: `/home/moeen/code/ws76-forge-immediate-concession-hardening`
- Branch: `ws76/forge-immediate-concession-hardening-20260912` (sole Forge engine writer)
- Audit base (successor candidate, WS67-validated): `22e7f17befee8fcce0684fa6d006f29afdb5c280`
  / tree `f667cb959aaa28d11d85708a3c9b5ddb3e94dd24`
- Source lineage branch: `ws67/forge-ws65-engine-remediation-20260912`
- Accepted Forge pin (UNCHANGED, no repin claimed): `a9a95db6662c2d28814390a9c0c2f986e39aa8b4`
- Validated head (production fix + tests, clean-head verified): `2a49cef3c4078577d3b47cc0d3139c9f93a2bc7f`
  / tree `a42dae88d89697026dc82ed3cff4376503deeb7a`

## Authority / input

- WS68 materialized evidence:
  `/tmp/ws76-ws68-authority-20260912/candidate-qualification/ws68-forge-provider-transport-remediation/`
- Coordinator adjudication of WS68 concession: `CONCESSION_TRANSPORT=PARTIAL`,
  `G04_CONCESSION_REENTRY_PREREQUISITE=NOT_READY` (deferred concede() is not
  Full-Rules semantics; CR 104.3a requires immediate leave-game support).

## Ownership respected

- Modified: Forge engine (`forge-game`) + Forge regression test (`forge-gui-desktop` test tree).
- NOT modified: CPL, provider overlay, RQ-C3 fixtures, WS67 evidence,
  accepted-pin declarations.
