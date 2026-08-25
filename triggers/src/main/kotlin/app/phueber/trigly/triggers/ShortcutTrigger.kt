package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Fires when the home-screen shortcut carrying this trigger's [shortcutId] is
 * tapped.
 *
 * This shares [DeviceRestartTrigger]'s cold-start problem: tapping the
 * shortcut can be what starts Trigly's process, so the tap can land before
 * this flow is even collecting. Unlike a boot, though, a tap is not
 * *reliably* cold — the engine is commonly already running, in which case
 * this trigger is already collecting when the tap happens. So, unlike
 * [DeviceRestartTrigger], this cannot be a single-shot read of a pending
 * record; it has to cover both the already-collecting case (via
 * [ShortcutEvents.taps]) and the not-yet-collecting one (via
 * [ShortcutEvents.pending]), because nothing available to this trigger says
 * in advance which one applies.
 *
 * Building both into every collection creates a real risk of firing twice for
 * one tap: [ShortcutEvents.record] both updates the pending record and
 * publishes on the bus, so a tap that arrives cold could in principle be seen
 * by the pending check at collection start *and then again* on the bus a
 * moment later. `lastReportedAtMillis` is the guard against that: it
 * remembers, for the lifetime of this one collection, the timestamp of the
 * tap most recently reported, and the bus branch skips any tap whose
 * timestamp matches it. A genuinely new tap always carries a new timestamp —
 * [ShortcutEvents.record] is called again for it — so it is never mistaken
 * for the one already reported, and this guard costs nothing in the case
 * where the bus never re-delivers a pre-collection publish in the first
 * place. Either way, one tap fires this trigger exactly once.
 */
class ShortcutTrigger(
    private val shortcutId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = ShortcutEvents.DEFAULT_WINDOW_MILLIS,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = flow {
        // Scoped to this collection only. A second rule reading the same
        // ShortcutEvents (sharing a shortcutId, or reading a later tap of its
        // own) tracks its own copy — nothing here is shared or consumed
        // globally, matching ShortcutEvents.pending's "reading does not
        // consume" contract.
        var lastReportedAtMillis: Long? = null

        if (ShortcutEvents.pending(now(), shortcutId, windowMillis)) {
            lastReportedAtMillis = ShortcutEvents.lastTapAtMillis(shortcutId)
            emit(eventFor(now()))
        }

        emitAll(
            ShortcutEvents.taps.events
                .filter { it == shortcutId }
                .mapNotNull {
                    val atMillis = ShortcutEvents.lastTapAtMillis(shortcutId)
                    if (atMillis == null || atMillis == lastReportedAtMillis) {
                        null
                    } else {
                        lastReportedAtMillis = atMillis
                        eventFor(atMillis)
                    }
                }
        )
    }

    private fun eventFor(atMillis: Long) = TriggerEvent(
        triggerType = TYPE,
        firedAtMillis = atMillis,
        payload = mapOf(PAYLOAD_SHORTCUT_ID to shortcutId),
    )

    companion object {
        const val TYPE = "shortcut"
        const val CONFIG_SHORTCUT_ID = "shortcutId"
        const val CONFIG_LABEL = "label"
        const val CONFIG_ICON = "icon"
        const val PAYLOAD_SHORTCUT_ID = "shortcutId"
    }
}

class ShortcutTriggerFactory : TriggerFactory {
    override val type = ShortcutTrigger.TYPE

    override val displayName = "Home screen shortcut"
    override val category = Category.DEVICE

    override val configFields = listOf(
        // Minted by the editor when the trigger is added, and never shown: a
        // launcher shortcut has to name the rule it fires, and a trigger is
        // never told its own rule id, so the identity lives here. It was a
        // required `Text` field whose help claimed it was "generated
        // automatically" while nothing generated it — mandatory, unfillable, and
        // silently fatal to the rule. `GeneratedId` exists because of that.
        ConfigField.GeneratedId(
            key = ShortcutTrigger.CONFIG_SHORTCUT_ID,
            label = "Shortcut id",
        ),
        ConfigField.Text(
            key = ShortcutTrigger.CONFIG_LABEL,
            label = "Button name",
            required = true,
            help = "The text under the icon on your home screen.",
        ),
        // A picker, not a URI or a file path: an icon should be choosable
        // without a file manager, and an emoji renders at any density without
        // shipping an asset.
        ConfigField.Emoji(
            key = ShortcutTrigger.CONFIG_ICON,
            label = "Icon",
            blankMeaning = "Trigly's own icon",
        ),
    )

    override fun create(config: Map<String, String>): Trigger {
        val shortcutId = config[ShortcutTrigger.CONFIG_SHORTCUT_ID]
        require(!shortcutId.isNullOrBlank()) {
            "${ShortcutTrigger.CONFIG_SHORTCUT_ID} is required"
        }
        return ShortcutTrigger(shortcutId = shortcutId)
    }
}
