# Screenshots

Empty on purpose. Play requires at least two phone screenshots per listing and
they have to be of the real app running on a real device — which has not happened
yet. There is nothing here that could be generated instead, and a mock-up would be
both a policy problem and a lie about a product nobody has seen work.

Take them while doing the on-device pass in [`docs/RELEASING.md`](../../docs/RELEASING.md):

```bash
adb exec-out screencap -p > play/screenshots/01-child-home.png
```

## The set to take

Ordered so the listing tells the story in the order it matters, with the
transparency screen high up — it is the thing that distinguishes this app from
everything else in the category, and it should not be the fifth thing anyone sees.

| File | Screen | Why it is in the set |
|---|---|---|
| `01-child-home.png` | The child's dashboard | The first screen, and it answers the question people open the app for |
| `02-privacy.png` | What leaves this phone | The argument for the whole product |
| `03-request.png` | Asking for more time | The everyday interaction |
| `04-parent-home.png` | The parent's home, a request waiting | The other half of the family |
| `05-audit.png` | The shared history | Both sides read the same list |

## Requirements

- PNG or JPEG, 16:9 or 9:16, each side between 320 px and 3840 px.
- No device frame, no added marketing text, no border. Play adds its own framing
  and rejects screenshots that fake a device.
- Use a phone with no personal data on it. Real app names and real times are fine
  and better than empty states — but a published screenshot cannot be recalled
  from the people who have already seen it.
