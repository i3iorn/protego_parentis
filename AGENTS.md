# Agent notes for protego_parentis

Read `.claude/CLAUDE.md` first — it points at `PHILOSOPHY.md` (the *why*) and
`FRAMEWORK.md` (the *how*) for the coding standard and how to apply it as an
agent. This file is the concrete, repo-specific reference those don't cover:
build/test commands, module layout, the invariants the build enforces, and
release mechanics.

## What this is

An Android parental-control app, enforced through Device Owner APIs rather than
an overlay. One APK runs on teenager and parent phones in different roles.
`README.md` is the full picture; `docs/` holds the plan-level documents.

## Modules

Pure-JVM (no Android, no device needed to test): `core-model`, `core-policy`,
`core-provisioning`, `core-sync`. Android libraries: `security`, `usage`,
`context`, `diagnostics`, `format`. The app itself: `app-child`.

**Decisions belong in `core-policy`; translation belongs in `app-child`.** If
something in an Android module looks like a policy decision (a budget
calculation, a rule resolution), it's in the wrong module. That split is what
lets ~1500 tests run with no device.

`core-sync` is the sync *domain* (crypto, roster, replay guard); the transport
on top of it is `app-child`'s `sync` package (ntfy relay by default).
`core-provisioning` defines the QR payload *format* only — nothing builds one
yet, so device owner still comes from `adb shell dpm set-device-owner`.

## Build / test

Needs JDK 21+ on the path. Every module targets JVM 11 bytecode, and the
`core-*` modules also pass `-Xjdk-release=11`, so the *API surface* is pinned
too — a method added after Java 11 will not compile even on a newer JDK.

```bash
./gradlew assembleDebug          # debug APK — the one that's installable
./gradlew test                   # every unit test, JVM only, no device
./gradlew :core-policy:test      # one module
./gradlew test --tests "*RequestRulesTest*"                # one class, any module
./gradlew :core-policy:test --tests "*RequestRulesTest*"   # one class, one module
./gradlew test lint              # what CI runs
./gradlew build                  # test + lint + release assembly (see trap)
```

Debug APK path: `app-child/build/outputs/apk/debug/app-child-debug.apk`.

**Trap:** `./gradlew build` (not `test`/`lint`) also produces
`app-child/build/outputs/apk/release/app-child-release-unsigned.apk`, which is
genuinely unsigned — Android refuses to install it. Use the debug APK unless
release signing is set up (`docs/RELEASING.md`). Don't run `./gradlew build`
routinely; it assembles and R8-minifies a release variant each time.

A release build without `keystore.properties` at the repo root fails fast with
an explanatory error instead of producing an unsigned artifact quietly — that
failure is deliberate; don't "fix" it.

**Warnings are errors in all ten modules** (`allWarningsAsErrors`), `app-child`
and the Android libraries included — an unused import or a deprecation fails
the build, not just a review. (`README.md` still says "pure modules"; it is
stale.)

### Testing quirks

- `app-child` unit tests run on **Robolectric**, pinned to API 36 in
  `app-child/src/test/resources/robolectric.properties`, so a class that takes
  a `Context` is testable without a device. The pin is deliberate and stays at
  36: Robolectric only knows the API levels it shipped with, the app already
  targets 37, and raising the pin before a Robolectric release knows the level
  fails every test at setup — don't couple the pin to `targetSdk`.
- There are **no instrumentation tests**. Every `DevicePolicyManager`
  interaction goes through `FakeDevicePolicyPort`, which proves the logic but
  not the platform. Real enforcement on a handset is the largest untested area.
- Robolectric setup failing with an `IllegalAccessException` (e.g. about
  `jdk.internal.access`) is preconfigured, not broken: the required JDK
  `--add-opens`/`--add-exports` already live in `app-child/build.gradle.kts`.
- A test is named after the *behaviour* it pins, not the method it calls.

### Environment gotchas

- **The JDK on `PATH` on this machine is Oracle's**, whose crypto provider
  fails its own self-test at class-init and breaks every `Cipher.getInstance`
  — the sync and vault tests with it. The root `build.gradle.kts` detects a
  JetBrains Runtime under `~/.jdks` and runs test JVMs on it instead. If you
  see `javax.crypto.JarVerifier` failures, that's the cause; don't patch the
  tests.
- **The configuration cache is on.** A Gradle task action that captures the
  build-script object (a `doLast` in a `build.gradle.kts`) can't be serialised
  and fails the build for an unrelated-looking reason — it prints the right
  output first and *then* fails with "configuration cache problems found in
  this build". Read the values at configuration time and capture only plain
  values in the action; see `app-child/build.gradle.kts`'s `printVersionInfo`.
- **If Gradle can't reach `dl.google.com`/`repo.maven.apache.org`, that's an
  environment network policy, not a build misconfiguration** — say so rather
  than working around it. When only `dl.google.com` is out of reach, the
  pure-JVM modules still build and test from Maven Central alone:

  ```bash
  ./gradlew -p jvm-only test
  ```

  That covers the policy engine, the sync protocol and the wire formats. The
  Android modules still need the full build. Say which of your changes were
  verified this way and which only by reading.

## Invariants the build catches

- **A new persisted preference breaks the build** until somebody classifies
  it. `RulesInventoryTest` reads every rule store's keys *by reflection* and
  asserts each one's reach (Parent / Pending / Local only / Not a rule).
  `docs/RULES-INVENTORY.md` is its readable rendering — the test wins if they
  disagree.
- **The diagnostic log records what the app did, never what the child did.**
  `DiagnosticLogFormat` permits one rendered shape only (an event name, counts
  and durations). A line that interpolates a package name, a reason or a
  prize text fails the test.
- **A new colour must also exist in `values-night/colors.xml`**, or the app
  crashes at inflate time in dark mode.
- **A missing translation is a build error** (lint). To add a language: add a
  `<locale>` to `res/xml/locales_config.xml` and a `res/values-xx/strings.xml`.
- **No bare dimension, text size, tuning number or user-visible string in
  Kotlin or layout XML.** Product constants live in `core-model`'s
  `Defaults.kt`; the rest in
  `app-child/res/values/{dimens,styles,config,strings}.xml`.

## Engine defaults to know before you touch the policy

- The day boundary is **04:00**, not midnight (`Defaults.DAY_BOUNDARY_HOUR`),
  so a 23:50 session is one day's usage, not two.
- Resolution: **allow beats a block**, the **tightest** limit wins, and the
  safety allowlist beats every rule.
- Contextual rules **fail closed** — a missing permission makes the condition
  false, so revoking a permission can never turn a block into an allowance.
- **Coordinates never leave `context`'s `ContextProvider`**, which resolves a
  position into "the set of places the phone is inside" and discards it.
  Nothing downstream — including the log and sync — can handle a location
  because it never receives one. Don't put a coordinate in a model type.
- `DeviceReleaser` must lift every restriction *before* surrendering device
  owner; a test asserts `clearDeviceOwner` is the final call.

## Versioning — bump on every PR

`versionCode` and `versionName` live in `app-child/build.gradle.kts`
(`defaultConfig`). **Every PR that changes `app-child` or a module it depends
on must bump both**, in the same PR, not a follow-up:

- `versionCode` — increment by 1. It gates OTA updates (`UpdateClient` only
  ever compares this number and only ever moves forward) and Play rejects a
  repeat.
- `versionName` — bump the patch component (`1.8.2` → `1.8.3`) for an
  ordinary change; bump minor for a new user-facing feature. If unsure,
  match the last release: `git log -p -- app-child/build.gradle.kts`.

Skip the bump only for a change touching no `app-child`-reachable code at all
(docs, CI config, tests, the store listing). When in doubt, bump it.

CI enforces only `versionCode`, in `test.yml`'s "Version bump" job: it fails
when a file outside `src/test/`, `docs/`, `.github/`, `.claude/`, `play/` or a
`.md` changed and `versionCode` did not go up. Dependabot PRs are exempt.
Before tagging a release, check that `versionCode` is above the last
release's.

**The release tag must be `v` + `versionName`** — `v1.8.2`, not `1.8.2` and
not `v1.8.2-release`. Obtainium and every other release-page updater compare
the *tag* against the version Android reports for the installed copy, so a
mismatched tag reads as "an update is available" forever. `release.yml`
refuses a tag that does not match; the version-name example above has to
change in step with `versionName`.

## CI

Two read-only workflows on every push and PR: `.github/workflows/test.yml`
(`./gradlew test --stacktrace`, plus a Kover coverage report — report-only,
nothing fails on a number) and `.github/workflows/lint.yml`
(`./gradlew lint --stacktrace`). Neither assembles a release build; the split
exists because a full `./gradlew build` on every push burned through Actions
spending. Only `.github/workflows/release.yml` assembles a release, on a `v*`
tag push, and it also holds the 16 KB native-library-alignment check.

## Releasing (context, not a PR concern)

Pushing a tag matching `v*` ships an OTA release via `release.yml`: it runs
the full `./gradlew build` (test + lint first, same bar as a manual release),
signs the APK, computes its SHA-256, and publishes it plus `update.json` as
GitHub Release assets, which `UpdateClient` polls daily. The same release is
what Obtanium and other release-page updaters install from, so the workflow
also writes release notes (what it is, how to install it, the signing
certificate's fingerprint) and refuses a tag that does not match
`versionName`. A PR bumping the version publishes nothing — that needs
`git tag vX.Y.Z && git push origin vX.Y.Z` from someone with the release
signing secrets.

`keystore.properties` at the repo root is parsed by hand, not by
`java.util.Properties`: a backslash in a Windows path is swallowed and
surrounding quotes end up inside the filename. Write `storeFile` with forward
slashes and no quotes (`storeFile=C:/Users/you/vault/upload.jks`).
`play/check_listing.py` checks the store text against Play's length limits.
Full mechanics: `docs/RELEASING.md`.
