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
disclosure** and adds decision cost per Hick's Law. (The small fixes that need
no wizard are gathered in §5.H.)

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

### H. The editor itself: small fixes that need no wizard

`ContextRuleEditActivity` is where §3 P4 actually lives, and a few of its costs
are self-contained — fixable without the Phase D wizard and without touching the
model or the engine. All citations are to that file unless said otherwise.

- **The "not connected" toast fires behind the input dialog.** `askForGlucose`
  (L625–641) shows the toast at L640 on the line *after* `promptForText(...)`,
  but `promptForText` only shows an `AlertDialog` and returns (`ViewDialogs.kt`
  L105–114). The toast therefore overlaps the dialog it is meant to accompany,
  and is shown even when the user cancels. Move it into the dialog's `message`
  parameter (which already exists), or raise it only once a value is accepted.
- **Save and Delete don't use the app's button vocabulary.** L250–274 build raw
  `Button`s, so the screen's primary action doesn't read as primary and its
  destructive one isn't marked dangerous. `primaryActionButton()` and
  `destructiveActionButton()` (`ViewButtons.kt` L37–51) already exist and are
  used everywhere else; swap them in, overriding `layoutParams` to keep the full
  width.
- **Removing a group is one tap, unconfirmed and irreversible.**
  `removeButton(R.string.context_remove_group)` (L325) deletes an entire nested
  subtree. A group is expensive to rebuild; route it through `confirmDialog`
  (or give it the undo §G asks for). A leaf row can take the same confirm far
  more cheaply.
- **Repeated row controls carry no accessible context.** Every leaf's remove
  button is the bare string `context_remove_condition` = "Remove" (L344–345,
  `strings.xml` L162) and every negation toggle is `context_row_not` = "Not"
  (L397–402, `strings.xml` L205). TalkBack reads the same two labels for every
  row. Give each a `contentDescription` naming its own row — the condition
  phrase is already available from `describer.condition(...)`.
- **The rule is never shown back while it's being built.** The list renders
  rules through `RuleDescriber.rule()` (`RuleDescriber.kt` L32), but the editor
  never displays that sentence. A read-only preview near Save closes the gulf of
  evaluation for a few lines and is a down-payment on §C/§D's review step.
- **The condition chooser is a long flat menu.** `chooseCondition` (L453–467)
  lists every condition — always, Wi-Fi, each place, device, battery, window,
  allowance, three glucose — in one menu. Sub-headings (Time / Place / Device /
  Health) lower the cost of each choice now, ahead of the template gallery.
- **Every "choice" is a full-width `Button`.** `choice()` (L970–975) renders
  conditions, effects and targets as raised buttons, and each row trails a
  `Not` checkbox and a `Remove` button (`horizontalRow`, L411–416). At depth —
  every nest adds `space_lg` of left indent — this reads as a wall of buttons
  and crowds the row. A lighter tappable row (`withRipple()`, as the rest of the
  app uses) would declutter the tree. Ties into §C.
- **A blank-name save only toasts.** `save()` (L828–832) doesn't focus or flag
  the name field, so the error points nowhere.

**Leave as-is.** The root group's "Apply when the above is NOT true" checkbox
(L198–208) sits *below* its tree rather than in a header row the way nested
groups' `Not` does. The label — "the above" — is exactly what makes the
placement read correctly, so the apparent inconsistency is deliberate and worth
keeping.

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

All of these shipped across rules-UX 1.4.1–1.6.2 (see §9): the hub is "More
controls", its inner "Rules" row is gone, both home rows lead to the rules
list, and the daily limit leads the list as the first card *inside* the
grouping rather than a pinned header above it — the last row's proposal, in the
grouped-list shape §9 decided on.

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
- The editor's **repeated row controls** need the same discipline: every leaf's
  `Remove` and every row's `Not` are currently identical unlabelled strings
  (see §5.H), so TalkBack cannot tell one row's control from the next.

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

**Phase C — Structured rule card & describer. ✅ Done (1.6.3).**
`RuleDescriber` grew `parts(rule): RuleParts` — the rule as labelled pieces
(`kind`, `whenLines`, `thenLines`, `except`) plus the first-leaf `RuleKind`
glyph — while `rule(rule)` kept returning the same one sentence for wherever a
whole rule is spoken. New `RuleCard.kt` renders it everywhere a rule appears:
a title row (glyph, name, on/off chip), the phrases as labelled When / Then /
Except rows, a trailing switch that pauses or resumes straight from the list
without entering the editor, and the per-rule version risk line naming the
phones that cannot read the rule (the per-rule half of §G's warning, which F
deferred to this phase). One `contentDescription` on the card carries name,
status and the full sentence for a screen reader, with the eye's pieces kept
out of the tree. The rules list and the editor's live preview both show the
card, so a rule cannot be described two different ways. Strings in en/sv
(`context_card_when/then/turn_on/turn_off/version_risk`, `rules_card_more`);
`RuleDescriberTest` pins the parts for every rule shape (window, wifi, place,
battery, glucose with the family's unit, multi-action, except, deep-tree
truncation), `ContextRulesActivityTest` pins the card render, the toggle
without the editor, and the affected-only risk line; `ContextRuleEditActivityTest`
pins the review step showing the card. Deferred within C, and why:
- **Child dashboard summary lines** — `HomeActivity.renderRules` keeps its
  one-line summaries; the card's labelled rows are for the screens a parent
  authors in, and the dashboard's transparency promise is that it shows the
  same *rules*, not the same layout (§10).
- **Search, sort and "Active now"** — still the Phase B leftovers (§9).

Next up: only the Phase B leftovers (search/sort/Active now) and Phase G
(motion) remain.

**Phase D0 — Rule-editor polish (independent of the wizard). ✅ Done (1.6.0).**
The §5.H fixes shipped all together, with the wizard: Save and Delete use
`primaryActionButton()`/`destructiveActionButton()`, delete still behind a
confirm; removing a nested group goes through `confirmDialog`; the glucose
"not connected" warning rides in the dialog's own message instead of the toast
that used to open behind it; every row's Not and Remove carries a
`contentDescription` naming the row it belongs to; the condition chooser is
sectioned (Time / Place / Device / Health, with "Always" above it unlabelled);
choices render as flat ripple rows rather than raised buttons; the rule is
shown back live under the form (`previewBox`), shared by the wizard's review
step; and a refused blank-name save puts an inline error on the name field
rather than only a toast. Files as planned: `ContextRuleEditActivity.kt`,
`ViewDialogs.kt`, `strings.xml` (+ `values-sv`);
`ContextRuleEditActivityTest` pins the blank-name refusal and the round-trip
shapes.

**Phase D — Authoring wizard + template gallery. ✅ Done (1.6.0), except the
expanded gallery.**
The wizard state machine is the planned pure, testable class
(`core-policy/RuleWizard`, `RuleWizardTest`), rendered step by step in
`ContextRuleEditActivity`: template gallery → what should happen → when → to
what → review, with back-navigation, and the full condition tree kept behind
"Edit details" (which seeds the editor with whatever the wizard already
answered). The gallery is the rules list's own starters, extracted into
`core-policy/RuleTemplates` (`RuleTemplatesTest`) so both screens offer the
same four shapes. Deferred within D: the further templates §D named
(no-games-after-nine, essentials-at-school, and the rest), plus the icons and
one-line descriptions — the four shipped shapes are the ones the list's
starters already carried, and §C's card work will change how a gallery row
looks anyway.

**Phase E — Unify daily limit/bedtime and the adjacent settings. ✅ Done (1.6.1).**
The daily limit is a rule in the same grouped list (`RuleSections`): its own
group, first, and still first when paused, so it leads without being a
separate pattern above the grouping — with a create-row when it does not
exist. The linking alternative was taken over surfacing per-app rules inline:
app limits, blocks and category budgets remain ordinary rules reached through
the list's single "More controls" row (which answers "what governs this
phone?" in one place), and the hub's leftovers keep that name.

**Phase F — Advanced disclosure polish + states + i18n + a11y pass. ✅ Done (1.6.2).**
The ⓘ essays are rewritten as a line or two (the ~450-character "when" body is
~180 now), each ending in the story rather than the mechanics, and the ones in the
advanced editor carry a "Learn more" button that opens the tutorial. The
tutorial itself now lives only beside the condition tree it describes — the
rules list, app limits, app control and category screens dropped their
standalone explainer buttons; the advanced editor's ⓘ dialogs are where
"How rules combine" is reachable. A11y: every rule row reads to a screen
reader as one item — a single `contentDescription` carrying name, status and
sentence, with its three fragments kept out of the tree — the screen title and
section headings are accessibility headings, and the "ⓘ" button carries a
48dp `touch_target` floor instead of its text-only glyph bounds (all pinned in
`ContextRulesActivityTest` and the `dimens.xml` note). i18n: the grouped-chooser
titles and everything else the pass touched is translated in `values-sv` (lint
treats a missing translation as an error). Deferred within F, and why:
- **Undo from a snackbar (§G)** — a snackbar needs the Material library, and
  the app deliberately carries no UI-kit dependencies (§10). Delete stays
  behind its confirm; §C's card made each rule its own row, so the undo
  affordance is now cheap — it remains the only reason to add the dependency.
- **Per-rule version pinning (§G)** — resolved by Phase C: the affected card
  carries the risk line naming the phones that cannot read the rule; the
  global banner and the editor's save-time confirmation stay as they were.

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
