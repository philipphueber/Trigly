package app.phueber.trigly.triggers

/** Which side of a threshold a trigger fires on. */
enum class Direction {
    BELOW,
    ABOVE,
    ;

    companion object {
        const val CONFIG_KEY = "direction"

        fun parse(raw: String?): Direction = when (raw?.lowercase()) {
            null, "below" -> BELOW
            "above" -> ABOVE
            else -> error("$CONFIG_KEY must be 'below' or 'above', was '$raw'")
        }
    }
}

/** The threshold is met, i.e. the condition the user asked about currently holds. */
const val STATE_MET = "met"

/** The threshold is not met. Tracked, not emitted — it is what re-arms the trigger. */
const val STATE_UNMET = "unmet"

/**
 * Pure so it can be unit-tested without a device. Both directions are
 * inclusive: "below 20" fires at exactly 20, which is what users mean.
 */
fun thresholdState(value: Int, threshold: Int, direction: Direction): String =
    when (direction) {
        Direction.BELOW -> if (value <= threshold) STATE_MET else STATE_UNMET
        Direction.ABOVE -> if (value >= threshold) STATE_MET else STATE_UNMET
    }
