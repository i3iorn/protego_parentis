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
./gradlew build                  # test + Android lint + assemble release — what CI runs
```

**Trap:** `./gradlew build` also produces a release APK
(`app-child-release-unsigned.apk`) that is genuinely unsigned — Android
refuses to install it. Use the debug APK unless release signing is set up
(`docs/RELEASING.md`).

CI (`.github/workflows/build.yml`) runs `./gradlew build --stacktrace` on
every push and PR — build, full test suite, and Android lint together, plus a
16 KB native-library-alignment check on the release APK. There is no separate
lint-only or typecheck-only command; `build` is the one gate.

If Gradle can't reach `dl.google.com`/`repo.maven.apache.org` (a blocked-network
sandbox), that's an environment network policy, not a build misconfiguration —
say so rather than trying to work around it, and verify changes by reading
rather than compiling.

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
all (docs, CI config, a pure-JVM module nothing in `app-child` depends on
yet). When in doubt, bump it.

## Releasing (not a PR concern, but relevant context)

Pushing a tag matching `v*` is what actually ships an OTA release
(`.github/workflows/release.yml`): it builds the signed APK, computes its
SHA-256, and publishes both plus `update.json` as GitHub Release assets, which
`UpdateClient` polls daily. A PR bumping the version does not itself publish
anything — that still needs `git tag vX.Y.Z && git push origin vX.Y.Z` from
someone with the release signing secrets. Full mechanics: `docs/RELEASING.md`.
