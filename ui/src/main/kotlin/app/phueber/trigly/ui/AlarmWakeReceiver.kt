package app.phueber.trigly.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.phueber.trigly.triggers.AlarmWakeEvents

/**
 * Brings the engine back for a durable wait's alarm, the same way
 * [BootReceiver] and [BluetoothConnectionReceiver] bring it back for their
 * own events.
 *
 * `AlarmManagerScheduler.waitForDurable` and `waitUntilDurable`, called from
 * `IntervalTrigger` and `SolarTrigger`, set a `PendingIntent` alarm carrying
 * [AlarmWakeEvents.ACTION_ALARM_WAKE] alongside the ordinary listener alarm
 * that dies with this process. `docs/todo.md`'s T17 is why that second alarm
 * exists: AOSP deletes the listener alarm the moment the process holding it
 * is gone, so without this receiver a killed process left an interval rule
 * or a sunrise/sunset rule with nothing pending at all, and nothing that
 * would ever set a new one.
 *
 * `exported="false"` in the manifest, unlike [BootReceiver] and
 * [BluetoothConnectionReceiver]: those two must be exported because the
 * system, a different uid, sends `BOOT_COMPLETED` and the ACL broadcasts.
 * [AlarmWakeEvents.ACTION_ALARM_WAKE] is an action this app invented and
 * only this app's own `PendingIntent` ever sends, scoped to this app's own
 * package by `AlarmManagerScheduler`, so nothing outside this app should
 * ever be able to reach this receiver at all.
 *
 * **Whether [EngineService.start] below actually succeeds is not settled by
 * this receiver running.** `AlarmManagerScheduler`'s own KDoc has the sourced
 * finding: only an *exact* alarm is put on the platform's own allowlist for
 * starting a foreground service from the background, and this receiver is
 * reached by an ordinary, inexact one, on purpose, per `docs/todo.md`'s T17.
 * `EngineService.start` already expects a refusal from a call site that is
 * not one of the platform's own exemptions, and catches it quietly. On a
 * device where the user has granted Trigly's own battery-optimisation
 * exemption request, the start still succeeds, through a different, older
 * allowlist that exemption joins; see the same KDoc. Either way, [record]
 * below still happens, so whichever restart comes next, for whatever reason,
 * finds the record and can treat itself as caught up.
 */
class AlarmWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmWakeEvents.ACTION_ALARM_WAKE) return

        // Recorded before the engine is started, for the same reason
        // BootReceiver and BluetoothConnectionReceiver record first: see
        // AlarmWakeEvents.
        AlarmWakeEvents.record(System.currentTimeMillis())

        EngineService.start(context)
    }
}
