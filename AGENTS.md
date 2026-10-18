# Agent notes for protego_parentis

Read `.claude/CLAUDE.md` first — it points at `PHILOSOPHY.md` (the *why*) and
`FRAMEWORK.md` (the *how*) for the coding standard and how to apply it as an
agent. This file is the concrete, repo-specific reference those don't cover:
build/test commands, module layout, and release mechanics.

## What this is

An Android parental-control app (`app-child`), enforced through Device Owner
APIs rather than an overlay. The same APK runs on teenager and parent phones in
different roles. Full picture: `README.md`.

## Modules

Pure-JVM (no Android, no device needed to test): `core-model`, `core-policy`,
`core-provisioning`, `core-sync`. Android libraries: `security`, `usage`,
`context`, `diagnostics`, `format`. The app itself: `app-child`.

**Decisions belong in `core-policy`; translation belongs in `app-child`.** If
something in an Android module looks like a policy decision (a budget
calculation, a rule resolution), it's in the wrong module. That split is what
lets 1000+ tests run with no device.

## Build / test

Needs JDK 21+ on the path. Everything compiles to JVM 11 bytecode regardless.

```bash
./gradlew assembleDebug          # debug APK — the one that's installable
./gradlew test                   # every unit test (1000+), JVM only, no device
./gradlew :core-policy:test      # one module
./gradlew test --tests "*RequestRulesTest*"   # one test class, any module
./gradlew test lint              # what CI runs
./gradlew build                  # test + lint + release assembly (see trap)
```

Debug APK path: `app-child/build/outputs/apk/debug/app-child-debug.apk`.

**Trap:** `./gradlew build` (not `test`/`lint`) also produces
`app-child/build/outputs/apk/release/app-child-release-unsigned.apk`, which is
genuinely unsigned — Android refuses to install it. Use the debug APK unless
release signing is set up (`docs/RELEASING.md`). Don't run `./gradlew build`
routinely; it assembles and R8-minifies a release variant each time.

CI is two separate read-only workflows on every push and PR:
`.github/workflows/test.yml` (`./gradlew test --stacktrace`, plus a Kover
coverage report — report-only, nothing fails on a number) and
`.github/workflows/lint.yml` (`./gradlew lint --stacktrace`). Neither assembles
a release build — only `.github/workflows/release.yml`, on a `v*` tag push,
which also holds the 16 KB native-library-alignment check. The split exists
because a full `./gradlew build` on every push burned through Actions spending;
verifying correctness doesn't need a release artifact alongside it.

If Gradle can't reach `dl.google.com`/`repo.maven.apache.org` (a blocked-network
sandbox), that's an environment network policy, not a build misconfiguration —
say so rather than working around it. When only `dl.google.com` is out of reach,
the pure-JVM modules — `core-model`, `core-policy`, `core-provisioning`,
`core-sync` — still build and test from Maven Central alone:

```bash
./gradlew -p jvm-only test
```

That covers the policy engine, the sync protocol and the wire formats. The
Android modules still need the full build. Say which of your changes were
verified this way and which only by reading.

## Versioning — bump on every PR

`versionCode` and `versionName` live in `app-child/build.gradle.kts`
(`defaultConfig`). **Every PR that changes `app-child` or a module it depends on
must bump both**, in the same PR, not a follow-up:

- `versionCode` — increment by 1. It gates OTA updates (`UpdateClient` only ever
  compares this number and only ever moves forward) and Play rejects a repeat.
- `versionName` — bump the patch component (`1.1.1` → `1.1.2`) for an ordinary
  change; bump minor for a new user-facing feature. If unsure, match the last
  release: `git log -p -- app-child/build.gradle.kts`.

Skip the bump only for a change touching no `app-child`-reachable code at all
(docs, CI config, tests, the store listing). When in doubt, bump it.
`test.yml`'s "Version bump" job enforces this on every PR: it fails when a file
outside `src/test/`, `docs/`, `.github/`, `.claude/`, `play/` or a `.md` changed
and `versionCode` did not go up. Dependabot PRs are exempt. Before tagging a
release, check that `versionCode` is above the last release's.

## Releasing (context, not a PR concern)

Pushing a tag matching `v*` ships an OTA release via
`.github/workflows/release.yml`: it runs the full `./gradlew build` (test +
lint first, same bar as a manual release), signs the APK, computes its SHA-256,
and publishes it plus `update.json` as GitHub Release assets, which
`UpdateClient` polls daily. A PR bumping the version publishes nothing — that
needs `git tag vX.Y.Z && git push origin vX.Y.Z` from someone with the release
signing secrets. Full mechanics: `docs/RELEASING.md`.
