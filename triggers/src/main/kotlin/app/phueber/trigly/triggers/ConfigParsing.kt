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

/**
 * [parseTarget] for a state choice a trigger did **not** always have.
 *
 * The difference is the absent case, and it exists for one situation: a trigger
 * that only ever did one thing grows a choice between two. Every rule saved
 * before that has no `state` key at all, and [parseTarget] would refuse it —
 * turning an app update into a set of rules that quietly stop firing, which is
 * the worst outcome this project has. [default] is what those rules meant, so
 * they keep meaning it.
 *
 * An *unknown* word is still an error. Absent is a rule written before the
 * question existed; a typo is a wrong answer to it, and guessing a direction
 * from one would be picking silently between "when it connects" and "when it
 * disconnects".
 *
 * Note this defaults *stored data*, not the form. The schema deliberately
 * declares no default, so a new rule prompts for a choice rather than assuming
 * one — the two concerns look alike and are not.
 */
internal fun parseTargetOrDefault(
    config: Map<String, String>,
    key: String,
    onWord: String,
    offWord: String,
    default: Boolean,
): Boolean = when (config[key]?.lowercase()) {
    onWord -> true
    offWord -> false
    null -> default
    else -> error("'$key' must be '$onWord' or '$offWord', was '${config[key]}'")
}
