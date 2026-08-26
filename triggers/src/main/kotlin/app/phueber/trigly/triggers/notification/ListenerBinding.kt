package app.phueber.trigly.triggers.notification

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.triggers.AlarmManagerScheduler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * How long the system is given to bind the listener on its own before Trigly
 * asks.
 *
 * A fresh process almost always gets its listener bound within a moment, so
 * asking immediately would mean asking every single time the app starts, and
 * `requestRebind` on an already-bound listener unbinds it first. That would
 * open a window with no listener on exactly the path that was working.
 */
const val BIND_GRACE_MILLIS = 15_000L

/**
 * How often to ask again while nothing is bound.
 *
 * Long, because this is a fallback and not a mechanism. If the first request
 * did not produce a binding, the reason is usually one more requests will not
 * fix, and the cost of asking forever at a short interval is worse than the
 * delay in the rare case where a later attempt does work.
 */
const val BIND_RETRY_MILLIS = 5 * 60_000L

/**
 * Asks for a binding whenever the notification listener does not have one.
 *
 * **The failure this exists for.** The system owns the listener's lifetime and
 * does not always give it back. A process killed by an OEM battery manager, and
 * an app update most reliably of all, can leave the listener unbound while
 * everything else recovers: `START_STICKY` brings `EngineService` back, the
 * engine starts every rule, and the ongoing notification says it is watching.
 * But nothing binds the listener, so `NotificationEvents.posted` never emits and
 * every notification rule is dead. There is no callback for this, because the
 * process that would have received `onListenerDisconnected` is the one that
 * died. `requestRebind` is static for exactly this reason: a process with no
 * binding at all can still ask for one.
 *
 * **Why a watcher rather than a call in `onListenerDisconnected`.** That
 * callback covers only the case where a live process is told, which is the case
 * that was already recoverable. Watching [connected] covers it *and* the process
 * that came back to find nothing bound, through one mechanism with one grace
 * period, and with no risk of a disconnect-request-disconnect loop.
 *
 * `collectLatest` is what makes the shape right: the retry loop below is
 * cancelled the instant a binding arrives, so the normal path costs one
 * cancelled wait and nothing else.
 *
 * **What it cannot see.** If the service is destroyed without
 * `onListenerDisconnected` being delivered, [connected] stays true and this
 * waits for a change that never comes. Nothing observable distinguishes that
 * from a healthy binding, short of a binder call on a timer, which would cost
 * every user battery to catch a case the platform is not documented to produce.
 *
 * **The grace period and the retry both now go through [scheduler], not a
 * plain coroutine `delay`.** This is the repair path for a dead listener, so
 * it must not itself be asleep in Doze while the process it is trying to fix
 * stays alive; `docs/todo.md`'s T1 names this file as the case that is
 * easiest to miss. What this still cannot do is run in a process the system
 * has already killed, or one the user has force-stopped; see that document's
 * R1.
 *
 * Suspends forever. The caller's scope is the stop button.
 *
 * @param connected whether the listener is bound; see `ServiceEventBus`.
 * @param isAccessGranted whether the user has granted notification access at
 *   all. Without it `requestRebind` cannot succeed, and asking would be noise
 *   rather than a fallback: a rule needing access it does not have is already
 *   reported as an unmet requirement.
 */
suspend fun keepListenerBound(
    connected: StateFlow<Boolean>,
    isAccessGranted: () -> Boolean,
    requestRebind: () -> Unit,
    scheduler: AlarmScheduler,
    graceMillis: Long = BIND_GRACE_MILLIS,
    retryMillis: Long = BIND_RETRY_MILLIS,
) {
    connected.collectLatest { isConnected ->
        if (isConnected) return@collectLatest

        scheduler.waitFor(graceMillis)
        while (true) {
            if (isAccessGranted()) requestRebind()
            scheduler.waitFor(retryMillis)
        }
    }
}

/**
 * [keepListenerBound], wired to the real listener and the real settings.
 *
 * Lives here rather than in `:ui` so the module that owns the service owns the
 * knowledge of how to get it back, and the caller only has to decide *when* to
 * run it. `EngineService` is that caller: the binding matters exactly as long as
 * there are rules running.
 */
suspend fun keepNotificationListenerBound(context: Context) {
    val checker = RequirementChecker(context)
    val access = ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_LISTENER)
    val component = ComponentName(context, TriglyNotificationListenerService::class.java)

    keepListenerBound(
        connected = NotificationEvents.posted.connected,
        isAccessGranted = { checker.isSatisfied(access) },
        requestRebind = {
            // The platform refuses this for a listener the user has not enabled,
            // and the check above is what normally keeps us from asking. It is
            // still wrapped, because access can be revoked between the two, and
            // a revocation is an ordinary thing a person does rather than a
            // reason for the engine's coroutine to die. Nothing is logged: the
            // rule that needs the access already reports the requirement.
            runCatching { NotificationListenerService.requestRebind(component) }
        },
        // Built here rather than threaded in, for the same reason `checker` is:
        // the caller only has to decide when to run this, not what it takes to
        // get a listener back.
        scheduler = AlarmManagerScheduler(context),
    )
}
