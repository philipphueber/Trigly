package app.phueber.trigly.actions

import android.content.Context
import app.phueber.trigly.core.ActionFactory

/**
 * Every action type this module provides.
 *
 * **This is the only existing file a new action touches** — same rule as
 * `triggerFactories`: new files for the implementation and its factory, one
 * line here.
 */
fun actionFactories(context: Context): List<ActionFactory> = listOf(
    PostNotificationActionFactory(context),
)
