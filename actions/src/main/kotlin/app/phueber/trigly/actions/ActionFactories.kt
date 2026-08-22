package app.phueber.trigly.actions

import android.content.Context
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.NotificationController

/**
 * Every action type this module provides.
 *
 * **This is the only existing file a new action touches** — same rule as
 * `triggerFactories`: new files for the implementation and its factory, one
 * line here.
 *
 * `docs/actions.md` catalogues the actions still to be built, and the ones that
 * are no longer possible for a third-party app at all.
 */
fun actionFactories(
    context: Context,
    /**
     * Supplied by `:ui`, which is the only module that can see both the listener
     * service in `:triggers` and the actions here. Defaults to the unavailable
     * implementation so tests and previews can assemble without it.
     */
    notifications: NotificationController = NotificationController.Unavailable,
): List<ActionFactory> = listOf(
    // Tell the user something
    PostNotificationActionFactory(context),
    CancelNotificationActionFactory(context),
    ToastActionFactory(context),
    SpeakActionFactory(context),
    VibrateActionFactory(context),
    PlayAlertActionFactory(context),

    // Open something
    OpenUrlActionFactory(context),
    OpenAppActionFactory(context),

    // Hand off to another app, user confirms
    ComposeEmailActionFactory(context),
    ComposeSmsActionFactory(context),
    SetAlarmActionFactory(context),
    AddCalendarEventActionFactory(context),

    // Device state
    SetVolumeActionFactory(context),
    SetRingerModeActionFactory(context),
    ClipboardWriteActionFactory(context),

    // Reach the outside world
    HttpRequestActionFactory(),

    // Other apps' notifications, via the listener service
    DismissNotificationActionFactory(notifications),
    TriggerNotificationButtonActionFactory(notifications),
    SetDndActionFactory(context),
)
