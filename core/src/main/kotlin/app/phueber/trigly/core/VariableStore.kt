package app.phueber.trigly.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Named values that outlive one run and are shared by every rule. Phase 2 of
 * `docs/variables.md`.
 *
 * This is what makes a counter, a "last seen at", or a cooldown possible, and it
 * is the only way one rule can tell another rule anything beyond the existing
 * `set_rule_enabled`.
 *
 * Shaped after [RuleRepository] rather than after `NotificationController`, and
 * the difference matters. That interface is a *port*: it exists because its
 * implementation has to live in `:triggers`, beside the listener service, while
 * its caller lives in `:actions`, and neither module can see the other. Its
 * "unavailable" default is a real answer, because notification access really can
 * be off. Nothing here has that problem: the Room implementation lives in this
 * module, next to `RoomRuleRepository`, so the honest default is a working
 * in-memory store and not one that refuses. "This device has no variables" is
 * not a state a device can be in.
 *
 * Values are strings, like everything else in this feature. A count is stored as
 * its digits and parsed where it is compared.
 */
interface VariableStore {

    /**
     * Every variable, with the moment each was last written.
     *
     * The only wholesale read, and it carries the timestamp even though most
     * callers do not want it. The alternative was two reads, one with the time
     * and one without, and the one without would have to be derived from the
     * one with it or the two could disagree. Two spellings of the same fact is
     * how a store ends up reporting a value from one and a time from the other.
     *
     * A caller that does not care when a value changed uses [all], which is
     * derived from this rather than declared beside it.
     */
    fun history(): Flow<Map<String, VariableRecord>>

    /** One variable's value, or null when nothing of that name is stored. */
    suspend fun get(name: String): String?

    /**
     * Stores [value] under [name], replacing whatever was there.
     *
     * Callers pass a name that has been through [variableNameProblem]. This does
     * not check again: a store is not the place to decide what a legal name is,
     * and a silent rejection here would be worse than a loud one at the point
     * where a person typed it.
     */
    suspend fun set(name: String, value: String)

    /** Forgets [name]. Removing something that was never there is not an error. */
    suspend fun remove(name: String)
}

/**
 * Every variable's current value, for a caller that does not care when it
 * changed. Derived from [VariableStore.history] rather than declared beside it:
 * see that method for why there is one read and not two.
 */
fun VariableStore.all(): Flow<Map<String, String>> =
    history().map { records -> records.mapValues { (_, record) -> record.value } }

/**
 * One stored value, with the moment it was written. See [VariableStore.history].
 */
data class VariableRecord(val value: String, val updatedAtMillis: Long)

/**
 * The store every test and every default gets. A real store, deliberately: see
 * [VariableStore].
 */
class InMemoryVariableStore(initial: Map<String, String> = emptyMap()) : VariableStore {

    private val state = MutableStateFlow(
        initial.mapValues { (_, value) -> VariableRecord(value, System.currentTimeMillis()) }
    )

    override fun history(): Flow<Map<String, VariableRecord>> = state.asStateFlow()

    override suspend fun get(name: String): String? = state.value[name]?.value

    override suspend fun set(name: String, value: String) {
        state.update { it + (name to VariableRecord(value, System.currentTimeMillis())) }
    }

    override suspend fun remove(name: String) {
        state.update { it - name }
    }
}

/** The variables in [store], offered to the editor as [ScopedVariable]s. */
fun VariableStore.scoped(): Flow<List<ScopedVariable>> = all().map { values ->
    values.entries.sortedBy { it.key }.map { (name, value) ->
        ScopedVariable(
            VariableScope.APP,
            VariableSpec(
                key = name,
                label = name,
                kind = VariableKind.TEXT,
                // The value itself, which is the most useful sample there could
                // be: unlike a trigger's payload, this one is known right now.
                sample = value,
                // An app variable a rule reads before anything has written it is
                // the ordinary case, not an edge case: the rule that sets it may
                // simply not have run yet.
                alwaysPresent = false,
            ),
        )
    }
}

/**
 * The one place "no folder" is decided has a twin here: the one place a variable
 * name is trimmed. See [normalizeFolder], which this follows deliberately.
 *
 * Comparison of two normalized names is then exact and case-sensitive, for the
 * reason [Rule.folder] gives: the person typed the name, and a store that
 * silently treated `Trips` and `trips` as one name would be guessing.
 */
fun normalizeVariableName(raw: String): String = raw.trim()

/**
 * Why [raw] cannot be a variable name, or null when it can.
 *
 * **Checked by round-tripping through the parser rather than against a pattern
 * written out here.** A name is only worth anything if a rule can refer to it,
 * and what a rule can refer to is exactly what `parseTemplate` accepts. A second
 * spelling of that rule, as a regular expression in this file, would be a rule
 * that drifts: the grammar would gain a character, this would not hear about it,
 * and a name a person was allowed to store would be a name no field could read.
 *
 * So the question asked here is the only one that matters. Build the reference
 * this name would need, parse it, and see whether what comes back is the
 * reference that was meant.
 */
fun variableNameProblem(raw: String): String? {
    val name = normalizeVariableName(raw)
    if (name.isEmpty()) return "A variable needs a name."

    val template = parseTemplate("{{${VariableScope.APP}.$name}}")
    val reference = template.references.singleOrNull()
    val readsBack = template.segments.size == 1 &&
        reference != null &&
        reference.scope == VariableScope.APP &&
        reference.name == name &&
        reference.fallback == null

    return if (readsBack) {
        null
    } else {
        "'$name' cannot be read back by a rule. Use letters, digits and the " +
            "underscore."
    }
}
