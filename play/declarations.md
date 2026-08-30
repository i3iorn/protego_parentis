# Sensitive-permission declarations

Text to paste into the Play Console forms, one section per declaration. Each is
written to answer the question the reviewer is actually asking, which is always
some version of *"is this permission doing work the app's core purpose needs, and
would a narrower permission have done?"*

Ordered by how likely each is to hold the release up.

---

## 1. `QUERY_ALL_PACKAGES` — the biggest rejection risk

**Read this before submitting.** Google restricts this permission to a documented
list of permitted uses, and the list is revised. **Confirm that the current policy
page still names parental control / device management as a permitted use before
relying on it.** If it does not, this app cannot ship with the permission as-is —
see the fallback below, which is real engineering work and not a form entry.

Declaration text:

> Dementor Watch is a family screen-time and parental control app. It is set up by
> a parent on a young person's device, where it operates as a Device Owner.
>
> Its core function is measuring and limiting time spent in apps. To do that it
> must enumerate every launchable app installed on the device: to show the family
> the complete list, to let a parent set a limit or a block on any individual app,
> to group apps into categories with shared allowances, and to notice when a new
> app appears so it can be reviewed.
>
> A partial list would silently fail at the app's purpose — an app the parental
> control app cannot see is an app with no limit on it, and neither parent nor
> child would be told it had been missed.
>
> The list is read on the device and used on the device. It is never transmitted:
> what leaves the phone is a daily summary naming at most the five most-used apps,
> which the young person can see on their own device in the same detail.
>
> A `<queries>` element cannot substitute here, because the set of apps to be
> limited is not known in advance — it is whatever the young person installs.

**Fallback if the declaration is refused.** Replace `QUERY_ALL_PACKAGES` with a
`<queries>` element containing `<intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent>`.
That returns launchable apps only, which is very nearly the same set for this
app's purposes. Verify on a device that `InstalledApps` still returns the full
launcher list before shipping it — this is a behaviour change, not a manifest
edit.

---

## 2. `ACCESS_BACKGROUND_LOCATION`

Requires the declaration form **and a video** showing the in-app flow that uses
it, including the runtime permission prompt.

> Location is used for three features, all of them optional and all of them
> visible to the person being located:
>
> 1. **Answering an explicit request.** A parent taps "Where is the phone?" on
>    their own device. The young person's phone takes a single fix and returns it.
>    The young person is notified every single time, without exception, and every
>    request is written into a shared history both sides can read.
> 2. **Arrivals and departures.** The family saves a place ("School"). The app
>    reports that the phone arrived or left — the place name only, never a
>    coordinate.
> 3. **Rules that depend on where the phone is** — for example a different
>    allowance at home than elsewhere. These are evaluated on the device and no
>    coordinate leaves it.
>
> Background access is required because a parent's request and a geofence crossing
> both arrive when the app is not in the foreground. Foreground-only access would
> mean the feature works only when the young person happens to have the app open,
> which for a safety feature is the same as not working.
>
> No location trail is kept anywhere. A parent's device holds one position, the
> most recent, with no history behind it. Leaving the family erases it.

**Video to record** — one continuous screen recording, no cuts:
1. Parent device: open the app, tap **Where is the phone?**
2. Child device: the notification "Somebody asked where you are" appears.
3. Child device: show the runtime location prompt being granted, including the
   "Allow all the time" step in Android settings.
4. Parent device: the position appears.
5. Child device: open **History** and show the request recorded there.

---

## 3. Foreground service — `specialUse` and `location`

`EnforcementService` declares `android:foregroundServiceType="specialUse|location"`.
The manifest already carries the required subtype property. Play asks for a
justification for `specialUse` specifically.

> The service applies the family's screen-time rules continuously: it measures
> usage, warns before an allowance runs out, suspends apps when it does, and
> restores them when a schedule or a parent's approval says so. This has to keep
> running while the young person is using other apps, which is precisely when it
> matters.
>
> No existing foreground service type describes device-policy enforcement. The
> closest candidates were considered and rejected: `dataSync` is wrong because the
> work is local rule evaluation, not synchronisation; `systemExempted` is not
> available to a non-system app.
>
> The `location` type is declared alongside because the same service takes the
> single position that answers a parent's explicit request.

---

## 4. VpnService

> The VPN interface is used to withhold internet access from specific apps the
> family has chosen to restrict, on a device where the app is the Device Owner.
>
> It routes nothing. No server is contacted, no traffic is forwarded, inspected,
> logged or modified. Traffic from a restricted app is dropped on the device; every
> other app is added to the allow list and bypasses the interface entirely.
>
> The interface is only established when at least one app has network restriction
> switched on, and the young person can see which apps those are in the app.

Play policy also requires that a VpnService app: declares it in the Console, does
not manipulate ads, and has a privacy policy — all satisfied.

---

## 5. Device administration / Device Owner

Not a permission-declaration form, but reviewers will ask what the device-admin
receiver is for, and DPC apps get extra scrutiny.

> **Do not paste the paragraph below into the Console yet.** It describes the
> intended, shipped-product provisioning flow — QR-code device-owner
> provisioning — which is not built. See the README's "Current state" for what
> is missing (the QR-generation screen, APK hosting, and the checksum wiring).
> Submitting this declaration before that flow exists would describe a setup
> path a real family cannot use. Either finish it first, or rewrite this
> paragraph to describe `adb shell dpm set-device-owner`, which is not a
> workable instruction to hand a non-technical parent and should not ship as
> the real answer.

> The app is provisioned as a Device Owner by a parent, on a device dedicated to a
> young person, using Android's standard QR-code provisioning. Device Owner is used
> to suspend and restore apps, lock the screen when a parent asks, set a private
> DNS host for family-wide web filtering, gate app installs, and prevent the app
> being uninstalled or the rules bypassed.
>
> The app can be released from control at any time from within it — "Release this
> phone", behind the parent PIN — which lifts every restriction and gives up
> Device Owner.

---

## 6. `PACKAGE_USAGE_STATS`

An appop, granted by the user in Android Settings rather than by a runtime prompt.
No Console form, but the listing and the in-app prompt should both be plain about
it. The app's own wording already is ("Dementor Watch needs usage access to
measure screen time").

---

## 7. Target audience and Families policy — decide before you submit

This app is **used by** a minor and **set up and controlled by** an adult. How the
Console's target-audience question is answered determines whether the Families
policy and its extra requirements apply, and it is not obvious which answer is
right.

This is a judgement call with real consequences either way, and it should be made
deliberately rather than by clicking through the questionnaire. Read the current
Families policy and the parental-control guidance in the Console before answering,
and be ready to justify the answer — a target-audience declaration that does not
match how the app is plainly used is treated as a misrepresentation, not a
mistake.
