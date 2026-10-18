# Agent notes for protego_parentis

Read `.claude/CLAUDE.md` first — it points at `PHILOSOPHY.md` (the *why*) and
`FRAMEWORK.md` (the *how*) for the actual coding standard and for how to apply
it as an agent. This file is the concrete, repo-specific reference those don't
cover: build/test commands, module layout, conventions and release mechanics.
Full product picture: `README.md`.

## What this is

An Android parental-control app (`app-child`), enforced through Device Owner
APIs rather than an overlay. The same APK runs on both a teen's and a parent's
phone, in different roles.

## Modules

Pure-JVM (no Android, no device needed to test): `core-model`, `core-policy`,
`core-provisioning`, `core-sync`. Android libraries: `security`, `usage`,
`context`, `diagnostics`, `format`. The app itself: `app-child`.

**Decisions belong in `core-policy`; translation belongs in `app-child`.** If
something in the Android module looks like a policy decision (a budget
calculation, a rule resolution), it's in the wrong module. This split is what
lets the 1000+ tests run with no device.

## Build / test

Needs JDK 21+ on the path. Everything compiles to JVM/Java 11 bytecode, and the
pure modules also pin the **Java 11 API surface** (`-Xjdk-release=11`), so a call
to a newer JDK API compiles cleanly on the build JDK and then throws
`NoSuchMethodError` on a handset — no unit test would catch it. Don't reach for
newer JDK APIs.

```bash
./gradlew assembleDebug          # debug APK — the one that's installable
./gradlew test                   # every unit test (1000+), JVM only, no device
./gradlew :core-policy:test      # one module
./gradlew test --tests "*RequestRulesTest*"   # one test class, any module
./gradlew test lint              # what CI runs — see below
./gradlew build                  # the above, plus assembling a release build
```

**Warnings are errors in every module** (`allWarningsAsErrors`), `app-child`
included — a Kotlin warning that would normally just be printed fails the build.
README's "Conventions" still says only the pure modules do this; that line is
stale.

**Trap:** `./gradlew build` (not `test`/`lint`) also produces a release APK
(`app-child-release-unsigned.apk`) that is genuinely unsigned — Android refuses
to install it. Use the debug APK unless release signing is set up
(`docs/RELEASING.md`).

There are **no instrumentation tests**. Every `DevicePolicyManager` interaction
is exercised through `FakeDevicePolicyPort`, so the tests prove the logic but not
the platform integration — real enforcement on a provisioned handset is the
largest untested area in the project. Don't expect a suite that needs a device.

## Sandboxed / blocked-network builds

If Gradle can't reach `dl.google.com`/`repo.maven.apache.org`, that's an
environment network policy, not a build misconfiguration — say so rather than
trying to work around it.

When only Google's repository (`dl.google.com`) is out of reach, the pure-JVM
modules — `core-model`, `core-policy`, `core-provisioning`, `core-sync` — can
still be built and tested on their own, from Maven Central alone:

    ./gradlew -p jvm-only test

That covers the policy engine, the sync protocol and the wire formats. The
Android modules still need the full build; say which of your changes were
verified this way and which only by reading.

## CI

Two separate, read-only workflows run on every push and PR:
`.github/workflows/test.yml` (`./gradlew test --stacktrace`) and
`.github/workflows/lint.yml` (`./gradlew lint --stacktrace`). Neither assembles a
release build — CI only does that once, in `.github/workflows/release.yml`, on a
`v*` tag push, which is also where the 16 KB native-library-alignment check now
lives. The split exists because a full `./gradlew build` on every push
(assembling and R8-minifying a release variant each time) was burning through
GitHub Actions spending fast enough to start failing runner assignment. Don't
"fix" CI by adding a release build back — a release artifact is a deliberate,
occasional action (a tag push, or a person running `./gradlew build`/
`bundleRelease` by hand per `docs/RELEASING.md`), not something every commit
should pay for.

## app-child conventions

- **No bare dimension, text size, tuning number or user-visible string in Kotlin
  or layout XML.** Add it to the matching resource — `dimens.xml`, `styles.xml`,
  `colors.xml`, `config.xml`, `strings.xml` — and reference it. See README's
  "Where the configuration lives" for which file owns what.
- **Every colour in `values/colors.xml` must also exist in `values-night/`,** or
  the app crashes at inflate time in dark mode.
- The app ships English and Swedish. Lint treats a missing translation as a build
  error, so a half-finished language can't ship. See README's "Adding a language".
- A new persisted key fails `RulesInventoryTest` until it's classified in
  `docs/RULES-INVENTORY.md` — the test reads the stores by reflection and that's
  the point. Decide whether a parent should reach it remotely; if not, say why
  next to the entry.
- The **diagnostic log records what the app did, never what the child did** —
  event names, counts and durations only. A test asserts the shape of every
  rendered line. **Location coordinates never leave `ContextProvider`.** Both are
  deliberate privacy invariants; don't interpolate content or pass a coordinate
  through.
- Release builds are R8-minified and obfuscated. Anything the platform reaches by
  name is invisible to R8; `app-child/proguard-rules.pro` lists every known case.
  A release build must be run on a real device before it goes near a review
  queue — it's the one build difference that can break what debug did fine.

## Versioning — bump on every PR

`versionCode` and `versionName` live in `app-child/build.gradle.kts`
(`defaultConfig`). **Every PR that changes `app-child` or a module it depends
on must bump both**, as part of the same PR, not a follow-up:

- `versionCode` — increment by 1. It gates OTA updates (`UpdateClient` only
  ever compares this number and only ever moves forward) and Play rejects a
  repeat outright.
- `versionName` — bump the patch component (`1.1.1` → `1.1.2`) for an
  ordinary change; bump minor for a new user-facing feature. Match whatever
  the last release actually did if unsure — see `git log -p -- app-child/build.gradle.kts`.

Skip the bump only for a change that touches no `app-child`-reachable code at
all (docs, CI config, tests, the store listing). When in doubt, bump it.
`test.yml`'s "Version bump" job enforces this on every PR: it fails when a
file outside `src/test/`, `docs/`, `.github/`, `.claude/`, `play/` or a `.md`
changed and `versionCode` did not go up. It reads the number with the regex
`^\s*versionCode = [0-9]+`, so keep that assignment on its own line in exactly
that shape — a reformat fails the check with a message saying so. Dependabot's
PRs are exempt: it can't bump the version, and the next PR that does covers its
update. Before tagging a release, check that `versionCode` is above the last
release's.

## Releasing

`keystore.properties` at the repository root — gitignored, never committed — is
what release signing reads. Without it, `assembleRelease`/`bundleRelease` fail
by design rather than producing an unsigned artifact Play would reject.

Pushing a tag matching `v*` is what actually ships an OTA release
(`.github/workflows/release.yml`): it builds the signed APK, computes its
SHA-256, and publishes both plus `update.json` as GitHub Release assets, which
`UpdateClient` polls daily. A PR bumping the version does not itself publish
anything — that still needs `git tag vX.Y.Z && git push origin vX.Y.Z` from
someone with the release signing secrets. Full mechanics: `docs/RELEASING.md`.
