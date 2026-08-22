package app.phueber.trigly.triggers

/**
 * Turns a stream of *state readings* into a stream of *changes*.
 *
 * Two Android facts make this necessary, and both are silent footguns:
 *
 *  - **Sticky broadcasts replay.** `ACTION_BATTERY_CHANGED` and
 *    `ACTION_HEADSET_PLUG` deliver the current state the moment you register.
 *    Without [suppressInitialState] a rule would fire once just for being
 *    enabled, which reads to the user as a spurious trigger.
 *  - **Broadcasts repeat.** `ACTION_BATTERY_CHANGED` arrives every time any
 *    battery field moves, and `ACTION_CONFIGURATION_CHANGED` arrives for locale
 *    and font-scale changes too. A trigger watching one dimension must ignore
 *    readings where that dimension did not move, or it fires hundreds of times.
 *
 * Not thread-safe: one instance per flow collection, touched only from the
 * receiver's callback thread.
 */
class StateTracker(private val suppressInitialState: Boolean) {

    private var seenAny = false
    private var lastKey: String? = null

    /**
     * @param stateKey the distinct state this reading represents, or null for a
     *   source that is already edge-shaped (`ACTION_POWER_CONNECTED` is an event,
     *   not a state) and so needs no deduplication.
     * @return whether this reading represents a change worth acting on.
     */
    fun accept(stateKey: String?): Boolean {
        if (stateKey == null) return true

        val isFirst = !seenAny
        val changed = stateKey != lastKey

        // Recorded even when not emitted, so the *next* genuine change is
        // still detected as a change.
        lastKey = stateKey
        seenAny = true

        if (isFirst && suppressInitialState) return false
        return changed
    }
}
