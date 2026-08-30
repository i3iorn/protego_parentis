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
