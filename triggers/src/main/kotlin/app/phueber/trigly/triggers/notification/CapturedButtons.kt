package app.phueber.trigly.triggers.notification

import android.app.PendingIntent

/**
 * The notification buttons a rule has kept, so another rule can press one after
 * the notification is gone.
 *
 * **In memory, and that is not a shortcut to be fixed later.** A
 * `PendingIntent` is a live token the system holds on behalf of the app that
 * created it. It is not a URI, not an id, and not anything that can be written
 * down: there is no form of it to put in a variable, store in the database or
 * rebuild after a restart. Anything that claimed to persist a capture would be
 * storing something it could not use. So this map is the honest shape, and the
 * limit it carries belongs in what a person reads before building the rule, not
 * in a comment: **a captured button dies with Trigly's process.**
 *
 * In practice that is survivable, and the engine is why. A rule that captures at
 * all is a rule that is enabled, so `EngineService` is running as a foreground
 * service, which is what keeps this process alive between the capture and the
 * press. `docs/todo.md`'s R1 covers the one cause nothing here can fix: a user's
 * force stop.
 *
 * **Why an object rather than a field on the controller.**
 * `ListenerNotificationController` deliberately holds no state of its own and is
 * constructed freshly wherever it is needed, precisely so it stays correct
 * across the unbind and rebind cycles the system puts the listener through. A
 * capture has to outlive that, and outlive the controller instance that made it,
 * so it cannot live there. This is the same reasoning `NotificationEvents` and
 * `ShortcutEvents` are objects for.
 *
 * Bounded by [MAX_CAPTURES]. Nothing here is ever read by name unless a rule
 * names it, so an unbounded map would be a slow leak fed by a rule firing all
 * day. When the bound is reached the oldest capture goes, because the newest is
 * the one the owning app is least likely to have rebuilt underneath.
 */
internal object CapturedButtons {

    /**
     * How many captures to hold. Eight, chosen the way the engine's other caps
     * are: comfortably past any rule set a person would deliberately build, and
     * small enough that the memory held is a handful of tokens rather than a
     * growing list.
     */
    const val MAX_CAPTURES = 8

    // Insertion-ordered on purpose: eviction takes the oldest, and
    // LinkedHashMap's iteration order is what makes "oldest" meaningful.
    private val kept = LinkedHashMap<String, PendingIntent>()

    /**
     * Keeps [pending] under [name], replacing whatever was there.
     *
     * Replacing rather than refusing, because a rule that captures on every
     * appearance of a notification is the ordinary case, and the newest token is
     * the one most likely to still work: an app that rebuilds its notification
     * with `FLAG_CANCEL_CURRENT` invalidates every copy anyone held of the old
     * one, which `CapturedButtonOutlivesDismissalTest` pins.
     */
    @Synchronized
    fun keep(name: String, pending: PendingIntent) {
        // Removed first so a re-capture moves the name to the newest position
        // rather than keeping its original place in the eviction order.
        kept.remove(name)
        kept[name] = pending

        while (kept.size > MAX_CAPTURES) {
            val oldest = kept.keys.firstOrNull() ?: break
            kept.remove(oldest)
        }
    }

    /** What is kept under [name], or null if nothing is. */
    @Synchronized
    fun get(name: String): PendingIntent? = kept[name]

    /** The names currently held, oldest first. For a screen that lists them. */
    @Synchronized
    fun names(): List<String> = kept.keys.toList()

    /** Drops everything. For tests, so one does not leak into the next. */
    @Synchronized
    fun clear() {
        kept.clear()
    }
}
