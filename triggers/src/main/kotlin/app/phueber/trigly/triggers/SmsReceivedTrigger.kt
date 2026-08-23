package app.phueber.trigly.triggers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/** Pure, so the filter semantics are tested without a radio. */
fun matchesSms(
    sender: String?,
    body: String?,
    senderFilter: TextFilter,
    bodyFilter: TextFilter,
): Boolean = senderFilter.matches(sender) && bodyFilter.matches(body)

/**
 * Fires when an SMS arrives.
 *
 * **Play-restricted.** `RECEIVE_SMS` is limited to the user's default SMS
 * handler, and unlike a runtime permission no user action lifts it — which is
 * why it is declared as [ComponentRequirement.PolicyRestricted] rather than a
 * permission the UI could prompt for. A Play build should hide this trigger; an
 * F-Droid or sideloaded build can offer it. See `docs/triggers.md`.
 *
 * A long message arrives as several PDUs in one broadcast, so the parts are
 * joined before matching — otherwise a phrase spanning the split would not match.
 */
class SmsReceivedTrigger(
    context: Context,
    private val senderFilter: TextFilter,
    private val bodyFilter: TextFilter,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)

    override fun read(intent: Intent): Reading? {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return null

        val sender = messages.first().displayOriginatingAddress
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }

        if (!matchesSms(sender, body, senderFilter, bodyFilter)) return null

        return Reading(
            payload = buildMap {
                sender?.let { put(PAYLOAD_SENDER, it) }
                put(PAYLOAD_BODY, body)
            },
        )
    }

    companion object {
        const val TYPE = "sms_received"
        const val CONFIG_SENDER_CONTAINS = "senderContains"
        const val CONFIG_SENDER_MODE = "senderContainsMode"
        const val CONFIG_BODY_CONTAINS = "bodyContains"
        const val CONFIG_BODY_MODE = "bodyContainsMode"
        const val PAYLOAD_SENDER = "sender"
        const val PAYLOAD_BODY = "body"
    }
}

class SmsReceivedTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = SmsReceivedTrigger.TYPE

    override val displayName = "SMS received"
    override val category = Category.TELEPHONY

    override val configFields = listOf(
        textFilter(
            key = SmsReceivedTrigger.CONFIG_SENDER_CONTAINS,
            label = "Sender contains",
            blankMeaning = "Leave blank for any sender",
        ),
        textFilter(
            key = SmsReceivedTrigger.CONFIG_BODY_CONTAINS,
            label = "Message contains",
            blankMeaning = "Leave blank for any message",
        ),
    )

    override val warning: String =
        "Google Play restricts SMS access to the default messaging app, so this " +
            "trigger cannot work in a Play Store build of Trigly."

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.RECEIVE_SMS),
        ComponentRequirement.PolicyRestricted(
            "Google Play restricts SMS access to the device's default SMS app. " +
                "This trigger cannot ship in a Play build."
        ),
    )

    override fun create(config: Map<String, String>): Trigger = SmsReceivedTrigger(
        context = context,
        senderFilter = TextFilter.fromConfig(
            config[SmsReceivedTrigger.CONFIG_SENDER_CONTAINS],
            config[SmsReceivedTrigger.CONFIG_SENDER_MODE],
        ),
        bodyFilter = TextFilter.fromConfig(
            config[SmsReceivedTrigger.CONFIG_BODY_CONTAINS],
            config[SmsReceivedTrigger.CONFIG_BODY_MODE],
        ),
    )
}
