package app.phueber.trigly.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import app.phueber.trigly.core.ActionResult

/**
 * The three ways a rule can hand an intent to the system, each with its own
 * platform reality: this function, [sendBroadcastForRule] and
 * [startServiceForRule] below. `fire_intent` is the only action that lets a
 * person choose among them; every other action that opens something is always
 * an activity start and always calls this one.
 *
 * **This is the least reliable thing Trigly does, and the reason is structural.**
 * Since Android 10, an app in the background may not start an activity at all
 * unless it holds one of a short list of exemptions: a visible foreground
 * service, the overlay permission, a recent notification the user tapped. A rule
 * that fires while the phone is in a pocket will therefore be *silently
 * ignored*: no exception is thrown, the system simply drops the start and logs
 * a line the app never sees.
 *
 * Nothing here can fix that; it is a platform decision. What this can do is not
 * pretend. Failures that *are* observable (no app to handle the intent, a
 * permission refusal) become [ActionResult.Failure] rather than a crash, and
 * the docs say plainly which actions depend on the exemption.
 *
 * **The engine's foreground service does not fix this**, which is worth saying
 * because it is the obvious guess and it is wrong: running a foreground service
 * is not on the platform's list of background-activity-start exemptions. What
 * is on it, and reachable for this app, is `SYSTEM_ALERT_WINDOW`, or posting a
 * notification and letting the user tap it, which turns the start into
 * something the user did. See `docs/actions.md`.
 *
 * A different exemption list governs [sendBroadcastForRule] and
 * [startServiceForRule] below. See their own KDoc and `docs/actions.md`'s
 * "Firing a predefined intent" for why neither copies this one's requirement.
 */
internal fun Context.launchForRule(intent: Intent): ActionResult {
    // Mandatory: there is no Activity on the stack to inherit a task from.
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return try {
        startActivity(intent)
        ActionResult.Success()
    } catch (notFound: ActivityNotFoundException) {
        ActionResult.Failure("No app on this device can handle this action.", notFound)
    } catch (denied: SecurityException) {
        ActionResult.Failure("The system refused to start this. ${denied.message}", denied)
    }
}

/**
 * Sends a broadcast on behalf of a rule.
 *
 * **Sending is not gated the way starting an activity is.** There is no
 * background restriction on `sendBroadcast()` at all; any app, in any state,
 * may call it at any time. What is restricted is *delivery* to a manifest-
 * declared receiver in another app for an *implicit* broadcast, one with no
 * explicit package or component, which Android has filtered since API 26 the
 * same way it filters most background broadcasts, precisely so a receiver
 * cannot be woken by every stray system event. A dynamically registered
 * receiver (`registerReceiver` called while the target app is alive) still
 * gets it regardless; Trigly has no way to know which kind the target uses.
 *
 * **This can report success when nothing happened, and there is no fix.**
 * `sendBroadcast()` is fire-and-forget: it never learns whether any receiver
 * ran, so a call that returns normally is not proof of delivery. `FireIntentAction`
 * pre-checks with `PackageManager` before reaching this function specifically
 * to catch the case it can prove (see `decideIntentTargetCheck`), but a
 * receiver Trigly cannot see, or a dynamically registered one it cannot query
 * at all, can still make this report success for a broadcast nobody acted on.
 *
 * The one thing this function does catch: sending an action from Android's
 * protected-broadcast list (a system-only action such as `BOOT_COMPLETED`)
 * throws `SecurityException` immediately, which is the platform's own defence
 * against an app impersonating the system, reported here rather than crashing
 * the rule.
 */
internal fun Context.sendBroadcastForRule(intent: Intent): ActionResult = try {
    sendBroadcast(intent)
    ActionResult.Success()
} catch (denied: SecurityException) {
    ActionResult.Failure("The system refused to send this broadcast. ${denied.message}", denied)
}

/**
 * Starts a service on behalf of a rule.
 *
 * **A different exemption than [launchForRule]'s, and this one usually
 * applies.** Since API 26, `startService()` throws for an app the platform
 * considers to be in the background. But a foreground service is one of the
 * platform's own exemptions from *that* restriction, unlike the newer,
 * stricter background-activity-start ban [launchForRule] documents. Trigly's
 * engine runs as a foreground service (`EngineService`) for as long as any
 * rule needs it, so a rule firing from inside it is ordinarily exempt and
 * this call should succeed without the overlay permission `launchForRule`
 * needs. If that were ever not true (this build stops relying on the
 * foreground service, or a future Android version removes the exemption),
 * the platform's own `IllegalStateException` says so here rather than being
 * silently swallowed.
 *
 * **An implicit service intent is refused before this is ever called.**
 * Unlike an activity or a broadcast, Android has required an *explicit*
 * intent (naming a package and a class) for `startService()` since API 21;
 * an implicit one throws `IllegalArgumentException` immediately. So
 * `buildFireIntentSpec` requires both a target app and a class name whenever
 * the send mode is "service", at config-build time, rather than letting a
 * rule reach this function with nothing to explicitly target.
 *
 * **A null return means no service was found, and that is reported as a
 * failure rather than as success.** `startService()` does not throw for a
 * target that does not exist; it returns null. Reading only "did this throw"
 * would report success for a service Android never started.
 */
internal fun Context.startServiceForRule(intent: Intent): ActionResult = try {
    val started = startService(intent)
    if (started == null) {
        ActionResult.Failure("No app on this device has a service that answers this.")
    } else {
        ActionResult.Success()
    }
} catch (illegalState: IllegalStateException) {
    ActionResult.Failure("The system refused to start this service. ${illegalState.message}", illegalState)
} catch (illegalArgument: IllegalArgumentException) {
    ActionResult.Failure("The system refused to start this service. ${illegalArgument.message}", illegalArgument)
} catch (denied: SecurityException) {
    ActionResult.Failure("The system refused to start this service. ${denied.message}", denied)
}
