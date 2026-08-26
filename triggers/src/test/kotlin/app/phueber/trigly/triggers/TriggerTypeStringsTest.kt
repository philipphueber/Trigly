package app.phueber.trigly.triggers

import app.phueber.trigly.triggers.accessibility.KeyboardVisibilityTrigger
import app.phueber.trigly.triggers.accessibility.ScreenContentTrigger
import app.phueber.trigly.triggers.accessibility.UiClickTrigger
import app.phueber.trigly.triggers.notification.DndModeTrigger
import app.phueber.trigly.triggers.notification.NotificationPostedTrigger
import app.phueber.trigly.triggers.notification.NotificationWatchdogTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ComponentFactory.type` is a stable identifier. Every saved rule and every
 * exported file stores it. This test holds the released trigger type
 * strings as literal text. The literal text does not depend on the constant
 * that produces it.
 *
 * A `TYPE` constant must keep its value after release. Renaming the Kotlin
 * identifier breaks compilation here. That failure is loud and easy to see.
 * Renaming the string value still compiles: the constant keeps the name
 * `TYPE`, it still has a value, and every place that reads it agrees with
 * every other place. Only a rule already saved or exported with the old
 * value can tell the difference, and that rule is not part of this build.
 * This test stands in for that missing witness.
 *
 * Add new trigger types to the list below at any time. Do not edit an
 * existing line. Editing an existing line is the exact drift this test
 * exists to catch.
 *
 * `triggers/src/main/kotlin/app/phueber/trigly/triggers/TriggerFactories.kt`
 * lists every registered trigger. See
 * `ui/src/androidTest/kotlin/app/phueber/trigly/ui/PinnedTypeStringsTest.kt`
 * for the matching check: it confirms each of these strings is still
 * registered.
 */
class TriggerTypeStringsTest {

    /**
     * Left side: the string a saved or exported rule holds. It is copied
     * here as literal text and is never edited again. Right side: the value
     * the current build's constant reads today.
     */
    private val pinned = listOf(
        // Time
        "interval" to IntervalTrigger.TYPE,
        "solar" to SolarTrigger.TYPE,
        "time_window" to TimeWindowCheck.TYPE,

        // Power
        "battery_level" to BatteryLevelTrigger.TYPE,
        "battery_temperature" to BatteryTemperatureTrigger.TYPE,
        "power_connection" to PowerConnectionTrigger.TYPE,
        "charging_type" to ChargingTypeTrigger.TYPE,

        // Radios
        "airplane_mode" to AirplaneModeTrigger.TYPE,
        "wifi_state" to WifiStateTrigger.TYPE,
        "bluetooth_adapter_state" to BluetoothAdapterStateTrigger.TYPE,
        "bluetooth_connected" to BluetoothConnectionTrigger.TYPE,
        "nfc_state" to NfcStateTrigger.TYPE,
        "gps_state" to GpsProviderTrigger.TYPE,

        // Device state
        "screen_state" to ScreenStateTrigger.TYPE,
        "headset_plug" to HeadsetPlugTrigger.TYPE,
        "dark_theme" to DarkThemeTrigger.TYPE,
        "screen_orientation" to OrientationTrigger.TYPE,
        "device_restart" to DeviceRestartTrigger.TYPE,
        "shortcut" to ShortcutTrigger.TYPE,

        // Apps and system settings
        "app_install_state" to PackageChangeTrigger.TYPE,
        "work_profile" to WorkProfileTrigger.TYPE,
        "auto_sync" to AutoSyncTrigger.TYPE,
        "app_foreground" to AppForegroundTrigger.TYPE,

        // Notification access
        "notification_posted" to NotificationPostedTrigger.TYPE,
        "dnd_mode" to DndModeTrigger.TYPE,
        "notification_watchdog" to NotificationWatchdogTrigger.TYPE,

        // Accessibility access
        "ui_click" to UiClickTrigger.TYPE,
        "screen_content" to ScreenContentTrigger.TYPE,
        "keyboard_visibility" to KeyboardVisibilityTrigger.TYPE,

        // Telephony and location
        "call_state" to CallStateTrigger.TYPE,
        "sms_received" to SmsReceivedTrigger.TYPE,
        "location" to LocationTrigger.TYPE,
        "location_check" to LocationCheckTriggerFactory.TYPE,

        // Restricted by the platform rather than by permission
        "clipboard_changed" to ClipboardTrigger.TYPE,
    )

    @Test
    fun `every released trigger type string still matches its constant`() {
        val drifted = pinned.filter { (released, current) -> released != current }
        assertTrue(
            "these trigger types no longer hold the string a saved or " +
                "exported rule was built with. A rule that names the old " +
                "string can no longer find its trigger. The failure is " +
                "silent everywhere else. Add a new type instead of " +
                "changing an old one: " +
                drifted.joinToString { (released, current) -> "$released -> $current" },
            drifted.isEmpty(),
        )
    }

    @Test
    fun `no released trigger type string has been removed from this list`() {
        // A guard that only checks equality would pass on an empty list too.
        // This pins the count so deleting a line reads as a failure, not as
        // one fewer thing to maintain.
        assertEquals(34, pinned.size)
    }
}
