package app.phueber.trigly.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Fires on an incoming, outgoing, answered, ended or missed call.
 *
 * **No caller number.** Reading it needs `READ_CALL_LOG`, which Play restricts
 * to default dialer and assistant apps. Rather than make the whole trigger
 * unshippable for one payload field, the number is simply not offered — a rule
 * can know a call came in, not who from. Revisit only if the distribution story
 * changes.
 *
 * **API 31+.** The modern `TelephonyCallback` is clean; the `PhoneStateListener`
 * it replaced must be constructed on a thread with a `Looper`, which does not
 * fit a `callbackFlow` without a main-thread hop. Declared as a
 * [ComponentRequirement.MinApiLevel] so the UI can say so rather than the
 * trigger silently doing nothing — which is exactly what the requirement model
 * is for. A pre-31 path is a reasonable follow-up.
 *
 * The version gate lives inside rather than as `@RequiresApi` on the class:
 * annotating the class also gates its companion, which the factory — correctly
 * not version-gated itself — has to read to know its own type name.
 */
class CallStateTrigger(
    private val context: Context,
    private val target: CallEvent,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateEvents()
        } else {
            // Unreachable in practice: the factory refuses to build this below
            // API 31, and the UI shows the requirement. Empty rather than a
            // throw, so an old rule restored from storage degrades quietly.
            emptyFlow()
        }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission") // READ_PHONE_STATE is declared as a requirement.
    private fun callStateEvents(): Flow<TriggerEvent> = callbackFlow {
        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?: return@callbackFlow

        val machine = CallStateMachine()

        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                machine.onState(state)
                    .filter { it == target }
                    .forEach { event ->
                        trySend(
                            TriggerEvent(
                                triggerType = TYPE,
                                firedAtMillis = now(),
                                payload = mapOf(PAYLOAD_EVENT to event.name.lowercase()),
                            )
                        )
                    }
            }
        }

        telephony.registerTelephonyCallback(context.mainExecutor, callback)
        awaitClose { telephony.unregisterTelephonyCallback(callback) }
    }

    /**
     * Only [CallEvent.INCOMING] has a state to ask about: "ringing" is a level
     * — `CALL_STATE_RINGING` — for as long as it lasts. Every other event this
     * trigger fires on is a transition with nothing left to read at rest:
     * ANSWERED and OUTGOING both read back as the same `CALL_STATE_OFFHOOK` as
     * an ordinary minute mid-call, so a level check could not tell them apart
     * without lying, and ENDED/MISSED describe something that already
     * finished, not a condition anyone is currently in.
     *
     * Gated the same way [events] is: below API 31 and without
     * `READ_PHONE_STATE` there is no honest answer, only a guess.
     */
    @SuppressLint("MissingPermission") // READ_PHONE_STATE is declared as a requirement.
    override suspend fun currentlyHolds(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (target != CallEvent.INCOMING) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        return runCatching {
            @Suppress("DEPRECATION") // No callback-based "current state" query exists.
            telephony.callState == TelephonyManager.CALL_STATE_RINGING
        }.getOrNull()
    }

    companion object {
        const val TYPE = "call_state"
        const val PAYLOAD_EVENT = "event"
    }
}

class CallStateTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = CallStateTrigger.TYPE

    override val displayName = "Phone call"
    override val category = Category.TELEPHONY

    override val configFields = listOf(
        ConfigField.Choice(
            key = CallEvent.CONFIG_KEY,
            label = "Fires on a call that is",
            options = listOf(
                ConfigField.Option("incoming", "ringing"),
                ConfigField.Option("answered", "answered"),
                ConfigField.Option("outgoing", "dialled out"),
                ConfigField.Option("ended", "ended"),
                ConfigField.Option("missed", "missed"),
            ),
        ),
    )

    override val warning: String =
        "This trigger needs Android 12 or later. It cannot show the caller's " +
            "number. That needs a permission Google reserves for dialler apps."

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.READ_PHONE_STATE),
        ComponentRequirement.MinApiLevel(Build.VERSION_CODES.S),
    )

    override val supportsCondition = true

    // Not the caller's number: see the KDoc on CallStateTrigger for why that is
    // never offered.
    override val variables = listOf(
        VariableSpec(
            key = CallStateTrigger.PAYLOAD_EVENT,
            label = "Call event",
            kind = VariableKind.STATE,
            sample = CallEvent.INCOMING.name.lowercase(),
            help = "One of " +
                CallEvent.entries.joinToString { "'${it.name.lowercase()}'" } + ".",
        ),
    )

    override fun create(config: Map<String, String>): Trigger =
        CallStateTrigger(context, CallEvent.parse(config[CallEvent.CONFIG_KEY]))
}
