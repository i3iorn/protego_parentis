# Project: one ruleset per protected device, all of it reachable from a parent

Status: **proposed**. Nothing below is built.

Supersedes the approach in #12, and absorbs #13's follow-on. See "Why #12 cannot
be done first".

---

## The problem, in one sentence

A parent's phone can change **fourteen** of the rules that govern a managed
phone, the managed phone actually has **nine more**, the whole replicated policy
is addressed as if a family could only ever have one child, and the two kinds of
phone edit rules through two separate sets of screens that have already drifted.

### What a parent can set today

`PolicySnapshot` is the entire remote surface — 14 fields:

`dailyLimitMinutes`, `bedtime{Enabled,StartMinute,EndMinute,Days}`, `categories`,
`appLimits`, `exemptPackages`, `blockedPackages`, `hiddenPackages`,
`networkBlockedPackages`, `installPolicy`, `installWindowUntil`.

### What only somebody holding the child's phone can set

| Store | What it controls | Reachable from a parent? |
|---|---|---|
| `ScheduleStore` | Every schedule window except bedtime | **No** |
| `ContextRuleStore` (rules) | Contextual rules — "no games at school" | **No** |
| `ContextRuleStore` (places) | The places those rules refer to | **No** |
| `WebFilterStore` | DNS filtering host, visit counting | **No** |
| `FeatureFlags` | Which features exist on the phone at all | **No** |
| `AutoApprovalStore` | Which requests answer themselves | **No** |
| `GoalStore` | Goals and their rewards | **No** |
| `NotificationPreferences` | What the child is told, and when | **No** |
| `LocalPolicyStore.tamperProtection` | The protection everything else rests on | **No — deliberate** |

Only the last one is a decision. The other eight are an accident of the snapshot
having been written before those features existed, and each new feature has
widened the gap.

### The single-child assumption is structural, not cosmetic

- `RosterStore.child()` is `devices.firstOrNull { it.role == SyncRole.CHILD }` —
  a second managed phone is invisible by construction.
- `Enrolment.shouldAccept` refuses any vouch for a `CHILD`, so a second one
  cannot be added remotely even if the roster could hold it.
- The replicated policy lives at flat paths — `policy/dailyLimitMinutes`,
  `policy/bedtime/start` — with **no device in the path**. Two managed phones in
  one family would converge on one shared ruleset and enforce each other's rules.
- `ReplicationRules.mayWrite` says `POLICY -> role != null`: *any* enrolled
  device may write *any* policy path. With one child that is a considered
  trade-off (documented at length, and correct — the boundary is the PIN, not
  the device). With two children it becomes **child A may rewrite child B's
  limits**, which is not a trade-off anybody chose.

That last point is the reason this is a rewrite and not an extension.

### The same rules are edited by two different screens

`ParentRulesActivity` is 562 lines that reimplement bedtime, install policy,
categories, app limits, exempt apps and an app picker. The managed phone edits
the same things across `RulesActivity`, `SchedulesActivity`,
`CategoryEditActivity`, `AppLimitsActivity`, `AppControlActivity` and
`ContextRulesActivity` — about 1,570 lines.

They have already drifted, in ways that are small individually and add up:

- The parent has no schedules, no contextual rules and no places, because there
  is nothing there to edit — the state gap above, showing through as a UI gap.
- `AppLimitsActivity` floats already-limited apps to the top of the list. The
  parent's `appRow` does not.
- The child picks apps through `AppPickerActivity`, a full screen with a
  selection that survives being filtered out. The parent picks them through a
  checkbox dialog inside `ParentRulesActivity`.
- PR #28 added a search field to four managed-phone screens **and, separately,
  to the parent's app section** — the same feature implemented twice in one
  change because there was no shared screen to put it in.

Every rule added from here costs two implementations, and each pair is one more
place for them to disagree about what a rule means.

---

## What done looks like

1. Every rule a parent may change is changeable from a parent device. The list
   is closed and testable: a test enumerates the rule stores and fails when one
   is not represented.
2. Rules are addressed **per protected device**. A family with two children has
   two independent rulesets; neither phone can write the other's.
3. `tamperProtection` stays local-only, and that stays a deliberate, documented
   exception rather than an oversight.
4. A phone on an older build in a family that has upgraded keeps working, and a
   family that upgrades does not lose its rules.
5. The parent UI shows which child is being edited, always, and never silently
   applies an edit to the wrong one.
6. **A rule is edited by one screen, wherever you are standing.** The same
   screens run on a parent's phone and on the managed phone, differing in what
   they are allowed to write rather than in what they are.

---

## Constraints the design has to respect

These are load-bearing and already paid for. The rewrite reuses them; it does
not replace them.

- **The CRDT is fine.** `StateMerge` (Lamport clock + origin tiebreak, with
  append-only inversion for `audit`) is sound and tested. The problem is the
  *address space*, not the merge.
- **`ReplicationRules` is the security boundary** and is drawn per path. That is
  exactly the right shape for this change — per-device ownership is expressible
  as a path rule and nothing else needs to move.
- **The wire format is a compatibility contract.** `SyncCodec` and the path
  strings are what two phones on different builds agree on. Every path change
  needs a migration story, not a rename.
- **Offline is normal.** A managed phone may be unreachable for days. Nothing
  may require both phones present to converge.
- **The managed phone may edit its own rules behind the PIN.** This is
  deliberate (see the paragraph in `ReplicationRules`) and does not change — but
  it must narrow to *its own* subtree.

---

## Target design

### Addressing

```
policy/<childDeviceId>/dailyLimitMinutes
policy/<childDeviceId>/schedules/<scheduleId>/...
policy/<childDeviceId>/places/<placeId>/...
```

The device id is the roster id already used everywhere else. No new identifier,
and the roster is already replicated, so both sides can resolve the name.

### Ownership

`ReplicationRules.mayWrite` gains the device in the path:

- a **parent** may write `policy/<any child>/…`
- a **child** may write `policy/<its own id>/…` and nothing else
- everything outside `policy/` is unchanged

This is a tightening. It closes the child-A-writes-child-B hole before it can
exist, and it makes the existing single-child behaviour a special case of the
general rule rather than an exception to it.

### Composite records

Schedules, context rules, places, categories and goals are **lists of records**,
where every replicated policy field today is a scalar or a string set. This is
the one genuinely new modelling problem. Proposed shape: one path per field per
record, with the record id in the path, plus a tombstone path for deletion —

```
policy/<child>/schedules/<id>/name
policy/<child>/schedules/<id>/start
policy/<child>/schedules/<id>/deleted
```

so that two parents editing different fields of the same schedule both win, and
a delete races a rename deterministically rather than by luck. Alternatives
(whole-record last-writer-wins; a JSON blob per record) are cheaper and lose
concurrent edits; M2 is where that choice gets made and written down.

---

## Milestones

Each is independently shippable and leaves the app working. **M1 is the one that
must not be got wrong** — everything after it assumes the address space.

### M0 — Close the gap on paper

Enumerate every rule store, its fields, and whether each is parent-settable,
child-settable, or deliberately local. Land it as a table in
`docs/RULES-INVENTORY.md`, with a test that fails when a store gains a field the
inventory does not mention.

*Done when:* the inventory exists and the test fails on a deliberately added
field.
*Risk:* none. This is the cheapest way to stop the gap widening while the rest
is built.

### M1 — Per-device address space

Move the replicated policy from `policy/<field>` to `policy/<childId>/<field>`.
No new fields. Behaviour identical for a one-child family.

*Done when:* a two-child fixture converges on two independent rulesets;
`mayWrite` refuses a child writing another child's path; a test proves a family
upgrading from flat paths keeps its rules.
*Risk:* **highest in the project.** Every phone in a family must agree on the
address space. Needs the dual-read migration in M6 designed before this lands,
not after.

### M2 — Composite records over the CRDT

Choose and implement the record representation. Schedules first, since #13 just
gave them a mode field and they are the most-wanted missing rule.

*Done when:* two parents editing different fields of one schedule both win; a
delete and a concurrent rename resolve the same way on every replica; a
malformed record from a newer build is ignored rather than crashing the merge.
*Risk:* getting last-writer-wins semantics subtly wrong. Mitigated by property
tests over op orderings, in the style of `ConvergenceTest`.

### M3 — Schedules, contextual rules and places replicate

The three biggest missing surfaces, using M2's machinery. Closes the genuine
half of #12 — bedtime and schedules become the same kind of thing, both
parent-settable — without removing anything.

*Done when:* a parent can create, edit and delete a schedule, a contextual rule
and a place from their own phone, and the managed phone enforces the result.

### M4 — The remaining stores

Web filter, feature flags, auto-approval, goals, notification preferences.
Mechanical once M2 and M3 are in.

*Done when:* the M0 inventory shows no unreachable parent-settable field, and
the test enforcing that passes.

### M5 — One set of rule screens, on both kinds of phone

Delete `ParentRulesActivity`'s parallel implementations and give the existing
rules screens a ruleset to edit rather than an implicit "this phone's rules".
Includes the child picker, because "which ruleset am I editing" is the same
question whether a family has one managed phone or three.

Possible only after M1–M4: a shared screen needs a shared model, and until the
ruleset is one addressable thing there is nothing for both phones to point at.

What differs between the two kinds of phone afterwards is **capability, not
code**: `ReplicationRules` already says who may write what, so a screen asks it
and disables what this device may not change. `tamperProtection` is the visible
case — present on both, editable only where somebody is standing.

*Done when:* `ParentRulesActivity` no longer contains a rule editor of its own;
one screen edits app limits for both kinds of phone; a parent with two managed
phones can tell at a glance which one they are editing; a test covers the
selected child being removed from the roster mid-edit; and a test asserts a
screen opened against a ruleset this device may not write offers no way to
write it.
*Risk:* the largest diff in the project, and the most visible if wrong. It is
also the milestone that stops the drift, so it should not be deferred into a
"later, when there is time" that never comes.

### M6 — Migration and mixed-version families

Dual-read (accept both flat and scoped paths), single-write (emit only scoped),
for one release. Then drop the flat reader.

*Done when:* an upgraded parent and a not-yet-upgraded child still agree on the
rules; the reverse also holds; a test pins both directions.
*Risk:* the one users actually feel. Deserves its own release cycle.

### M7 — Enrolling a second managed phone

`Enrolment.shouldAccept` currently refuses vouches for `CHILD`, for a stated
reason: one compromised parent phone should not be able to introduce a device
that receives every rule and every position. Adding a second child must not
weaken that.

*Done when:* a second managed phone can be added by somebody physically holding
it, the existing refusal of remote child-vouches still stands, and the reasoning
is written down.
*Risk:* security. Do not fold into M5 for convenience.

---

## Why #12 cannot be done first

#12 asks to delete bedtime and tell people to use schedules. Bedtime is
replicated; schedules are not. Doing it before M3 converts the single most-used
remote control in the product into one that needs physical access to the child's
phone.

After M3 the two are genuinely the same kind of object and #12 becomes what it
was meant to be: deleting a duplicate. It should be closed as blocked-by, or
retitled, and picked up at M3.

---

## Explicitly out of scope

- Changing the merge algorithm. `StateMerge` stays.
- Changing the transport or the crypto.
- Making `tamperProtection` remotely settable.
- Any new third-party dependency.

---

## Open decisions

1. **Record representation** (M2): field-per-path with tombstones, or
   whole-record LWW? The first preserves concurrent edits; the second is
   substantially less code. Two parents editing one schedule at once is rare —
   is it rare enough?
2. **Migration window** (M6): one release of dual-read, or two? Longer is safer
   for families who update rarely; it is also a second release carrying dead
   code.
3. **Second child, or many?** "Per protected device" is the same work for two as
   for five, but the parent UI is not. Design M5 for two, or for a list?
5. **How far does the shared view go?** A single screen that renders read-only
   where it may not write is the cleanest end state, and it means the managed
   phone gets a screen showing rules it cannot change — arguably good, since
   this product's stated position is that the child can see every rule that
   applies to them. Or the screens stay editable-only and simply are not reached
   from a device that may not write. The first is more honest; the second is
   less work and less surface to get the permission check wrong on.
4. **Does `policy/` stay one section?** Per-child sections would let a digest
   exchange skip children that have not changed, at the cost of a more
   complicated `ReplicationRules`. Worth it only if digest size becomes a real
   problem.
