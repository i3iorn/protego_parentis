# Framework for Scalable, Maintainable Codebases

This is the technical reference companion to `PHILOSOPHY.md`. Where that document is the *why*, this is the *how*: architectural patterns, design principles, and project conventions, each with the cost side included. Nothing here is unconditionally correct — pick the tool that matches the problem's actual size.

## How to use this document

Don't apply every section to every project. A weekend prototype and a five-year platform need different amounts of this. As a rule of thumb: adopt a pattern when you can point to the specific pain it removes; skip it when you're adopting it because it's "best practice" in the abstract.

## Architectural Patterns

Choose an architecture that enforces **modular boundaries** and clear data flow — but match its overhead to your team size and problem shape.

- **Layered (Multitier):** Separates presentation, business logic, and data access. Cheap to adopt, well understood, works for most CRUD-shaped applications. Cost: can encourage anemic layers that just pass data through with no real logic of their own if applied too rigidly.
- **Hexagonal (Ports & Adapters):** Decouples core logic from external systems via interfaces. Pays off when you genuinely expect to swap infrastructure (databases, message brokers, UI) or need to test business logic in isolation. Cost: more files and indirection than a layered approach for the same feature; not worth it for a small CRUD app with one obvious persistence choice.
- **Microkernel (Plugin):** A minimal core plus pluggable modules. Use when third parties or separate teams need to extend the system without touching the core. Cost: plugin contracts are hard to design well up front and expensive to change later.
- **Microservices:** Independently deployable, independently scalable services. Use when parts of the system have genuinely different scaling, deployment, or ownership needs. Cost: network calls replace function calls, distributed failure modes appear, and operational overhead (deploys, observability, versioning across services) is substantial — this is usually the wrong default for a small team or an early-stage product. Start with a modular monolith and split only where you feel real pain.
- **Event-Driven:** Components emit and react to events, decoupling senders from receivers. Good fit for asynchronous, multi-consumer workflows. Cost: harder to trace a request end-to-end, eventual consistency has to be designed for explicitly, debugging requires different tooling than a linear call stack.
- **Domain-Driven Design (Bounded Contexts):** Organizes code by business domain rather than technical layer. Pays off once a system has multiple domains with different rates of change and different owners. Cost: the full DDD toolkit (aggregates, ubiquitous language, anti-corruption layers) is heavy ceremony for a system with one obvious domain — take the parts that reduce confusion, skip the rest.
- **Modular Monolith:** A single deployable with enforced internal module boundaries. Often the right starting point: gets most of the maintainability benefit of microservices without the distributed-systems cost, and can be split later along boundaries that already exist.

**Decision heuristic:** default to a modular monolith with layered or hexagonal internals. Move to microservices, event-driven architecture, or a microkernel only when you can name the specific scaling, team-ownership, or extensibility problem that the simpler option doesn't solve.

## Modular Design Principles

- **Single Responsibility / Separation of Concerns:** One clear purpose per module. Taken too far, this fragments a simple operation into a dozen tiny classes that are individually simple but collectively hard to follow — a class doing "one thing" should still do a whole, coherent thing.
- **Open/Closed:** Extend via new code rather than editing stable code, where the extension point is real. Don't pre-build extension points for variation that hasn't shown up yet — that's speculative generality, and it usually guesses the wrong axis of variation anyway.
- **Liskov Substitution / Interface Segregation / Dependency Inversion:** Depend on abstractions where more than one implementation plausibly exists or where testing needs a seam. A single-implementation interface that exists only "for DI" is usually unnecessary indirection.
- **DRY:** Every piece of *domain logic* should have one canonical home. This is about knowledge, not text — two code blocks that happen to look alike but encode different business rules are not duplication, and merging them creates a false abstraction that has to be split apart again when the rules diverge. Prefer waiting for a third occurrence before extracting a shared abstraction (rule of three).
- **KISS:** Prefer the simplest solution that satisfies the actual requirement. Simplicity is relative to the reader, not to the elegance of the solution — the "simple" option is the one a new team member understands fastest.
- **YAGNI:** Don't build speculative features or configurability. The cost of building it now and never needing it is usually higher than the cost of adding it later when you do.

## Documentation & Self-Documenting Code

- **Code walkthroughs:** A README or `/docs` overview of architecture and how the main pieces interact — aim it at someone joining the project, not at yourself.
- **Design rationale:** Record *why* a non-obvious approach was chosen, and what was rejected and why. This is the highest-value documentation and the easiest to lose if you don't write it down at decision time.
- **API/module docs:** Docstrings for public interfaces. Prefer expressive names over comments that just restate what a well-named function already says.
- **Changelogs:** Record what changed and why, especially for anything that resolved a production issue — that context is exactly what gets lost otherwise.

Store docs with the code, version them together, and treat a stale doc as a bug — either fix it or delete it; a wrong doc is worse than no doc.

## Naming Conventions & Code Organization

- Use descriptive, intention-revealing names (`calculateInvoiceTotal`, not `calc`), and one consistent casing convention per project.
- Organize by feature or by layer — pick one and be consistent; don't mix both at the same directory depth.
- Keep configuration, tests, and docs out of the source tree proper (`config/`, `tests/`, `docs/`) so the code directory contains only code.
- Match directory layout to package/namespace structure where the language supports it, so files are locatable by their import path.

## Common Design Patterns

Use these when they remove real complexity, not by default:

- **Factory Method:** Object creation varies by context/configuration and you want callers decoupled from the concrete type.
- **Strategy:** Multiple interchangeable algorithms selected at runtime; avoids long conditional chains.
- **Observer:** One-to-many state-change notification; standard for event-driven or MVC-style UIs.
- **Singleton:** Use sparingly — it's global mutable state with a design-pattern name, and it makes testing harder. A dependency-injected instance usually serves the same purpose more safely.
- **Adapter:** Bridges two incompatible interfaces without scattering conversion code across call sites.
- **Decorator:** Adds behavior (logging, caching) to an object without subclassing or modifying it.
- **Facade:** A simple interface over a complex subsystem, hiding internal complexity from callers.

Prefer composition over inheritance when implementing any of the above.

## Technical Debt & Redundancy Management

- Use static analysis and linters to catch complexity, duplication, and style drift automatically — don't rely on memory or habit.
- Refactor as you touch code, not on a separate "refactoring sprint" that competes with feature work for priority and usually loses.
- Track known debt explicitly (a backlog item, not a comment that says `TODO` and nothing else) so it competes for prioritization instead of being invisible.
- Watch quality trend metrics (coverage, complexity, churn) over time — a single snapshot number matters less than the direction it's moving.

## Versioning, Dependency Management & Refactoring

- **Semantic Versioning:** `MAJOR.MINOR.PATCH` — breaking / additive / fix. Tag releases and maintain a changelog.
- **Dependency management:** Lock dependency versions for reproducible builds; update deliberately and validate with CI, not reflexively on every release.
- **Version control:** Trunk-based or GitFlow, whichever matches your release cadence and team size — trunk-based scales better for continuous deployment, GitFlow gives more structure for scheduled releases. Every change goes through review and CI.
- **Refactoring:** Continuous and incremental, under test coverage. Reserve large-scale rewrites for cases where the codebase is genuinely unsalvageable, since big-bang rewrites have a well-documented history of running over budget and losing subtle behavior no one remembered to re-specify.

## Security & Performance as Design Inputs

- Validate and sanitize input at every trust boundary (user input, external APIs, file uploads) — not "somewhere in the stack," at the boundary itself.
- Treat secrets, credentials, and PII as things that must never land in source control, logs, or error messages by default.
- Know the complexity class of code on a hot path or handling unbounded input; an elegant O(n²) solution over unbounded n is a latent incident, not a stylistic choice.
- Don't pre-optimize paths that aren't hot — profile before you optimize, and optimize the bottleneck you measured, not the one you assumed.

## Folder Structure & Configuration Management

A conventional layout, adapted per language/framework:

```
src/          # or lib/ — production code, organized by layer or feature
tests/        # or spec/ — mirrors src/ structure
docs/         # architecture notes, design rationale, diagrams
config/       # environment and application configuration
scripts/      # build, deploy, and dev tooling scripts
```

- Keep configuration and secrets out of the code directory — environment-specific values belong in `.env`/`config/`, not hardcoded or scattered through source files.
- Use the ecosystem's standard dependency manifest (`package.json`, `requirements.txt`, `pom.xml`, etc.) as the single source of truth for dependencies; don't hand-install anything a lockfile should track.
- Commit configuration and setup scripts to version control; never commit build artifacts, secrets, or environment-specific credentials.
