#!/usr/bin/env python3
"""D18 (#499) contract: only superseded pull-request heads are ever cancelled.

Every workflow that runs on ``pull_request`` carries the same concurrency
block. Its group and cancel expressions are evaluated here, with a small
evaluator for the GitHub expression subset they use, against the events this
repository sees:

* two heads of one pull request share a group, and the newer one cancels;
* different pull requests, and different workflows, never share a group;
* a push (to master or to a qualification branch), a tag, workflow_dispatch
  and schedule each get a group of their own, keyed by the run id, and never
  cancel.

Any other ``cancel-in-progress`` in the repository must be listed, with its
reason, in ``CANCEL_EXCEPTIONS``. Standard library only, so it runs on any
runner without installing anything.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"

GROUP = (
    "${{ github.event_name == 'pull_request' && format('{0}-pr-{1}', github.workflow, "
    "github.event.pull_request.number) || format('{0}-{1}-{2}', github.workflow, "
    "github.event_name, github.run_id) }}"
)
CANCEL = "${{ github.event_name == 'pull_request' }}"

#: Workflows allowed a different cancel-in-progress, and why.
CANCEL_EXCEPTIONS = {
    # Publishes docs to the wiki; a newer master push supersedes an older
    # publication. It produces no qualification or test evidence.
    "sync-wiki.yml": "group: publish-wiki",
}


# --------------------------------------------------------------------------- #
# A minimal evaluator for the GitHub expression subset used above.

_TOKEN = re.compile(r"\s*(?:(?P<str>'(?:[^']|'')*')|(?P<num>\d+)|(?P<op>==|!=|&&|\|\||[!(),])"
                    r"|(?P<name>[A-Za-z_][A-Za-z0-9_\-]*(?:\.[A-Za-z_][A-Za-z0-9_\-]*)*))")


def _tokens(text: str) -> list:
    out, pos = [], 0
    text = text.strip()
    while pos < len(text):
        match = _TOKEN.match(text, pos)
        if not match or match.end() == pos:
            raise ValueError("cannot tokenize at {!r}".format(text[pos:]))
        pos = match.end()
        kind = match.lastgroup
        value = match.group(kind)
        if kind == "str":
            value = value[1:-1].replace("''", "'")
        out.append((kind, value))
    return out


def evaluate(expression: str, context: dict):
    """Evaluate ``${{ ... }}`` with GitHub's value-returning && and ||."""
    inner = expression.strip()
    if not (inner.startswith("${{") and inner.endswith("}}")):
        raise ValueError("not an expression: {!r}".format(expression))
    tokens = _tokens(inner[3:-2])
    pos = 0

    def peek():
        return tokens[pos] if pos < len(tokens) else (None, None)

    def take(value=None):
        nonlocal pos
        token = peek()
        if value is not None and token[1] != value:
            raise ValueError("expected {!r}, got {!r}".format(value, token))
        pos += 1
        return token

    def lookup(name):
        node = context
        for part in name.split("."):
            if not isinstance(node, dict) or part not in node:
                return None
            node = node[part]
        return node

    def primary():
        kind, value = peek()
        if value == "(":
            take("(")
            result = disjunction()
            take(")")
            return result
        if value == "!":
            take("!")
            return not primary()
        take()
        if kind == "str":
            return value
        if kind == "num":
            return int(value)
        if kind == "name":
            if peek()[1] == "(":
                take("(")
                args = [disjunction()]
                while peek()[1] == ",":
                    take(",")
                    args.append(disjunction())
                take(")")
                if value != "format":
                    raise ValueError("unsupported function {}".format(value))
                return re.sub(r"\{(\d+)\}", lambda m: str(args[1 + int(m.group(1))]), args[0])
            return lookup(value)
        raise ValueError("unexpected token {!r}".format(value))

    def comparison():
        left = primary()
        while peek()[1] in ("==", "!="):
            op = take()[1]
            right = primary()
            left = (left == right) if op == "==" else (left != right)
        return left

    def conjunction():
        left = comparison()
        while peek()[1] == "&&":
            take("&&")
            right = comparison()
            left = right if left else left
        return left

    def disjunction():
        left = conjunction()
        while peek()[1] == "||":
            take("||")
            right = conjunction()
            left = left if left else right
        return left

    result = disjunction()
    if pos != len(tokens):
        raise ValueError("trailing tokens in {!r}".format(expression))
    return result


def event(name, workflow="Test build", pr=None, run_id=1, ref="refs/heads/master"):
    github = {"event_name": name, "workflow": workflow, "run_id": run_id, "ref": ref, "event": {}}
    if pr is not None:
        github["event"]["pull_request"] = {"number": pr}
    return {"github": github}


# --------------------------------------------------------------------------- #


def workflow_files():
    return sorted(p for p in WORKFLOWS.iterdir() if p.suffix in (".yml", ".yaml"))


def triggers(text: str) -> set:
    """Top-level trigger names of a workflow, read from its ``on:`` key."""
    found = set()
    match = re.search(r"^on:[ \t]*(.*)$", text, re.MULTILINE)
    if not match:
        return found
    inline = match.group(1).strip()
    if inline:
        found.update(re.findall(r"[a-z_]+", inline))
        return found
    for line in text[match.end():].splitlines():
        if line and not line.startswith(" ") and not line.startswith("#"):
            break
        key = re.match(r"^  ([a-z_]+):", line)
        if key:
            found.add(key.group(1))
    return found


def concurrency_block(text: str):
    match = re.search(r"^concurrency:\n  group: (.+)\n  cancel-in-progress: (.+)$", text, re.MULTILINE)
    return (match.group(1).strip(), match.group(2).strip()) if match else None


class ConcurrencyContract(unittest.TestCase):
    def test_every_pull_request_workflow_carries_the_block(self) -> None:
        covered = []
        for path in workflow_files():
            text = path.read_text()
            if "pull_request" not in triggers(text):
                continue
            covered.append(path.name)
            with self.subTest(workflow=path.name):
                self.assertEqual(concurrency_block(text), (GROUP, CANCEL))
        self.assertGreaterEqual(len(covered), 3, covered)

    def test_no_other_cancellation_without_a_listed_reason(self) -> None:
        for path in workflow_files():
            text = path.read_text()
            if "cancel-in-progress" not in text or "pull_request" in triggers(text):
                continue
            with self.subTest(workflow=path.name):
                self.assertIn(path.name, CANCEL_EXCEPTIONS)
                self.assertIn(CANCEL_EXCEPTIONS[path.name], text)
                self.assertNotIn("pull_request", triggers(text))

    def test_a_newer_head_of_the_same_pull_request_cancels_the_older(self) -> None:
        older, newer = event("pull_request", pr=17, run_id=100), event("pull_request", pr=17, run_id=101)
        self.assertEqual(evaluate(GROUP, older), evaluate(GROUP, newer))
        self.assertIs(evaluate(CANCEL, newer), True)

    def test_pull_requests_and_workflows_never_share_a_group(self) -> None:
        groups = {
            evaluate(GROUP, event("pull_request", pr=17)),
            evaluate(GROUP, event("pull_request", pr=18)),
            evaluate(GROUP, event("pull_request", pr=17, workflow="iOS compatibility gate")),
        }
        self.assertEqual(len(groups), 3)

    def test_durable_events_are_never_cancelled_and_never_share_a_group(self) -> None:
        cases = [
            event("push", run_id=1),
            event("push", run_id=2),
            event("push", run_id=3, ref="refs/heads/foundry/ws40-af04-core-remediation"),
            event("push", run_id=4, ref="refs/tags/v1.0"),
            event("workflow_dispatch", run_id=5),
            event("schedule", run_id=6),
        ]
        groups = [evaluate(GROUP, case) for case in cases]
        self.assertEqual(len(set(groups)), len(cases), groups)
        for case, group in zip(cases, groups):
            with self.subTest(event=case["github"]["event_name"], ref=case["github"]["ref"]):
                self.assertIs(evaluate(CANCEL, case), False)
                self.assertNotIn("-pr-", group)
                # A pull request group can never collide with a durable group.
                self.assertNotEqual(group, evaluate(GROUP, event("pull_request", pr=case["github"]["run_id"])))

    def test_the_evaluator_is_not_vacuous(self) -> None:
        self.assertEqual(evaluate("${{ 'a' == 'a' && 'x' || 'y' }}", {}), "x")
        self.assertEqual(evaluate("${{ 'a' == 'b' && 'x' || 'y' }}", {}), "y")
        self.assertEqual(evaluate("${{ format('{1}-{0}', 'a', 'b') }}", {}), "b-a")
        self.assertIs(evaluate("${{ !(1 == 2) }}", {}), True)
        with self.assertRaises(ValueError):
            evaluate("${{ contains('a', 'b') }}", {})


if __name__ == "__main__":
    unittest.main()
