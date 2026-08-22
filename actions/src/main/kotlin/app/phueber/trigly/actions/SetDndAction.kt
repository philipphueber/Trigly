package app.phueber.trigly.actions

import android.app.NotificationManager
import android.content.Context
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.TriggerEvent

/**
 * The Do Not Disturb settings a rule can select.
 *
 * Named for what the user sees rather than for the framework constant:
 * `INTERRUPTION_FILTER_ALL` means DND is *off*, which reads backwards in a rule.
 */
enum class DndMode(val configValue: String, val filter: Int) {
    OFF("off", NotificationManager.INTERRUPTION_FILTER_ALL),
    PRIORITY("priority", NotificationManager.INTERRUPTION_FILTER_PRIORITY),
    ALARMS("alarms", NotificationManager.INTERRUPTION_FILTER_ALARMS),
    SILENCE("silence", NotificationManager.INTERRUPTION_FILTER_NONE),
    ;

    companion object {
        const val CONFIG_KEY = "mode"

        fun parse(raw: String?): DndMode =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
    }
}

/**
 * Turns Do Not Disturb on or off.
 *
 * Goes through `NotificationManager` directly rather than the listener service —
 * this needs notification *policy* access, which is a different grant from
 * notification *listener* access, and does not require the service to be bound.
 * That is why this action is not part of the controller port.
 */
class SetDndAction(
    private val context: Context,
    private val mode: DndMode,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return ActionResult.Failure("no notification service")

        if (!manager.isNotificationPolicyAccessGranted) {
            return ActionResult.Failure("Do Not Disturb access is not granted")
        }

        return try {
            manager.setInterruptionFilter(mode.filter)
            ActionResult.Success
        } catch (denied: SecurityException) {
            ActionResult.Failure("Do Not Disturb access was revoked", denied)
        }
    }

    companion object {
        const val TYPE = "set_dnd"
    }
}

class SetDndActionFactory(private val context: Context) : ActionFactory {
    override val type = SetDndAction.TYPE

    override val displayName = "Set Do Not Disturb"
    override val category = ActionCategory.DEVICE

    override val configFields = listOf(
        ConfigField.Choice(
            key = DndMode.CONFIG_KEY,
            label = "Switch Do Not Disturb to",
            options = listOf(
                ConfigField.Option("off", "off — allow everything"),
                ConfigField.Option("priority", "priority only"),
                ConfigField.Option("alarms", "alarms only"),
                ConfigField.Option("silence", "total silence"),
            ),
        ),
    )

    override val requirements = listOf(
        ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_POLICY),
    )

    override fun create(config: Map<String, String>): Action = SetDndAction(
        context = context,
        mode = DndMode.parse(config[DndMode.CONFIG_KEY]),
    )
}
