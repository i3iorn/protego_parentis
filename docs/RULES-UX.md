# Rules UX — deep dive and redesign plan

A review of how the Rules area is navigated, understood and authored, and a
plan to make it first-rate.

**Method.** This is a review of the code and the copy — the activities, the
describer, the strings — not of rendered pixels (I could not run the app). Every
claim below cites the file or string it comes from. Where I am inferring rather
than reading, I say so.

The product model is genuinely strong. The problem is almost entirely that the
UI exposes the **engine** rather than a parent's **mental model**. That is a
presentation problem, which is good news: it can be fixed without touching the
policy engine.

---

## 1. What a rule actually is

From `model/ContextRule` and `RuleDescriber`:

> a rule = a **name**, an on/off flag, a **when** condition tree, an optional
> **except-when** tree, and one or more **actions** (an effect + a target).

The condition tree can nest groups combined with *all / any / at least N*, with
per-row and per-group negation (`RuleScope` is unrelated; the tree lives in
`policy/ConditionDraft`). Effects are block / allow / always-block / safety-exempt
/ set-a-budget. Targets are everything / apps / categories / mixed /
everything-except.

This is a small, expressive logical language — closer to a query builder than to
a consumer feature. The rest of this document is about hiding that language
behind something a tired parent understands in three seconds.

---

## 2. The current journey, and why "Rules → Rules" happens

Child home (`HomeSections.kt` + `strings.xml`):

| Step | What the user sees | Code |
|---|---|---|
| 1 | Group header **"Rules"** | `home_group_rules` (L1191) |
| 2 | Row **"Change rules"** — *"Limits and settings, behind the parent PIN"* | `home_edit_rules` / `..._detail` (L67, 445) → `HomeDestination.RULES` |
| 3 | A screen titled **"Change rules"** | `rules_title` (L493) → `RulesActivity` |
| 4 | Section **"How the day works"**, row **"Rules"** — *"The daily limit, bedtime, schedules, and rules for where or when they apply"* | `rules_row_rules` / `..._detail` (L465-466) |
| 5 | A screen titled **"Rules"** | `context_title` (L101) → `ContextRulesActivity` |
| 6 | The actual list of rules | `ContextRulesActivity.render` |

So a parent taps a **"Rules"** group, then **"Change rules"**, then **"Rules"**,
and only now sees a rule. Four labels, three screens, two of them called some
variant of "Rules". The parent's home row is even worse — it is literally called
**"Rules"** (`parent_row_rules`, L615), so on a parent's phone the path reads
**Rules → Rules → Rules**.

The root cause: `RulesActivity` is not a rules screen at all. It is a **settings
hub** for everything the app enforces — three sections, about a dozen
destinations:

- **How much** (`rules_group_limits`): Categories, App limits, App control, Web
  filter
- **How the day works** (`rules_group_day`): Rules, Places/Place alerts,
  Nightscout, Goal, Bonus
- **Requests** (`rules_group_requests`): Requests, Auto-approve

The real rules list is just one row inside the middle section. The hub answers
"what can I configure?", not "what are my rules?" — and it is reached by tapping
something called "Rules".

---

## 3. The problems, grounded

**P1 — Misleading, redundant navigation.** As above. "Rules" is both the group
and the destination, and the destination is a hub. Violates Nielsen's *match
between the system and the real world* and plain *consistency and standards*.

**P2 — No overview.** `ContextRulesActivity` pins the daily limit under a
"Daily limit" header, then renders **"Your rules"** as one flat, ungrouped,
unsorted list of *every* rule regardless of kind (`context_rules_header`,
`context_no_rules`). Past six rules the rest hide behind "Show more"
(`COLLAPSED_ROW_LIMIT = 6`). There is no filter, no sort, no search, no count,
and no separation of on vs paused. A parent with eight rules cannot answer "what
have I set up?" without reading all eight sentences.

**P3 — Rules are sentences, and the sentences are long.** Every row is rendered
by `RuleDescriber.rule()` through `context_rule_summary` (`"%1$s → %2$s %3$s"`)
with `context_when_except` (`"%1$s, except when %2$s"`) and joined actions.
Nested groups add brackets (`context_when_group`). The result is scannable only
if you already know the grammar. Violates Krug's *don't make me think* and the
principle of **recognition over recall** — the eye cannot pick out "bedtime" or
"school" from a wall of clauses.

**P4 — Authoring exposes the engine.** The editor (`ContextRuleEditActivity`)
presents: a name field, an *enabled* checkbox, then the **when tree** — rows
with a condition chooser, a per-row **NOT** checkbox, a group mode chooser
(all/any/at-least-N), an "add group" that nests, and a root-level **"invert"**
checkbox that negates the entire tree — then a second **except-when tree**, then
a list of effect+target actions. This is a direct rendering of `ConditionDraft`.
It is the right power, offered at the wrong altitude. Violates **progressive
disclosure** and adds decision cost per Hick's Law.

**P5 — The concept is fragmented across the app.** The daily limit, bedtime,
schedules and contextual rules are *one engine* (the #106 unification) yet the
daily limit is pinned separately, bedtime only exists as a hidden starter, and
per-app limits (`AppLimitsActivity`), blocks (`AppControlActivity`), category
budgets (`CategoriesActivity`) and the web filter (`WebFilterActivity`) are
separate screens that each write `ContextRule`s via well-known ids
(`RuleStores.kt`). A parent has no single place that says "here is everything
that governs this phone."

**P6 — An explanation tax.** Nearly every heading carries an "ⓘ" whose dialog is
paragraph-length — `context_when_info_body` runs to ~450 characters, and
there are similar bodies for *except*, *do*, *to*, *limits*, *day*, *requests*.
There is a whole tutorial screen with eight worked examples
(`RulesTutorialActivity`), plus a banner warning that older family phones will
*delete* rules they can't read (`RuleVersionWarning`, the 1.2.0 notice). When a
feature needs a manual, a tutorial and a compatibility warning before first use,
the feature is carrying the design.

**P7 — No "is it working?" feedback in the list.** The child's home computes
which context rules are applying right now (`HomeActivity.renderActiveRules` →
`enforcement.activeContextRules`), but the parent's rules list shows no such
state — and no "last mattered at" either. Violates Nielsen's *visibility of
system status*.

---

## 4. Principles this design should apply

Drawn from widely used, credible sources — named so the reasoning is auditable:

- **Mental model over implementation model** (Norman; *gulf of execution and
  evaluation*). Parents think in jobs: "homework time", "no games at night",
  "the phone only calls and maps at school". Build the UI around those.
- **Recognition over recall** and **match to the real world** (Nielsen's
  heuristics). Prefer named templates and plain phrases over a builder for the
  common case.
- **Progressive disclosure** (Nielsen). Simple by default; the full condition
  tree behind an explicit "Advanced".
- **Chunking / cognitive-load reduction** (Sweller; Miller). One decision per
  step, not one scroll with ten.
- **Information scent** (Pirolli & Card). The label must smell like the thing
  behind it; "Rules" must lead to rules.
- **Hick's Law.** Fewer, clearer choices at each screen.
- **Gestalt proximity & similarity.** Group by meaning; make like things look
  alike (a rule card looks like a rule card everywhere).
- **Fitts's Law.** Primary actions big and near the thumb; destructive ones far
  from the flow.
- **Nielsen: consistency, error prevention, reversible actions, aesthetic and
  minimalist design, visibility of status.**
- **Jobs-to-be-done** (Christensen). Most parents' job is *set it once and
  forget it*, then *react to a specific problem* — the two entry modes the plan
  should optimise for.
- **Platform conventions** — Apple HIG's grouped settings lists and "clarity,
  deference, depth"; Material 3 lists, cards and settings guidance. Neither
  platform invents a two-level "Rules" maze; both would render this as a list
  of clearly named items leading straight to the thing.

---

## 5. Target design

### A. Make navigation honest (fixes P1)

The move: **"Rules" leads directly to the rules list.** The list is the thing
parents mean and the thing with a "your rules" mental model. The hub becomes its
*own*, accurately named screen — or is dissolved (see E).

- Home group **"Rules"** stays.
- The child's row **"Change rules"** and the parent's **"Rules"** both open the
  **rules list** directly (`ContextRulesActivity`), not the hub.
- The hub is renamed (e.g. **"Controls"** / **"What this phone enforces"**) and
  reached from the rules list via a **"Manage"** affordance, or folded into
  Settings. It should never be called "Rules".
- Delete the "Rules" row inside the hub. Net label count drops from four to one.

This is a small, high-confidence change: it is renaming and re-pointing
(`HomeSections.kt`, `RulesEntry`, `RulesActivity`), no model change.

### B. Turn the list into a dashboard (fixes P2, P5, P7)

A parent should see their whole setup and its state without scrolling sentences.

- **Header summary:** "4 rules on · 1 paused", plus the daily limit as the first,
  prominent card ("1 hour a day") and the device status
  (monitor-only vs enforced) promoted from `RulesActivity.statusCard` to the top.
- **Group, not dump.** Group by how a parent thinks, in this order:
  1. **Daily limit & bedtime** (the two everyone sets)
  2. **Time** — school, homework, dinner, weekend windows
  3. **Place & network** — school Wi-Fi, Grandma's, home
  4. **Health** — glucose
  5. **Other**
  Or offer a one-tap toggle between **"By time"**, **"By place"** and **"List"**.
- **Status on every card:** On / Paused, and **"Active now"** when the child
  dashboard already knows it is applying. Never rely on colour alone — pair the
  chip with a label.
- **Paused rules separated** under "Paused", Grey-ed, at the bottom — so they
  stop diluting the active setup.
- **Sort & search:** sort by name / last edited / kind; a search field (the app
  already has a debounced `searchField`).
- **Selection & bulk:** long-press to enable/disable several; "Pause all".

### C. A rule card that scans like a recipe (fixes P3)

Replace the one-line sentence with a **card** carrying a structural summary.
`RuleDescriber` already yields the pieces (`condition`, `effect`, `target`,
`action`) — render them as labelled, chip-like lines:

```
┌────────────────────────────────────────────┐
│  School hours                       [ On ]  │
│  WHEN   Mon–Fri · 8:30–15:00                │
│  THEN   Block  ·  Everything except         │
│         Phone, Maps, Messages (+3)          │
│  ⓘ Applies at school Wi-Fi                  │
└────────────────────────────────────────────┘
```

- A one-word **icon/badge per condition family** (clock, pin, wifi, bluetooth,
  battery, hourglass, droplet) so the eye sorts by kind before reading.
- A **human title** first; the sentence becomes the secondary line, truncated
  with "+2 more" for deep trees.
- Tapping opens the editor; a trailing **switch** toggles on/off without
  entering.

The important part: the same visual grammar everywhere a rule is shown — list,
child dashboard, review step of the editor. One component, one meaning
(Gestalt similarity).

### D. Authoring as a guided flow, not a form (fixes P4)

Two entry modes, matching the two jobs:

1. **Start from a template** (the common path). A gallery of cards — Bedtime,
   School hours, Homework, Dinner, No games after 9pm, Only essentials at
   school, Unblock a school app, Low battery = essentials. The `STARTERS` in
   `ContextRulesActivity` are the seed of this; promote them to a first-class
   gallery with icons and one-line descriptions, and pre-fill everything.
2. **Build your own**, as a short wizard:
   - **Step 1 — What should happen?** Block / Allow / Set a time limit. Big
     tappable cards with one-line meanings.
   - **Step 2 — When?** Always · A schedule · At a place · On a network · Near a
     device · Battery low · After X minutes · Glucose. Each is a single choice
     that opens a focused editor (the existing dialogs already do this well).
   - **Step 3 — To what?** Everything / Pick apps / Pick categories.
   - **Step 4 — Review.** Show the resulting **rule card** (§C) and a plain
     sentence — "Say it back" — with Save / Back.

   Steps are progress-chunked (Sweller), each screen has one primary action
   (Material/HIG), and the review closes the *gulf of evaluation*.

   The condition **tree**, **NOT**, **at least N** and multiple actions move
   behind an explicit **"Add another condition / Advanced"** affordance. That
   keeps every capability, discovered when needed (Nielsen: progressive
   disclosure), and stops the common case paying for the rare one.

### E. Unify the concept (fixes P5)

- Present the **daily limit and bedtime as rules** in the same list (they already
  are `ContextRule`s), rather than a pinned special header. If the daily limit
  must stay visually first, make it the first *card* in the same system, not a
  different pattern.
- Surface **app limits, blocks and category budgets** as rules where possible,
  or link them from the list ("Also enforced: app limits, categories, web
  filter") so the app can answer "what governs this phone?" in one place.
- The hub's remaining, genuinely different settings (Web filter, Nightscout,
  Goal, Bonus, Places, Auto-approve) become a clearly-labelled **"More controls"**
  list — reached intentionally, not en route to rules.

### F. Kill the tutorial by absorbing it

Templates + the review step + one-line inline hints replace most of
`RulesTutorialActivity`. Keep the tutorial as an optional **"How rules combine"**
link for the advanced tree only. Rewrite the ⓘ bodies as a short line plus a
"Learn more" if the full explanation is still wanted; a 450-character dialog is
where a feature goes to hide.

### G. States, feedback and safety

- **Empty state:** "No rules yet" becomes a proper empty state with the template
  gallery (already partly there) — an invitation, not a dead end.
- **Read-only (before PIN):** the dashboard should still be *legible* — show the
  cards, disable the switches — so a parent can see what is set without unlocking.
- **Mixed-version warning:** keep it, but attach it to the affected rules rather
  than a global banner where possible.
- **Destructive actions:** delete stays behind a confirm; add undo via a snackbar
  where feasible (reversible actions, Nielsen).

---

## 6. Concrete label changes (appendix)

| Now (string) | Text | Proposed |
|---|---|---|
| `home_group_rules` | "Rules" | keep, but it must lead to rules |
| `home_edit_rules` (child) | "Change rules" | "Rules" |
| `parent_row_rules` | "Rules" | keep, now correct |
| `rules_title` (hub) | "Change rules" | "More controls" (or fold into Settings) |
| `rules_row_rules` | "Rules" | remove — no longer needed |
| `context_title` (list) | "Rules" | "Rules" (correct) |
| `context_daily_limit_header` | "Daily limit" | a card, not a separate header |
| `context_rules_header` | "Your rules" | "Your rules" |

---

## 7. Accessibility & internationalisation

- Sentences built by concatenation (`context_rule_summary`, joins, brackets) are
  fragile in translation and word order. Structured, **separately labelled**
  rows ("When", "Then", "For") translate far more safely — a real win given the
  app ships English and Swedish and lint treats a missing translation as a
  build error.
- Rule cards should expose a **single, complete `contentDescription`** so TalkBack
  reads the rule once, not three fragments; headers as `isAccessibilityHeading`;
  status as text, not colour alone (WCAG 1.4.1 / 1.3.1).
- Tap targets ≥ 48dp (Fitts's Law); switches need accessible labels.

---

## 8. How we will know it worked

- **Behavioural:** taps-to-first-rule (target ≤ 2 from home), time-to-create a
  rule, share of rules created from templates vs builder, % of families with ≥1
  rule, rule-on rate.
- **Usability:** moderated 5–8 participant test with two tasks — "set bedtime for
  a school night" and "stop games at school" — measuring success, time, and
  errors; System Usability Scale before/after.
- **Comprehension:** show five rule cards; ask "what will this do?"; target high
  first-attempt accuracy (tests §C directly).

---

## 9. Phased delivery

Each phase is independently shippable; A and B alone remove the reported pain.

**Phase A — Navigation honesty (small, high impact). ✅ Done (1.4.1).**
Re-point the home Rules row to the list; rename the hub; delete the inner
"Rules" row. Files: `HomeSections.kt`, `RulesEntry.kt`, `RulesActivity.kt`,
`strings.xml` (+ `values-sv`). Tests: extend `HomeSectionsTest`; add a test that
the child Rules destination resolves to the rules list.

**Phase B — Rules dashboard. ✅ Done (1.5.0), except search/sort and "Active now".**
Header summary, grouping, status chips, paused section. Grouping is pure data
(`RuleSections.kt`, tested like `HomeSections`); the device-status card moved to
a shared helper and is now shown at the top of the list. `ContextRulesActivity`,
`RuleSections.kt` + tests, `DeviceStatusCard.kt`, `RulesActivity.kt`,
`strings.xml`, `res/drawable/chip.xml`.

Deferred within B: **search/sort** over the rules, and **"Active now"** — the
latter would need `EnforcementCoordinator.evaluate()` on every screen open,
which replays a full day of usage for one status word. Revisit when there is a
cheap cached snapshot to read instead.

**Phase C — Structured rule card & describer.**
Evolve `RuleDescriber` to emit structured parts (kind, when, then, for) and a
reusable card. Files: `RuleDescriber.kt`, new `RuleCard.kt`, tests pinning the
parts per rule shape.

**Phase D — Authoring wizard + template gallery.**
Extract the wizard state machine into a **pure, testable class** (mirroring
`ConditionDraft`), and render it step by step. Templates from the existing
`STARTERS`, expanded. Files: new `RuleWizard`/`RuleTemplates`, `ContextRuleEditActivity.kt`,
`strings.xml`; unit tests on the state machine; keep the advanced tree.

**Phase E — Unify daily limit/bedtime and the adjacent settings.**
Surface all `ContextRule`s in one list; relabel the hub's leftovers.

**Phase F — Advanced disclosure polish + states + i18n + a11y pass.**

**Phase G — Motion and finish** (card expand/collapse, transitions), only after
the structure is right.

---

## 10. Guardrails & risks

- **No model change.** This is presentation; `ContextRule`, the engine, sync and
  the well-known migrated ids must not move. `RulesInventoryTest` and
  `PolicyReplicationTest` must stay green.
- **Keep the local/remote split.** Every screen already branches on
  `RuleScope.isOwnDevice` (`RuleStores.kt`); the redesign must keep working when
  editing a remote child's rules, where icons and app labels are unavailable.
- **Keep the mixed-version warning** — older phones deleting V2 rules is a real
  data-loss path (README "Contextual rules").
- **Don't break the child's transparency promise** — the dashboard must keep
  showing the child the same rules the parent sees (`HomeActivity.renderRules`).
- **Avoid new dependencies** for the dashboard if the existing view helpers
  suffice; the app deliberately builds screens in code and is dependency-light.
- **Version bump** on every shipped PR touching `app-child` (AGENTS.md).

---

## 11. The one-paragraph version

Today a parent taps **Rules → Change rules → Rules** to reach a flat list of
long sentences, then authors logical expressions through a form with three
info-dialog essays and a tutorial. The fix is to make **"Rules" mean rules**, put
a **dashboard** in front of them (grouped, status-bearing, scannable cards), and
turn authoring into a **short, template-first wizard** whose review step shows
the rule back as the same card the list uses — keeping the full condition tree
behind an "Advanced" affordance and leaving the engine untouched.
