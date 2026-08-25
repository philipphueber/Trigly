package app.phueber.trigly.ui

import android.app.Activity
import android.os.Bundle
import app.phueber.trigly.triggers.ShortcutEvents

/**
 * Where a tap on a pinned home-screen shortcut actually lands.
 *
 * Plain [Activity] rather than `ComponentActivity`: this never inflates a view
 * or touches Compose, and pulling in that machinery for something that finishes
 * inside `onCreate` would be dead weight. See the manifest entry for the theme
 * that keeps it from ever drawing a frame.
 *
 * **Order matters, and mirrors [BootReceiver].** A tap on the shortcut is, from
 * a cold state, exactly the kind of event `BootReceiver` deals with for a
 * reboot: the thing that starts the process is the same thing a trigger wants
 * to react to, so by the time the engine is up and a collector could be
 * listening, the moment is already gone. The fix is the same one `BootEvents`
 * uses — record first, in this same process, so the engine's collector finds
 * the record already sitting there a few milliseconds later. Recording after
 * starting the service would race the engine's own startup against the record
 * it depends on, and on a slow device the engine could win.
 */
class ShortcutTargetActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Exported, because a launcher (a different app, a different uid) has
        // to be able to start this directly by component name — which also
        // means anything else on the device can send this intent with any
        // extra or none at all. A missing or blank id is not a bug report,
        // it is normal traffic for an exported activity, so this finishes
        // quietly instead of asserting or crashing.
        val shortcutId = intent?.getStringExtra(ShortcutPinning.EXTRA_SHORTCUT_ID)
        if (shortcutId.isNullOrBlank()) {
            finish()
            return
        }

        ShortcutEvents.record(shortcutId, System.currentTimeMillis())

        // Same reasoning as BootReceiver: this is one of the cases Android
        // exempts from the API 31 background-start ban, because the activity
        // is what the user just interacted with. EngineService.start() already
        // swallows a refusal, so nothing here needs to guard against it again.
        EngineService.start(this)

        // No UI was ever inflated, so finishing is the whole lifecycle. Nothing
        // here is asynchronous - record() and start() both return before this
        // line - so there is nothing to wait for before finishing.
        finish()
    }
}
