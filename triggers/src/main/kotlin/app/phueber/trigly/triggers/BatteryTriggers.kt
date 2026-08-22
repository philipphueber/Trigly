package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
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
}
