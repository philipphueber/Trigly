package app.phueber.trigly.core

/**
 * A persisted reference to one trigger or action type plus its settings.
 *
 * Rules store the [type] string rather than a class, so `:core` never needs to
 * know which implementations exist. Resolution happens in [Registry].
 */
data class ComponentSpec(
    val type: String,
    val config: Map<String, String> = emptyMap(),
)

/** "When [trigger] fires, run [actions]." The unit the user creates and toggles. */
data class Rule(
    val id: String,
    val name: String,
    val trigger: ComponentSpec,
    val actions: List<ComponentSpec>,
    val enabled: Boolean = true,
)
