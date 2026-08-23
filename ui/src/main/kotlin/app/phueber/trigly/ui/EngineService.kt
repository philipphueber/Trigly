package app.phueber.trigly.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.TriggerEngine
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Where the [TriggerEngine] actually lives.
 *
 * **Why a foreground service and not the application scope.** Since API 26 most
 * implicit broadcasts may not be declared in a manifest, so every broadcast
 * trigger registers its receiver at runtime — which means a live process is not
 * an optimisation, it is the mechanism. An engine in the application scope has
 * exactly the lifetime the system feels like giving the process, and on many
 * OEM builds that is minutes. A foreground service with an ongoing notification
 * is the only arrangement Android offers where "keep running" is a promise
 * rather than a hope, and it is the arrangement the user can see and revoke.
 *
 * The visible notification is a feature, not a tax. An automation app that
 * watches the device silently and invisibly is exactly what a user should not
 * have to trust; the ongoing notification says it is watching, says how much,
 * and leads back into the app.
 *
 * **The service owns the engine's lifetime.** The engine's scope is this
 * service's scope, so there is one answer to "is Trigly running?" rather than
 * two that can disagree. Nothing outside holds a reference to the engine.
 *
 * **It ends itself when there is nothing to run.** A permanent notification for
 * a service watching zero rules is a cost with no benefit, so zero enabled rules
 * stops it; [TriglyApp] starts it again when a rule is enabled. Starting is the
 * app's job and stopping is the service's, so the two never race for the same
 * decision.
 */
class EngineService : Service() {

    /**
     * Deliberately not `lifecycleScope`: the engine's work is collection and
     * dispatch with no main-thread component, and `Dispatchers.Default` keeps
     * rule evaluation off the thread the system watches for responsiveness.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var engine: TriggerEngine

    /**
     * Whether the rule store has been read at least once. Guards [onStartCommand]
     * from re-posting a notification that would say "no rule could be started"
     * in the moment before the first emission arrives.
     */
    private var hasRules = false

    private val notifications: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        val container = (application as TriglyApp).container

        // First, before anything that could be slow: startForegroundService gives
        // us five seconds to become a foreground service, and missing that window
        // is a ForegroundServiceDidNotStartInTimeException, not a warning.
        createChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.engine_starting)))

        engine = TriggerEngine(
            registry = container.registry,
            scope = scope,
            onOutcome = ::report,
            onStartFailure = { rule, cause ->
                // The rule is stored and enabled but cannot be built — most
                // likely config from an import this build does not understand.
                // Logged rather than swallowed; the other rules keep running.
                Log.w(TAG, "rule '${rule.name}' could not be started", cause)
            },
        )

        scope.launch {
            container.ruleRepository.rules().collect(::applyRules)
        }
    }

    /**
     * [START_STICKY] because the whole point of this service is to come back.
     * The restart arrives with a null intent, which is why nothing here reads
     * one: every piece of state the service needs comes from the rule store.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-post, because a start request is also how the app says "something
        // changed out here". The case that needs it: on Android 13 and later the
        // system silently drops this notification while POST_NOTIFICATIONS is
        // refused, so the grant arrives long after the only notify() call — and
        // without this the service would keep running invisibly until the next
        // rule edit.
        if (hasRules) notifications.notify(NOTIFICATION_ID, notification(summary()))
        return START_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        scope.cancel()
        super.onDestroy()
    }

    /** Nothing binds to this; the engine is reached through the rule store, not through IPC. */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyRules(rules: List<Rule>) {
        if (rules.none { it.enabled }) {
            stopSelf()
            return
        }

        engine.sync(rules)
        hasRules = true
        notifications.notify(NOTIFICATION_ID, notification(summary()))
    }

    /**
     * What the notification says, counting what is *running* rather than what is
     * enabled. The two differ exactly when a rule could not be built, and the
     * running count is the one that is true.
     */
    private fun summary(): String {
        val running = engine.runningRuleIds.size
        return if (running == 0) {
            getString(R.string.engine_none_started)
        } else {
            resources.getQuantityString(R.plurals.engine_watching, running, running)
        }
    }

    private fun report(rule: Rule, event: TriggerEvent, result: ActionResult) {
        if (result is ActionResult.Failure) {
            Log.w(TAG, "rule '${rule.name}' on ${event.triggerType}: ${result.reason}")
        }
    }

    /**
     * `IMPORTANCE_LOW`: the notification has to exist, but it is a status line,
     * not news. Low keeps it silent and out of the heads-up area while leaving
     * it visible in the shade, which is the honest middle — importance `MIN`
     * would hide the one thing telling the user the app is watching.
     */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.engine_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.engine_channel_description)
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            // No timestamp: this notification is a state, not an event, and a
            // relative time next to it only invites the question "since when?".
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "Trigly"
        private const val CHANNEL_ID = "trigly_engine"
        private const val NOTIFICATION_ID = 1

        /**
         * Starts the engine, or does nothing if it is already running.
         *
         * **The refusal is caught rather than prevented**, and the reason is
         * that it cannot be prevented from here. From API 31 an app may only
         * start a foreground service while it is exempt — visible on screen,
         * responding to `BOOT_COMPLETED`, or excused from battery optimisation —
         * and there is no API that answers "am I exempt right now?" reliably
         * enough to branch on. Every call site here is one of the exempt cases,
         * so a refusal means the process woke for some other reason and the
         * service will be restarted by [START_STICKY] anyway. Crashing over it
         * would turn a missed start into a dead app.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, EngineService::class.java))
            } catch (refused: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException, which is API 31+ and
                // an IllegalStateException — catching the supertype avoids a
                // version guard for a class that does not exist on API 30.
                Log.w(TAG, "the system refused a background start of the engine", refused)
            }
        }
    }
}
