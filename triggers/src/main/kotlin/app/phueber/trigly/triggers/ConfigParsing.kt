package app.phueber.trigly.triggers

/**
 * Config values arrive as strings from storage, so every factory has to
 * validate. Failing loudly here is deliberate: a rule with unparseable config
 * should refuse to start with a readable message rather than sit there never
 * firing.
 */
internal fun requiredInt(config: Map<String, String>, key: String, type: String): Int {
    val raw = config[key] ?: error("$type needs '$key'")
    return raw.toIntOrNull() ?: error("$key must be a whole number, was '$raw'")
}

/**
 * Parses a two-state target, e.g. `state=plugged` vs `state=unplugged`.
 *
 * Each trigger supplies its own vocabulary because "on/off" reads wrong for
 * most of them — a headset is plugged, Wi-Fi is enabled, power is connected.
 *
 * @return true for [onWord], false for [offWord].
 */
internal fun parseTarget(
    config: Map<String, String>,
    key: String,
    onWord: String,
    offWord: String,
): Boolean = when (config[key]?.lowercase()) {
    onWord -> true
    offWord -> false
    null -> error("'$key' is required; expected '$onWord' or '$offWord'")
    else -> error("'$key' must be '$onWord' or '$offWord', was '${config[key]}'")
}
