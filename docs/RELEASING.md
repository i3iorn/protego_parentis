# Releasing

How to turn this repository into something Play will accept. Read the whole file
before running the first command — step 1 creates a file that cannot be replaced
if it is lost.

---

## 1. Create the upload keystore — once, ever

The key that signs an upload is how Play knows a new version came from you. Google
holds the *app signing* key under Play App Signing; you hold the *upload* key. If
you lose it, recovery means asking Google to reset it, and if you lose it while
also having declined Play App Signing, the app can never be updated again by
anyone.

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Answer the prompts, and choose a password you have not used elsewhere. Then:

- Put `upload-keystore.jks` somewhere backed up that is **not** this repository.
  `.gitignore` already refuses `*.jks`, `*.keystore` and `keystore.properties`, so
  a mistake here fails safe — but the file still should not live in the working
  tree.
- Store the password in a password manager. Not in a note, and not in this repo.

Then create `keystore.properties` at the repository root:

```properties
storeFile=../secrets/upload-keystore.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

`storeFile` is resolved relative to the repository root. The build reads this file
if it exists and refuses to produce a release artifact if it does not — see
`app-child/build.gradle.kts`.

---

## 2. Build the bundle

Play takes an Android App Bundle, not an APK.

```bash
./gradlew clean build bundleRelease
```

`build` first, deliberately: it runs every unit test and Android Lint, and a
release that skips them is a release nobody checked. The bundle lands at
`app-child/build/outputs/bundle/release/app-child-release.aab`.

Keep the mapping file that comes with it —
`app-child/build/outputs/mapping/release/mapping.txt`. Play accepts it alongside
the bundle and it is the only thing that turns an obfuscated crash report back
into names.

---

## 3. Install the release build on a real device first

**This is not optional and it is not the same as the debug build.** The release
build is minified and obfuscated by R8, and anything the platform reaches by name
rather than by call is invisible to it. `app-child/proguard-rules.pro` lists every
such case that is known; a case that is *not* known shows up as a crash on a real
device and nowhere else.

```bash
./gradlew :app-child:assembleRelease
adb install -r app-child/build/outputs/apk/release/app-child-release.apk
```

Then walk the whole app, both roles. At minimum:

- Device Owner provisioning. **QR provisioning is not built yet** — see the
  README's "Current state" — so use `adb shell dpm set-device-owner` instead
  until it is. Do not submit to Play claiming QR provisioning works; either
  finish it first or describe the actual setup path in the listing.
- Usage access granted; screen time actually accumulating.
- An app being suspended when the allowance runs out, and restored.
- A request raised on the child device and answered on the parent device.
- Lock now, and a locate — including the notification on the child device.
- A message sent each way.
- Boot the device and confirm enforcement resumes.
- **Release this phone**, and confirm every restriction lifts.

Nothing in this list has ever been exercised on hardware. Budget real time for it.

---

## 4. Store listing

Everything to paste in is in `play/`:

| File | Goes where |
|---|---|
| `play/listing-en.md` | Main store listing |
| `play/listing-sv.md` | Swedish store listing |
| `play/icon-512.png` | App icon |
| `play/feature-graphic-1024x500.png` | Feature graphic |
| `play/data-safety.md` | Data safety form |
| `play/declarations.md` | Sensitive-permission declaration forms |
| `play/privacy-policy.html` | Host it, then paste the URL |

Regenerate the two graphics with `python play/make_graphics.py` if the icon
changes.

### Screenshots — you have to take these

Play requires at least two phone screenshots per listing, and they must be of the
real app. Take them on the device you tested on:

```bash
adb exec-out screencap -p > play/screenshots/01-child-home.png
```

Suggested set, in the order they tell the story:

1. The child's dashboard, with time remaining.
2. Asking for more time.
3. The parent's home, with a request waiting.
4. **What leaves this phone** — the transparency screen, which is the thing that
   makes this app different from its competitors.
5. The shared history.

Use a device with no personal data on it. A screenshot is published to the world
and cannot be recalled from the people who already downloaded the app.

---

## 5. The privacy policy needs a URL

`play/privacy-policy.html` has `{{PLACEHOLDER}}` markers — developer name, contact
address, date. Fill them, then host the file somewhere public and stable. GitHub
Pages on this repository is enough, and has the advantage that the policy is
versioned with the code it describes.

The URL has to keep working for as long as the app is listed.

---

## 6. Release order

1. **Internal testing** first, always. Up to 100 testers, available within
   minutes, and the only way to find out whether the release build works at all.
2. **Closed testing** with a handful of real families. Play now requires a period
   of closed testing before a personal developer account can go to production —
   check the current requirement in the Console, it has changed more than once.
3. **Production.**

Do not skip to production because the debug build worked. The list in step 3 is
the reason.

---

## Version numbering

`versionCode` must increase with every upload, without exception — Play rejects a
repeat and there is no way to reuse one. `versionName` is what people see.

Both live in `app-child/build.gradle.kts`.

---

## OTA updates, without Play

Play App Signing is not involved here: Google re-signs an upload with a key it
holds, so the *upload* key above can be rotated without breaking anyone's
installed copy. Sideloaded from GitHub Releases, there is no such intermediary
— the APK a phone downloads has to be signed with **the exact same key** as
whatever it already has installed, forever, or Android's installer refuses it
outright, silently or not. That makes the keystore from step 1 above
irreplaceable the moment the first OTA release goes out, in a way it is not
for a Play-only app: losing it does not just cost you a support ticket to
Google, it means every phone already running this app can never be updated
again short of uninstalling and re-provisioning it from scratch.

### How it works

- `app-child`'s `update` package (`UpdateManifest`, `UpdateClient`,
  `UpdateChecker`, `ApkInstaller`) checks once a day
  (`UpdateCheckWorker`), and on demand from the diagnostics screen, for a
  newer release — see each class's own doc comment for the mechanics.
- The managed phone applies it unattended, through the same device-owner
  `PackageInstaller` privilege that already lets it suspend and restore apps
  without asking (Android 6+, see the [F-Droid client's own device-owner
  install work](https://gitlab.com/fdroid/fdroidclient/-/merge_requests/258)
  for prior art). A parent's own, unmanaged phone downloads and verifies the
  same APK but has no such privilege, so it notifies instead and waits for a
  tap.
- `PackageInstaller` replaces the APK on disk; it does nothing to the process
  already running from the old one. `UpdateInstallReceiver` restarts it the
  moment a silent install succeeds — the same clean-task-then-`killProcess`
  sequence `CrashRecovery` already uses for an uncaught exception — because
  the alternative is a phone that installed a newer version and kept right on
  showing the old one until it happened to be rebooted for some unrelated
  reason, possibly days later.
- `.github/workflows/release.yml` is the publisher: every push of a tag
  matching `v*` builds the signed release APK (reusing the same
  `keystore.properties` mechanism as the Play path above, provided as CI
  secrets instead of a local file), computes its SHA-256, and publishes both
  the APK and a small `update.json` manifest as assets on a GitHub Release
  for that tag. `UpdateClient` fetches both from GitHub's
  `/releases/latest/download/<name>` URLs — plain static-file downloads, not
  the REST API, so there is no rate limit regardless of how many family
  phones check.

### One-time setup

1. Do step 1 above if you have not already — the same keystore signs both the
   Play upload and every OTA release; there is only ever one.
2. Base64-encode the keystore file and add four repository secrets under
   **Settings → Secrets and variables → Actions**:

   ```bash
   base64 -w0 upload-keystore.jks
   ```

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | The output of the command above |
   | `RELEASE_KEYSTORE_PASSWORD` | The keystore password from step 1 |
   | `RELEASE_KEY_ALIAS` | `upload`, if you followed step 1 as written |
   | `RELEASE_KEY_PASSWORD` | The key password from step 1 |

3. Push a tag to cut the first OTA release:

   ```bash
   git tag v1.1.2
   git push origin v1.1.2
   ```

   The workflow builds, signs, and publishes it. Nothing installed anywhere
   updates itself until this has happened at least once — `UpdateClient`
   fails closed (a failed check, logged and retried the next day) rather than
   assuming a release exists.

### What this does not do

- **No rollback.** `UpdateClient` only ever compares against
  `versionCode` and only ever moves forward; there is no path back to an
  older release short of manually reinstalling one.
- **No staged rollout.** Every phone checking after a tag is published sees
  the same release at the same time — there is no percentage-based ramp the
  way Play's own release tracks offer.
- **Real device-owner silent installation has never been exercised on
  hardware** — see the README's own "Current state" section for the same
  caveat about every other `DevicePolicyManager` interaction. Test a real OTA
  update on a provisioned handset before trusting it with a family's phone.
