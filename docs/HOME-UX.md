# Home UX — the child dashboard's next four steps

A plan for the four *big* improvements identified in the 1.6.5 start-screen
review. The easy wins (loading placeholders, "resets at" clock time, the
"Active now" chip) shipped in 1.6.5; this document is what is left that needs
design rather than a line of code.

Governing rules (see `AGENTS.md`, and `RULES-UX.md` §10 where they overlap):

- **No model change.** Everything below reads what enforcement already
  computes (`Enforcement`, `EnforcementSnapshot`, `Reason`). If a phase seems
  to need a new persisted field, it is in the wrong module.
- **Dependency-light.** Screens are built in code; no UI kit, no icon library.
- **The transparency promise** (`RULES-UX.md` §10): the child sees exactly what
  the parent sees. Improving the *layout* is fine; hiding or softening is not.
- Every phase bumps `versionCode`/`versionName` and ships with tests, named
  after the behaviour they pin.

---

## The problem this plan closes

The dashboard answers "how much time is left" well, and answers everything
else badly. The three questions a child actually asks — *why is it locked*,
*when does it open again*, *what can I do about it* — are each answered
somewhere below the fold, in small text, or not at all until the child opens
the app that is blocked. A parent who has set the rules can read them; a child
who is *inside* them cannot find them when it matters.

---

## Phase 1 — A status card that answers "why is my phone locked"

The first thing on the screen, above the budget summary. One card that states,
in one line, the current headline restriction and **when it lifts**, with the
countdown to that moment:

```
┌──────────────────────────────────────────┐
│  ⏸  Time's up for today                  │
│     Opens again in 7h 40m — at 06:00     │
└──────────────────────────────────────────┘
```

Priority order of what it says (first match wins):

1. **Emergency active** — "Emergency is on", no countdown (it lifts by a
   parent, not a clock).
2. **Device budget exhausted** — "Time's up for today" + the day-boundary
   reset (`Enforcement.nextChangeAt`, which the engine already sets to `dayEnd`
   when the budget is gone).
3. **A contextual rule is applying** — the rule name + when it lifts
   (`Reason.ContextRuleActive.liftsAt`); several applying names the tightest.
4. **Bedtime window active** — same shape, from the bedtime rule's window.
5. **Nothing restricted** — the card does not render at all. A status card
   standing over "everything is fine" is noise; the budget summary already
   carries the good news.

The countdown stays honest by re-rendering on `onResume` only — a ticking
timer would need a loop the app's screens do not otherwise run, and a
stale-by-minutes countdown that corrects on the next open is better than a
foreground service for a label.

**Files:** new `LockStatusCard.kt` (shared shape with `DeviceStatusCard.kt`),
`HomeActivity.kt` (render slot above `summaryHolder`), `strings.xml` +
`values-sv`. **Tests:** `LockStatusCardTest` pinning the priority order and the
countdown wording; a `HomeActivityTest` case that the card is absent when
nothing is restricted.

## Phase 2 — The dashboard reads the same grammar as the rules list

The rules, blocked, and usage groups are flat bordered lists today, while the
parent's rule cards are labelled When/Then rows with chips and glyphs. The
product's stated promise is that parent and child screens read identically;
adopting the phase-C card grammar on the dashboard is what makes that true.

- **Rules group:** render each rule as a read-only card — glyph, name, chip —
  via `ruleCard(onOpen = null, onToggle = null)`. The child gets the same
  scannable shape without the parent's controls.
- **Blocked group:** each row already carries a reason; promote it from a
  trailing fragment to a labelled line (WHAT is blocked / WHY / UNTIL), so a
  row is an answer, not a label plus hint.
- **Usage group:** unchanged — a time-plus-icon list is already the right
  shape for it.

**Files:** `HomeActivity.kt`, `RuleCard.kt` (no change expected; verify the
preview shape renders without parent affordances), `strings.xml`. **Tests:**
extend the existing `HomeSectionsTest`-style coverage with a render test
pinning that a blocked row shows reason and lift-time, and that rule cards on
the dashboard carry no switch.

## Phase 3 — A tappable notification at the block moment

Android's device-owner "app paused" popup cannot be replaced. What *can*
carry the explanation is a companion notification posted when a restriction
starts: the same plain-words reason the dashboard's why-dialog shows, tappable
into that dialog (or the status card) for the full answer.

- Post on **transition into** a restricted state (budget exhausted, a context
  rule becoming active, bedtime starting) — never on every tick. `TickWork`
  already computes the snapshot each pass; the notifier compares against the
  last posted state, the same way `EnforcementStatus.dnsFailed` only writes on
  change.
- Reuse the **warning ladder's** channel and gating (`WarningNotifier`,
  `NotificationUx`, `NotificationPreferences`) — a new channel only if the
  child must be able to silence this one without silencing the ladder.
- Deep link: a `HomeActivity` intent extra naming the blocked app opens the
  why-dialog directly; a plain launch opens the status card. The child who was
  mid-app when the block hit lands on the answer, not on a generic screen.

**Files:** new `BlockNotifier.kt` beside `WarningNotifier`, `TickWork.kt`
(post-transition hook), `NotificationIds.kt`, `NotificationPreferences.kt`,
`HomeActivity.kt` (intent extra → dialog), `strings.xml`. **Tests:** a
notifier test pinning transition-only posting (no repeats on an unchanged
state), and a `HomeActivityTest` case that the intent extra opens the dialog
for the named app.

## Phase 4 — "Ask for more time" one tap from the blocked list

The request inbox is already the child's way to negotiate. When the device
budget is exhausted — the moment of maximum frustration — the ask should be
adjacent, not two screens away: a pinned row at the top of the blocked group,
**"Ask for more time" → `RequestsActivity`**, shown only while the budget is
exhausted and only for targets the request flow actually accepts.

Placed *inside* the blocked card, above the app rows, so it is found in the
same glance that discovers the block — not as a floating button competing with
SOS, which has its own job and its own gravity.

**Files:** `HomeActivity.kt` (`renderBlocked`), `strings.xml`. **Tests:**
a `HomeActivityTest` case pinning the row appears exactly while the budget is
exhausted, and launches the request inbox.

---

## Order and dependencies

Phases 1 and 4 are independent; ship either first. Phase 2 is the largest
change and stands alone. Phase 3 depends on Phase 1's dialog existing to be
worth its deep link (the notification should open something that answers), so
it goes last.

A phase that turns out to need data the engine does not already produce is a
signal to stop and re-read `Enforcement` — every phase above was checked
against it; none needs a new field.
