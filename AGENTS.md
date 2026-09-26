# Agent notes for protego_parentis

Read `.claude/CLAUDE.md` first — it points at `PHILOSOPHY.md` (the *why*) and
`FRAMEWORK.md` (the *how*) for the actual coding standard and for how to apply
it as an agent. This file is the concrete, repo-specific reference those don't
cover: build/test commands, module layout, and release mechanics.

## What this is

An Android parental-control app (`app-child`), enforced through Device Owner
APIs rather than an overlay. Full picture: `README.md`.

## Modules

Pure-JVM (no Android, no device needed to test): `core-model`, `core-policy`,
`core-provisioning`, `core-sync`. Android libraries: `security`, `usage`,
`context`, `diagnostics`, `format`. The app itself: `app-child`.

**Decisions belong in `core-policy`; translation belongs in `app-child`.** If
something in the Android module looks like a policy decision (a budget
calculation, a rule resolution), it's in the wrong module. This split is what
lets the pure-JVM tests run with no device.

## Build / test

Needs JDK 21+ on the path.

```bash
./gradlew assembleDebug          # debug APK — the one that's installable
./gradlew test                   # every unit test (1000+), JVM only, no device
./gradlew :core-policy:test      # one module
./gradlew test --tests "*RequestRulesTest*"   # one test class, any module
./gradlew test lint              # what CI runs — see below
./gradlew build                  # the above, plus assembling a release build
```

**Trap:** `./gradlew build` (not `test`/`lint`) also produces a release APK
(`app-child-release-unsigned.apk`) that is genuinely unsigned — Android
refuses to install it. Use the debug APK unless release signing is set up
(`docs/RELEASING.md`).

CI is two separate workflows, both read-only and both running on every push
and PR: `.github/workflows/test.yml` (`./gradlew test --stacktrace`) and
`.github/workflows/lint.yml` (`./gradlew lint --stacktrace`). Neither
assembles a release build — CI only does that once, in
`.github/workflows/release.yml`, on a `v*` tag push, which is also where the
16 KB native-library-alignment check now lives. This split exists because a
full `./gradlew build` on every single push (assembling and R8-minifying a
release variant each time) was burning through GitHub Actions spending fast
enough to start failing runner assignment outright — verifying the code is
correct doesn't need a release artifact built alongside it, so CI stopped
doing that on every push. Building a release artifact is a deliberate,
occasional action (a tag push, or a person running `./gradlew build`/
`bundleRelease` by hand per `docs/RELEASING.md`), not something every commit
should pay for.

If Gradle can't reach `dl.google.com`/`repo.maven.apache.org` (a blocked-network
sandbox), that's an environment network policy, not a build misconfiguration —
say so rather than trying to work around it.

When only Google's repository (`dl.google.com`) is out of reach, the pure-JVM
modules — `core-model`, `core-policy`, `core-provisioning`, `core-sync` — can
still be built and tested on their own, from Maven Central alone:

    ./gradlew -p jvm-only test

That covers the policy engine, the sync protocol and the wire formats. The
Android modules still need the full build; say which of your changes were
verified this way and which only by reading.

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
changed and `versionCode` did not go up.

## Releasing (not a PR concern, but relevant context)

Pushing a tag matching `v*` is what actually ships an OTA release
(`.github/workflows/release.yml`): it builds the signed APK, computes its
SHA-256, and publishes both plus `update.json` as GitHub Release assets, which
`UpdateClient` polls daily. A PR bumping the version does not itself publish
anything — that still needs `git tag vX.Y.Z && git push origin vX.Y.Z` from
someone with the release signing secrets. Full mechanics: `docs/RELEASING.md`.
