# UI improvements — implementation plan

This turns the UI review into an ordered, testable set of changes. It covers
all sixteen findings, including the optional maintainability work.

Governing rules (see `AGENTS.md`):

- Every PR that changes `app-child`-reachable code bumps `versionCode` **and**
  `versionName` in `app-child/build.gradle.kts`. PRs that only touch `src/test/`
  or docs do not.
- Warnings are errors in every module.
- Add tests as part of the change; name safety-relevant tests after the
  behaviour.
- No bare dimens, text sizes, colours or user-visible strings in Kotlin or XML.
- Run `./gradlew test lint` before calling a change done.

## Implementation status

Implemented in `1.4.0` (`versionCode` 53):

| Phase                                             | State                                                                                               |
|---------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| 1 — accessibility (chevron, slider, static title) | Done; the static title was fixed by 4b building it with `screenTitle`                               |
| 2 — action rows without a chevron                 | Done                                                                                                |
| 3 — SOS arming                                    | **Not changed** — see the correction below                                                          |
| 4a — shared budget summary card                   | Done                                                                                                |
| 4b — unify the dashboard into code                | Done (`activity_home.xml` deleted)                                                                  |
| 5 — button hierarchy                              | Done (programmatic primary/destructive styles)                                                      |
| 6a — empty state + collapse tuning (`6`/`8`)      | Done                                                                                                |
| 6b — pull-to-refresh on the parent dashboard      | Done (new `swiperefreshlayout` dependency)                                                          |
| 6c — uninstall cue                                | Done (decorative trash icon)                                                                        |
| 7a — split `Views.kt`                             | Done — into `ViewText`, `ViewButtons`, `ViewNavigation`, `ViewDialogs`, `ViewInputs`, `ViewBanners` |
| 7b — extract a home renderer                      | **Deferred** — see the correction below                                                             |
| 8 — light/dark contrast guard                     | Done (`ColorContrastTest`)                                                                          |

Robolectric tests could not run on this machine's JDK until `app-child` was given
the `--add-opens`/`--add-exports` the library documents; that is included. A clean
`HEAD` worktree fails the same 44 pre-existing tests (a `javax.crypto.JarVerifier`
environment error, cross-run audit-state leakage, and a stale `PackageGraphTest`
allow-list), so the bar for "no regression" was: the same 44 failures, none new.

## Corrections found while implementing

### Phase 3 was based on a mis-read

The review said the dashboard SOS button was a single accidental tap. It is not:
`SosActivity` already puts three deliberate steps between the button and raising
an emergency (open the explanatory screen → tap "Send it" → confirm the dialog),
and its own doc comment records the deliberate trade-off — "a button that is hard
to press by accident must still be easy to press on purpose." Adding a
press-and-hold would have made a safety feature harder to use for no real risk
reduction, so the behaviour is unchanged. The button is now styled destructive,
which is the useful part of the finding.

### Phase 7b was deferred

`HomeActivity` already delegates its row structure to `HomeSections` and every
rendering primitive to the `View*` helpers; what is left is the wiring between
them. Extracting a presenter that receives the view containers would introduce an
architectural pattern the project does not otherwise use, which `.claude/CLAUDE.md`
explicitly warns against — and the plan itself authorises stopping here. The
summary decision logic is the one part genuinely worth testing in isolation, and
that remains follow-up work.

## Correction to the review

Finding **#6 (uninstall affordance)** was overstated. `HomeActivity.renderBlocked`
already appends `· tap to uninstall` to the row's trailing text
(`home_tap_to_uninstall`). The affordance exists, but it is text-only and easy to
miss. Treat it as a scannability polish, not a missing feature.

## Findings → phases

| #  | Finding                                        | Phase      |
|----|------------------------------------------------|------------|
| 1  | Chevron on non-navigating action rows          | 2          |
| 2  | Chevron glyph announced by screen readers      | 1          |
| 3  | Static home title not an accessibility heading | 1          |
| 4  | SeekBars have no spoken value                  | 1          |
| 5  | SOS accidental-tap risk                        | 3          |
| 6  | Uninstall affordance is text-only              | 6 (polish) |
| 7  | Two dashboard construction patterns            | 4b         |
| 8  | Duplicated "today summary" card                | 4a         |
| 9  | No primary/secondary button hierarchy          | 5          |
| 10 | Inconsistent empty states                      | 6          |
| 11 | Collapse limit too high                        | 6          |
| 12 | `dashboard_max_rows` too high                  | 6          |
| 13 | No pull-to-refresh on parent dashboard         | 6          |
| 14 | `Views.kt` is 844 lines                        | 7a         |
| 15 | `HomeActivity` mixes render + event logic      | 7b         |
| 16 | No automated dark-mode contrast guard          | 8          |

## Ordering and dependencies

1. **Phase 1 — accessibility** (independent, lowest risk)
2. **Phase 2 — row semantics** (independent)
3. **Phase 3 — SOS safety** (independent; easier after 4b if the button moves
   into code)
4. **Phase 4a — shared budget summary card** → **4b — unify the dashboard**
5. **Phase 5 — button hierarchy** (after 4b, to avoid call-site conflicts)
6. **Phase 6 — empty states, collapse tuning, refresh** (after 4b)
7. **Phase 7 — maintainability refactors** (last, behaviour-preserving)
8. **Phase 8 — contrast guard** (test-only, can land anytime)

---

## Phase 1 — Accessibility correctness

Files: `ui/Views.kt`, `res/layout/activity_home.xml`, tests.

### 1.1 Mark the navigation chevron decorative (#2)

- In `navigationRow` (`Views.kt` ~569-579), set
  `importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO` and
  `contentDescription = null` on the `"›"` `TextView`.
- Test: new `NavigationRowAccessibilityTest` (Robolectric) builds
  `activity.row(...)`, finds the trailing `TextView`, asserts
  `importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_NO`.

### 1.2 Make the static home title a heading (#3)

- Add `android:accessibilityHeading="true"` to the title `TextView` in
  `activity_home.xml` (lines ~24-29).
- Test: a Robolectric layout-inflation test asserting the title view's
  `isAccessibilityHeading`. If inflating the full layout is awkward, move this
  assertion into the Phase 4b dashboard test instead; do not leave it untested.

### 1.3 Give sliders a spoken value (#4)

- Add a helper next to `LimitSlider` in `ViewUtils.kt`, e.g.
  `SeekBar.bindAccessibleValue(format: (Int) -> String)`.
- Use `ViewCompat.setAccessibilityDelegate` and override
  `onInitializeAccessibilityNodeInfo` / `onPopulateAccessibilityEvent` to set
  the node's text/value to the formatted minutes. Update whenever progress
  changes.
- Wire it into `sliderDialog` and `limitSliderBlock` in `Views.kt`.
- Extract the value text into a pure function and test that; then assert the
  delegate is installed. Add a manual TalkBack check to the PR notes.

Commit: one PR; bump `versionCode`/`versionName`.

---

## Phase 2 — Row semantics (#1)

Files: `ui/HomeSections.kt`, `ui/Views.kt`, both home activities, tests.

- Add a pure property to `HomeDestination`, e.g.
  `val isAction: Boolean get() = this == LOCK_NOW || this == LOCATE`.
  Keep it in `HomeSections.kt` so it is testable without Android.
- Add `showChevron: Boolean = true` to `navigationRow` / `row` (or a separate
  `actionRow`). In `HomeActivity.buildNavigation` and
  `ParentHomeActivity.render`, pass `showChevron = !destination.isAction`.
- Tests:
    - `HomeSectionsTest`: assert `LOCK_NOW` and `LOCATE` are actions and that no
      other destination is.
    - `Views` test: an action row has no `"›"` child; a navigation row does.
- Small copy check: the two action rows still use the navigation row's
  title/detail, which is fine; only the chevron changes.

Commit: one PR; bump.

---

## Phase 3 — SOS safety (#5) — not changed

This finding was based on a mis-read of `SosActivity`; see "Corrections found
while implementing" above. The existing three-step confirmation is kept, and the
dashboard button is now styled destructive. No behaviour change.

---

## Phase 4a — Shared budget summary card (#8)

Files: new `ui/BudgetSummary.kt`, `HomeActivity.kt`, `ParentHomeActivity.kt`,
tests.

- Extract a builder, e.g.
  `Activity.budgetSummaryCard(headline, subline, progress: Int?, level, note: String?)`,
  rendering the Display headline, the optional `ProgressBar`, the subline and
  the note, using the existing `colourFor`/`percentUsed`.
- Use it from `HomeActivity.renderSummary` and `ParentHomeActivity.todayCard`.
- Keep the two screens' *content* different; only the surface and colour logic
  are shared.
- `BudgetSummaryTest` (Robolectric): unlimited hides the bar; normal sets the
  expected progress and `state_ok`; warning/exhausted use the right colour;
  the note appears only when supplied.

Commit: one PR; bump. Land before 4b.

---

## Phase 4b — Unify dashboard construction (#7)

Files: `HomeActivity.kt`, `activity_home.xml` (delete), tests.

Grep confirmed the layout's IDs are referenced only in `HomeActivity.kt`, so
this is contained.

- Rebuild `HomeActivity` with `contentColumn()` / `showColumn()`, the same
  pattern `ParentHomeActivity` and `SettingsActivity` already use.
- Build the permission card, family/activity banner holders, summary (via 4a),
  SOS, navigation and the rules/blocked/apps sections in code.
- Delete `activity_home.xml` once nothing references it.
- Preserve behaviour exactly: same visibility rules, same order, same empty
  cases, same `dashboard_max_rows` cap.
- Verification: `./gradlew test lint`; manual pass on a child phone and a
  parent phone in both themes. No new Robolectric test is required if the
  existing coverage plus manual smoke is stronger than a heavy Activity test;
  say so in the PR.

Risk: this is the largest structural change. Keep it behaviour-preserving and
separate from the Phase 4a card extraction.

Commit: one PR; bump.

---

## Phase 5 — Button hierarchy (#9)

Files: `res/values/styles.xml`, `ui/Views.kt`, call sites.

- Add explicit styles rather than changing `colorButtonNormal` globally:
    - `Widget.Dementor.Button.Primary` — filled `brand`, `on_brand` text.
    - `Widget.Dementor.Button.Secondary` — current look.
    - `Widget.Dementor.Button.Destructive` — `state_critical` (SOS, release,
      leave).
- Update `actionButton()` to take a style parameter (default Secondary), or add
  `primaryActionButton()` / `destructiveActionButton()`.
- Inventory call sites (`familyBanner`, `activityBanner`, `readOnlyBanner`,
  `twoButtonRow`, and the screens that call `actionButton()`). Pick:
    - primary: the one main CTA on each screen;
    - secondary: alternatives and secondary navigation;
    - destructive: release/leave/SOS.
- `twoButtonRow` (approve/deny): approve primary, deny secondary (or
  destructive where the domain says so).
- Test: a small Robolectric/style-resolution assertion that the three styles
  differ; manual visual check in both themes.

Commit: one PR; bump.

---

## Phase 6 — Empty states, collapse tuning, refresh, uninstall cue (#10-#13, #6)

Split into two PRs to keep review sizes sane.

### 6a — Empty state + tuning (#10, #11, #12)

- Add `Activity.emptyState(title, hint = null, icon = null)` in `Views.kt` and
  test it.
- Replace `noSearchResults()` and the parent "no summary yet" block first;
  migrate other empty lines incrementally rather than in one sweep.
- Change `COLLAPSED_ROW_LIMIT` from 10 to 6 and update `ShowMoreRowsTest`
  (which hardcodes ten).
- Change `config.xml` `dashboard_max_rows` from 12 to 8; confirm no test
  depends on 12.
- Optional, only if cheap: add "Show less" to `addCollapsibleRows`. The
  current one-way expansion is defensible; do not let it block the PR.

### 6b — Pull-to-refresh (#13)

- Add `androidx.swiperefreshlayout:swiperefreshlayout` to
  `libs.versions.toml` + `app-child/build.gradle.kts`.
- Add a `showRefreshableColumn(column, onRefresh)` variant in `Views.kt`, or
  wrap `ParentHomeActivity`'s column in a `SwipeRefreshLayout`. Insets are
  already handled by `DementorActivity.setContentView(View)`.
- `ParentHomeActivity`: on refresh, call `sync.pollNow` and clear the spinner in
  the callback (including the failure path).
- Test: Robolectric asserts the `SwipeRefreshLayout` is present and the listener
  is wired; manual gesture check on device.
- Dependency decision: this is a standard androidx view, but if the project
  prefers no new dependency, the fallback is a visible "Refresh" row/button on
  the parent dashboard. Note the choice in the PR.

### 6c — Uninstall cue (#6)

- The row already says "tap to uninstall". Add a small trailing `ImageView`
  (trash) or make the trailing line read as an action, so the affordance is
  scannable at a glance. No behaviour change.

Commit: 6a and 6b/6c as two PRs; bump each.

---

## Phase 7 — Maintainability (optional, behaviour-preserving)

Do these only after the functional phases, and only if the team accepts the
refactor cost (see `.claude/CLAUDE.md`'s "match effort to change").

### 7a — Split `Views.kt` (#14)

- Move functions into cohesive files: `TextViews.kt`, `Dialogs.kt`, `Lists.kt`,
  `Inputs.kt`, `Cards.kt`, `Navigation.kt`, keeping `internal` visibility and
  the existing KDoc.
- Pure move, no behaviour change. Tests should not need edits. Do it as its own
  PR so the diff is reviewable as a move.

### 7b — Extract a home renderer (#15)

**Deferred** — see "Corrections found while implementing" above.

- After 4b, move `renderSummary`, `renderRules`, `renderBlocked`, `renderUsage`
  and the `describe` helper out of `HomeActivity` into a renderer/presenter that
  receives the view containers.
- Keep `HomeActivity` for lifecycle, role routing, permissions and intents.
- Follow the existing `HomeSections` / `SettingsRows` "logic as data" precedent;
  do not introduce a new architectural pattern beyond that.
- If this starts to fight the project's YAGNI rule, stop and leave
  `HomeActivity` as-is — this item is explicitly optional.

Commit: one PR per sub-item; bump.

---

## Phase 8 — Dark-mode contrast guard (#16)

Files: new test only, e.g. `ui/ColorContrastTest.kt`.

- Using Robolectric, compute WCAG 2.1 contrast for light and night qualifiers:
    - `text_primary`, `text_secondary` on `window_background` and `card`
    - `on_brand` on `brand`
    - `state_ok` / `state_warning` / `state_critical` on `card`
- Assert ≥ 4.5:1 for normal text (≥ 3:1 for large text). The night palette
  currently looks compliant; this is a guard against future edits, not a fix.
- Test-only, so no version bump (the CI check ignores `src/test/`).

---

## Optional copy review (not one of the sixteen)

- "Openness" is an abstract group label. "Transparency" or "Data & privacy"
  may read more plainly; the group contains Audit + Privacy. This is a
  two-language string change (`values/strings.xml`, `values-sv/strings.xml`) and
  `HomeSectionsTest` references the resource, not the literal, so it is safe.
  Treat as a product/copy decision, not part of the technical plan.

---

## Verification checklist (every phase)

- `./gradlew test lint` green.
- `./gradlew assembleDebug` produces an installable debug APK.
- Manual, on a device:
    - light and dark themes;
    - TalkBack: headings, navigation rows, sliders, SOS fallback;
    - both roles: child dashboard and parent dashboard;
    - long lists collapse and expand;
    - pull-to-refresh on the parent dashboard;
    - SOS cancel and confirm;
    - button hierarchy readable at a glance.
- For phases that change shipped code, `versionCode`/`versionName` are bumped in
  the same PR.

## Suggested first slice

If you want to start small and low-risk, take **Phase 1 + Phase 2** together:
they are contained to `Views.kt`, `HomeSections.kt`, `activity_home.xml` and
tests, need no product decision, and remove the misleading chevron and the
biggest screen-reader noise. Phase 4a is the next-best small win.
