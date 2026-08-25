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

/**
 * "When [trigger] happens, run [actions]." The unit the user creates and toggles.
 *
 * [trigger] is a single node, and that node may be a group — see [TriggerNode].
 * The rule therefore has exactly one trigger however complicated it gets, which
 * is what keeps the editor to one slot and the storage to one column.
 */
data class Rule(
    val id: String,
    val name: String,
    val trigger: TriggerNode,
    val actions: List<ComponentSpec>,
    val enabled: Boolean = true,
) {
    /**
     * The single-component rule, which is most of them.
     *
     * Kept as a constructor rather than pushed onto every call site because
     * `Rule(id, name, ComponentSpec("solar"), actions)` is what a rule *is* in
     * the simple case, and wrapping it in `TriggerNode.One(...)` at two hundred
     * call sites would say nothing extra.
     */
    constructor(
        id: String,
        name: String,
        trigger: ComponentSpec,
        actions: List<ComponentSpec>,
        enabled: Boolean = true,
    ) : this(id, name, TriggerNode.One(trigger), actions, enabled)
}

/** The same rule triggered by one component, whatever its trigger held before. */
fun Rule.withTrigger(spec: ComponentSpec): Rule = copy(trigger = TriggerNode.One(spec))
