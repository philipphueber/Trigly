package app.phueber.trigly.triggers

import kotlinx.coroutines.CompletableDeferred

/**
 * [TimeZoneChanges] a test can fire on demand, instead of waiting for a real
 * `ACTION_TIMEZONE_CHANGED` broadcast that no JVM test can send.
 *
 * A fresh [CompletableDeferred] is armed after every [fire], so a trigger
 * that loops back around to await another change is not handed one that
 * already completed.
 */
class FakeTimeZoneChanges : TimeZoneChanges {

    private var next = CompletableDeferred<Unit>()

    override suspend fun awaitChange() {
        next.await()
    }

    /** Completes whichever [awaitChange] call is currently suspended, and arms the next one. */
    fun fire() {
        val completing = next
        next = CompletableDeferred()
        completing.complete(Unit)
    }
}
