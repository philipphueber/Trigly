package app.phueber.trigly.triggers

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fires when an app comes to the foreground.
 *
 * `UsageStatsManager` offers no callback, only a query over a time window, so
 * this polls. That has two consequences worth stating plainly: events arrive up
 * to [pollMillis] late, and the poll costs battery whether or not anything
 * happened. The default interval is a compromise; a rule that needs to react
 * instantly wants the accessibility service instead, at a much higher privacy
 * cost.
 *
 * The wait between polls is [AlarmScheduler.waitFor], not a plain coroutine
 * `delay`, so the poll keeps its promised interval through Doze instead of
 * only running on whatever next wakes the CPU; `docs/todo.md`'s T1 covers why.
 * It still stops if the user force-stops Trigly, which no scheduler design can
 * change (see that document's R1).
 *
 * The window advances to the end of each query rather than being recomputed
 * from "now", so an app launch that happens during a query is not lost between
 * two windows.
 */
class AppForegroundTrigger(
    private val context: Context,
    private val packageName: String?,
    private val pollMillis: Long,
    private val scheduler: AlarmScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = flow {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return@flow
        var windowStart = now()

        while (true) {
            scheduler.waitFor(pollMillis)

            val windowEnd = now()
            val events = runCatching { usage.queryEvents(windowStart, windowEnd) }.getOrNull()
            windowStart = windowEnd
            if (events == null) continue

            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != FOREGROUND_EVENT) continue
                if (packageName != null && event.packageName != packageName) continue

                emit(
                    TriggerEvent(
                        triggerType = TYPE,
                        firedAtMillis = event.timeStamp,
                        payload = mapOf(PAYLOAD_PACKAGE to event.packageName.orEmpty()),
                    )
                )
            }
        }
    }

    /**
     * The same [UsageStatsManager] [events] polls, asked for a snapshot instead
     * of a stream: walk a trailing window of events and track whichever package
     * most recently moved to the foreground without a matching move to the
     * background since. Reusing the query this way means the edge and the level
     * can never disagree about what "foreground" means.
     *
     * The one case this cannot see: an app that has held the foreground for
     * longer than [FOREGROUND_LOOKBACK_MILLIS] with nothing else in between
     * scrolls out of the window entirely and reads as "nothing foregrounded."
     * The same staleness trade `docs/conditions.md` accepts for a cached
     * location fix — a wider window costs more to scan for a case that is rare
     * in practice.
     */
    override suspend fun currentlyHolds(): Boolean? {
        if (!context.hasUsageAccess()) return null
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null

        val end = now()
        val events = runCatching { usage.queryEvents(end - FOREGROUND_LOOKBACK_MILLIS, end) }
            .getOrNull() ?: return null

        var foreground: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                FOREGROUND_EVENT -> foreground = event.packageName
                BACKGROUND_EVENT -> if (event.packageName == foreground) foreground = null
            }
        }

        return if (packageName == null) foreground != null else foreground == packageName
    }

    companion object {
        const val TYPE = "app_foreground"
        const val CONFIG_PACKAGE = "package"
        const val CONFIG_POLL_MILLIS = "pollMillis"
        const val PAYLOAD_PACKAGE = "package"

        const val DEFAULT_POLL_MILLIS = 5_000L

        private const val FOREGROUND_LOOKBACK_MILLIS = 60 * 60 * 1_000L

        /**
         * `MOVE_TO_FOREGROUND` was renamed `ACTIVITY_RESUMED` in API 29; both
         * are the same constant value, so one reference covers every version.
         */
        @Suppress("DEPRECATION")
        private val FOREGROUND_EVENT = UsageEvents.Event.MOVE_TO_FOREGROUND

        /** `MOVE_TO_BACKGROUND`'s API-29 rename, same reasoning as above. */
        @Suppress("DEPRECATION")
        private val BACKGROUND_EVENT = UsageEvents.Event.MOVE_TO_BACKGROUND
    }
}

/**
 * Usage access is an app op, not a runtime permission — [Context.checkSelfPermission]
 * has no idea it exists. Mirrors the check `RequirementChecker` makes for the
 * [app.phueber.trigly.core.SpecialAccessKind.USAGE_STATS] requirement below,
 * duplicated here rather than shared because that class lives in `:core` and
 * cannot depend back on `:triggers`.
 */
private fun Context.hasUsageAccess(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

class AppForegroundTriggerFactory(
    private val context: Context,
    private val scheduler: AlarmScheduler,
) : TriggerFactory {
    override val type = AppForegroundTrigger.TYPE

    override val displayName = "App comes to the foreground"
    override val category = Category.APPS

    override val configFields = listOf(
        packageFilter(help = "Select the app. This trigger fires when the app comes to the foreground."),
        ConfigField.Duration(
            key = AppForegroundTrigger.CONFIG_POLL_MILLIS,
            label = "Check every",
            defaultMillis = AppForegroundTrigger.DEFAULT_POLL_MILLIS,
            preferred = DurationUnit.SECONDS,
        ),
    )

    override val warning: String =
        "Android sends no notification when an app opens. This trigger checks " +
            "on a timer instead. An event can arrive up to one interval late. " +
            "The checks use battery."

    override val requirements = listOf(
        ComponentRequirement.SpecialAccess(SpecialAccessKind.USAGE_STATS),
    )

    override val supportsCondition = true

    override val variables = listOf(
        VariableSpec(
            key = AppForegroundTrigger.PAYLOAD_PACKAGE,
            label = "Package",
            kind = VariableKind.PACKAGE,
            sample = "com.example.app",
        ),
    )

    override fun create(config: Map<String, String>): Trigger = AppForegroundTrigger(
        context = context,
        packageName = config[AppForegroundTrigger.CONFIG_PACKAGE],
        pollMillis = config[AppForegroundTrigger.CONFIG_POLL_MILLIS]?.toLongOrNull()
            ?: AppForegroundTrigger.DEFAULT_POLL_MILLIS,
        scheduler = scheduler,
    )
}
