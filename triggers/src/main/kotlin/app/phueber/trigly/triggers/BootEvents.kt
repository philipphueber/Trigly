package app.phueber.trigly.triggers

/** Why the process came back without the user asking. */
enum class BootReason(val configValue: String) {
    /** The device restarted. */
    RESTART("restart"),

    /** Trigly itself was updated, which also kills the process. */
    APP_UPDATED("app_updated"),
}

/**
 * How the boot broadcast reaches a trigger.
 *
 * Every other broadcast trigger registers a receiver and waits. This one cannot:
 * `BOOT_COMPLETED` is what *starts* the engine, so by the time any trigger is
 * collecting, the broadcast is long delivered and gone. Waiting for it is waiting
 * for something that already happened — the same shape of mistake as watching for
 * a notification's removal edge after it has been removed.
 *
 * So the manifest receiver records it and the trigger reads the record. Both live
 * in the same process — `BootReceiver` starts `EngineService` immediately after
 * writing here, so the write always precedes the engine and therefore the
 * collection. A process-wide object is enough; nothing needs to persist, because
 * the only question is "did *this* process start because of a boot", and a new
 * process means a new answer.
 *
 * Not consume-once, deliberately: two rules on the same trigger must both fire,
 * so reading does not clear. [pending] is bounded by a freshness window instead —
 * see there for why that is the honest bound.
 */
object BootEvents {

    private data class Boot(val reason: BootReason, val atMillis: Long)

    @Volatile
    private var last: Boot? = null

    /** Called by the manifest receiver, before the engine is started. */
    fun record(reason: BootReason, atMillis: Long) {
        last = Boot(reason, atMillis)
    }

    /**
     * The boot this collection should fire for, or null.
     *
     * Bounded by [windowMillis] because the record outlives the moment. Boot to
     * engine start to trigger registration is sub-second in practice, so anything
     * inside the window is genuinely "we came up because of this". Outside it,
     * the rule was enabled — or the engine restarted — long after the event, and
     * firing then would report a restart that finished ten minutes ago. A rule
     * toggled off and on at lunchtime must not announce the morning's reboot.
     */
    fun pending(
        nowMillis: Long,
        reason: BootReason,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): Boolean {
        val boot = last ?: return false
        if (boot.reason != reason) return false
        val age = nowMillis - boot.atMillis
        return age in 0..windowMillis
    }

    /** Test seam: forget any recorded boot. */
    fun clear() {
        last = null
    }

    /**
     * Generous next to the sub-second reality, because the cost of being late is
     * asymmetric: a slow device that takes half a minute to finish booting should
     * still fire its restart rules, while a rule enabled by hand minutes later
     * should not.
     */
    const val DEFAULT_WINDOW_MILLIS = 60_000L
}
