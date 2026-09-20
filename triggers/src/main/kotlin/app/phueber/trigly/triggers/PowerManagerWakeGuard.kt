package app.phueber.trigly.triggers

import android.content.Context
import android.os.PowerManager
import app.phueber.trigly.core.WakeGuard

/**
 * The real [WakeGuard]: a `PARTIAL_WAKE_LOCK` held for the length of one block.
 *
 * Lives here beside [AlarmManagerScheduler] rather than in `:ui`, for the
 * reason that file gives: the Android side of a `:core` port belongs with the
 * other platform plumbing, and `:ui` only has to know where to find it.
 *
 * **A partial lock, which is the weakest one that answers the question.** It
 * keeps the CPU running and lets the screen go off and stay off. The stronger
 * levels exist to light a display, which no automation rule has any business
 * doing to a phone in a pocket.
 *
 * **A fresh lock object per span, with reference counting off.** The two
 * settings go together. A counted lock throws `WakeLock under-locked` on a
 * release that outnumbers its acquires, and a timeout releases the whole lock
 * whatever the count says, so the two features combined turn a late timeout
 * into a crash in unrelated code. One object per span has neither problem: the
 * span owns its lock outright, and a second release is a no-op rather than an
 * exception.
 *
 * **The release is in a `finally`, and that is the real release.** Kotlin runs
 * `finally` when a coroutine is cancelled, which is how disabling a rule
 * mid-wait gives the lock back: `TriggerEngine.stopRule` cancels the rule's
 * job, the cancellation reaches the suspension inside the block, and the hold
 * ends on the way out. The same path covers a thrown exception and the whole
 * scope being cancelled when `EngineService` stops.
 *
 * **`acquire(timeout)` is a backstop for a path that escapes all of that while
 * the process still lives.** Process death needs no backstop: a wake lock is
 * held against a Binder token, so the system releases every lock belonging to a
 * process it kills, whether that is a crash, an out-of-memory kill or a user's
 * force stop.
 *
 * **A missing `PowerManager` degrades to running the block unguarded** rather
 * than refusing to run it. That matches [WakeGuard.None]: the work still
 * happens, and it happens late if the device suspends underneath it, which is
 * exactly what this app did before this class existed.
 */
class PowerManagerWakeGuard(context: Context) : WakeGuard {

    private val power = context.applicationContext.getSystemService(PowerManager::class.java)

    override suspend fun <T> keepingAwake(
        reason: String,
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T {
        val lock = power
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:$reason")
            ?.apply { setReferenceCounted(false) }
            ?: return block()

        lock.acquire(timeoutMillis)
        try {
            return block()
        } finally {
            // The timeout releases through a Handler in this process and clears
            // the held flag, so asking is the correct guard and there is no
            // exception to catch here.
            if (lock.isHeld) lock.release()
        }
    }

    private companion object {
        /**
         * What `dumpsys power` and battery stats show. Namespaced, because the
         * one question a person asks of that output is which app is holding
         * the CPU, and a bare tag answers it for nobody.
         */
        const val TAG = "trigly"
    }
}
