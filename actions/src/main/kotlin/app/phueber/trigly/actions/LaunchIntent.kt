package app.phueber.trigly.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import app.phueber.trigly.core.ActionResult

/**
 * Starts an activity on behalf of a rule.
 *
 * **This is the least reliable thing Trigly does, and the reason is structural.**
 * Since Android 10, an app in the background may not start an activity at all
 * unless it holds one of a short list of exemptions — a visible foreground
 * service, the overlay permission, a recent notification the user tapped. A rule
 * that fires while the phone is in a pocket will therefore be *silently
 * ignored*: no exception is thrown, the system simply drops the start and logs
 * a line the app never sees.
 *
 * Nothing here can fix that; it is a platform decision. What this can do is not
 * pretend. Failures that *are* observable — no app to handle the intent, a
 * permission refusal — become [ActionResult.Failure] rather than a crash, and
 * the docs say plainly which actions depend on the exemption.
 *
 * **The engine's foreground service does not fix this**, which is worth saying
 * because it is the obvious guess and it is wrong: running a foreground service
 * is not on the platform's list of background-activity-start exemptions. What
 * is on it, and reachable for this app, is `SYSTEM_ALERT_WINDOW` — or posting a
 * notification and letting the user tap it, which turns the start into
 * something the user did. See `docs/actions.md`.
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
