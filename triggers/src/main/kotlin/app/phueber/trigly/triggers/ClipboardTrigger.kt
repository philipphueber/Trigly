package app.phueber.trigly.triggers

import android.content.ClipboardManager
import android.content.Context
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Fires when the clipboard contents change.
 *
 * **Mostly does not work, by design of the platform.** Since Android 10 an app
 * may only read the clipboard while it has focus, is the default input method,
 * or is an accessibility service. A background automation app is none of those,
 * so the listener fires but the read returns null.
 *
 * It is shipped anyway for the one case where it does work: if the user has
 * enabled Trigly's accessibility service, clipboard reads succeed. The
 * restriction is declared as [ComponentRequirement.PolicyRestricted] so the UI
 * can warn rather than let the user build a rule that quietly never matches.
 *
 * A null read is treated as "no event" rather than "empty clipboard", so a
 * blocked read never fires a rule with empty text.
 */
class ClipboardTrigger(
    private val context: Context,
    private val textFilter: TextFilter,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = callbackFlow {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return@callbackFlow

        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = runCatching {
                clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
            }.getOrNull()

            if (text != null && textFilter.matches(text)) {
                trySend(
                    TriggerEvent(
                        triggerType = TYPE,
                        firedAtMillis = now(),
                        payload = mapOf(PAYLOAD_TEXT to text),
                    )
                )
            }
        }

        clipboard.addPrimaryClipChangedListener(listener)
        awaitClose { clipboard.removePrimaryClipChangedListener(listener) }
    }

    companion object {
        const val TYPE = "clipboard_changed"
        const val CONFIG_TEXT_CONTAINS = "textContains"
        const val CONFIG_TEXT_MODE = "textContainsMode"
        const val PAYLOAD_TEXT = "text"
    }
}

class ClipboardTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = ClipboardTrigger.TYPE

    override val displayName = "Clipboard changes"
    override val category = Category.DEVICE

    override val configFields = listOf(
        textFilter(
            key = ClipboardTrigger.CONFIG_TEXT_CONTAINS,
            label = "Only if it contains",
            blankMeaning = "Leave blank for any copied text",
        ),
    )

    override val warning: String =
        "Android 10 and later only let an app read the clipboard while it is open, " +
            "is the default keyboard, or is an accessibility service. Without " +
            "Trigly's accessibility service enabled this will not fire."

    override val requirements = listOf(
        ComponentRequirement.PolicyRestricted(
            "Android 10 and later only allow clipboard reads while the app is in " +
                "the foreground, is the default keyboard, or is an accessibility " +
                "service. Without Trigly's accessibility service enabled, this " +
                "trigger will not fire."
        ),
    )

    override fun create(config: Map<String, String>): Trigger = ClipboardTrigger(
        context = context,
        textFilter = TextFilter.fromConfig(
            config[ClipboardTrigger.CONFIG_TEXT_CONTAINS],
            config[ClipboardTrigger.CONFIG_TEXT_MODE],
        ),
    )
}
