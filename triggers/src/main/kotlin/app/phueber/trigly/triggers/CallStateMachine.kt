package app.phueber.trigly.triggers

import android.telephony.TelephonyManager

/** What a rule can react to. Android reports states; users think in events. */
enum class CallEvent {
    INCOMING,
    OUTGOING,
    ANSWERED,
    ENDED,
    MISSED,
    ;

    companion object {
        const val CONFIG_KEY = "event"

        fun parse(raw: String?): CallEvent =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.name.lowercase() }}, " +
                        "was '$raw'"
                )
    }
}

/**
 * Derives call events from the three states telephony actually reports.
 *
 * Android exposes IDLE / RINGING / OFFHOOK and nothing else — there is no
 * "missed call" callback. Every event a user cares about is a *transition*:
 * a missed call is ringing that returned to idle without going off-hook, and an
 * outgoing call is off-hook that was never preceded by ringing.
 *
 * Pure and separated from the telephony plumbing so the whole table is
 * unit-tested; getting it wrong means silently wrong automations rather than a
 * crash.
 */
class CallStateMachine(initialState: Int = TelephonyManager.CALL_STATE_IDLE) {

    private var previous = initialState

    fun onState(state: Int): List<CallEvent> {
        if (state == previous) return emptyList()

        val events = when (previous to state) {
            TelephonyManager.CALL_STATE_IDLE to TelephonyManager.CALL_STATE_RINGING ->
                listOf(CallEvent.INCOMING)

            TelephonyManager.CALL_STATE_IDLE to TelephonyManager.CALL_STATE_OFFHOOK ->
                listOf(CallEvent.OUTGOING)

            TelephonyManager.CALL_STATE_RINGING to TelephonyManager.CALL_STATE_OFFHOOK ->
                listOf(CallEvent.ANSWERED)

            // Rang, then stopped without being picked up.
            TelephonyManager.CALL_STATE_RINGING to TelephonyManager.CALL_STATE_IDLE ->
                listOf(CallEvent.MISSED)

            TelephonyManager.CALL_STATE_OFFHOOK to TelephonyManager.CALL_STATE_IDLE ->
                listOf(CallEvent.ENDED)

            else -> emptyList()
        }

        previous = state
        return events
    }
}
