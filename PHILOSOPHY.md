# Coding Philosophy

## Why this document exists

This is the set of values behind how code gets written on this project. `FRAMEWORK.md` is the technical reference — the *how*. This is the *why*, and the tensions between competing goods that no checklist resolves for you.

None of this is absolute. Every principle below has a cost, and the skill isn't reciting the principle — it's knowing when its cost is worth paying. A rule applied without judgment is just superstition.

---

### I. Build for the size of the problem you have, not the one you imagine

Architect for growth you can already see evidence of, not growth you're guessing at. A module boundary that isn't needed yet is speculative cost paid today for a benefit that may never arrive (YAGNI). A module boundary added *after* the second or third time you feel the pain of its absence is usually cheap and obviously correct. When in doubt, wait for the second occurrence before abstracting — duplication is reversible; the wrong abstraction is not.

### II. Interfaces are bridges, not ceremony

Design around contracts where a boundary genuinely needs to flex — swappable backends, testable seams, code owned by different people or teams. Don't wrap every function in an interface "for testability" if there's only ever going to be one implementation; that's obfuscation wearing SOLID's clothes.

### III. Duplication is a smell, not a sin

Centralize logic that has one true meaning and multiple call sites. But two pieces of code that look similar today and change for unrelated reasons tomorrow are not duplication — they're coincidence. Merging them creates a false shared abstraction that has to be pulled back apart later, which costs more than the duplication would have.

### IV. Name and structure for the reader who has no context

Names, modules, and folders should make sense to someone who wasn't in the room when the decision was made — including future you. Consistency inside a codebase beats "correctness" of convention; don't introduce a second naming scheme because it's technically better if the whole codebase already speaks the first one.

### V. Complexity is a budget, not a war to win outright

Some complexity is essential — it reflects real problem complexity and can only be moved around, not deleted. Track it (cyclomatic complexity, function length, class size) so you notice when *accidental* complexity — complexity from how the code is written rather than what it does — is creeping in, and refactor that away. Don't chase a complexity metric to zero at the cost of readability.

### VI. Document intent, not narration

Comments and docs should carry the *why* that isn't recoverable from reading the code — a constraint, a rejected alternative, a workaround for something specific. Documentation that restates the code in English rots the moment the code changes and it wasn't updated. Prefer clear code over a comment explaining unclear code.

### VII. Draw boundaries around the problem domain, not the org chart (when it's warranted)

Domain-driven boundaries pay off once a system has multiple real domains with different rates of change and different owners. For a small service or an early-stage project, imposing DDD's full ceremony (bounded contexts, ubiquitous language, aggregates) is often over-engineering. Use the parts that reduce confusion; skip the parts that just add layers.

### VIII. Automate what you can, so it isn't a personality trait

Style, formatting, and many correctness checks should live in tooling (linters, formatters, static analysis, pre-commit hooks), not in memory or in someone's habit of "always double-checking." A rule that only lives in a person's head is a rule that gets forgotten under deadline pressure.

### IX. Design for testing, but don't worship the test pyramid

Small, pure functions and explicit data flow are easier to test and easier to reason about independent of tests. Write tests as part of building the thing, not as an afterthought bolted on before a release. But 100% coverage is not the goal — coverage of the paths that actually break, and the paths a user actually takes, is.

### X. Refactor continuously, in small doses

Pay down debt as you touch code, not in a single heroic rewrite. Big-bang rewrites have a well-documented failure rate; incremental refactoring under test coverage does not carry the same risk. Leave the code you touch a little better than you found it — leave code you *don't* need to touch alone.

### XI. Prefer boring and correct over clever and fragile

If a clever trick saves five lines but costs the next reader twenty minutes, it's a net loss. Optimize for the ability of a competent stranger to modify this code safely six months from now, not for how impressive the diff looks today.

### XII. Treat security and performance as design inputs, not later passes

A design that's elegant but leaks secrets, trusts unvalidated input, or is O(n²) where n is unbounded isn't actually a good design — it's an incomplete one. Ask "what happens with adversarial or unexpectedly large input" at design time, not at incident time. This doesn't mean pre-optimizing everything; it means not being surprised by the obvious failure modes.

### XIII. Feedback is how you find out you were wrong cheaply

No design is too settled to be reviewed, and no author is exempt from having their assumptions questioned. Code review exists to catch what one person's blind spots miss, not to enforce hierarchy. Take critique of the code as critique of the code, not of the author.

### XIV. Design for replaceability, not permanence

Assume every component will eventually be rewritten, replaced, or deleted, and try not to make that expensive. A codebase where no single piece is too sacred or too tangled to remove stays adaptable; one that accumulates untouchable cores calcifies.

### XV. Ship, and let this remain a compass, not a gate

None of the above is a reason to miss a deadline or block a real user need. When "sustainable" and "shipping now" genuinely conflict, ship — but write down the shortcut taken and why, so it's a tracked debt rather than a silent one. A philosophy that never bends to reality isn't principled, it's impractical.

---

## Adopting this for a project

Copy this file and `FRAMEWORK.md` into a project as-is, or trim sections that don't apply — a two-person prototype doesn't need the DDD section exercised, but it still benefits from I, III, IV, IX, XI. Keep the tensions visible rather than resolving them into false certainty; a team that argues from these values will make better local calls than a team following a rule it doesn't understand the cost of.
