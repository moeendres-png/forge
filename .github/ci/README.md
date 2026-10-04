# Workflow contracts

Static contracts over this repository's own workflow definitions, run by
`.github/workflows/ci-contracts.yml` with the standard library only.

## Concurrency (D18, commander-playtest-lab#499)

Only a superseded pull-request head is ever cancelled. Every workflow that runs
on `pull_request` carries the same block: its group is keyed by workflow and PR
number, so a newer head cancels the older run. A push (to `master` or to a
`foundry/*` qualification branch), a tag, `workflow_dispatch` and `schedule`
each get a group keyed by their own run id, so they never cancel and are never
cancelled. Canonical and manually requested evidence therefore always runs to
completion.

`test_concurrency_contract.py` evaluates the real group and cancel expressions
for each of those events. Any other `cancel-in-progress` must be listed in
`CANCEL_EXCEPTIONS` with its reason; the only one is the wiki publication,
which produces no evidence.

What this does not decide: whether a cancelled PR run's evidence is needed. A
cancelled run is never credit; only the newest head's completed runs count.
