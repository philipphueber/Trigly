package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ComponentFactory
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.triggers.triggerFactories
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ComponentFactory.type` is a stable identifier. Every saved rule and every
 * exported file stores it. Two JVM tests hold the released type strings as
 * literal text and pin each one to the constant that produces it:
 * `triggers/src/test/kotlin/app/phueber/trigly/triggers/TriggerTypeStringsTest.kt`
 * and `actions/src/test/kotlin/app/phueber/trigly/actions/ActionTypeStringsTest.kt`.
 * Those tests catch a released string that changes its value.
 *
 * This test catches the other failure: a released string that falls out of
 * the registry. An example is a factory line deleted from
 * `TriggerFactories.kt` or `ActionFactories.kt` while its `TYPE` constant
 * stays behind, unused. Either failure means an old rule can no longer find
 * its trigger or action. Building the registry needs a `Context`. That is
 * why this test lives here, beside `ConfigSchemaContractTest`, rather than
 * in a plain JVM test.
 *
 * The released set is copied here as literal text, the same way the two JVM
 * tests hold it. It is not read from the constants. Reading the constants
 * here would only prove a factory agrees with itself. It would not prove
 * that a rule saved against the released string still resolves.
 */
@RunWith(AndroidJUnit4::class)
class PinnedTypeStringsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val factories: List<ComponentFactory> =
        triggerFactories(context) + actionFactories(context, NotificationController.Unavailable)

    /** Every trigger type string ever released. Add new lines. Do not remove any. */
    private val releasedTriggerTypes = setOf(
        // Time
        "interval",
        "solar",
        "time_window",

        // Power
        "battery_level",
        "battery_temperature",
        "power_connection",
        "charging_type",

        // Radios
        "airplane_mode",
        "wifi_state",
        "bluetooth_adapter_state",
        "bluetooth_connected",
        "nfc_state",
        "gps_state",

        // Device state
        "screen_state",
        "headset_plug",
        "dark_theme",
        "screen_orientation",
        "device_restart",
        "shortcut",

        // Apps and system settings
        "app_install_state",
        "work_profile",
        "auto_sync",
        "app_foreground",

        // Notification access
        "notification_posted",
        "dnd_mode",
        "notification_watchdog",

        // Accessibility access
        "ui_click",
        "screen_content",
        "keyboard_visibility",

        // Telephony and location
        "call_state",
        "sms_received",
        "location",
        "location_check",

        // Restricted by the platform rather than by permission
        "clipboard_changed",
    )

    /** Every action type string ever released. Add new lines. Do not remove any. */
    private val releasedActionTypes = setOf(
        // Tell the user something
        "post_notification",
        "cancel_notification",
        "toast",
        "speak",
        "vibrate",
        "play_alert",

        // Open something
        "open_url",
        "open_app",

        // Hand off to another app, user confirms
        "compose_email",
        "compose_sms",
        "set_alarm",
        "add_calendar_event",

        // Trigly's own rules
        "set_rule_enabled",

        // Device state
        "set_volume",
        "set_ringer_mode",
        "set_clipboard",

        // Reach the outside world
        "http_request",

        // Other apps' notifications, via the listener service
        "dismiss_notification",
        "notification_button",
        "set_dnd",
    )

    @Test
    fun every_released_type_string_is_still_registered() {
        val registered = factories.map { it.type }.toSet()

        val missingTriggers = releasedTriggerTypes - registered
        val missingActions = releasedActionTypes - registered

        assertTrue(
            "these released trigger types are no longer registered by " +
                "triggerFactories(): $missingTriggers. A rule saved or " +
                "exported with one of these can no longer find its trigger.",
            missingTriggers.isEmpty(),
        )
        assertTrue(
            "these released action types are no longer registered by " +
                "actionFactories(): $missingActions. A rule saved or " +
                "exported with one of these can no longer find its action.",
            missingActions.isEmpty(),
        )
    }
}
