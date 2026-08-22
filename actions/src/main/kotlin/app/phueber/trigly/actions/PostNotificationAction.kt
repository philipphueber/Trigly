package app.phueber.trigly.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent

/**
 * Posts a notification. The reference [Action] implementation: it needs a
 * runtime permission, which makes it a good example of reporting a refusal as
 * [ActionResult.Failure] instead of throwing.
 */
class PostNotificationAction(
    private val context: Context,
    private val title: String,
    private val body: String,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        // Covers both the API 33+ runtime permission and the user switching
        // notifications off in settings, on every supported API level.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return ActionResult.Failure("notifications are disabled for this app")
        }

        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        // The id is derived from the firing time so repeated firings stack rather
        // than overwrite one another.
        val id = (event.firedAtMillis and 0x7FFFFFFF).toInt()
        return runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .fold(
                onSuccess = { ActionResult.Success },
                onFailure = { ActionResult.Failure("notify failed: ${it.message}", it) },
            )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val TYPE = "post_notification"
        const val CONFIG_TITLE = "title"
        const val CONFIG_BODY = "body"

        private const val CHANNEL_ID = "trigly_actions"
        private const val CHANNEL_NAME = "Rule notifications"
    }
}

class PostNotificationActionFactory(
    private val context: Context,
) : ActionFactory {
    override val type: String = PostNotificationAction.TYPE

    override val displayName = "Show a notification"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        ConfigField.Text(
            key = PostNotificationAction.CONFIG_TITLE,
            label = "Title",
            blankMeaning = "Defaults to \"Trigly\"",
        ),
        messageText(PostNotificationAction.CONFIG_BODY, "Message", required = false),
    )

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission("android.permission.POST_NOTIFICATIONS"),
    )

    override fun create(config: Map<String, String>): Action = PostNotificationAction(
        context = context,
        title = config[PostNotificationAction.CONFIG_TITLE] ?: "Trigly",
        body = config[PostNotificationAction.CONFIG_BODY].orEmpty(),
    )
}
