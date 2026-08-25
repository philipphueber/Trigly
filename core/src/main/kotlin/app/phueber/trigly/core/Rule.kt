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
 *
 * [folder] is a user-typed name the rule list groups by; a rule with no folder
 * collects under "Other" there. Null means "not in a folder" — deliberately
 * distinct from `""`, which nothing in this codebase should ever store: a blank
 * and a null both mean "no folder", and letting two spellings of that exist
 * would mean some ungrouped rules compare equal to each other and some do not,
 * depending on which boundary they came in through. Every place a folder name
 * enters from outside (the editor field, an imported JSON file, a hand-edited
 * database row) must pass it through [normalizeFolder] rather than storing it
 * as received, and every place one leaves (JSON export, the database row) must
 * normalize again defensively rather than trust that the caller already did.
 * Once normalized, two names are the same folder only if they are the same
 * string — comparison is exact and case-sensitive, because the user typed the
 * name, and an app that silently retitles "car" to "Car" is worse than one
 * that shows two headings.
 */
data class Rule(
    val id: String,
    val name: String,
    val trigger: TriggerNode,
    val actions: List<ComponentSpec>,
    val enabled: Boolean = true,
    val folder: String? = null,
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

/**
 * The one place "what counts as no folder" is decided: trim, then collapse a
 * blank result to null. Every boundary that accepts a folder name from outside
 * `:core` — the editor field, an imported JSON file, a hand-edited database
 * row — calls this rather than storing what it received, so `null` is the only
 * spelling of "not in a folder" that can ever exist. Comparison of two
 * normalized names is then exact `String` equality: case-sensitive, no further
 * folding. See [Rule]'s kdoc for why.
 */
fun normalizeFolder(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
