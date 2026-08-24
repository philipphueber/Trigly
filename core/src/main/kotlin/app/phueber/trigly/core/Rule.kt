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
 * "When the [gate] opens, run [actions]." The unit the user creates and toggles.
 *
 * The gate is one or more trigger edges plus optional conditions — see [Gate] and
 * `docs/conditions.md`. It replaced a bare `trigger: ComponentSpec`, and [trigger]
 * remains as the accessor for the many places that only ever want the first edge:
 * a rule summary, an editor that shows one trigger, a test that names one.
 */
data class Rule(
    val id: String,
    val name: String,
    val gate: Gate,
    val actions: List<ComponentSpec>,
    val enabled: Boolean = true,
) {
    /** The single-trigger rule, which is every rule written before gates existed. */
    constructor(
        id: String,
        name: String,
        trigger: ComponentSpec,
        actions: List<ComponentSpec>,
        enabled: Boolean = true,
    ) : this(id, name, Gate(trigger), actions, enabled)

    /**
     * The first edge.
     *
     * Not "the" trigger any more, and callers that could act on several should
     * read [Gate.triggers] instead. Kept because most callers genuinely want one:
     * the list screen's summary line, and anything written before the gate.
     */
    val trigger: ComponentSpec get() = gate.triggers.first()
}

/**
 * The same rule with one trigger replacing whatever the gate held.
 *
 * `copy(trigger = …)` stopped compiling when the constructor learned about gates,
 * and this is the honest replacement: it says that the *whole* first level is
 * being replaced, which is what the old copy did without saying so.
 */
fun Rule.withTrigger(spec: ComponentSpec): Rule = copy(gate = Gate(spec, gate.conditions))
