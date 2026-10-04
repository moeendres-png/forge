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
   grow back. Raw Java Unicode escapes are rejected rather than partially
   interpreted by this source-text ratchet. Adjudicating each legacy pick is
   separate work.
2. **Production picks.** The gate finds first-element picks in the bridge's
   production code, whatever the collection is called, in these spellings:
   - `get(0)` and `remove(0)`, including `0x0`, `0L` and `(0)`;
   - `getFirst()`, `removeFirst()`, `getLast()`, `first()`;
   - `iterator().next()`, `listIterator().next()`;
   - `findFirst()`, `findAny()`;
   - `toArray()[0]`;
   - `Iterables.get*`, `Iterators.get*`.

   It reads the source with comments, string literals and text blocks blanked,
   so a pick split over lines is still found. Raw Java Unicode escapes are
   rejected fail-closed because Java translates them before lexical analysis and
   this bounded scanner does not emulate that phase. Each recognised pick must be
   one of two things.
   - **A forced singleton.** The innermost block containing the pick must be
     opened by `if (<same collection>.size() == 1)`, or `else if (...)`. The guard
     may only be conjoined (`&&`) with the bridge's existing optionality
     guards `!isOptional`, `!optional`, or `!cancelAllowed`.
     These do not count:
     - a positive flag or a call;
     - a disjunction or a negation of the size test;
     - a guard whose block closed before the pick;
     - a guard in a comment;
     - a guard on a different collection;
     - a guard with no braces.

     There are 34 such sites today, and the count is pinned.
   - **A pinned exception.** It is listed by file, exact line number and exact
     whitespace-collapsed line text, with the reason it is not a first-option
     choice. Moving or changing the line forces deliberate re-review. There are
     6 today:
     - the pilot's own one-card cost answer, read back twice;
     - a cost's source zone;
     - the minimum amount of a divided-allocation decision, which every
       recipient shares;
     - one singleton guarded by `spells.size() == num && num == 1`;
     - a replay match the code proves unique.

   Sensitivity checks run through the same scanner on literal snippets. They
   exercise the listed pick spellings plus positive singleton controls and
   negative controls for fake, wrong-collection, `size() >= 1`, `!isEmpty()`,
   OR, positive-flag, closed-block, comment and string-literal guards. On the
   real tree, replacing a guard with `!subsets.isEmpty()`, or turning `&&` into
   `||`, fails the gate.

**What this does not claim.** Both gates read source text. They do not prove that
no production-reachable decision falls back to a first option. Known bounded
blind spots include:
- spellings not listed above, for example:
  - returning from a `for` loop on its first element;
  - `subList(0, 1)`, `peek()`, `poll()`;
  - `stream().min(...)` / `stream().max(...)`, or sorting before
    `findFirst()` (the pick can be caught while the ordering semantics are not);
  - an index held in a variable (`legal.get(i)` with `i == 0`);
  - syntactic variants such as `legal.<T>get(0)`, `listIterator(0).next()`,
    `descendingIterator().next()`, nested-argument `toArray(...)[0]`, or
    numeric-separator zero spellings;
- a receiver or aliased collection reassigned/mutated after the size-one guard;
- optionality semantics: even the three explicitly recognised optionality flag
  names are only syntactic evidence here; the ratchet does not prove that
  declining is impossible or that those flags preserve their intended meaning;
- helper methods whose internal selection uses an unrecognised spelling;
- a pinned exception whose surrounding semantics change without moving or
  changing the pinned line;
- a production decision path moved or delegated outside
  `forge-protocol2-bridge/src/main/java`; the current source-lock audit found
  the Protocol-2 Java implementation under that module, but the ratchet does not
  prove that future architecture cannot move it;
- in tests, option lists under other names (the test ratchet is keyed on the
  names listed in 1).

That proof belongs to runtime qualification: the candidate's unsupported paths
fail closed, and its decisions are observed as engine-offered options chosen
explicitly. A green ratchet is never evidence of that.
