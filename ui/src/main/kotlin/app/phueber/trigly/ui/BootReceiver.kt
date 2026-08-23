package app.phueber.trigly.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the engine back after the two events that end a process without the
 * user doing anything: a reboot, and the app being updated.
 *
 * This is the one receiver in the project that is declared in the manifest
 * rather than registered at runtime, and it has to be: there is no process to
 * register anything in at the moment either broadcast is sent. Both are on the
 * short list of broadcasts still delivered to manifest receivers, and both are
 * exemptions from the API 31 ban on starting a foreground service from the
 * background — which is why the engine can be started from here and not from
 * an arbitrary background wake-up.
 *
 * `ACTION_MY_PACKAGE_REPLACED` matters more than it looks. Updating the app
 * kills the process and does *not* restart the service, so without this line
 * every update would silently stop every rule until the user next opened the
 * app — the failure mode that is hardest to notice, because nothing looks wrong.
 *
 * Deliberately **not** `ACTION_LOCKED_BOOT_COMPLETED`: the rule database lives
 * in credential-encrypted storage and cannot be read before the first unlock,
 * so starting earlier would only mean starting and finding nothing.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in STARTS_THE_ENGINE) return

        // No check for "are any rules enabled?" first: that is a database read,
        // it would need goAsync() and a coroutine in a receiver, and the service
        // already answers the same question one emission later and stops itself.
        // A notification that is deferred for ten seconds by default is never
        // seen when the service lives for milliseconds.
        EngineService.start(context)
    }

    private companion object {
        val STARTS_THE_ENGINE = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
