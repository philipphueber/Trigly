package app.phueber.trigly.triggers

import android.content.Context
import app.phueber.trigly.core.TriggerFactory

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

    // Power
    BatteryLevelTriggerFactory(context),
    BatteryTemperatureTriggerFactory(context),
    PowerConnectionTriggerFactory(context),

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
)
