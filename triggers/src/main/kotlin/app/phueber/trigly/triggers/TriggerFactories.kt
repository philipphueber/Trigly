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
 * Not yet implemented: a notification trigger. It needs a
 * `NotificationListenerService` declared in the app manifest and enabled by the
 * user in system settings, which is a different shape from these two (a
 * long-lived service rather than a receiver registered per collection) and is
 * left for its own change rather than stubbed out here.
 */
fun triggerFactories(context: Context): List<TriggerFactory> = listOf(
    IntervalTriggerFactory(),
    BluetoothConnectionTriggerFactory(context),
)
