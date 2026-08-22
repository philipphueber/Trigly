package app.phueber.trigly.actions

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.TriggerEvent

/**
 * Clears notifications Trigly itself posted.
 *
 * Only its own — dismissing *another* app's notification needs the notification
 * listener service, and belongs with the other listener-backed components. See
 * `docs/actions.md`.
 */
class CancelNotificationAction(
    private val context: Context,
    private val id: Int?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val manager = NotificationManagerCompat.from(context)

        if (id == null) manager.cancelAll() else manager.cancel(id)
        return ActionResult.Success
    }

    companion object {
        const val TYPE = "cancel_notification"

        /** Omit to clear everything Trigly has posted. */
        const val CONFIG_ID = "id"
    }
}

class CancelNotificationActionFactory(private val context: Context) : ActionFactory {
    override val type = CancelNotificationAction.TYPE

    override fun create(config: Map<String, String>): Action = CancelNotificationAction(
        context = context,
        id = config[CancelNotificationAction.CONFIG_ID]?.toIntOrNull(),
    )
}
