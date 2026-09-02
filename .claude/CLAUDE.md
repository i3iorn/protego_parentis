# Agent Instructions

This repository's coding standard is defined by two documents at the repo root:

- `PHILOSOPHY.md` — the values and trade-offs behind how code here should be written.
- `FRAMEWORK.md` — the concrete architectural patterns, principles, and conventions.

Read both before making non-trivial changes. When this file conflicts with them, `PHILOSOPHY.md` and `FRAMEWORK.md` win — this file exists to translate them into agent behavior, not to replace them.

## How to apply the philosophy as an agent

- **Match effort to the change.** A one-line bug fix doesn't need a new interface, a new abstraction layer, or a refactor of surrounding code. Fix what was asked; don't redesign what wasn't.
- **Don't introduce architecture the project doesn't have yet.** If the codebase is a modular monolith, don't start splitting out a microservice or adding an event bus because a task touches a boundary — that's a decision for the project's humans, not something to do incidentally.
- **Prefer the third occurrence over the first.** Don't extract a shared abstraction the first time you see similar code; note it, and extract it once a genuine third case shows the same pattern actually recurring.
- **Leave touched code better, not touched code perfect.** Refactor what you had to change to do the task correctly. Don't expand a task into a drive-by cleanup of files you didn't need to touch.
- **Treat security and performance as part of correctness, not follow-up work.** Validate input at trust boundaries as you write the code that receives it. Don't defer "let's add validation later."
- **Write tests as part of the change**, not as a final step tacked on to satisfy a checklist. A change without a test for its new behavior isn't done.
- **Follow existing naming and structure conventions** in the surrounding code even if `FRAMEWORK.md` would suggest something else — consistency within a codebase beats a technically better convention introduced piecemeal. Raise the inconsistency to the user rather than silently converting the whole codebase.
- **Don't add comments that restate the code.** Only comment the non-obvious why — a constraint, a workaround, a rejected alternative.

## When to ask rather than act

Stop and ask the user before:
- Introducing a new architectural pattern or major dependency not already present in the project.
- Making a change that trades off between two competing principles in `PHILOSOPHY.md` in a way that isn't obviously correct (e.g., adding an abstraction that isn't yet justified by a third use, or skipping a test for a deadline reason).
- Any change large enough that "which principle wins here" isn't clear-cut.

For everything else — a clear bug fix, a requested feature that fits existing patterns, a refactor of code you're already touching — proceed and explain the change afterward rather than asking permission for each step.

## Commit and PR hygiene

- Keep commits scoped to one logical change.
- Explain *why* in commit messages, not just what changed — the diff already shows what.
- Run the project's existing lint/test/build commands before considering a change complete; don't rely on judgment alone where automation exists.
