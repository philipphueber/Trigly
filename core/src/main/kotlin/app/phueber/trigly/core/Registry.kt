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
)

private fun ComponentFactory.describe() = ComponentDescriptor(
    type = type,
    displayName = displayName,
    category = category,
    requirements = requirements,
    configFields = configFields,
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
     * Display name for a stored type string, falling back to the raw type so a
     * rule referring to a component this build no longer has still renders as
     * something rather than blank.
     */
    fun displayNameOf(type: String): String =
        (triggers[type] ?: actions[type])?.displayName ?: type

    /** Everything [rule] needs, deduplicated — what a "why isn't this firing?" screen shows. */
    fun requirementsOf(rule: Rule): List<ComponentRequirement> =
        (triggerRequirements(rule.trigger.type) +
            rule.actions.flatMap { actionRequirements(it.type) })
            .distinct()

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
