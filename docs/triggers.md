# Trigger catalogue

Every trigger Trigly intends to support, what it costs to build, and what it
needs from the user. Each entry names the concrete Android API so building it is
a mechanical change: a new file in `:triggers`, one line in `triggerFactories()`,
any permission declared in that module's manifest.

Requirements are declared in code as `ComponentRequirement` on the factory, so
the UI can explain a trigger before the user picks it and can explain a silent
rule afterwards. Keep the code and this document in step.

**Verification note.** Everything marked *implemented* compiles and its pure
logic is unit-tested. None of it has been exercised against real system
broadcasts yet — the emulator can fake some (`adb shell cmd`), but battery
temperature, NFC, and headset plug realistically need a device. Treat
"implemented" as "written and reviewed", not "proven on hardware".

---

## Cross-cutting blockers

Three gaps block whole groups below. They are worth fixing before picking off
individual triggers, because each one otherwise gets a private workaround.

**1. No foreground service.** The engine runs in the application scope, so every
runtime-registered receiver dies with the process. Since API 26 most implicit
broadcasts cannot be declared in a manifest, so runtime registration is the only
option and a live process is mandatory. *Blocks:* reliable delivery of every
Tier 1 broadcast trigger, and all of boot, calls, and notifications.

**2. No scheduler.** `IntervalTrigger` uses a coroutine `delay`, which stops in
Doze and dies with the process. Wall-clock triggers need `AlarmManager`
(`setExactAndAllowWhileIdle` for exact, `setWindow` for everything else).
Exact alarms need `SCHEDULE_EXACT_ALARM` from API 31, and Google restricts
`USE_EXACT_ALARM` to alarm-clock-like apps — so prefer inexact and design for
a few minutes of drift. *Blocks:* time of day, day of week, sunrise/sunset,
calendar, stopwatch.

**3. No permission-request flow.** `ComponentRequirement` describes what is
needed; nothing asks for it yet. *Blocks:* every Tier 2 entry.

---

## Tier 1 — implemented

| Trigger | Type string | Source | Requirement |
|---|---|---|---|
| Battery level threshold | `battery_level` | `ACTION_BATTERY_CHANGED` | — |
| Battery temperature | `battery_temperature` | `ACTION_BATTERY_CHANGED` | — |
| Power connected/disconnected | `power_connection` | `ACTION_POWER_CONNECTED`/`_DISCONNECTED` | — |
| Airplane mode | `airplane_mode` | `ACTION_AIRPLANE_MODE_CHANGED` | — |
| Wi-Fi radio on/off | `wifi_state` | `WIFI_STATE_CHANGED_ACTION` | — |
| Bluetooth radio on/off | `bluetooth_adapter_state` | `BluetoothAdapter.ACTION_STATE_CHANGED` | `BLUETOOTH_CONNECT` (API 31+) |
| Bluetooth device connected | `bluetooth_connected` | `ACTION_ACL_CONNECTED` | `BLUETOOTH_CONNECT` for the address |
| NFC on/off | `nfc_state` | `android.nfc.action.ADAPTER_STATE_CHANGED` | feature `android.hardware.nfc` |
| GPS on/off | `gps_state` | `PROVIDERS_CHANGED_ACTION` | — (only *reading* a location needs permission) |
| Screen on/off | `screen_state` | `ACTION_SCREEN_ON`/`_OFF` | — |
| Headset plugged | `headset_plug` | `ACTION_HEADSET_PLUG` (sticky) | — |
| Dark theme | `dark_theme` | `ACTION_CONFIGURATION_CHANGED` | API 29+ |
| Orientation | `screen_orientation` | `ACTION_CONFIGURATION_CHANGED` | — |
| Interval | `interval` | coroutine delay | — (see blocker 2) |

Two behaviours are easy to get wrong and are handled centrally in
`BroadcastTrigger`/`StateTracker`: sticky broadcasts replay on registration (a
rule would fire just for being enabled), and `ACTION_BATTERY_CHANGED` /
`ACTION_CONFIGURATION_CHANGED` fire constantly for unrelated reasons (a rule
would fire hundreds of times). New broadcast triggers get both for free by
returning a `stateKey`.

---

## Tier 1 — remaining

### USB vs AC charging
`ACTION_BATTERY_CHANGED` → `BatteryManager.EXTRA_PLUGGED`, compared against
`BATTERY_PLUGGED_USB` / `_AC` / `_WIRELESS`. Same source and file as the battery
triggers; no permission. **Smallest remaining item.**

### Wi-Fi SSID
Not the same as the radio toggle. From API 31 read `WifiInfo` off
`NetworkCapabilities.getTransportInfo()` via a `ConnectivityManager` network
callback; below that, `WifiManager.getConnectionInfo()`. Either way the SSID is
location-derived data: **`ACCESS_FINE_LOCATION` is required from API 27**, and
without it the framework returns `<unknown ssid>` rather than failing — a silent
wrong answer, so check the permission and surface it.

### Mobile data changed
`CONNECTIVITY_ACTION` is deprecated (API 28) and not delivered to background
apps at all from API 26. Use `ConnectivityManager.registerNetworkCallback` with a
`NetworkRequest` for `TRANSPORT_CELLULAR`. No permission. Callback-based, so it
fits `callbackFlow` the same way `BroadcastTrigger` does — likely wants a sibling
base class, `NetworkCallbackTrigger`.

### Device restart
`ACTION_BOOT_COMPLETED` with `RECEIVE_BOOT_COMPLETED`. This one *must* be a
manifest-declared receiver, and it is exempt from the implicit-broadcast ban.
Add `ACTION_LOCKED_BOOT_COMPLETED` for direct-boot devices if rules should run
before first unlock. **Blocked on blocker 1** — the receiver has nowhere to
deliver to until a service exists.

### SIM change / roaming
There is no public SIM-state broadcast. Use
`SubscriptionManager.OnSubscriptionsChangedListener` for SIM changes and
`TelephonyCallback.ServiceStateListener` (API 31+; `PhoneStateListener` before)
for roaming. Both need `READ_PHONE_STATE`; reading `ServiceState` also needs
location permission on recent versions. Verify the exact combination on a device
before promising the trigger.

### Sensors — shake, flip, light, proximity
`SensorManager` with `TYPE_ACCELEROMETER` (shake and flip), `TYPE_LIGHT`,
`TYPE_PROXIMITY`. No permission. Shake is a magnitude threshold with debounce;
flip is better served by `TYPE_GRAVITY` than raw acceleration.

The real cost is power: a registered sensor listener keeps the CPU busy and does
not survive Doze. Use the slowest delay that works, and consider
`SensorManager.registerListener` with a batch latency so the SoC can sleep
between deliveries. These should probably be off by default and carry a warning
in the UI.

### Time of day / day of week
`AlarmManager`, per blocker 2. Reschedule on boot, on time-zone change
(`ACTION_TIMEZONE_CHANGED`), and after each firing. The recurring-alarm bug to
avoid: computing the next occurrence from *now* rather than from the scheduled
time, which makes the rule drift later on every fire.

### Sunrise / sunset
Pure calculation from latitude, longitude, and date (NOAA solar equations) —
then scheduled like any other time trigger. Worth noting for privacy: if the
user types a location, this needs **no permission at all**. Only auto-detection
needs `ACCESS_COARSE_LOCATION`. Offer the manual path first.

### Calendar event
Query `CalendarContract.Instances` with `READ_CALENDAR`. There is no "event
starting" broadcast: register a `ContentObserver` on the calendar URI to notice
edits, and schedule an alarm for each upcoming event's start. Re-query after
every observer callback, since events move.

### Stopwatch
No Android API — engine state plus a notification. Depends on the scheduler for
the elapsed-time callback.

---

## Tier 2 — special permission or service

### Notification listener
`NotificationListenerService`, bound via `BIND_NOTIFICATION_LISTENER_SERVICE`,
enabled by the user at `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` — a
settings screen, not a permission dialog, which is exactly the case
`ComponentRequirement.SpecialAccess` exists for.

- **Notification posted** — `onNotificationPosted`. Filter by package and by
  text extras.
- **Notification action button** — the posted `Notification.Action` carries a
  `PendingIntent`; firing it is an *action*, not a trigger, and belongs in
  `:actions`.
- **DND mode changed** — `onInterruptionFilterChanged`, available through the
  same service. Changing DND additionally needs `ACCESS_NOTIFICATION_POLICY`.

The service is long-lived and rebound by the system, so it is a natural place to
host the engine — worth considering as part of blocker 1.

### Accessibility service
`AccessibilityService` with `BIND_ACCESSIBILITY_SERVICE`, enabled at
`Settings.ACTION_ACCESSIBILITY_SETTINGS`. Gives screen content
(`TYPE_WINDOW_CONTENT_CHANGED`), UI clicks (`TYPE_VIEW_CLICKED`), and window
changes.

**Read this before building it.** Google requires accessibility-API use to be for
accessibility, prominently disclosed, and justified in the Play listing;
automation apps have been removed for this. It is also the most invasive
permission on the platform. For an open-source app distributed outside Play this
is a product decision, not a technical one — decide the distribution story first.

*Keyboard opened/closed* has no real API. It is inferred from window insets or
`TYPE_WINDOW_STATE_CHANGED`, and is unreliable across keyboards and OEMs. Treat
as best-effort or drop it.

### App installed / uninstalled
`ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REMOVED`, runtime-registered. On API 30+
package *visibility* rules mean a `<queries>` element (or `QUERY_ALL_PACKAGES`,
which Play restricts) is needed to resolve details about other apps. The
broadcast itself still arrives.

### App started / stopped
`UsageStatsManager.queryEvents`, gated by the `PACKAGE_USAGE_STATS` special
access (`Settings.ACTION_USAGE_ACCESS_SETTINGS`). Poll-based — there is no push —
so expect seconds of latency and a battery cost proportional to poll frequency.
The accessibility service gives this in real time, at the cost above.

### Calls
`TelephonyCallback.CallStateListener` (API 31+; `PhoneStateListener` before) with
`READ_PHONE_STATE` covers ringing / off-hook / idle, from which
incoming/answered/ended follow. The **caller's number** needs `READ_CALL_LOG`,
which Play restricts to default dialer/assistant apps. Missed calls likewise come
from the call log. Design so the number is optional and the trigger degrades to
"a call came in".

### SMS sent / received
`RECEIVE_SMS` / `READ_SMS`. Play restricts these to the user's default SMS
handler; an automation app is very unlikely to be approved, and the restriction
survives user consent. Modelled as
`ComponentRequirement.PolicyRestricted` so a Play build can hide it while an
F-Droid or sideloaded build keeps it. **Decide the distribution story before
building this.**

### Location and geofence
`FusedLocationProviderClient` and the Geofencing API from
`play-services-location` — a Google Play services dependency, which matters if
Trigly targets de-Googled devices. Needs `ACCESS_FINE_LOCATION` plus
`ACCESS_BACKGROUND_LOCATION` (its own separate dialog, and a Play review form).
Geofences cap at 100 per app and are dropped on reboot, so re-register on boot.

### Clipboard changed
`ClipboardManager.OnPrimaryClipChangedListener` exists, but from Android 10 an
app may only read the clipboard while it holds focus, is the default IME, or is
an accessibility service. A background automation app is none of these, so this
is **effectively not implementable** without the accessibility route. Recommend
dropping it rather than shipping something that works only while the app is open.

### Work profile
`ACTION_MANAGED_PROFILE_ADDED` / `_REMOVED` / `_AVAILABLE` / `_UNAVAILABLE`,
delivered to the primary user. No permission. Untested territory — verify on a
device with a work profile before claiming support.

### Auto-sync changed
`ContentResolver.addStatusChangeListener(SYNC_OBSERVER_TYPE_SETTINGS)`. No
permission, no service. **The cheapest Tier 2 entry** — arguably Tier 1.

### Activity recognition
`ActivityRecognitionClient` from `play-services-location`, with the
`ACTIVITY_RECOGNITION` runtime permission (API 29+). Same Play-services caveat as
geofencing.

---

## Suggested order

1. USB vs AC, auto-sync — hours each, no new infrastructure.
2. Foreground service (blocker 1) — makes every existing trigger actually
   reliable, which is worth more than any new trigger.
3. Scheduler (blocker 2) — unlocks five time-based triggers at once.
4. Permission flow (blocker 3), then notification listener — the highest-value
   Tier 2 entry and a candidate host for the engine.
5. Network callbacks (mobile data, Wi-Fi SSID), sensors, calendar.
6. Decide distribution before touching accessibility, SMS, or call log.
