package app.phueber.trigly.core

/** Thrown when a rule names a trigger or action type that no factory provides. */
class UnknownComponentException(message: String) : IllegalArgumentException(message)

/**
 * Everything the editor needs to know about one component type, without handing
 * it the factory itself.
 *
 * A flattened snapshot rather than the live factory, so the UI cannot
 * accidentally call `create()` while someone is still typing — construction is
 * the validation step, and it belongs at save time.
 */
data class ComponentDescriptor(
    val type: String,
    val displayName: String,
    val category: String,
    val requirements: List<ComponentRequirement>,
    val configFields: List<ConfigField>,
    val warning: String?,
    /**
     * Whether this can be used as a *condition* as well as a trigger — see
     * `docs/conditions.md`. Always false for an action, which has no state to be
     * asked about.
     *
     * On the descriptor so the editor can decide which slots to offer a component
     * in without instantiating one, which is the same reason [configFields] and
     * [requirements] are here.
     */
    val supportsCondition: Boolean = false,
    /** Whether this component can start a rule at all — see `TriggerFactory.producesEvents`. */
    val producesEvents: Boolean = true,
)

private fun ComponentFactory.describe() = ComponentDescriptor(
    type = type,
    displayName = displayName,
    category = category,
    requirements = requirements,
    configFields = configFields,
    supportsCondition = this is TriggerFactory && supportsCondition,
    producesEvents = this !is TriggerFactory || producesEvents,
    warning = warning,
)

/**
 * Resolves the [ComponentSpec]s stored in a [Rule] to live objects.
 *
 * The factory lists are supplied at construction by whoever assembles the app,
 * which is what keeps `:core` free of any dependency on `:triggers` and
 * `:actions`. Adding a trigger type means adding one entry to the list passed
 * in here; no file in this module changes.
 */
class Registry(
    triggerFactories: List<TriggerFactory>,
    actionFactories: List<ActionFactory>,
) {
    private val triggers: Map<String, TriggerFactory> =
        triggerFactories.associateByUnique("trigger") { it.type }

    private val actions: Map<String, ActionFactory> =
        actionFactories.associateByUnique("action") { it.type }

    val triggerTypes: Set<String> get() = triggers.keys
    val actionTypes: Set<String> get() = actions.keys

    /**
     * What this trigger type needs before it can fire. Answerable without
     * instantiating the trigger, so a rule editor can show it while the user is
     * still choosing.
     */
    fun triggerRequirements(type: String): List<ComponentRequirement> =
        triggers[type]?.requirements.orEmpty()

    fun actionRequirements(type: String): List<ComponentRequirement> =
        actions[type]?.requirements.orEmpty()

    /** Everything the editor needs to render a picker, sorted for display. */
    val triggerDescriptors: List<ComponentDescriptor> by lazy {
        triggers.values.map { it.describe() }.sortedBy { it.displayName }
    }

    val actionDescriptors: List<ComponentDescriptor> by lazy {
        actions.values.map { it.describe() }.sortedBy { it.displayName }
    }

    fun triggerDescriptor(type: String): ComponentDescriptor? = triggers[type]?.describe()

    fun actionDescriptor(type: String): ComponentDescriptor? = actions[type]?.describe()

    /**
     * Whether [type] can ever start a rule — the by-type-string form of
     * [TriggerFactory.producesEvents], for feeding [TriggerNode.canStart]'s
     * `hasEvents` parameter from the editor, which has only the type string a
     * slot is considering, not a built factory.
     *
     * An unknown type answers false rather than throwing: a rule editor asks
     * this about a type before anything has validated it, same as
     * [triggerRequirements].
     */
    fun producesEvents(type: String): Boolean = triggers[type]?.producesEvents ?: false

    /**
     * Whether this exact component, configured this way, can start a rule.
     *
     * The form [TriggerNode.canStart] is fed with, and the one to prefer when a
     * [ComponentSpec] is in hand. A component can be configured not to watch
     * anything: see [TriggerFactory.producesEvents] with a config. Asking by
     * type alone would answer for the component in general and miss the switch,
     * so a rule whose only location leaf was set to check rather than watch
     * would look able to start when nothing in it can.
     */
    fun producesEvents(spec: ComponentSpec): Boolean =
        triggers[spec.type]?.producesEvents(spec.config) ?: false

    /**
     * Whether [type] can be asked for its current state — the by-type-string
     * form of [TriggerFactory.supportsCondition], for [TriggerNode.canStart]
     * and [TriggerNode.canHold]'s `hasState` parameter.
     */
    fun supportsCondition(type: String): Boolean = triggers[type]?.supportsCondition ?: false

    /**
     * Display name for a stored type string, falling back to the raw type so a
     * rule referring to a component this build no longer has still renders as
     * something rather than blank.
     */
    fun displayNameOf(type: String): String =
        (triggers[type] ?: actions[type])?.displayName ?: type

    /**
     * Everything [rule] needs, deduplicated — what a "why isn't this firing?"
     * screen shows.
     *
     * **Every leaf of the trigger tree, not just the first one.** This read
     * `rule.trigger` as a single [ComponentSpec] while a rule could only have
     * one; once a rule's trigger became a tree that can hold several
     * components — see [TriggerNode] — a permission needed by a second leaf,
     * whether it is asked as an edge or only ever read as a state, became
     * invisible, and the list would call such a rule firable when it was not.
     * That is the exact failure the requirement model exists to prevent, so
     * it is worth being explicit: every leaf, every action.
     *
     * Each component is asked what it needs *as configured* — see
     * [ComponentFactory.requirementsFor]. A rule that never uses a capability is
     * not blocked on it.
     */
    fun requirementsOf(rule: Rule): List<ComponentRequirement> {
        val fromTrigger = rule.trigger.leaves()

        return (
            fromTrigger.flatMap { triggers[it.type]?.requirementsFor(it.config).orEmpty() } +
                rule.actions.flatMap { actions[it.type]?.requirementsFor(it.config).orEmpty() }
            ).distinct()
    }

    /**
     * The tools a component offers on its editor block, for this configuration.
     *
     * Deliberately *not* a field on [ComponentDescriptor]: a descriptor is a
     * config-independent snapshot, and the tools depend on config — a shortcut
     * trigger offers pinning only once it has an id to pin. Asking the registry
     * keeps the descriptor honest about what it is.
     *
     * An unknown type yields nothing rather than throwing: a rule naming a
     * component this build lacks already renders as an unavailable block, and
     * failing here would take the whole editor down with it.
     */
    fun toolsFor(spec: ComponentSpec): List<ComponentTool> =
        (triggers[spec.type] ?: actions[spec.type])?.toolsFor(spec.config).orEmpty()

    fun createTrigger(spec: ComponentSpec): Trigger {
        val factory = triggers[spec.type]
            ?: throw UnknownComponentException(
                "No trigger factory for type '${spec.type}'. Registered: ${triggers.keys.sorted()}"
            )
        return factory.create(spec.config)
    }

    fun createAction(spec: ComponentSpec): Action {
        val factory = actions[spec.type]
            ?: throw UnknownComponentException(
                "No action factory for type '${spec.type}'. Registered: ${actions.keys.sorted()}"
            )
        return factory.create(spec.config)
    }
}

/**
 * Two factories claiming the same type would make rule resolution depend on
 * list order, so fail loudly at assembly time instead of silently at runtime.
 */
private fun <T> List<T>.associateByUnique(label: String, key: (T) -> String): Map<String, T> {
    val duplicates = groupingBy(key).eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) {
        "Duplicate $label type(s) registered: ${duplicates.sorted()}"
    }
    return associateBy(key)
}
