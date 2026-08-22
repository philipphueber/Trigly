package app.phueber.trigly.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Storage for the user's rules.
 *
 * An interface so `:ui` binds to the contract and not to a database, and so the
 * engine can be tested against [InMemoryRuleRepository]. The real
 * implementation belongs in this module — persistence is `:core`'s job.
 */
interface RuleRepository {
    fun rules(): Flow<List<Rule>>

    suspend fun upsert(rule: Rule)

    suspend fun delete(ruleId: String)
}

/**
 * Non-persistent implementation, used by tests and by the UI until a real
 * database lands.
 *
 * TODO(persistence): replace with a Room-backed implementation. Rules must
 *  survive process death — an automation app that forgets its rules on reboot
 *  is useless — so this is not a shippable default.
 */
class InMemoryRuleRepository(initial: List<Rule> = emptyList()) : RuleRepository {
    private val state = MutableStateFlow(initial)

    override fun rules(): Flow<List<Rule>> = state.asStateFlow()

    override suspend fun upsert(rule: Rule) {
        state.update { current ->
            val index = current.indexOfFirst { it.id == rule.id }
            if (index >= 0) current.toMutableList().also { it[index] = rule } else current + rule
        }
    }

    override suspend fun delete(ruleId: String) {
        state.update { current -> current.filterNot { it.id == ruleId } }
    }
}
