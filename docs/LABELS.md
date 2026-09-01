# Labels

Four categories, answering four different questions about an issue. They are
deliberately orthogonal: a label from one category never implies one from
another, so a filter on any single category is meaningful on its own.

| Category | The question it answers | How many per issue |
|---|---|---|
| **Type** | What kind of work is this? | Exactly one |
| **Priority** | When should it be looked at? | Exactly one |
| **Area** | Which part of the app? | Zero or more |
| **Status** | Why is it not moving? | Only while true |

The first two are required on every issue. An issue with no type cannot be
triaged, and an issue with no priority quietly becomes everyone's problem or
nobody's.

## Type

One saturated colour each, because this is the thing you scan for first.

| Label | Colour | Use it when |
|---|---|---|
| `bug` | `d73a4a` | Something does not do what it says. Includes code that is unreachable, or a guarantee the code does not actually provide. |
| `feature` | `0e8a16` | Capability that does not exist yet. |
| `enhancement` | `a2eeef` | Something that works, made better — including hardening that is not a defect today. |
| `refactor` | `5319e7` | Behaviour unchanged, structure improved. If users would notice, it is not a refactor. |
| `documentation` | `0075ca` | Prose, comments, or a doc file is the deliverable. |
| `design` | `d876e3` | Visual or interaction design is the substance, not the implementation. |

`bug` versus `enhancement` is the pair most often got wrong here. The test:
does the code fail to do what it claims? A missing defence that was never
promised is an `enhancement`; a defence that is documented and does not run is
a `bug`.

## Priority

A ramp in one hue family, so it reads as a scale rather than three unrelated
tags.

| Label | Colour | Meaning |
|---|---|---|
| `priority: high` | `b60205` | Do before the next release. Either a live hole, or a window that closes — some changes become breaking once anything ships. |
| `priority: medium` | `d93f0b` | Should be done, no deadline attached. |
| `priority: low` | `fbca04` | Worth doing when convenient. |

Keep `high` scarce. If most things are high, the label has stopped saying
anything and the sorting has to happen somewhere else.

## Area

Muted, so they sit behind type and priority rather than competing with them.

| Label | Colour | Covers |
|---|---|---|
| `ui` | `c2e0c6` | Screens, layout, text shown to a person. |
| `device-owner` | `bfdadc` | `DevicePolicyManager`, provisioning, user restrictions, anything only a DPC can do. |
| `schedules` | `d4c5f9` | Bedtime, schedule blocks, time windows. |
| `policies` | `f9d0c4` | The policy model and engine: budgets, categories, rules, enforcement decisions. |
| `security` | `e99695` | Crypto, keys, the roster, trust boundaries, anything a motivated teen would attack. |
| `sync` | `fef2c0` | Replication, the relay, message handling, convergence. |
| `build-system` | `ededed` | Gradle, CI, packaging, release. |

### Two departures from the original proposal

`backend` is not created. There is no server and no backend in this project —
the README is explicit that rules are edited on the same phone that enforces
them. A label nothing can carry is the clutter labels exist to prevent. Worth
adding the day a server exists, and not before.

`security` and `sync` are added. The original set had nowhere to put the
majority of the defects actually on the tracker, which cluster in the crypto,
roster and replication code. An area label that cannot be applied to the work
being done is not describing this codebase.

## Status

Applied only while the statement is true, and removed when it stops being
true. A stale `blocked` is worse than no label, because it stops people
looking.

| Label | Colour | Meaning |
|---|---|---|
| `in progress` | `c5def5` | Someone is actively working on it now. |
| `blocked` | `e4e669` | Cannot proceed until something else happens. Say what, in a comment. |
| `needs discussion` | `cc317c` | A decision is required before implementation makes sense. Usually the issue offers options and asks someone to pick. |
| `needs reproduction` | `bfd4f2` | Reported, not yet reproduced. |

## Creating labels

GitHub creates a label the first time it is applied to an issue, with a
default grey and no description. That is why the colours above have to be set
deliberately — otherwise the vocabulary is right and the board is unreadable.

To apply this file's colours in one pass:

```bash
# Type
gh label create "bug"               --color d73a4a --description "Does not do what it says"           --force
gh label create "feature"           --color 0e8a16 --description "Capability that does not exist yet"  --force
gh label create "enhancement"       --color a2eeef --description "Works, made better"                  --force
gh label create "refactor"          --color 5319e7 --description "Structure improved, behaviour same"  --force
gh label create "documentation"     --color 0075ca --description "Prose is the deliverable"            --force
gh label create "design"            --color d876e3 --description "Visual or interaction design"        --force
# Priority
gh label create "priority: high"    --color b60205 --description "Before the next release"             --force
gh label create "priority: medium"  --color d93f0b --description "Should be done, no deadline"         --force
gh label create "priority: low"     --color fbca04 --description "When convenient"                     --force
# Area
gh label create "ui"                --color c2e0c6 --description "Screens and text"                    --force
gh label create "device-owner"      --color bfdadc --description "DPC, provisioning, restrictions"     --force
gh label create "schedules"         --color d4c5f9 --description "Bedtime, schedule blocks"            --force
gh label create "policies"          --color f9d0c4 --description "Policy model and engine"             --force
gh label create "security"          --color e99695 --description "Crypto, keys, roster, trust"         --force
gh label create "sync"              --color fef2c0 --description "Replication and the relay"           --force
gh label create "build-system"      --color ededed --description "Gradle, CI, packaging, release"      --force
# Status
gh label create "in progress"       --color c5def5 --description "Actively being worked on"            --force
gh label create "blocked"           --color e4e669 --description "Waiting on something else"           --force
gh label create "needs discussion"  --color cc317c --description "A decision is needed first"          --force
gh label create "needs reproduction" --color bfd4f2 --description "Not yet reproduced"                 --force
```

`--force` updates a label that already exists rather than failing, so this is
safe to re-run and is the way to correct a colour later.
