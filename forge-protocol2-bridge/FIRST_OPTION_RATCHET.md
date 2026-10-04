# First-option ratchet: debt control, not a runtime proof (D23)

`src/test/java/forge/bridge/FirstOptionRatchetTest.java` holds two syntactic gates
(commander-playtest-lab#504, roadmap #479).

1. **Test picks.** A bridge test may not answer a decision frame with whatever
   option is listed first (`options.get(0)`). The 214 legacy picks in older
   evidence tests on `master` are frozen per file. No file may gain one, a file
   not listed may have none, and a count that drops must be lowered so it cannot
   grow back. Adjudicating each legacy pick is separate work.
2. **Production picks.** Every first-element pick over a decision's options in
   the bridge's production code (`options`, `choices`, `candidates`, `legal*`,
   `valid*`, `offered*` with `get(0)`, `getFirst()` or `iterator().next()`) must be
   a forced singleton: guarded by `<same collection>.size() == 1` on the same or
   the preceding line. There are 9 such sites today, and the expected count is
   pinned so a new site is added deliberately. A sensitivity check confirms an
   unguarded pick fails the gate.

**What this does not claim.** Both gates read source text. They do not prove
that no production-reachable decision falls back to a first option. Such a
fallback could be spelled in a way the pattern does not cover, or sit outside
the bridge. That proof belongs to runtime qualification: the candidate's
unsupported paths fail closed, and its decisions are observed as engine-offered
options chosen explicitly. A green ratchet is never evidence of that.
