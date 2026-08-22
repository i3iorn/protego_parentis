# Dementor Watch

A parental control app for Android, aimed at 11–16 year olds. It measures screen
time, enforces daily limits, category limits, per-app limits and bedtime, and
shows the teenager exactly the same numbers their parent sees.

Enforcement is done through Android's **Device Owner** APIs, so blocked apps are
suspended by the operating system rather than covered by an overlay.

Full product spec: <https://claude.ai/code/artifact/049658ba-b93f-4733-a1e9-bfcfa45ce64c>

---

## Modules

| Module | Kind | What lives here |
| --- | --- | --- |
| `core-model` | Pure Kotlin | Domain types: `Policy`, `Category`, `WeeklyBudget`, `Grant`, `Enforcement`. No Android, no I/O. |
| `core-policy` | Pure Kotlin | All the real logic: `PolicyEngine` (what should be blocked), `UsageReplay` (how much time was spent), `PolicyReconciler` (apply it), `DeviceReleaser` (undo it). |
| `core-provisioning` | Pure Kotlin | The setup QR payload. Kept Android-free so the parent app can generate codes without a device SDK. |
| `app-child` | Android app | The app that runs on the teenager's phone. Reads usage, drives `DevicePolicyManager`, hosts the UI. |

The rule of thumb: **decisions belong in `core-policy`, translation belongs in
`app-child`.** Anything in the Android module that looks like a policy decision is
in the wrong place — that separation is what lets 148 tests run without a phone.

---

## Building

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

In Android Studio, select the **app-child** run configuration. If it is missing,
use *File → Sync Project with Gradle Files*.

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

There are two ways to become device owner.

### The real path

Factory reset, then scan a provisioning QR code during setup. The payload is built
by `ProvisioningPayload` in `core-provisioning`. This is what ships to families.

### The development path

No factory reset needed, but Android imposes two conditions that catch everyone:

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
adb shell dpm set-device-owner dev.schrammel.dementor_watch/.dpc.DementorDeviceAdminReceiver
```

Accounts can be added back immediately afterwards — the restriction only applies
at the moment ownership is set.

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

148 unit tests, all on the JVM, no device required. The bulk sit in `core-policy`
and cover the things that are easy to get quietly wrong:

- day boundaries at 04:00, so an 23:50 session is not split across two days
- bedtime windows that cross midnight
- conflicting rules — the reason shown is the one that lifts *last*
- an app in several categories spending from each of them
- the safety allowlist surviving every possible restriction
- screen-off ending open sessions, so a face-down phone accrues nothing

There are **no instrumentation tests** yet. Every `DevicePolicyManager` interaction
is exercised through a fake (`FakeDevicePolicyPort`), which proves the logic but
not the platform integration. Real enforcement on a provisioned handset is the
largest untested area in the project.

---

## Current state

Working: usage measurement, device/category/per-app limits, bedtime, exempt apps,
bonus time, the warning ladder, PIN-protected rules, the teen dashboard, device
owner enforcement and release.

Not built yet:

- **No persistence.** Usage is read fresh from Android each time, and Android only
  keeps a few days. No history, no trends, no weekly digest.
- **No server and no parent app.** Rules are edited on the same phone that enforces
  them. `LocalPolicyStore` is a stand-in for Room plus sync.
- **No request/approval loop** between child and parent.
- **No web filtering or location.**

## Conventions

- Pure modules compile with `allWarningsAsErrors`.
- Comments explain *why*, not *what* — particularly where a decision looks
  arbitrary but is protecting against a specific failure.
- Every safety-relevant behaviour has a test named after the behaviour, not the
  method.
