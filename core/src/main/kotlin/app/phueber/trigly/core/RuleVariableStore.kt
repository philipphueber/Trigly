package app.phueber.trigly.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * The `{{mine.*}}` scope: values that belong to one rule, survive its runs, and
 * are invisible to every other rule.
 *
 * **Why this is not [VariableStore] with a prefixed name.** Putting a rule's
 * private values in the shared table under a key like `rule-7/count` would work
 * and would be wrong in three ways that matter. The saved values screen would
 * list them beside the shared ones, so a person's own names would be buried in
 * bookkeeping they never wrote. Deleting a rule would leave its values behind
 * for ever, because nothing joins a name to a rule. And two rules could collide
 * by writing the same prefix, which is the exact thing this scope exists to make
 * impossible. A keyed table gets all three right by construction: the rule id is
 * a column, and a foreign key deletes the values with the rule.
 *
 * Shaped after [VariableStore] and for the same reason it is not a port: the
 * implementation lives in `:core` beside the interface, so this is an interface
 * because there are two of them, the real one and the in-memory one tests use,
 * not because a module boundary demanded it.
 *
 * Every method takes a rule id. There is no "current rule" here, on purpose: a
 * store that remembered one would have to be told when a rule started and
 * stopped, and would be wrong the moment two rules ran at once. The caller
 * always knows which rule it is acting for; see `RunScope`, which is how an
 * action finds that out.
 */
interface RuleVariableStore {

    /** Every value [ruleId] holds, live, for the editor to offer and a screen to list. */
    fun history(ruleId: String): Flow<Map<String, VariableRecord>>

    /** Every rule's values, live, keyed by rule id. What the saved values screen lists. */
    fun historyByRule(): Flow<Map<String, Map<String, VariableRecord>>>

    suspend fun get(ruleId: String, name: String): String?

    suspend fun set(ruleId: String, name: String, value: String)

    /** Removing a name that was never set is not an error, per [VariableStore.remove]. */
    suspend fun remove(ruleId: String, name: String)
}

/** [RuleVariableStore.history] without the timestamps, for a plain read. */
fun RuleVariableStore.all(ruleId: String): Flow<Map<String, String>> =
    history(ruleId).map { records -> records.mapValues { it.value.value } }

/**
 * A [RuleVariableStore] with no storage behind it, for tests and previews.
 *
 * Keyed the same way the table is, so a test that writes for one rule and reads
 * for another gets the same nothing the real store would give it. That is the
 * property most worth having in a fake here: the isolation *is* the feature.
 */
class InMemoryRuleVariableStore(
    initial: Map<String, Map<String, String>> = emptyMap(),
) : RuleVariableStore {

    private val state = MutableStateFlow(
        initial.mapValues { (_, values) ->
            values.mapValues { VariableRecord(it.value, updatedAtMillis = 0L) }
        }
    )

    override fun history(ruleId: String): Flow<Map<String, VariableRecord>> =
        state.asStateFlow().map { it[ruleId].orEmpty() }

    override fun historyByRule(): Flow<Map<String, Map<String, VariableRecord>>> =
        state.asStateFlow()

    override suspend fun get(ruleId: String, name: String): String? =
        state.value[ruleId]?.get(name)?.value

    override suspend fun set(ruleId: String, name: String, value: String) {
        state.value = state.value + (
            ruleId to (state.value[ruleId].orEmpty() + (name to VariableRecord(value, 0L)))
            )
    }

    override suspend fun remove(ruleId: String, name: String) {
        val existing = state.value[ruleId] ?: return
        state.value = state.value + (ruleId to (existing - name))
    }
}

/**
 * One rule's own values as the editor's picker lists them, mirroring
 * [VariableStore.scoped] entry for entry.
 *
 * Marked as sometimes-absent for the same reason app scope is, and it is the
 * same reason twice over here: the action that writes a rule value may not have
 * run yet, and a person editing the rule is very often writing the reader before
 * the writer.
 */
fun RuleVariableStore.scopedFor(ruleId: String): Flow<List<ScopedVariable>> =
    all(ruleId).map { values ->
        values.entries.sortedBy { it.key }.map { (name, value) ->
            ScopedVariable(
                VariableScope.MINE,
                VariableSpec(
                    key = name,
                    label = name,
                    kind = VariableKind.TEXT,
                    // The current value, which is the most useful sample there
                    // could be: it is known right now, unlike a trigger payload.
                    sample = value,
                    alwaysPresent = false,
                ),
            )
        }
    }
