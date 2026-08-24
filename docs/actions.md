# Action catalogue

Companion to `docs/triggers.md`. Same shape: what is built, what is next, and
what turns out not to be possible.

**Read the corrections section.** Several actions that were straightforward when
automation apps like Tasker were written have since been closed off. Any plan
based on a pre-2019 mental model of Android will over-promise.

---

## Cross-cutting blocker: background activity starts

Since Android 10, an app in the background **may not start an activity** unless
it holds one of a short list of exemptions — a visible window, being the current
input method, the overlay permission (`SYSTEM_ALERT_WINDOW`), or a
`PendingIntent` the system itself sent, which is what a notification tap is.

There is no error. The system drops the start and logs a line the app never
sees, so the action reports success and nothing happens. This affects every
action that opens something: `open_url`, `open_app`, `compose_email`,
`compose_sms`, `set_alarm`, `add_calendar_event`.

**Running a foreground service is not one of the exemptions.** This document
used to say it was, and it is worth correcting rather than quietly deleting,
because it is a natural guess and now a tempting one: the engine *does* run in a
foreground service (`EngineService`, see `docs/architecture.md`), so it looks
like the blocker should have lifted with it. It did not. A foreground service
buys the *process* the right to stay alive; it buys the app no right to put
something on the user's screen unasked, and Google has kept those two questions
separate on purpose. Measured rather than reasoned about — the system says so
itself, with the engine's service running and nothing else changed:

    Background activity launch blocked!  callingUidProcState: FOREGROUND_SERVICE
    Abort background activity starts from 10209                    →  BAL_BLOCK

### The fix: the overlay permission

`SYSTEM_ALERT_WINDOW` — "Display over other apps" — **is** on the exemption
list, and holding it is enough. No overlay has to be drawn, which is what makes
it usable here: Trigly asks for a window-drawing permission and draws nothing,
purely for the side effect on activity starts.

The same rule, from the same truly-background state, with the permission
granted:

    START … pkg=com.android.settings from uid 10209 (BAL_ALLOW_SAW_PERMISSION)
    result code=0

Worth knowing how that was measured, because it is easy to fool yourself. An app
that recently had a visible window keeps activity-start privileges for a grace
period of roughly half a minute, and the system reports those launches as
`BAL_ALLOW_VISIBLE_WINDOW` — so a test that backgrounds the app and fires
immediately succeeds either way and proves nothing. The measurement above was
taken with the activity *finished* rather than merely backgrounded, well past the
grace period. Android 15 adds a visibility condition to some overlay-based
exemptions; it does not apply to this one, which is why the API 35 result is
quoted rather than inferred.

How it is wired: `SpecialAccessKind.OVERLAY`, declared through
`ACTIVITY_START_REQUIREMENTS` by every action that calls `launchForRule`, so the
list of "actions that open something" is one fact in one place and a new one gets
it by construction. `RequirementChecker` reads it with `Settings.canDrawOverlays`
— its own API rather than a secure setting or an app-op, which is exactly why
that enum carries a kind and not just an intent. The permission is declared in
`:actions`' manifest but never granted at install time; the rules screen explains
it and offers a Grant button.

That button asks for this app's own row, since `OVERLAY` is the one kind whose
settings screen documents a `package:` URI — but do not promise the user it lands
there. On the API 35 emulator, Settings resolves the intent to
`Settings$OverlaySettingsActivity` and then redirects into its newer `SpaActivity`
implementation, which shows the full list of apps and ignores the URI. The URI is
kept because it is the documented form and does scope the screen on other
implementations; the list is a perfectly usable fallback, and
`RequirementPossibilityTest` covers the failure that would actually hurt — an
intent that resolves nowhere, which would drop the user at the top of Settings.

**Without the permission these actions still work while the phone is in use**,
which is why it is both a requirement *and* a warning: the requirement is
truthful about automation, where the background case is the only case that
matters, and the warning keeps the editor from claiming the action is useless.
The other route, unchanged and still available, is to post a notification and let
the tap start the activity — worse automation, but honest about who decided.

---

## Implemented

| Action | Type string | Requirement |
|---|---|---|
| Post a notification | `post_notification` | `POST_NOTIFICATIONS` (API 33+) |
| Cancel own notifications | `cancel_notification` | — |
| Show a toast | `toast` | — (suppressed in background from API 12) |
| Speak text aloud | `speak` | — |
| Vibrate | `vibrate` | `VIBRATE` (install-time) |
| Play an alert sound | `play_alert` | — (storage access only for a `file:` custom sound; notification access only for "stop when the notification goes away") |
| Open a website | `open_url` | Display over other apps, to work in the background |
| Open an app | `open_app` | Display over other apps, to work in the background (and see package visibility below) |
| Compose an email | `compose_email` | Display over other apps, to work in the background (user confirms) |
| Compose an SMS | `compose_sms` | Display over other apps, to work in the background (user confirms) |
| Set an alarm | `set_alarm` | Display over other apps, to work in the background |
| Add a calendar event | `add_calendar_event` | Display over other apps, to work in the background (user confirms) |
| Set stream volume | `set_volume` | — (silencing needs DND access) |
| Set ringer mode | `set_ringer_mode` | Do Not Disturb access |
| Copy text to clipboard | `set_clipboard` | — |
| Turn a rule on or off | `set_rule_enabled` | — |
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
  urgently: the tone loops, so the failure mode is a phone alarming in a meeting.
  Disabling the rule cancels a running alert, which is why the action suspends
  for its duration instead of firing and forgetting — and it is what makes
  "stop when the notification goes away" possible at all.
- **`play_alert` custom sounds are `content:`/`file:` only.** A remote sound URI
  in an imported rule would be a beacon: it would report to a stranger's server
  every time the rule fired. Same reasoning as https-only `http_request`.

### Pressing a button on a notification that is not the trigger's

`notification_button` used to ask two impossible questions: a notification *key*,
generated by the posting app, and a button *index*, counted from zero into a list
the editor could not show. Both are gone.

A button is now captured off a notification that is on screen. What gets stored is
never the key — a key is regenerated on every post, so a rule pinned to one works
once — but three durable things: which **app**, what the button **says**, and what
it **means**. `Notification.Action.getSemanticAction()` supplies that last one from
API 28: `REPLY`, `ARCHIVE`, `MARK_AS_READ` and the rest, neither translated nor
position-dependent. At fire time `chooseButton` tries meaning, then label, then the
stored index, so a rule survives an app reordering its buttons *or* translating
them, and reports a failure rather than pressing whatever now sits in that
position.

**The target is not always the notification that fired the rule**, and getting
this wrong was a real mistake in an earlier pass: the key field was deleted on the
reasoning that the action "always acts on the notification that fired the rule".
It does not. "When I connect to the car, press play on the music notification" has
a Bluetooth trigger and a media target. So a captured button records its package,
and `chooseNotification` uses it — falling back to the triggering notification when
no package is set, which stays the commoner case.

Two honest limits. **Capturing is live**: `getActiveNotifications` reports only
what is posted right now, there is no history, so the picker says to make the
notification appear and look again rather than pretending to offer a catalogue.
And a **reply button is refused, not pressed**. It carries a `RemoteInput`, and
firing its intent with no text attached does nothing — the picker marks those and
the action fails with a reason, because reporting success for a press that
achieved nothing is the failure this action exists to stop having.

### Rules that switch each other

`set_rule_enabled` is the first action whose subject is Trigly rather than the
device, and it needed no engine machinery at all: `EngineService` collects the
rule store and `TriggerEngine.sync` starts and stops rules against the `enabled`
flag, so **writing the flag is the mechanism**. Starting and stopping was already
the engine's job; this only changes its mind.

What it makes expressible is the class of rules that arm and disarm each other —
a one-shot that fires and turns itself off, a "driving mode" that enables a group
on Bluetooth connect, a guard that disables everything expensive at 10% battery.

The target is stored as the rule's **id**, not its name, and the field is a picker
for the same reason `dismiss_notification` takes an app rather than a key: a name
can be edited, and a reference that broke when someone tidied a title would fail
silently. `ConfigField.RuleRef` shows the name and stores the id. It has no
typed-entry escape hatch, unlike the app picker — an id is a UUID, so there is no
value anyone could usefully type.

Two consequences documented in the action's own warning, because both are
discovered by confusion otherwise:

- **Turning off the rule that is running stops the rest of its actions.** The
  engine cancels the coroutine those actions are running in — that is the same
  cancellation that makes disabling a rule a working stop button for a long
  `play_alert`. So "do the thing, then disable myself" has to put the disable
  last.
- **Two rules that switch each other on will keep doing so.** Nothing inside a
  single action can detect that, and refusing to write when the target is another
  switching rule would break the legitimate arm/disarm pairs this exists for.

Idempotent on purpose: enabling an already-enabled rule writes nothing and
reports success. A write would churn the engine into restarting a rule that was
already running, and a failure would make "make sure this is on" an action that
fails precisely when it has nothing to do. A rule that has since been deleted is
the opposite case and fails loudly, naming the id — silently doing nothing is how
an automation is discovered to be broken months later.

### A button the system does not expose

`NotificationListenerService` + `actionIntent.send()` is the right mechanism, and
it is worth being precise about what it fixes. Four things stop an accessibility
scan finding a notification's buttons:

1. **Wrong window** — the buttons live in a SystemUI window, so
   `getRootInActiveWindow()` never contains them.
2. **Collapsed rows have no button nodes** until expanded.
3. **Custom `RemoteViews`** — an app that builds its own layout may not mark its
   buttons clickable or give them a content description.
4. **Package filtering** — the nodes belong to `com.android.systemui`, not the
   posting app.

Reading `Notification.actions` bypasses 1, 2 and 4 completely. **It does not
bypass 3**, and the difference matters: if the app never created
`Notification.Action`s at all — drawing its buttons inside `RemoteViews` instead —
then `actions` is *empty*. There is no `PendingIntent` anywhere for those buttons.
No amount of cleverness in the listener reaches them, because the thing to send
does not exist. Blitzer.de's "MELDEN" / "BEENDEN" is this case.

So `notification_button` gained an opt-in **"use the screen if the button is not
exposed"**. When the notification offers no matching action, and only when the
rule has asked for it, the action opens the shade, presses the button *by name*,
and closes the shade again — through the accessibility service, which is the only
component that can touch SystemUI's tree.

**Opt-in, and off by default**, because it is worse in every respect: it shows the
shade to the user, it depends on how an OEM lays that shade out, and a custom
layout that labels nothing defeats it too. A rule that did all that without being
asked would be indistinguishable from the phone acting on its own. The refusal
carries both findings — what the notification API looked for *and* what the screen
route said — since "no such button" and "accessibility is not granted" send
someone to different settings.

**It needs the phone unlocked, and refuses early when it is not.** With a secure
lock set and the keyguard showing, `canPressThroughShade` returns false before the
shade is touched. Three independent things would stop it anyway — lock-screen
privacy can redact the notification so the label is never drawn, action buttons are
often not rendered on the keyguard at all, and firing one there demands
authentication — and a rule cannot answer an unlock prompt, since
`KeyguardManager.requestDismissKeyguard` needs an `Activity` and a rule fired by
the engine has none. Opening the shade to fail would leave an unlock prompt on
screen that nobody asked for. A phone with *no* secure lock is still attempted: the
keyguard is a swipe with nothing behind it, and pre-refusing there would report
"locked" about a press that would have worked.

Worth contrasting with the ordinary route, which has none of this: sending a
`PendingIntent` touches no UI and works with the phone locked and in a pocket. The
one caveat there is that a button whose intent starts an *activity* still cannot
come to the foreground past the keyguard — but a broadcast or service intent, which
is what most notification buttons use, completes.

The scan itself handles the other three causes explicitly rather than by retrying:
it iterates `getWindows()` for system windows (1), sends `ACTION_EXPAND` to
candidate rows and searches again (2), and never filters nodes by the target
package — the package only narrows *which row to expand*, and the press target is
always chosen by label (4).

**The clickable-node trap is the part worth knowing.** The node carrying the word
"BEENDEN" is normally a non-clickable `TextView` inside a clickable container:
`performAction(ACTION_CLICK)` on it returns false and does nothing, with no error
— which reads as "the button is broken" rather than "you pressed the wrong node".
`findPressTarget` in `:core` finds the node that *says* the label, then walks up
to the nearest ancestor that will *take* a click. It stops at the nearest one, not
the outermost, because the outermost clickable node in a shade is usually the
notification itself and clicking that opens the app. It refuses rather than
guessing when nothing above the label is clickable, and it requires an exact
label match — "BEENDEN" must not press "BEENDEN UND LÖSCHEN". That logic is pure
and unit-tested against a fake tree, since on a device its failure looks like a
shade opening and nothing happening.

### Choosing which notification to dismiss

`dismiss_notification` had the same mistake as `notification_button`, left
standing after that one was fixed. Its raw-key text box was removed for the right
reason — a key is minted by the posting app and cannot be typed in advance — but
**nothing replaced it**, so the action could only ever dismiss the notification
that fired the rule. "When I leave the house, clear the shopping-list reminder"
was not expressible, and with no field on the block there was nothing to suggest
otherwise. Removing an unusable field is only half the job; the other half is
offering the usable one.

It now selects the way the button action does, through the same
`chooseNotification` in `:core`: an **app** chosen in the editor means that app's
newest live notification, and no app chosen means the one the trigger reported.
The fallback stays the default, because "when my bank notifies me, dismiss it"
should need no configuration.

Three decisions worth keeping:

- **An app picker, not a capture off a live notification.** The button action has
  to capture, because buttons only exist while the notification is on screen.
  Dismissing needs nothing but the app, so requiring the notification to be
  showing while the rule is written would be a restriction with no reason behind
  it.
- **A named app never falls back.** If that app has nothing showing, the action
  fails and says so. Quietly dismissing the trigger's notification instead would
  be the wrong notification, reported as success.
- **With no app, the payload key is used directly — no lookup.** The key already
  names one exact notification. Routing it through the active list, as the button
  action must, would add a way to fail that dismissing by key does not have: a
  notification already gone, or a list that came back empty, would become
  "nothing to dismiss" instead of a harmless no-op.

A `key` stored by a rule saved when the text box existed is still read and still
wins. It will usually be stale — that is why the field went — but honouring it
keeps such a rule behaving as it did rather than silently retargeting it.

### Stopping an alert when the notification goes away

An alarm that sounds until the phone is looked at is the point of `play_alert`,
and until now "I have looked at it" had no expression: the sound ran its set time
whatever the user did, and the only way to cut it short was to disable the rule —
which means opening Trigly to silence a noise made *about another app*. The
natural gesture is the notification itself. Swipe the thing away and the alarm
about it should stop.

With the option on, the alert ends the moment the notification that fired the
rule is no longer posted, or when the duration runs out, whichever comes first.
The duration stays the safety net rather than becoming decorative: an *ongoing*
notification never goes away on its own, and without the cap such a rule would be
an alarm with no end.

Three deliberate choices in how it watches:

- **The notification is identified by the key from the event, never from
  configuration.** A stored key is stale by the next post, which is the same
  reason `notification_button` stores an app and a label instead. Within one
  firing the key is exactly right: an app that *updates* its notification keeps
  the key, so a progress notification that keeps changing still counts as
  present.
- **Presence is polled, twice a second, not subscribed to.** The same reason the
  watchdog trigger polls (`docs/triggers.md`): removal is an edge, and an alert
  that started after the notification was already gone would wait forever for an
  edge that had already passed. Checking before the first sleep is what covers
  that case, and half a second is short enough that the silence reads as a
  consequence of the swipe.
- **When the option cannot work, the action says so.** Two ways it cannot: the
  rule was fired by something that is not a notification, or notification access
  is not granted. Both play the alert for its full length and then report a
  failure naming which it was. The alternative — falling back quietly — leaves
  someone believing an alarm has a stop gesture that does nothing, and they find
  out in a meeting.

Access revoked mid-alert reads as "the notification is gone" and stops the sound,
because the listener reports an empty list either way. That is the safe direction
to be wrong in: the mistake is a silence, not an alarm nobody can stop.

Not covered by an automated test on a device, and worth stating rather than
implying: nothing in the suite grants notification access, so the decision and
the polling loop are unit-tested against a fake listener and the real gesture is
checked by hand.

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
own document rather than a bullet in a catalogue. **It now has one:
`docs/conditions.md`**, where the decision taken is that a condition *is* a
trigger and triggers compose into an AND/OR tree. The two ends of the range that
were considered:

- **Conditions only** — an optional predicate on a rule, evaluated against the
  trigger payload, plus `TriggerEvent` values usable in action config
  (`{{battery.level}}`). Small, covers most real automations, no execution model
  needed.
- **A scripting model** — variables, branching, loops. Powerful, and it turns
  Trigly into a language runtime with everything that implies for persistence,
  debugging and safety.

Recommend starting with conditions and payload substitution, and only moving
further if real rules demand it. The chosen design is narrower than either end:
no variables, no control flow, and conditions reuse the trigger catalogue rather
than introducing a second component family. See `docs/conditions.md`.

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
The *permission* is now asked for and used, which is what unblocked the "open"
actions — see the top of this document. What is still unbuilt is an action that
actually draws something: a popup a rule can put on screen over whatever the
user is doing. That needs a window rather than a permission, and it now needs no
new permission to get there.

### Default dialer role — answer, end, reject, screen calls
`RoleManager.ROLE_DIALER` or `ROLE_CALL_SCREENING`. Becoming the *default phone
app* is a large commitment: the app must then implement the whole dialer
experience, not just the automation hook. `ROLE_CALL_SCREENING` is the narrower
and far more realistic option for rejecting calls.

---

## Suggested order

1. `WRITE_SETTINGS` kind, then auto-rotate and brightness.
2. `open_settings_panel`, replacing the toggles that are no longer possible.
3. An overlay *popup* action — the permission is already asked for; what is
   missing is a window that draws something.
4. Conditions and payload substitution, per the design note above.
5. Accessibility actions and call roles, after the distribution decision.
