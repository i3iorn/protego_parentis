# Rules inventory

Every setting the app persists that governs the managed phone, and how far it
reaches.

`RulesInventoryTest` is the source of truth. This page is its readable
rendering; if the two disagree, the test is right and this is stale. The test
reads the stores by reflection, so a new persisted key fails the build until
somebody classifies it — which is the point. Nine rule stores ended up
unreachable from a parent's phone not because anyone decided so, but because
each was added without the question being asked.

## What the classifications mean

| | |
|---|---|
| **Parent** | A parent can change it from their own phone today. |
| **Pending** | A rule, but only settable standing at the managed phone. What [#37](https://github.com/i3iorn/dementor_watch/issues/37) is closing. |
| **Local only** | Deliberately never remote. The reason is recorded next to the entry. |
| **Not a rule** | Persisted, but not a rule — bookkeeping, or state the app keeps about itself. |

**8 settings are Pending.** That number is asserted by the test, so it can only
change on purpose. It started at 78; [#41](https://github.com/i3iorn/dementor_watch/issues/41)
made the 9 schedule fields Parent, then the 11 contextual-rule condition/effect/
target fields, then the 2 places-list fields — everything about a place except
its coordinates, which move to Local only instead, below. [#42](https://github.com/i3iorn/dementor_watch/issues/42)
made web filtering, the goal's three settable fields, and every optional
`Feature` reachable, and reclassified `NotificationPreferences` and the three
non-optional `Feature`s (never really Pending — see their own sections) rather
than closing them. What's left: `announce_arrivals` (below) and
`AutoApprovalStore`, a list of records rather than flat scalars and #42's own
next slice.

## LocalPolicyStore — `local_policy`

The only store a parent already reaches. Its 9 Parent rows are exactly what
`PolicySnapshot` carries, plus `version`.

| Key | Stored as | Reach |
|---|---|---|
| `KEY_VERSION` | `version` | Not a rule — tells a parent's copy from a stale one |
| `KEY_DAILY_LIMIT` | `daily_limit_minutes` | Parent |
| `KEY_BLOCKED` | `blocked_packages` | Parent |
| `KEY_HIDDEN` | `hidden_packages` | Parent |
| `KEY_INSTALL_WINDOW` | `install_window_until` | Parent |
| `KEY_NETWORK_BLOCKED` | `network_blocked_packages` | Parent |
| `KEY_EXEMPT` | `exempt_packages` | Parent |
| `KEY_CATEGORIES` | `categories` | Parent |
| `KEY_APP_LIMITS` | `app_limits` | Parent |
| `KEY_INSTALL_POLICY` | `install_policy` | Parent |
| `KEY_TAMPER` | `tamper_protection` | **Local only** |

`tamper_protection` is the one deliberate exception in the whole inventory.
Flipping it with nobody standing at the phone is the single rule change this app
treats as too dangerous to make from a distance — see `LocalPolicyStore`'s own
doc comment. It stays out of scope for #37.

Bedtime used to be four fields here (`bedtime_enabled`/`bedtime_start_minute_of_day`/
`bedtime_end_minute_of_day`/`bedtime_days`). [#12](https://github.com/i3iorn/dementor_watch/issues/12)
removed them — bedtime is a `StoredSchedule` now, see `ScheduleStore` below.

## ScheduleStore — `schedules` · 10 Parent

`KEY_SCHEDULES` holds the list; the rest are the fields of one schedule inside
it. Was the gap behind #12 (below); [#41](https://github.com/i3iorn/dementor_watch/issues/41)
closed the remote-editing gap first —
`PolicyReplication.writeSchedules`/`readSchedules`/`pushSchedulesFromLocal`/
`pullSchedulesIntoLocal` replicate the whole list under `policy/<childId>/schedules/`,
one path per field per schedule with a `deleted` marker rather than a
whole-record tombstone (see [#40](https://github.com/i3iorn/dementor_watch/issues/40)),
and `ParentRulesActivity` gained a Schedules section to edit it from a parent's
own phone.

`schedules`, `id`, `name`, `days`, `start`, `end`, `allowed`, `mode`, `enabled`, `kind`

`allowed` is the on-disk name of what the model calls `packages`; it was renamed
in the model when schedules gained a mode and deliberately not renamed on disk,
because that would empty the exception list of every schedule already saved.

`kind` is new as of #12: `BEDTIME` for the one fixed-id (`bedtime`) entry
`ScheduleStore` and its screens treat specially, `CUSTOM` for everything else,
absent-reads-as-`CUSTOM` for anything saved before this field existed. Bedtime
replicates through this same channel now instead of through four dedicated
`LocalPolicyStore` fields — the engine already treated a replicated schedule and
the synthesised bedtime block as the same kind of object once assembled, and
this made storage agree. It keeps its own dedicated card on both kinds of
phone rather than appearing in the generic schedules list, so a family tidying
up a homework block can't delete it by accident — the risk that kept it
separate in the first place, now handled by every screen that lists these
windows filtering `bedtime` out instead.

## ContextRuleStore — `context_rules` · 9 Parent, 1 Pending, 2 Local only

Contextual rules and the places they refer to, in one store.

`rules`, `id`, `name`, `enabled`, `condition`, `effect`, `target`, `places`,
`radius`

[#41](https://github.com/i3iorn/dementor_watch/issues/41) closed the rule half
of this store the way [#40](https://github.com/i3iorn/dementor_watch/issues/40)
closed schedules: `PolicyReplication.writeContextRules`/`readContextRules`/
`pushContextRulesFromLocal`/`pullContextRulesIntoLocal` replicate the rule list
under `policy/<childId>/contextRules/`, one path per field per rule with a
`deleted` marker rather than a whole-record tombstone. `condition`, `effect` and
`target` each replicate as one opaque JSON-string register rather than being
decomposed further — a condition can nest `AllOf`/`AnyOf`/`Not` arbitrarily, and
splitting that tree into paths was judged not worth the complexity for how
rarely two parents edit the exact same rule's logic at once. This milestone is
wiring only: no parent-phone condition-builder UI yet.

The places half closed the same way, via `writePlaceRefs`/`readPlaceRefs`/
`pushPlaceRefsFromLocal`/`pullPlaceRefsIntoLocal` and a new model type,
`PlaceRef(id, name, radiusMetres)` — deliberately not `Place` itself, since a
`Place` carries a coordinate and a `PlaceRef` structurally cannot. A parent can
now rename, resize or delete a place and re-point rules at it from any phone.
Creating a place, or moving one, still only happens on whichever device holds
its coordinate (ordinarily the managed phone): `pullPlaceRefsIntoLocal` merges
a remote rename/resize/delete onto a place this device already has coordinates
for, and leaves alone any place id it doesn't — there is nothing to
materialise a brand-new place *with* on a device that never received a
position for it.

Pending: `announce_arrivals`

Local only: `lat`, `lon` — deliberately, not merely not yet. Replicating a
coordinate would break an explicit invariant the README states twice
("coordinates never leave the device"; "no location"), which #41's places
slice was scoped around rather than around changing. See `PlaceRef`'s own doc
comment.

## ContextRuleCodec — `kind`/`value`/`parts`/`minutes` · 4 Parent

Not a `SharedPreferences`-backed store — the JSON codec `ContextRuleStore` and
`PolicyReplication` share for a rule's `condition`/`effect`/`target`, extracted
so both use exactly the same wire shape. `kind`/`value`/`parts`/`minutes` are
its field names *within* that JSON, not preference keys, but the inventory
test reaches them the same way (by reflection over `KEY_*` constants) so a new
condition or effect kind is still forced through classification. Deliberately
open-ended: a future `Xor` condition, say, is a new `KIND` constant and a new
branch in the codec, never a change to this shape.

## WebFilterStore — `web_filter` · 9 Parent

`profile`, `custom_host`, `safe_search`, `youtube`, `incognito`, `blocked`,
`allowed`, `suspend_browsers`, `count_visits`

[#42](https://github.com/i3iorn/dementor_watch/issues/42) closed this store —
all nine fields joined `PolicySnapshot`, riding the same flat-scalar and
membership-set channel `dailyLimitMinutes` and the packages sets already use
(`blocked`/`allowed` as URL membership sets, same reasoning as `exemptPackages`
below: two parents editing different URLs both survive). Wiring only in this
slice — no parent-phone web filter screen yet.

## AutoApprovalStore — `auto_approval` · 7 Pending, 1 not a rule

`rules`, `id`, `name`, `enabled`, `maxMinutes`, `maxPerWindow`, `window`

`fired_total` is a running count the phone keeps about itself — not a rule.

The one #42 slice still outstanding: a list of records (the schedules/
contextual-rules shape — one path per field per rule, a `deleted` marker),
not the flat-scalar shape the rest of #42 closed, so it belongs in its own
milestone slice rather than riding `PolicySnapshot`.

## GoalStore — `goal` · 3 Parent, 6 not a rule

Parent, via `PolicySnapshot` as of #42: `enabled`, `max_minutes`, `prize`.

Not rules — what has happened rather than what may, or bookkeeping nobody
chooses: `effective_from` (reclassified alongside #42 — it's
`GoalStore.save`'s own restart-detection recording when progress last reset,
the same kind of fact `KEY_VERSION` already is, never something a parent
picks), `celebrated_days`, `pending_day`, `pending_used`, `pending_target`,
`pending_prize`.

## FeatureFlags — `features` · 14 Parent, 3 not a rule

Enum-backed, so the settings are the `Feature` entries rather than keys:

`REQUESTS`, `AUTO_APPROVE`, `BONUS`, `CATEGORIES`, `APP_LIMITS`, `SCHEDULES`,
`CONTEXT_RULES`, `PLACES`, `GOALS`, `APP_CONTROL`, `WEB_FILTER`, `WEB_ACTIVITY`,
`LOCATION`, `CHAT`, `EMERGENCY`, `HISTORY`, `TRANSPARENCY`

[#42](https://github.com/i3iorn/dementor_watch/issues/42) decided: a parent can
switch any of the 14 optional features off remotely, via
`LocalPolicyStore.disabledFeatures`. The remaining three (`EMERGENCY`,
`HISTORY`, `TRANSPARENCY`) are marked `optional = false` in the enum and
`setEnabled` always refuses them — on every phone, not only remotely — so they
were never really a Pending rule waiting to be reached; reclassified to Not a
rule rather than left as backlog.

## NotificationPreferences — `notifications` · 10 Local only, 6 not a rule

Enum-backed on `NotificationKind`:

`TIME_WARNING`, `REQUEST_ANSWERED`, `GOAL_EARNED`, `LOCATED`, `LOCKED`,
`REQUEST_RECEIVED`, `AUTO_APPROVED`, `NEW_APP`, `DEVICE_HEALTH`,
`PLACE_TRANSITION`, `EMERGENCY`, `DEVICE_PENDING`, `DEVICE_ADDED`,
`DEVICE_REMOVED`, `FAMILY_DISBANDED`, `CHAT_MESSAGE`

These govern what the *child* is told. Decided alongside #42: **stays
local-only.** A parent silencing what the other side of the family is told
about, from a distance, was weighed against the product's stated position
that the child can see everything that applies to them, and the answer was no
— each phone keeps deciding what it itself notifies about. The six marked
`optional = false` (`LOCATED`, `LOCKED`, `EMERGENCY`, `DEVICE_ADDED`,
`DEVICE_REMOVED`, `FAMILY_DISBANDED`) were never a Pending rule either, for
the same reason as `FeatureFlags`' three above: `setEnabled` always refuses
them.

## Adding a rule

The test fails, and its message says what to do: add the key here and in
`RulesInventoryTest`, deciding as you go whether a parent should be able to set
it. If the answer is no, say why next to the entry, the way `tamper_protection`
does.
