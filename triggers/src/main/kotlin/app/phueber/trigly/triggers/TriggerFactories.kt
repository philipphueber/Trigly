package app.phueber.trigly.triggers

import android.content.Context
import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.triggers.accessibility.KeyboardVisibilityTriggerFactory
import app.phueber.trigly.triggers.accessibility.ScreenContentTriggerFactory
import app.phueber.trigly.triggers.accessibility.UiClickTriggerFactory
import app.phueber.trigly.triggers.notification.DndModeTriggerFactory
import app.phueber.trigly.triggers.notification.NotificationPostedTriggerFactory
import app.phueber.trigly.triggers.notification.NotificationWatchdogTriggerFactory

/**
 * Every trigger type this module provides.
 *
 * **This is the only existing file a new trigger touches.** Add the
 * implementation and its factory in new files, then add one line here. If a new
 * trigger type forces a change to `:core` or to a sibling trigger, the
 * abstraction is wrong. Fix the interface instead of special-casing the type.
 *
 * Files are organised by *broadcast source* rather than strictly one per type:
 * two triggers reading `ACTION_BATTERY_CHANGED` share a file because they share
 * the extras and the sticky/chatty caveats. Adding one still does not touch the
 * other's logic, which is the property that matters.
 *
 * `docs/triggers.md` catalogues the triggers still to be built, each with its
 * API, permission and known pitfalls.
 *
 * @param scheduler the port every wall-clock or poll-based trigger below
 *   waits through instead of a plain coroutine `delay`, so its wait survives
 *   Doze. See `app.phueber.trigly.core.AlarmScheduler` and `docs/todo.md`'s
 *   T1.
 * @param store where `variable_check` reads an app-scope variable from.
 *   Defaulted, unlike [scheduler]: its Room-backed implementation lives in
 *   `:core`, next to the interface, so a working [InMemoryVariableStore] is a
 *   real and always-correct answer for a caller that has not wired one up,
 *   the same way `actionFactories`' defaults are, and not the refusal
 *   [scheduler] would need if it had one. See `VariableStore`'s KDoc.
 */
fun triggerFactories(
    context: Context,
    scheduler: AlarmScheduler,
    store: VariableStore = InMemoryVariableStore(),
): List<TriggerFactory> = listOf(
    // Time
    IntervalTriggerFactory(scheduler),
    SolarTriggerFactory(scheduler),
    TimeOfDayTriggerFactory(scheduler, AndroidTimeZoneChanges(context)),

    // Condition only: no event stream, so it can never start a rule. It lives in
    // this list anyway because a condition *is* a trigger, asked rather than
    // watched; the editor decides which slots to offer it in from
    // `supportsCondition`. See `docs/conditions.md`.
    //
    // It is the *exception*: there is no time trigger to fold it into, because
    // nobody has built a time-of-day trigger yet, even though `scheduler` above
    // could now back one. Everything else that can be asked is a passive form
    // of a trigger that already existed. The location check folded into
    // `location` rather than standing beside it, which is what "one component,
    // the slot decides the question" means.
    TimeWindowCheckFactory(),

    // Condition only, for the same reason as `time_window`: which day, which
    // month and which date range are all levels, never edges. Each reads the
    // device's own zone, because none of the three names a place of its own
    // to be wrong about; see `DayOfWeekCheck`'s KDoc for the full reasoning.
    DayOfWeekCheckFactory(),
    MonthCheckFactory(),
    DateRangeCheckFactory(),

    // Condition only, for the same reason as `time_window` above: reading an
    // app-scope variable is a level, never an edge. See `docs/variables.md`,
    // section 10.
    VariableCheckFactory(store),

    // Power
    BatteryLevelTriggerFactory(context),
    BatteryTemperatureTriggerFactory(context),
    PowerConnectionTriggerFactory(context),
    ChargingTypeTriggerFactory(context),

    // Radios
    AirplaneModeTriggerFactory(context),
    WifiStateTriggerFactory(context),
    BluetoothAdapterStateTriggerFactory(context),
    BluetoothConnectionTriggerFactory(context),
    NfcStateTriggerFactory(context),
    GpsProviderTriggerFactory(context),

    // Device state
    ScreenStateTriggerFactory(context),
    HeadsetPlugTriggerFactory(context),
    DarkThemeTriggerFactory(context),
    OrientationTriggerFactory(context),
    DeviceRestartTriggerFactory(),
    // Tapped by the user, so it can only ever be an edge. There is no "is a
    // shortcut currently being tapped". Like `device_restart` it may arrive
    // before the engine exists, because the tap is what starts the process; see
    // `ShortcutEvents`.
    ShortcutTriggerFactory(),

    // Apps and system settings
    PackageChangeTriggerFactory(context),
    WorkProfileTriggerFactory(context),
    AutoSyncTriggerFactory(),
    AppForegroundTriggerFactory(context, scheduler),

    // Notification access
    NotificationPostedTriggerFactory(),
    DndModeTriggerFactory(),
    NotificationWatchdogTriggerFactory(scheduler),

    // Accessibility access: the most invasive grant Trigly asks for
    UiClickTriggerFactory(),
    ScreenContentTriggerFactory(),
    KeyboardVisibilityTriggerFactory(),

    // Telephony and location
    CallStateTriggerFactory(context),
    SmsReceivedTriggerFactory(context),
    LocationTriggerFactory(context),
    LocationCheckTriggerFactory(context),

    // Restricted by the platform rather than by permission; see the class docs
    ClipboardTriggerFactory(context),
)
