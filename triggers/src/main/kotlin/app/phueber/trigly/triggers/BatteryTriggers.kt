package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/**
 * `ACTION_BATTERY_CHANGED` is sticky and very chatty — it fires whenever any
 * battery field moves, including voltage and temperature. Both triggers here
 * therefore lean on [StateTracker] to collapse readings where their own
 * dimension has not crossed the threshold.
 */
private const val BATTERY_ACTION = Intent.ACTION_BATTERY_CHANGED

/**
 * The current battery snapshot, read without waiting for the next broadcast.
 *
 * Registering a null receiver for a sticky action is the idiomatic way to poll
 * one: the system hands back the last broadcast Intent immediately instead of
 * queuing a live registration. Wrapped in [runCatching] because there is no
 * documented guarantee the sticky value still exists on every OEM skin.
 */
private fun Context.currentBatteryIntent(): Intent? =
    runCatching { registerReceiver(null, IntentFilter(BATTERY_ACTION)) }.getOrNull()

/** Percentage from the raw level/scale pair, or null if the extras are absent. */
fun batteryPercent(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0) null else level * 100 / scale

class BatteryLevelTrigger(
    context: Context,
    private val threshold: Int,
    private val direction: Direction,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(BATTERY_ACTION)
    override val suppressInitialState = true

    override fun read(intent: Intent): Reading? {
        val percent = batteryPercent(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        ) ?: return null

        val state = thresholdState(percent, threshold, direction)
        return Reading(
            payload = mapOf(PAYLOAD_LEVEL to percent.toString()),
            stateKey = state,
            emit = state == STATE_MET,
        )
    }

    override suspend fun currentlyHolds(): Boolean? {
        val intent = appContext.currentBatteryIntent() ?: return null
        val percent = batteryPercent(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        ) ?: return null
        return thresholdState(percent, threshold, direction) == STATE_MET
    }

    companion object {
        const val TYPE = "battery_level"
        const val CONFIG_THRESHOLD = "threshold"
        const val PAYLOAD_LEVEL = "level"
    }
}

class BatteryLevelTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = BatteryLevelTrigger.TYPE

    override val displayName = "Battery level"
    override val category = Category.POWER

    override val configFields = listOf(
        ConfigField.Number(
            key = BatteryLevelTrigger.CONFIG_THRESHOLD,
            label = "Threshold",
            required = true,
            min = 0,
            max = 100,
            unit = "%",
        ),
        ConfigField.Choice(
            key = Direction.CONFIG_KEY,
            label = "Fires when the level goes",
            options = listOf(
                ConfigField.Option("below", "below the threshold"),
                ConfigField.Option("above", "above the threshold"),
            ),
            required = false,
            default = "below",
        ),
    )

    override fun create(config: Map<String, String>): Trigger = BatteryLevelTrigger(
        context = context,
        threshold = requiredInt(config, BatteryLevelTrigger.CONFIG_THRESHOLD, type),
        direction = Direction.parse(config[Direction.CONFIG_KEY]),
    )

    override val supportsCondition = true
}

class BatteryTemperatureTrigger(
    context: Context,
    private val thresholdTenthsC: Int,
    private val direction: Direction,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(BATTERY_ACTION)
    override val suppressInitialState = true

    override fun read(intent: Intent): Reading? {
        // EXTRA_TEMPERATURE is tenths of a degree Celsius.
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return null

        val state = thresholdState(tenths, thresholdTenthsC, direction)
        return Reading(
            payload = mapOf(PAYLOAD_TEMPERATURE_C to (tenths / 10f).toString()),
            stateKey = state,
            emit = state == STATE_MET,
        )
    }

    override suspend fun currentlyHolds(): Boolean? {
        val intent = appContext.currentBatteryIntent() ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return null
        return thresholdState(tenths, thresholdTenthsC, direction) == STATE_MET
    }

    companion object {
        const val TYPE = "battery_temperature"

        /** Whole degrees Celsius; converted to the tenths the framework reports. */
        const val CONFIG_THRESHOLD_C = "thresholdC"
        const val PAYLOAD_TEMPERATURE_C = "temperatureC"
    }
}

class BatteryTemperatureTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = BatteryTemperatureTrigger.TYPE

    override val displayName = "Battery temperature"
    override val category = Category.POWER

    override val configFields = listOf(
        ConfigField.Number(
            key = BatteryTemperatureTrigger.CONFIG_THRESHOLD_C,
            label = "Threshold",
            required = true,
            min = -20,
            max = 100,
            unit = "°C",
        ),
        ConfigField.Choice(
            key = Direction.CONFIG_KEY,
            label = "Fires when the level goes",
            options = listOf(
                ConfigField.Option("below", "below the threshold"),
                ConfigField.Option("above", "above the threshold"),
            ),
            required = false,
            default = "below",
        ),
    )

    override fun create(config: Map<String, String>): Trigger = BatteryTemperatureTrigger(
        context = context,
        thresholdTenthsC =
            requiredInt(config, BatteryTemperatureTrigger.CONFIG_THRESHOLD_C, type) * 10,
        direction = Direction.parse(config[Direction.CONFIG_KEY]),
    )

    override val supportsCondition = true
}

/**
 * Which *kind* of charger the phone is on: USB, mains, or wireless.
 *
 * Not the same question as `power_connection`, and the difference is the reason
 * this exists. That trigger fires on plug and unplug; this one fires when the
 * phone starts charging *in a particular way*, which is what separates "I plugged
 * into the car" from "I put it on the bedside pad" — the same plug event, a
 * different rule.
 *
 * Reads `EXTRA_PLUGGED` off the same sticky `ACTION_BATTERY_CHANGED` as its two
 * siblings above, so it inherits their caveat: the broadcast fires whenever any
 * battery field moves, and [StateTracker] is what stops a rule firing on every
 * voltage wobble.
 */
class ChargingTypeTrigger(
    context: Context,
    private val source: ChargingSource,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(BATTERY_ACTION)

    // Sticky, so registration replays the current value. Suppressed for the same
    // reason as the level and temperature triggers: enabling a rule must not fire
    // it merely because the phone already happens to be on that charger.
    override val suppressInitialState = true

    override fun read(intent: Intent): Reading? {
        // A missing extra is a malformed broadcast, not "unplugged" — zero is how
        // the framework says unplugged, and conflating the two would make an absent
        // extra look like the charger had just been pulled out.
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        if (plugged < 0) return null

        val actual = ChargingSource.ofPluggedValue(plugged)
        return Reading(
            payload = mapOf(PAYLOAD_SOURCE to (actual?.configValue ?: UNPLUGGED)),
            // Keyed on what is plugged in rather than on met/unmet, so swapping a
            // USB cable for mains without an unplug in between is still a change
            // this trigger can see — and so unplugging re-arms it.
            stateKey = actual?.configValue ?: UNPLUGGED,
            emit = actual == source,
        )
    }

    override suspend fun currentlyHolds(): Boolean? {
        val intent = appContext.currentBatteryIntent() ?: return null
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        if (plugged < 0) return null
        return ChargingSource.ofPluggedValue(plugged) == source
    }

    companion object {
        const val TYPE = "charging_type"
        const val PAYLOAD_SOURCE = "source"

        /** Payload value when nothing is plugged in. Never an emitting state. */
        const val UNPLUGGED = "unplugged"
    }
}

/** The charger kinds `BatteryManager.EXTRA_PLUGGED` can report. */
enum class ChargingSource(val configValue: String, val displayName: String) {
    USB("usb", "USB"),
    AC("ac", "mains"),
    WIRELESS("wireless", "wireless"),
    ;

    companion object {
        const val CONFIG_KEY = "source"

        /**
         * The reported kind, or null for "not plugged in".
         *
         * Matched as a bitmask rather than by equality: `EXTRA_PLUGGED` is
         * documented as a set of flags, and equality would silently report
         * "unplugged" on any device that ever set two at once. AC wins that tie
         * because it is the faster supply and the one a rule about "charging
         * properly" means.
         */
        fun ofPluggedValue(plugged: Int): ChargingSource? = when {
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> AC
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> USB
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> WIRELESS
            else -> null
        }

        fun parse(raw: String?): ChargingSource =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
    }
}

class ChargingTypeTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = ChargingTypeTrigger.TYPE

    override val displayName = "Charger type"
    override val category = Category.POWER

    override val configFields = listOf(
        ConfigField.Choice(
            key = ChargingSource.CONFIG_KEY,
            label = "Fires when charging by",
            options = ChargingSource.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            required = false,
            default = ChargingSource.AC.configValue,
            help = "The phone reports this, not the charger: a wireless pad that " +
                "feeds a case over USB can report USB.",
        ),
    )

    override fun create(config: Map<String, String>): Trigger = ChargingTypeTrigger(
        context = context,
        source = ChargingSource.parse(
            config[ChargingSource.CONFIG_KEY] ?: ChargingSource.AC.configValue
        ),
    )

    override val supportsCondition = true
}
