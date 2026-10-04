# First-option ratchet: debt control, not a runtime proof (D23)

`src/test/java/forge/bridge/FirstOptionRatchetTest.java` holds two syntactic gates
(commander-playtest-lab#504, roadmap #479).

1. **Test picks.** A bridge test may not answer a decision frame with whatever
   option is listed first. The pick is spelled on the receivers `options`, `opts`,
   `offered*`, `choices`, `candidates`, `legal*` or `getOptions()`, using
   `get(0)`, `getFirst()`, `iterator().next()` or `stream().findFirst()/findAny()`.
   The 245 legacy picks in older evidence tests are frozen per source file. The
   scan is recursive, so it includes subpackages. No file may gain a pick, a file
   not listed may have none, and a count that drops must be lowered so it cannot
   grow back. Adjudicating each legacy pick is separate work.
2. **Production picks.** The gate finds first-element picks in the bridge's
   production code, whatever the collection is called, in these spellings:
   - `get(0)` and `remove(0)`, including `0x0`, `0L` and `(0)`;
   - `getFirst()`, `removeFirst()`, `getLast()`, `first()`;
   - `iterator().next()`, `listIterator().next()`;
   - `findFirst()`, `findAny()`;
   - `toArray()[0]`;
   - `Iterables.get*`, `Iterators.get*`.

   It reads the source with comments, string literals and text blocks blanked,
   so a pick split over lines is still found. Each pick must be one of two
   things.
   - **A forced singleton.** The innermost block containing the pick must be
     opened by `if (<same collection>.size() == 1)`, or `else if (...)`. The guard
     may only be conjoined (`&&`) with negated plain flags such as `!isOptional`.
     These do not count:
     - a positive flag or a call;
     - a disjunction or a negation of the size test;
     - a guard whose block closed before the pick;
     - a guard in a comment;
     - a guard on a different collection;
     - a guard with no braces.

     There are 34 such sites today, and the count is pinned.
   - **A pinned exception.** It is listed by file and exact line, with a count
     and the reason it is not a first-option choice. There are 6 today:
     - the pilot's own one-card cost answer, read back twice;
     - a cost's source zone;
     - the minimum amount of a divided-allocation decision, which every
       recipient shares;
     - one singleton guarded by `spells.size() == num && num == 1`;
     - a replay match the code proves unique.

   Sensitivity checks run through the same scanner on literal snippets. They
   cover every spelling above and every fake guard above. On the real tree,
   replacing a guard with `!subsets.isEmpty()`, or turning `&&` into `||`, fails
   the gate.

**What this does not claim.** Both gates read source text. They do not prove that
no production-reachable decision falls back to a first option. Known blind spots:
- spellings not listed above, for example:
  - returning from a `for` loop on its first element;
  - `subList(0, 1)`, `peek()`, `poll()`;
  - `stream().min(...)`, `sorted(...).findFirst()` (the latter is caught, the
    ordering is not judged);
  - an index held in a variable (`legal.get(i)` with `i == 0`);
- a receiver reassigned inside its guarded block;
- a size-one guard on a decision that is optional for a reason other than a flag;
- a pinned exception whose surrounding code changes while its line text stays
  the same (exceptions are pinned by file and exact line text);
- a choice delegated outside the bridge;
- in tests, option lists under other names (the test ratchet is keyed on the
  names listed in 1).

That proof belongs to runtime qualification: the candidate's unsupported paths
fail closed, and its decisions are observed as engine-offered options chosen
explicitly. A green ratchet is never evidence of that.
