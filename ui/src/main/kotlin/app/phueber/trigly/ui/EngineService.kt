package app.phueber.trigly.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.TriggerEngine
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.triggers.notification.keepNotificationListenerBound
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
    @Volatile
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
        goForeground(notification(getString(R.string.engine_starting)))

        engine = TriggerEngine(
            registry = container.registry,
            scope = scope,
            onOutcome = ::report,
            onStartFailure = ::reportStartFailure,
            onSuppressed = ::reportSuppressed,
        )

        scope.launch {
            container.ruleRepository.rules().collect(::applyRules)
        }

        // The notification listener can come back unbound, most reliably after
        // an app update, and nothing tells this process that it did. The engine
        // would then run every rule and report itself as watching while every
        // notification rule was dead. Tied to the engine's scope because the
        // binding matters exactly as long as there are rules to run.
        scope.launch { keepNotificationListenerBound(this@EngineService) }
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
        //
        // Through `startForeground` rather than `notify`, because this is also
        // the only moment the service can re-claim its foreground types. The
        // types are fixed when they are claimed, a location grant usually
        // arrives long after `onCreate`, and `MainActivity` pokes the service
        // after every grant. Without this the engine would keep running as
        // `specialUse` alone until something restarted it, and the area check
        // would keep failing after the user did exactly what was asked.
        if (hasRules) goForeground(notification(summary()))
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
        // A rule the engine is running is not a rule that failed to start, so an
        // edit that fixes the config clears the report here rather than leaving
        // it up until a trigger fires, which may be days. Narrow by design: see
        // RuleFaultLog.started.
        val faults = (application as TriglyApp).container.ruleFaults
        engine.runningRuleIds.forEach(faults::started)
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

    /**
     * Every action outcome, recorded where the rule list can read it.
     *
     * The log line stays: logcat is still where a developer looks, and it is the
     * only record that survives the process. What is new is that a failure also
     * reaches the screen, through [AppContainer.ruleFaults]. Until now a
     * rule whose trigger fired and whose action failed was indistinguishable, to
     * the person using it, from a rule whose trigger never fired.
     *
     * A success clears the rule's record, which is what stops a fixed rule
     * carrying an accusation from an hour ago. [RuleFaultLog.succeeded]
     * clears only a record belonging to the same action, so one failing action
     * among several is not erased by the ones that work.
     */
    private fun report(
        rule: Rule,
        event: TriggerEvent,
        actionType: String,
        result: ActionResult,
    ) {
        val failures = (application as TriglyApp).container.ruleFaults
        when (result) {
            is ActionResult.Failure -> {
                Log.w(TAG, "rule '${rule.name}' on ${event.triggerType}: ${result.reason}")
                failures.failed(rule.id, actionType, result.reason)
            }

            is ActionResult.Success -> failures.succeeded(rule.id, actionType)
        }
    }

    /**
     * Becomes a foreground service, or re-posts as one.
     *
     * **Why the version branch, and it is not belt-and-braces.** Below API 34
     * the untyped call is the only safe one. It claims whatever the manifest
     * declares, which is what this service did before location entered the
     * picture and is still right there: nothing under 34 enforces a permission
     * per type, so claiming `location` costs nothing when it is not granted and
     * grants the position read when it is.
     *
     * Naming the types there would be actively wrong. `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
     * arrived in API 34, so on an API 30 device it is a bit the platform does
     * not know, the passed value is then not a subset of the declared types, and
     * `startForeground` throws `IllegalArgumentException`. That is the engine
     * failing to start at all, on every device below 34, to serve a check only
     * devices above 34 perform. `minSdk` is 26 and one of the two gate devices
     * is API 30, so this is a live path and not a hypothetical one.
     *
     * From API 34 the types must be named, because that is where claiming one
     * the app has no permission for throws instead. See [foregroundTypes].
     */
    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundTypes())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Which foreground service types this instance claims.
     *
     * Only ever called from API 34 up; see [goForeground] for why naming types
     * below that would break the service instead of protecting it.
     *
     * **Why this is computed and not simply read from the manifest.** The
     * manifest declares `specialUse|location`, and the no-type `startForeground`
     * claims everything declared. From API 34 that throws for a type the app
     * holds no permission for, so an engine that claimed `location` on a device
     * where the user never granted location would die at startup. Every rule
     * would stop, to fix a location rule that person does not have.
     *
     * So `location` is claimed only when it can be. `specialUse` is always
     * there, which keeps the service legal on its own terms whatever the
     * location answer is.
     *
     * **What claiming it buys.** The fine-location grant alone is "while in
     * use": a position read answers while an activity is on screen, and returns
     * nothing when it is not. The engine is off screen almost always, which is
     * why `location_check` inside an AND answered "I cannot look" and the rule
     * was silently dropped. A foreground service of type `location` is what
     * makes the engine count as in use for that read.
     *
     * It is half of the fix and not all of it. Since Android 12 a foreground
     * service started while the app was in the background loses while-in-use
     * access for the whole life of that instance, whatever type it claims, and
     * `BOOT_COMPLETED` is such a start. `ACCESS_BACKGROUND_LOCATION` is the
     * other half and is what survives a reboot; see the `:triggers` manifest.
     */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (hasLocationGrant()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return types
    }

    /**
     * Coarse counts, not only fine. The platform accepts either for the
     * `location` service type, and refusing to claim the type on a coarse-only
     * grant would deny a position to a rule the platform would have answered.
     */
    private fun hasLocationGrant(): Boolean = LOCATION_PERMISSIONS.any {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * A rule that fired and then ran nothing, because part of its trigger could
     * not say whether it held.
     *
     * **The hole this closes.** An ALL group asks every leaf it did not fire on
     * for a state, and a leaf that cannot answer does not satisfy the group, on
     * purpose: running unattended actions on a guess is the worse failure. But
     * the rule was then dropped in silence, and on screen that is identical to
     * the condition answering a plain no. "I am at home and it did not run"
     * had no cause the app could name, and the area check reading no position
     * in the background is exactly that case.
     *
     * Names the components rather than counting them, because the fix is
     * specific to which one could not look, and a person reading this has to
     * know where to go. The engine's own `Log.w` line stays the developer's
     * copy; this one is the user's.
     */
    /**
     * A rule that is stored, enabled, and was never built.
     *
     * Most likely config from an import a newer build wrote, or a component this
     * build does not have. Still logged, because a stack trace is the
     * developer's copy, and now also written where the person can read it. This
     * was the last way a rule could do nothing and say nothing, and it is the
     * worst of the three, because the rule shows as on while nothing at all is
     * watching for it.
     *
     * The message quotes what the failure said, and falls back to naming the
     * exception type when it said nothing. That reads badly and it reads better
     * than an empty sentence: a factory refusing config explains itself, and one
     * that throws something unexpected at least says what.
     */
    private fun reportStartFailure(rule: Rule, cause: Throwable) {
        Log.w(TAG, "rule '${rule.name}' could not be started", cause)

        val said = cause.message?.takeIf { it.isNotBlank() } ?: cause::class.simpleName.orEmpty()
        (application as TriglyApp).container.ruleFaults.couldNotStart(
            rule.id,
            getString(R.string.rules_could_not_start, said),
        )
    }

    private fun reportSuppressed(
        rule: Rule,
        event: TriggerEvent,
        unreadable: List<ComponentSpec>,
    ) {
        val registry = (application as TriglyApp).container.registry
        val names = unreadable
            .map { registry.displayNameOf(it.type) }
            .distinct()
            .joinToString(", ")

        Log.w(TAG, "rule '${rule.name}' on ${event.triggerType}: no answer from $names")
        (application as TriglyApp).container.ruleFaults.couldNotDecide(
            rule.id,
            getString(R.string.rules_could_not_decide, names),
        )
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

        /** Either of these satisfies the platform for the `location` type. */
        private val LOCATION_PERMISSIONS = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )

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
