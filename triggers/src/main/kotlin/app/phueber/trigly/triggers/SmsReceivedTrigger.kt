package app.phueber.trigly.triggers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/** Pure, so the filter semantics are tested without a radio. */
fun matchesSms(
    sender: String?,
    body: String?,
    senderContains: String?,
    bodyContains: String?,
): Boolean {
    if (senderContains != null &&
        sender?.contains(senderContains, ignoreCase = true) != true
    ) {
        return false
    }
    if (bodyContains != null && body?.contains(bodyContains, ignoreCase = true) != true) {
        return false
    }
    return true
}

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
    private val senderContains: String?,
    private val bodyContains: String?,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)

    override fun read(intent: Intent): Reading? {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return null

        val sender = messages.first().displayOriginatingAddress
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }

        if (!matchesSms(sender, body, senderContains, bodyContains)) return null

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
        const val CONFIG_BODY_CONTAINS = "bodyContains"
        const val PAYLOAD_SENDER = "sender"
        const val PAYLOAD_BODY = "body"
    }
}

class SmsReceivedTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = SmsReceivedTrigger.TYPE

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.RECEIVE_SMS),
        ComponentRequirement.PolicyRestricted(
            "Google Play restricts SMS access to the device's default SMS app. " +
                "This trigger cannot ship in a Play build."
        ),
    )

    override fun create(config: Map<String, String>): Trigger = SmsReceivedTrigger(
        context = context,
        senderContains = config[SmsReceivedTrigger.CONFIG_SENDER_CONTAINS],
        bodyContains = config[SmsReceivedTrigger.CONFIG_BODY_CONTAINS],
    )
}
