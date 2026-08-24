package app.phueber.trigly.triggers

import android.content.Context
import app.phueber.trigly.core.TriggerFactory
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
 * abstraction is wrong — fix the interface instead of special-casing the type.
 *
 * Files are organised by *broadcast source* rather than strictly one per type:
 * two triggers reading `ACTION_BATTERY_CHANGED` share a file because they share
 * the extras and the sticky/chatty caveats. Adding one still does not touch the
 * other's logic, which is the property that matters.
 *
 * `docs/triggers.md` catalogues the triggers still to be built, each with its
 * API, permission and known pitfalls.
 */
fun triggerFactories(context: Context): List<TriggerFactory> = listOf(
    // Time
    IntervalTriggerFactory(),
    SolarTriggerFactory(),

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

    // Apps and system settings
    PackageChangeTriggerFactory(context),
    WorkProfileTriggerFactory(context),
    AutoSyncTriggerFactory(),
    AppForegroundTriggerFactory(context),

    // Notification access
    NotificationPostedTriggerFactory(),
    DndModeTriggerFactory(),
    NotificationWatchdogTriggerFactory(),

    // Accessibility access — the most invasive grant Trigly asks for
    UiClickTriggerFactory(),
    ScreenContentTriggerFactory(),
    KeyboardVisibilityTriggerFactory(),

    // Telephony and location
    CallStateTriggerFactory(context),
    SmsReceivedTriggerFactory(context),
    LocationTriggerFactory(context),

    // Restricted by the platform rather than by permission; see the class docs
    ClipboardTriggerFactory(context),
)
