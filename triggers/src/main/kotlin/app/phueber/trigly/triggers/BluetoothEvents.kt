package app.phueber.trigly.triggers

/**
 * How a Bluetooth connect or disconnect reaches a trigger.
 *
 * `ACTION_ACL_CONNECTED` and `ACTION_ACL_DISCONNECTED` are sent by the system,
 * not by Trigly, so nothing forces them to arrive while Trigly's own process
 * is alive. Registering a receiver only while a trigger is collecting misses
 * every connect that happens after the system has killed that process for
 * being idle, which is the exact shape of the bug this object exists to fix:
 * the phone reconnects to a device, nothing is listening, and nothing is ever
 * reported, in a process that no longer exists to report it from.
 *
 * The fix has the same shape as [BootEvents] and [ShortcutEvents], and sits
 * closer to [ShortcutEvents]: **a Bluetooth connect is not reliably cold or
 * warm.** Trigly's engine is commonly already running as a foreground
 * service, in which case the trigger asking about this device is already
 * collecting and wants the sighting delivered live; but the system can just
 * as easily have killed the process, in which case the connect is what
 * restarts it, and the sighting lands before any trigger exists to collect
 * it. [sightings] is the live bus for the first case. [pending] is the
 * freshness-windowed record for the second. [record] feeds both from the one
 * call site, in `BluetoothConnectionReceiver`, so that manifest receiver does
 * not have to work out which case applies; it never can, since a manifest
 * receiver runs before anything in this process has had a chance to say
 * whether it was already alive.
 *
 * Unlike [ShortcutEvents], there is no id to key [pending] on. A shortcut tap
 * already carries the one identifier that matters, its `shortcutId`; a
 * Bluetooth sighting's identity is a device address or name, and deciding
 * whether that satisfies a particular rule is [bluetoothDeviceMatches]'s job,
 * not this object's. [pending] just answers "what was last seen, and how long
 * ago", the same way [BootEvents.pending] does, and leaves the matching to
 * whichever trigger asks.
 *
 * **Exactly once, in three cases, not two.** Reading here does not consume,
 * for the same reason as [BootEvents]: two rules watching the same device
 * both need to see the same sighting. What keeps one sighting from reaching
 * one rule *twice* is [BluetoothConnectionTrigger]'s own guard, which tracks
 * the timestamp it has already turned into an emission and skips a repeat of
 * that exact timestamp arriving a second time, the same defence
 * [ShortcutTrigger] uses against [ShortcutEvents]. What is specific to
 * Bluetooth, and has no counterpart on [BootEvents] or [ShortcutEvents], is
 * the third case: some real accessories are known to send the same edge for
 * the same device a second time, several seconds after the first, as a
 * platform quirk rather than a second event. [record] is where that is
 * caught, once, for every trigger at once, rather than asked of each trigger
 * separately. See [record] for the window that bounds it.
 */
object BluetoothEvents {

    /** Which of the two ACL edges a sighting reports. */
    enum class Action {
        CONNECTED,
        DISCONNECTED,
    }

    /** One connect or disconnect, with whatever identity could be read for it. */
    data class Sighting(
        val action: Action,
        val address: String?,
        val name: String?,
        val atMillis: Long,
    )

    @Volatile
    private var last: Sighting? = null

    /** Delivered to whichever trigger is already collecting when a sighting lands. */
    val sightings = ServiceEventBus<Sighting>()

    /**
     * Called once per broadcast `BluetoothConnectionReceiver` gets, before it
     * starts the engine. [address] and [name] are already resolved by then;
     * see `bluetoothIdentify`, which the receiver and this object's callers
     * share so every reader of a sighting agrees on what it means.
     *
     * A repeat of the same edge for the same device, arriving within
     * [DUPLICATE_WINDOW_MILLIS] of the one just recorded, updates nothing and
     * publishes nothing. That is not a guess about what the second broadcast
     * meant; it is the documented behaviour of some real accessories, which
     * report one physical disconnect as two `ACTION_ACL_DISCONNECTED`
     * broadcasts several seconds apart. Swallowing the repeat here, once,
     * means no trigger has to notice it on its own, and the disconnect
     * debounce and the connect emission stay exactly as simple as a rule with
     * no such accessory needs them to be.
     */
    fun record(action: Action, address: String?, name: String?, atMillis: Long) {
        val candidate = Sighting(action, address, name, atMillis)
        val previous = last
        if (previous != null && candidate.isRepeatOf(previous) &&
            atMillis - previous.atMillis in 0..DUPLICATE_WINDOW_MILLIS
        ) {
            return
        }
        last = candidate
        sightings.publish(candidate)
    }

    /**
     * The most recent sighting, if it is fresh enough for a cold-starting
     * collection to treat as "why this process just came up", or null.
     *
     * Bounded by [windowMillis] for the same reason [BootEvents.pending] is:
     * the record outlives the moment. Receiver to engine start to trigger
     * collection is ordinarily sub-second, the same handoff a shortcut tap
     * makes; a rule enabled by hand well after that would otherwise announce
     * a connect that already happened and was already missed.
     */
    fun pending(nowMillis: Long, windowMillis: Long = DEFAULT_WINDOW_MILLIS): Sighting? {
        val sighting = last ?: return null
        val age = nowMillis - sighting.atMillis
        return sighting.takeIf { age in 0..windowMillis }
    }

    /** Test seam: forget any recorded sighting. */
    fun clear() {
        last = null
    }

    /**
     * Same magnitude as [ShortcutEvents.DEFAULT_WINDOW_MILLIS] and for the same
     * reason: starting Trigly's own engine service from a manifest receiver is
     * this app's own handoff, ordinarily sub-second, and fifteen seconds is
     * generous next to that without being wide enough for a stale sighting to
     * look fresh.
     */
    const val DEFAULT_WINDOW_MILLIS = 15_000L

    /**
     * How long a repeat of the same edge for the same device is treated as the
     * platform re-sending rather than a second, genuine event. The known case
     * resends roughly ten seconds after the first; this is set comfortably
     * past that rather than tuned tightly to it, since being a little too slow
     * to accept a second genuine connect from the very same device costs far
     * less than firing a rule twice for one.
     */
    const val DUPLICATE_WINDOW_MILLIS = 20_000L
}

/**
 * Same edge, same device, ignoring [BluetoothEvents.Sighting.atMillis].
 *
 * The device half is [isSameDevice], the rule the disconnect debounce already
 * uses, rather than a field-by-field comparison. The difference shows up in the
 * case that matters: a repeat broadcast does not have to carry the same fields
 * as the first one. A device whose name the stack had not resolved yet reports
 * the first sighting with an address and no name, and the repeat a few seconds
 * later with both. Demanding that the names match too would call that a new
 * connect, and the rule would run twice for one of them, which is the failure
 * this filter exists to prevent rather than one to introduce.
 */
private fun BluetoothEvents.Sighting.isRepeatOf(other: BluetoothEvents.Sighting): Boolean =
    action == other.action && isSameDevice(address, name, other.address, other.name)
