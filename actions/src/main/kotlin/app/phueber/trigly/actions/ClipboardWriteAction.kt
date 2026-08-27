package app.phueber.trigly.actions

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent

/**
 * Puts text on the clipboard.
 *
 * Note the asymmetry with the clipboard *trigger*: writing is unrestricted,
 * while reading has been foreground-only since Android 10. Copying from a rule
 * works everywhere.
 *
 * From Android 13 the system shows a preview of whatever is copied, so a rule
 * that copies a password would flash it on screen; [sensitive] marks the clip so
 * the system redacts the preview.
 */
class ClipboardWriteAction(
    private val context: Context,
    private val text: String,
    private val sensitive: Boolean,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return ActionResult.Failure("There is no clipboard service.")

        val clip = ClipData.newPlainText(LABEL, text).apply {
            if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }

        clipboard.setPrimaryClip(clip)
        return ActionResult.Success()
    }

    companion object {
        const val TYPE = "set_clipboard"
        const val CONFIG_TEXT = "text"
        const val CONFIG_SENSITIVE = "sensitive"
        private const val LABEL = "Trigly"
    }
}

class ClipboardWriteActionFactory(private val context: Context) : ActionFactory {
    override val type = ClipboardWriteAction.TYPE

    override val displayName = "Copy text"
    override val category = ActionCategory.DEVICE

    override val configFields = listOf(
        messageText(ClipboardWriteAction.CONFIG_TEXT, "Text to copy"),
        ConfigField.Flag(
            key = ClipboardWriteAction.CONFIG_SENSITIVE,
            label = "Hide from the copy preview",
            help = "Android 13 and later show what was copied on screen. Turn this " +
                "on for passwords and codes.",
        ),
    )

    override fun create(config: Map<String, String>): Action = ClipboardWriteAction(
        context = context,
        text = config[ClipboardWriteAction.CONFIG_TEXT]
            ?: error("$type needs '${ClipboardWriteAction.CONFIG_TEXT}'"),
        sensitive = config[ClipboardWriteAction.CONFIG_SENSITIVE]?.toBoolean() ?: false,
    )
}
