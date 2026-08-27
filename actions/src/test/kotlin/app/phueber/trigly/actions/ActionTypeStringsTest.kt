package app.phueber.trigly.actions

import app.phueber.trigly.core.VariableScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ComponentFactory.type` is a stable identifier. Every saved rule and every
 * exported file stores it. This test holds the released action type
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
 * Add new action types to the list below at any time. Do not edit an
 * existing line. Editing an existing line is the exact drift this test
 * exists to catch.
 *
 * `actions/src/main/kotlin/app/phueber/trigly/actions/ActionFactories.kt`
 * lists every registered action. See
 * `ui/src/androidTest/kotlin/app/phueber/trigly/ui/PinnedTypeStringsTest.kt`
 * for the matching check: it confirms each of these strings is still
 * registered.
 */
class ActionTypeStringsTest {

    /**
     * Left side: the string a saved or exported rule holds. It is copied
     * here as literal text and is never edited again. Right side: the value
     * the current build's constant reads today.
     */
    private val pinned = listOf(
        // Tell the user something
        "post_notification" to PostNotificationAction.TYPE,
        "cancel_notification" to CancelNotificationAction.TYPE,
        "toast" to ToastAction.TYPE,
        "speak" to SpeakAction.TYPE,
        "vibrate" to VibrateAction.TYPE,
        "play_alert" to PlayAlertAction.TYPE,
        "play_sound" to PlaySoundAction.TYPE,

        // Open something
        "open_url" to OpenUrlAction.TYPE,
        "open_app" to OpenAppAction.TYPE,

        // Hand off to another app, user confirms
        "compose_email" to ComposeEmailAction.TYPE,
        "compose_sms" to ComposeSmsAction.TYPE,
        "set_alarm" to SetAlarmAction.TYPE,
        "add_calendar_event" to AddCalendarEventAction.TYPE,

        // Timing
        "delay" to DelayAction.TYPE,

        // Trigly's own rules
        "set_rule_enabled" to SetRuleEnabledAction.TYPE,
        "set_variable" to SetVariableAction.TYPE,
        "run_rule" to RunRuleAction.TYPE,

        // Device state
        "set_volume" to SetVolumeAction.TYPE,
        "set_ringer_mode" to SetRingerModeAction.TYPE,
        "set_clipboard" to ClipboardWriteAction.TYPE,

        // Reach the outside world
        "http_request" to HttpRequestAction.TYPE,

        // Other apps' notifications, via the listener service
        "dismiss_notification" to DismissNotificationAction.TYPE,
        "notification_button" to TriggerNotificationButtonAction.TYPE,
        "capture_notification_button" to CaptureNotificationButtonAction.TYPE,
        "press_captured_button" to PressCapturedButtonAction.TYPE,
        "set_dnd" to SetDndAction.TYPE,
    )

    @Test
    fun `every released action type string still matches its constant`() {
        val drifted = pinned.filter { (released, current) -> released != current }
        assertTrue(
            "these action types no longer hold the string a saved or " +
                "exported rule was built with. A rule that names the old " +
                "string can no longer find its action. The failure is " +
                "silent everywhere else. Add a new type instead of " +
                "changing an old one: " +
                drifted.joinToString { (released, current) -> "$released -> $current" },
            drifted.isEmpty(),
        )
    }

    @Test
    fun `no released action type string has been removed from this list`() {
        // A guard that only checks equality would pass on an empty list too.
        // This pins the count so deleting a line reads as a failure, not as
        // one fewer thing to maintain.
        assertEquals(26, pinned.size)
    }

    /**
     * `VariableScope.reserved` names the five namespaces a `{{...}}` reference
     * can use besides a component's own type string: `trigger`, `event`,
     * `rule`, `app` and `action`. An action type equal to one of those would
     * make `{{<that type>.key}}` unresolvable as the type-qualified action
     * reference it should be. See `EventLookup.value` and `ActionOutputs`.
     * `TriggerTypeStringsTest` runs the matching check for trigger types.
     */
    @Test
    fun `no action type is a reserved variable namespace`() {
        val collisions = pinned.filter { (released, _) -> released in VariableScope.reserved }
        assertTrue(
            "these action types collide with a reserved variable namespace " +
                "(${VariableScope.reserved}) and must be renamed: " +
                collisions.joinToString { (released, _) -> released },
            collisions.isEmpty(),
        )
    }
}
