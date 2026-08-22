package app.phueber.trigly.triggers

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.delay
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
 * The window advances to the end of each query rather than being recomputed
 * from "now", so an app launch that happens during a query is not lost between
 * two windows.
 */
class AppForegroundTrigger(
    private val context: Context,
    private val packageName: String?,
    private val pollMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = flow {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return@flow
        var windowStart = now()

        while (true) {
            delay(pollMillis)

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

    companion object {
        const val TYPE = "app_foreground"
        const val CONFIG_PACKAGE = "package"
        const val CONFIG_POLL_MILLIS = "pollMillis"
        const val PAYLOAD_PACKAGE = "package"

        const val DEFAULT_POLL_MILLIS = 5_000L

        /**
         * `MOVE_TO_FOREGROUND` was renamed `ACTIVITY_RESUMED` in API 29; both
         * are the same constant value, so one reference covers every version.
         */
        @Suppress("DEPRECATION")
        private val FOREGROUND_EVENT = UsageEvents.Event.MOVE_TO_FOREGROUND
    }
}

class AppForegroundTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = AppForegroundTrigger.TYPE

    override val displayName = "App comes to the foreground"
    override val category = Category.APPS

    override val configFields = listOf(
        packageFilter(help = "Which app opening should fire this rule."),
        ConfigField.Number(
            key = AppForegroundTrigger.CONFIG_POLL_MILLIS,
            label = "Check every",
            default = AppForegroundTrigger.DEFAULT_POLL_MILLIS,
            min = 1000,
            unit = "ms",
        ),
    )

    override val warning: String =
        "Android gives no notification when an app opens, so this polls. Events " +
            "arrive up to one interval late and the checking itself costs battery."

    override val requirements = listOf(
        ComponentRequirement.SpecialAccess(SpecialAccessKind.USAGE_STATS),
    )

    override fun create(config: Map<String, String>): Trigger = AppForegroundTrigger(
        context = context,
        packageName = config[AppForegroundTrigger.CONFIG_PACKAGE],
        pollMillis = config[AppForegroundTrigger.CONFIG_POLL_MILLIS]?.toLongOrNull()
            ?: AppForegroundTrigger.DEFAULT_POLL_MILLIS,
    )
}
