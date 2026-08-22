# Action catalogue

Companion to `docs/triggers.md`. Same shape: what is built, what is next, and
what turns out not to be possible.

**Read the corrections section.** Several actions that were straightforward when
automation apps like Tasker were written have since been closed off. Any plan
based on a pre-2019 mental model of Android will over-promise.

---

## Cross-cutting blocker: background activity starts

Since Android 10, an app in the background **may not start an activity** unless
it holds one of a short list of exemptions — a visible foreground service, the
overlay permission (`SYSTEM_ALERT_WINDOW`), or a notification the user just
tapped.

There is no error. The system drops the start and logs a line the app never
sees, so the action reports success and nothing happens. This affects every
action that opens something: `open_url`, `open_app`, `compose_email`,
`compose_sms`, `set_alarm`, `add_calendar_event`.

The foreground service the engine needs anyway is one of the qualifying
exemptions, which makes it the fix for this too. Until then, treat "open"
actions as working when the phone is in use and unreliable when it is not.

---

## Implemented

| Action | Type string | Requirement |
|---|---|---|
| Post a notification | `post_notification` | `POST_NOTIFICATIONS` (API 33+) |
| Cancel own notifications | `cancel_notification` | — |
| Show a toast | `toast` | — (suppressed in background from API 12) |
| Speak text aloud | `speak` | — |
| Vibrate | `vibrate` | `VIBRATE` (install-time) |
| Play an alert sound | `play_alert` | — (storage access only for a `file:` custom sound) |
| Open a website | `open_url` | — (background-start caveat) |
| Open an app | `open_app` | — (see package visibility below) |
| Compose an email | `compose_email` | — (user confirms) |
| Compose an SMS | `compose_sms` | — (user confirms) |
| Set an alarm | `set_alarm` | — |
| Add a calendar event | `add_calendar_event` | — (user confirms) |
| Set stream volume | `set_volume` | — (silencing needs DND access) |
| Set ringer mode | `set_ringer_mode` | Do Not Disturb access |
| Copy text to clipboard | `set_clipboard` | — |
| HTTP request | `http_request` | `INTERNET` (install-time) |
| Dismiss another app's notification | `dismiss_notification` | Notification access |
| Press a notification's button | `notification_button` | Notification access |
| Set Do Not Disturb | `set_dnd` | Do Not Disturb access |

Design lines held deliberately:

- **Compose, never send.** The email and SMS actions open the user's own app
  with the fields filled in. Sending silently needs the Play-restricted
  `SEND_SMS`, and an automation app that can send messages without confirmation
  is a different and more dangerous product than this one.
- **`open_url` accepts only http and https.** A rule config can come from an
  import or a shared recipe; `ACTION_VIEW` on a `file:` or custom scheme would
  turn "open a website" into an arbitrary-intent primitive.
- **`http_request` is https-only** for the same reason — a webhook URL usually
  carries a token in it.
- **`vibrate` is capped at 10 seconds.** A config typo of 30000 for 300 is
  otherwise unstoppable short of killing the app.
- **`play_alert` is capped at 60 seconds**, for the same reason and more
  urgently: the tone loops, so the failure mode is a phone alarming in a meeting
  with no in-app stop button. Disabling the rule cancels a running alert, which
  is why the action suspends for its duration instead of firing and forgetting.
- **`play_alert` custom sounds are `content:`/`file:` only.** A remote sound URI
  in an imported rule would be a beacon: it would report to a stranger's server
  every time the rule fired. Same reasoning as https-only `http_request`.

### Why `play_alert` exists next to `post_notification`

A notification's sound is at the mercy of its channel, and the user's ringer.
`play_alert` plays on the **alarm** stream, which is the one an average silenced
phone still lets through, and it keeps sounding for a set duration rather than
playing one short tone — the difference between "a beep" and "an alarm until I
look at the phone" is a number, not a different action.

It is not absolute, and the UI says so: it cannot exceed the alarm volume the
user has set (raising that needs Do Not Disturb access, too high a price for a
sound effect), and Do Not Disturb still silences it unless alarms are allowed
through, which is the common default.

Paired with the `notification_posted` trigger's `package` and `textContains`
filters, this covers the "alert me loudly when a specific app or keyword shows
up" job that dedicated apps like Alertify exist for.

`open_app` has a caveat worth resolving: on API 30+, package visibility rules
mean `getLaunchIntentForPackage` returns null for apps not declared in
`<queries>`, which cannot be done for a package the *user* picks at runtime. The
options are `QUERY_ALL_PACKAGES` (Play-restricted) or building the app picker on
the system's own chooser. It currently fails with a clear message rather than
doing nothing.

---

## Corrections to the Tier 1 list

These were listed as easy, standard-SDK, no-permission. They are not, and no
amount of implementation effort changes it.

| Listed as easy | Reality |
|---|---|
| **Wi-Fi toggle** | `setWifiEnabled` is a no-op for third-party apps from **Android 10**. The most an app can do is open the Wi-Fi settings panel and let the user flip it. |
| **Bluetooth toggle** | `BluetoothAdapter.enable()/disable()` is a no-op from **Android 13**. Same story: send the user to settings. |
| **Airplane mode toggle** | System-app only since **Android 4.2**. Never coming back. |
| **Hotspot toggle** | Restricted to system and carrier apps since API 26. `LocalOnlyHotspot` exists but creates a private hotspot with no internet sharing — not what users mean. |
| **NFC toggle** | No public API at all. Settings intent only. |
| **VPN toggle** | An app can run *its own* `VpnService`; it cannot toggle someone else's VPN. |
| **Brightness / screen timeout** | Both need `WRITE_SETTINGS`, which the same list correctly puts in Tier 2. Per-window brightness (this app's own screen) is free; system brightness is not. |
| **Share last photo** | Needs `READ_MEDIA_IMAGES` (API 33+) or `READ_EXTERNAL_STORAGE` below that. |
| **Show dialog** | From the background this needs `SYSTEM_ALERT_WINDOW` — the same overlay permission the list puts in Tier 2. A toast is the no-permission option, and is itself suppressed in the background from Android 12. |
| **Clipboard read** | Foreground, default-IME or accessibility only, from Android 10. Writing is unrestricted; reading effectively is not available. |
| **Generate QR code** | Needs a dependency (ZXing) or a hand-rolled encoder. Not hard, but not free either. |

The honest version of "toggle Wi-Fi" for a modern automation app is **open the
relevant settings panel** (`Settings.Panel.ACTION_WIFI` on API 29+, which shows
an inline panel rather than leaving the app). Worth building as
`open_settings_panel`, and worth being upfront in the UI that it prompts rather
than toggles.

---

## Not an action: variables, conditions, loops

"All variable / if / loop / repeat logic" is not an action type — it is a
change to what a `Rule` *is*. Today a rule is a trigger plus a flat list of
actions; conditions and control flow mean an execution model, a value type, and
somewhere to store state between runs.

That is the largest single design decision left in the project, and it wants its
own document rather than a bullet in a catalogue. The two ends of the range:

- **Conditions only** — an optional predicate on a rule, evaluated against the
  trigger payload, plus `TriggerEvent` values usable in action config
  (`{{battery.level}}`). Small, covers most real automations, no execution model
  needed.
- **A scripting model** — variables, branching, loops. Powerful, and it turns
  Trigly into a language runtime with everything that implies for persistence,
  debugging and safety.

Recommend starting with conditions and payload substitution, and only moving
further if real rules demand it.

---

## Tier 2 — remaining

### Accessibility service
The service already exists for triggers; these are the *action* half.
`dispatchGesture` simulates taps and swipes; `performGlobalAction` covers back,
home, recents, notification shade, and screen lock;
`AccessibilityNodeInfo.performAction` clicks a found node.

Same Play policy caveat as the triggers: Google restricts accessibility-API use
to genuine accessibility purposes. Simulating input is the most likely thing to
draw scrutiny. Distribution decision first.

*Screen unlock* is not available at all — `GLOBAL_ACTION_LOCK_SCREEN` locks; there
is no unlock. A keyguard dismissal only works from a visible activity via
`KeyguardManager.requestDismissKeyguard`, with the user authenticating.

### ~~Notification listener actions~~ — done

Worth recording how these are wired, because the next action that needs a
service will follow the same path.

Dismissing another app's notification needs the listener service, which lives in
`:triggers` — but the action lives in `:actions`, and `:actions` must not depend
on `:triggers`. So `:core` declares a `NotificationController` port, `:triggers`
implements it over the live service, and `:ui` wires the two. The same shape as
the factory lists handed to `Registry`.

Two details that matter:

- The controller resolves the service on **every call** rather than caching it.
  The system unbinds and rebinds a listener freely — on app update, on low
  memory, when the user toggles access — and a controller that captured the
  first instance would go quietly dead.
- Both actions default to the notification that *fired the rule*, taking its key
  from the trigger payload. That is the common case ("when my bank notifies me,
  dismiss it") and the only way it can work, since the key is generated by the
  posting app and cannot be configured in advance. The payload key is named in
  `core`'s `SharedPayloadKeys` because it is a contract between two modules that
  cannot see each other.

Buttons are addressed by index, not label: labels are localised and change
between app versions. The trade-off is that a rule breaks silently if an app
reorders its buttons, so the failure message reports how many buttons the
notification actually has.

`set_dnd` deliberately does *not* go through the port — it needs notification
*policy* access, a different grant from *listener* access, and works through
`NotificationManager` with no service bound.

### MediaProjection — screenshot, pixel colour
`MediaProjectionManager` returns a token only from an activity result, and from
Android 14 the user must re-consent for **every** capture session. A rule that
silently screenshots is therefore not possible by design, which likely rules out
the pixel-colour use case entirely. Verify the consent flow before promising
anything.

### WRITE_SETTINGS — auto-rotate, brightness, timeout
Granted at `ACTION_MANAGE_WRITE_SETTINGS`, so another `SpecialAccessKind`.
Covers `ACCELEROMETER_ROTATION`, `SCREEN_BRIGHTNESS`, `SCREEN_OFF_TIMEOUT`.
Straightforward once the kind is added.

### Camera2 — silent capture
`CAMERA` permission plus a background capture session with no preview. Legal and
technically possible; note that many jurisdictions require a shutter sound and
some OEM firmwares enforce it regardless of app settings.

### SYSTEM_ALERT_WINDOW — overlay popup
Granted at `ACTION_MANAGE_OVERLAY_PERMISSION`. Worth building for its own sake
*and* because holding it is one of the background-activity-start exemptions,
which would fix the blocker at the top of this document for users who grant it.

### Default dialer role — answer, end, reject, screen calls
`RoleManager.ROLE_DIALER` or `ROLE_CALL_SCREENING`. Becoming the *default phone
app* is a large commitment: the app must then implement the whole dialer
experience, not just the automation hook. `ROLE_CALL_SCREENING` is the narrower
and far more realistic option for rejecting calls.

---

## Suggested order

1. `WRITE_SETTINGS` kind, then auto-rotate and brightness.
2. `open_settings_panel`, replacing the toggles that are no longer possible.
3. Overlay permission — a real action, and it unblocks background activity starts.
4. Conditions and payload substitution, per the design note above.
5. Accessibility actions and call roles, after the distribution decision.
