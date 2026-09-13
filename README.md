# Dementor Watch

A parental control app for Android, aimed at 11–16 year olds. It measures screen
time, enforces daily limits, category limits, per-app limits and bedtime, and
shows the teenager exactly the same numbers their parent sees.

Enforcement is done through Android's **Device Owner** APIs, so blocked apps are
suspended by the operating system rather than covered by an overlay.

---

## Modules

| Module | Kind | What lives here |
| --- | --- | --- |
| `core-model` | Pure Kotlin | Domain types: `Policy`, `Category`, `WeeklyBudget`, `Grant`, `Enforcement`. No Android, no I/O. |
| `core-policy` | Pure Kotlin | All the real logic: `ContextRules` (where and near what),  `PolicyEngine` (what should be blocked), `UsageReplay` (how much time was spent), `RequestRules` (asking and answering), `PolicyReconciler` (apply it), `DeviceReleaser` (undo it). |
| `core-provisioning` | Pure Kotlin | The setup QR payload format. Kept Android-free so it can be generated without a device SDK. **Nothing yet builds one or turns it into a QR code** — see [Turning on enforcement](#turning-on-enforcement-device-owner). |
| `core-sync` | Pure Kotlin | The sync domain: `SyncMessage`, `MessageCrypto`/`MessageSignatures` (encrypt, sign, verify), `ReplayGuard`, `Enrolment`/`RosterRules` (who may say what). No network, no Android — `app-child`'s `sync` package is the transport on top of this. |
| `security` | Android library | The PIN: `PinAttempts`/`PinBackoff` (the lockout curve), `PinHash`, `KeystoreSigner`, `SecretVault`, `ParentSession`, `LocalDeviceId`. |
| `usage` | Android library | Screen-time measurement: `UsageCollector`, `UsageHistoryStore`, `InstalledApps`. |
| `context` | Android library | Resolves "where and near what" for contextual rules — Wi-Fi, saved places, paired devices, battery — without ever handing a coordinate to anything downstream (see [Contextual rules](#contextual-rules)). |
| `diagnostics` | Android library | The privacy-safe diagnostic log and crash recovery — see [Privacy](#privacy). |
| `format` | Android library | Shared human-readable formatting (durations), so "20 minutes" reads the same everywhere. |
| `app-child` | Android app | The app that runs on both a family's phones — teenager and parent alike, the same APK in a different role (see [Current state](#current-state)). Reads usage, drives `DevicePolicyManager`, stores history, hosts the UI, and carries sync between phones. |

The rule of thumb: **decisions belong in `core-policy`, translation belongs in
`app-child`.** Anything in the Android module that looks like a policy decision is
in the wrong place — that separation is what lets over a thousand tests run
without a phone.

---

## Building

Needs a **JDK 21 or newer** on the path — nothing here requires more than that,
and every module compiles to JVM 11 bytecode against the Java 11 API regardless
of which JDK you build with, so a newer one cannot quietly introduce a call that
only exists off-device.

```bash
./gradlew assembleDebug
```

The installable APK is at:

```
app-child/build/outputs/apk/debug/app-child-debug.apk
```

> **Trap:** `./gradlew build` also produces
> `app-child/build/outputs/apk/release/app-child-release-unsigned.apk`. That one is
> **unsigned** and Android refuses to install it — it fails with a parse or
> "can't extract" error. Always use the **debug** APK until a release signing key
> is set up.

Run everything — unit tests plus Android lint:

```bash
./gradlew build
```

The same command runs on every push and pull request; see
`.github/workflows/build.yml`.

In Android Studio, select the **app-child** run configuration. If it is missing,
use *File → Sync Project with Gradle Files*.

### 16 KB memory page sizes

Google Play requires apps targeting Android 15 or higher to support 16 KB memory
page sizes on 64-bit devices, and from **1 February 2027** will refuse updates
that do not. This app targets API 37, so it is in scope.

It already complies, and not because of anything the build does. The requirement
is about **native libraries**: a `.so` whose ELF segments are aligned for 4 KB
pages cannot be mapped on a device with 16 KB pages. Google's guidance is
explicit that an app written only in Java or Kotlin — dependencies included —
already supports 16 KB devices and needs no rebuild.

This app has no NDK code, and none of its dependencies ship a native library:
AppCompat, core-ktx, WorkManager, Biometric, CameraX and ZXing are all pure
Java or Kotlin. So there is nothing in the packaged app to align.

Two things follow, and both are deliberate:

- **Nothing is configured for this.** AGP 9 aligns native libraries to 16 KB
  automatically (it has since 8.5.1), so the day a native dependency does arrive
  the packaging is already right. Adding settings now would only be settings that
  do nothing.
- **CI checks the assumption, not the alignment.** `.github/workflows/build.yml`
  looks for `lib/*` in the release APK. While there is none it passes and says
  so; the moment a dependency brings one in, it runs `zipalign -c -P 16` against
  it and fails if that alignment is missing. A check that verified alignment on
  an APK containing no native code would pass forever without ever having tested
  anything.

Note that page-size support is a **compatibility** requirement, not a
performance one. It is unrelated to asset packing or startup time, and there is
no measurable startup improvement to be had from it on an app with no native
code.

Reference: [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes).

---

## Installing

Copy the debug APK to the phone and tap it, or:

```bash
adb install -r app-child/build/outputs/apk/debug/app-child-debug.apk
```

On first launch the app asks for notification permission and for **usage access**.
Usage access is an app-op, not a normal permission, so it can only be granted by
the user in Settings — the app cannot grant it to itself even as device owner.
Without it, every measurement reads zero.

---

## Turning on enforcement (Device Owner)

Until the device is device owner the app runs in **monitor-only** mode: it measures
and evaluates, but nothing is ever blocked. The dashboard says so plainly.

### The only path that currently works

There is no working QR-provisioning flow yet — see the note below. The only way
to make a phone device owner today is `adb`, which Android allows under two
conditions that catch everyone:

1. **Exactly one user.** Private space and work profiles both count.

   ```bash
   adb shell pm list users
   ```

   Remove every user whose id is not `0` with `adb shell pm remove-user <id>`.
   This permanently deletes that profile's apps and data.

2. **No accounts at all.** Google, Samsung, everything.

   ```bash
   adb shell dumpsys account | findstr "Account {"
   ```

   Remove them in Settings → Passwords & accounts.

Then:

```bash
adb shell dpm set-device-owner dev.schrammel.protego_parentis/.dpc.DementorDeviceAdminReceiver
```

Accounts can be added back immediately afterwards — the restriction only applies
at the moment ownership is set. A freshly factory-reset phone that has not been
signed into anything yet already satisfies both conditions, so this is also the
path to use on a brand-new device — not just for development.

> **QR provisioning (factory reset, six taps, scan a code) is not built.**
> `ProvisioningPayload` in `core-provisioning` defines the payload *format* Android
> expects, but nothing in the app constructs one, renders it as a QR code, or hosts
> the APK it would need to download from an HTTPS URL. The QR code the family
> connection screen shows is unrelated — it carries this app's own sync topic/id
> for pairing two already-installed phones with each other, not Android's
> device-owner provisioning payload. Scanning it during Android's setup wizard
> will not work and produces "Wrong QR code." See **Current state** below.

### Getting back out

**Rules → Device control → Release this phone.** Lifts every restriction, restores
every blocked app, and relinquishes ownership.

`DeviceReleaser` does this in a specific order, and the order is load-bearing:
every restriction must be lifted *before* ownership is surrendered, because the
authority to lift them disappears with it. There is a test asserting
`clearDeviceOwner` is the final call.

> **Tamper protection defaults to off.** Turning it on blocks factory reset, clock
> changes and safe mode, and makes the app un-uninstallable. That is correct for a
> child's phone and wrong for a device you are testing on. Leave it off unless you
> are willing to factory reset.

---

## Testing

```bash
./gradlew test
```

Over a thousand unit tests, all on the JVM, no device required. The bulk sit in
`core-policy` and cover the things that are easy to get quietly wrong:

- day boundaries at 04:00, so an 23:50 session is not split across two days
- bedtime windows that cross midnight
- conflicting rules — the reason shown is the one that lifts *last*
- an app in several categories spending from each of them
- the safety allowlist surviving every possible restriction
- screen-off ending open sessions, so a face-down phone accrues nothing
- screen-on resuming them again, so unlocking back into the same app keeps counting
- gaps in history filling as real zeros, so a day off is not mistaken for a day
  that was never measured
- an approved request always producing a grant that matches what was asked for
- a goal never being awarded for days that predate it, including backfilled ones

There are **no instrumentation tests** yet. Every `DevicePolicyManager` interaction
is exercised through a fake (`FakeDevicePolicyPort`), which proves the logic but
not the platform integration. Real enforcement on a provisioned handset is the
largest untested area in the project.

---

## Current state

Working: usage measurement, device/category/per-app limits, bedtime, exempt apps,
bonus time, daily goals with a parent-written prize, the warning ladder,
PIN-protected rules, the teen dashboard, the
weekly view with local history, the request and approval loop, device owner
enforcement and release, and multi-phone sync: a parent's own phone (the same
APK, in the parent role) can remotely view a linked child's status and edit
most of their rules — schedules, categories, per-app limits and blocking,
contextual rules, and auto-approval — over an end-to-end encrypted channel that
never passes through infrastructure this project runs (see
[Security model](#security-model) and [Privacy](#privacy)).

Not built yet:

- **No weekly digest.** The weekly view exists in the app, but nothing is pushed
  or emailed on a schedule.
- **No company-run server or accounts.** Sync is device-to-device through a
  public relay (ntfy.sh by default), which only ever sees ciphertext — there is
  no backend this project operates, and nothing to sign up for.
  `LocalPolicyStore` itself is still `SharedPreferences` rather than Room; that
  part of the original plan is separate from sync and remains outstanding.
- **Two rule screens are still own-device-only.** Web filtering and reviewing
  web activity can only be set or seen standing at the managed phone itself —
  see `docs/RULES-INVENTORY.md` for the full reachability inventory.
- **No QR-code device-owner provisioning.** `ProvisioningPayload` in
  `core-provisioning` defines the payload format, but no screen builds one,
  renders it as a QR code, or hosts the APK it points at. Becoming device owner
  today means `adb shell dpm set-device-owner` (see above). Finishing the real
  path needs: a screen that builds a `ProvisioningPayload` and turns it into a QR
  image, an HTTPS location to host the release APK (not Play — provisioning
  downloads it directly), and the release signing certificate's SHA-256 checksum
  wired in rather than hand-copied. Until all three exist, this is the largest gap
  between what the product spec describes and what the app does.

## Contextual rules

Beyond "how much" and "when on the clock", a rule can depend on where the phone
is and what it is near. A rule reads as a sentence: **when this, do that, to
those.**

- **When** — always, on a named Wi-Fi network, at a saved place, near a paired
  Bluetooth device. Any of them can be inverted, and they compose with and/or.
- **Do** — block, allow freely, or replace the daily limit.
- **To** — everything, some categories (following nesting), or some apps.

Resolution rules, both chosen so a parent cannot open a hole by accident: an
**allow beats a block**, and where several rules set a limit the **tightest
wins**. The safety allowlist still beats every rule.

Three things about how context is read:

- **Rules fail closed, never open.** A missing permission leaves the value absent,
  which makes the condition false. Revoking a permission can never turn a block
  into an allowance.
- **Nothing is read that no rule needs**, and permissions are requested at the
  moment a rule asks for them rather than at first launch.
- **Coordinates never leave `ContextProvider`.** It resolves a position into "the
  set of places the phone is inside" and discards it. Everything downstream —
  including the diagnostic log and sync — is structurally incapable of handling a
  location because it never receives one.

Android treats a Wi-Fi network name as a location signal, so reading the SSID
needs the location permission. Places use the last known fix rather than waking
the GPS, so a rule lags rather than costing battery. Proximity means *paired and
connected over Bluetooth*, not a BLE scan — much cheaper, and good enough for
"is a parent's phone with them".

## Security model

The adversary is a motivated teenager holding the device, with unlimited time and
no need for tooling. That shapes several decisions:

- **The PIN throttles.** Four digits is ten thousand combinations, and without a
  lockout they can simply be tapped in. Failures persist across restarts and the
  lockout is measured against `SystemClock.elapsedRealtime()`, because the wall
  clock is settable by the person being locked out.
- **Every parent-only screen checks the session**, not just the entry point.
  Gating only the rules screen left the recents list as a way back into an
  already-unlocked settings screen.
- **The parent session lives in memory only** and lasts five minutes. Persisting
  it would let it survive a force-stop, which helps nobody except the child.
- **Parent screens set `FLAG_SECURE`**, keeping rules and PIN entry out of
  screenshots and the recents thumbnail.
- **The PIN is stored as a salted PBKDF2 hash** and compared in constant time.
- **Replacing a PIN verifies the old one first** and overwrites only once the new
  one is confirmed. Clearing it up front left the app unprotected if the parent
  backed out.

## Privacy

The child is the subject, so the default is to collect and transmit as little as
possible about them.

- **Nothing leaves the device unless sync is switched on.** Even then, only what
  a specific feature needs is sent, encrypted before it leaves: requests and
  their answers (minutes, target, the reason the child wrote, yes or no), daily
  usage totals (not individual app sessions), the installed-app list (a name and
  package id only, so a parent's rule screen can offer it — never usage or an
  icon), which domains were visited today (counted, never a browsing history),
  and a position **only** when a parent explicitly asks or during an SOS —
  never a continuous stream, and never for a contextual "at school" style rule,
  which only ever learns which saved place the phone is inside, not a
  coordinate (see [Contextual rules](#contextual-rules)). The one coordinate
  that travels the other way is one a parent typed themselves, to name a place
  for somewhere the managed phone is not currently standing — it becomes an
  ordinary saved place on that phone and, like every other saved place, never
  replicates back out as a coordinate afterward.
- **The diagnostic log records what the app did, never what the child did.** Event
  names, counts and durations only: no package names, no reasons, no prize text,
  no session times. A test asserts the shape of every rendered line, so a later
  change that interpolates content fails the build.
- **The child can read all of it.** "What leaves this phone" and the log itself
  are reachable from the home screen without the parent PIN. A privacy page the
  monitored person cannot open is marketing, not transparency.
- **No analytics, no crash reporting, no third-party SDKs.** The only network
  destination is the family's own topic.

Known gap: clearing the app's data from Settings resets the PIN and the rules.
Closing that requires device owner plus `DISALLOW_APPS_CONTROL`, which is part of
the tamper-protection switch rather than something the app can do on its own.

## Adding a language

The app ships English and Swedish, and picks its language from the in-app
selector on the home screen (Android stores the choice per app, so it survives
restarts).

To add one:

1. Add a `<locale>` line to `res/xml/locales_config.xml`.
2. Add `res/values-xx/strings.xml`.

Nothing in Kotlin changes — the picker reads the supported list back at runtime
and names each language in itself. Lint treats a missing translation as a build
error, so a half-finished language cannot ship by accident.

## Where the configuration lives

| What | Where |
| --- | --- |
| Product behaviour — day boundary, default limits, warning thresholds, history retention | `core-model/.../model/Defaults.kt` |
| Per-family behaviour that varies — budgets, categories, bedtime, warning thresholds | the `Policy` object itself |
| Spacing and component sizes | `app-child/res/values/dimens.xml` |
| The type scale, the theme, card styling | `app-child/res/values/styles.xml` |
| Colours we draw ourselves | `app-child/res/values/colors.xml` (+ `values-night/`) |
| UI tuning — list caps, week length, bonus amounts | `app-child/res/values/config.xml` |
| All user-visible text | `app-child/res/values/strings.xml` |

There should be no bare dimension, text size, tuning number or user-visible
string anywhere in Kotlin or layout XML. If you need one, add it to the right file above and reference it.

Every colour name in `values/colors.xml` must also exist in `values-night/`, or
the app crashes at inflate time in dark mode.

## Conventions

- Pure modules compile with `allWarningsAsErrors`.
- Comments explain *why*, not *what* — particularly where a decision looks
  arbitrary but is protecting against a specific failure.
- Every safety-relevant behaviour has a test named after the behaviour, not the
  method.

## Publishing

Everything the Play Console asks for lives in `play/`, and the order to do it in
is in [`docs/RELEASING.md`](docs/RELEASING.md).

| What | Where |
| --- | --- |
| Signing the release, building the bundle, the on-device pass | `docs/RELEASING.md` |
| Store text, both languages, with a length checker | `play/listing-*.md`, `play/check_listing.py` |
| Icon and feature graphic, and the script that draws them | `play/*.png`, `play/make_graphics.py` |
| Data safety form answers, with the code each one comes from | `play/data-safety.md` |
| Sensitive-permission declaration texts | `play/declarations.md` |
| The privacy policy, to be hosted somewhere public | `play/privacy-policy.html` |

The release build is minified; `app-child/proguard-rules.pro` says what is kept
and why. It has to be run on a device before it goes anywhere — that is the one
build difference that can break something the debug build did fine.

Release signing reads `keystore.properties` from the repository root. The file is
not in the repository and never should be; a release build without it fails with
an explanation rather than quietly producing something Play will reject.
