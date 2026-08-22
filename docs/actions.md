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

### Notification listener actions
The listener service also exists already. `cancelNotification(key)` dismisses
another app's notification, and a posted `Notification.Action` carries a
`PendingIntent` that can be fired — that is the "notification-line button"
action. `setInterruptionFilter` changes DND and needs notification-policy
access, which is now modelled as `SpecialAccessKind.NOTIFICATION_POLICY`.

**Cheapest remaining Tier 2 work** — the service is built, the requirement kind
exists, and these are a few dozen lines each.

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

1. Notification-listener actions — service exists, requirement kind exists.
2. `WRITE_SETTINGS` kind, then auto-rotate and brightness.
3. `open_settings_panel`, replacing the toggles that are no longer possible.
4. Overlay permission — a real action, and it unblocks background activity starts.
5. Conditions and payload substitution, per the design note above.
6. Accessibility actions and call roles, after the distribution decision.
