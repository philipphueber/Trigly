package app.phueber.trigly.actions

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
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

    override val displayName = "Clear Trigly's notifications"
    override val category = ActionCategory.NOTIFY

    /**
     * No fields, deliberately.
     *
     * There used to be a "Notification id" box, and no value a person could type
     * into it could ever be right: `post_notification` mints its ids from
     * `event.firedAtMillis`, so they are timestamps unknowable in advance. A wrong
     * id matched nothing and still reported success. Clearing everything Trigly
     * posted was the only working configuration, so that is now what the action
     * does, and the field is gone rather than left as a trap.
     *
     * The `id` config key is still read by `create()`, so a rule saved with one
     * keeps its old behaviour instead of silently widening.
     */
    override val configFields = emptyList<ConfigField>()

    override fun create(config: Map<String, String>): Action = CancelNotificationAction(
        context = context,
        id = config[CancelNotificationAction.CONFIG_ID]?.toIntOrNull(),
    )
}
