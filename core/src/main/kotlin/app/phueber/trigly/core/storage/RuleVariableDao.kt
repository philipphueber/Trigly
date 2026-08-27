package app.phueber.trigly.core.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the `rule_variables` table: the `{{mine.*}}` scope.
 *
 * Its own DAO rather than more methods on [VariableDao], even though the two
 * tables hold the same shape of thing. Every method here takes a rule id and
 * none there does, so sharing one DAO would mean half its methods silently
 * ignoring an argument the other half requires. Two names for two scopes is the
 * honest arrangement, and it matches `RuleVariableStore` sitting beside
 * `VariableStore` rather than inheriting from it.
 *
 * Not folded into [RuleDao] either, despite the foreign key. That DAO covers
 * `rules` and `components` because a component is meaningless without its rule.
 * A rule variable is owned by a rule but is not part of what a rule *is*: a rule
 * loads, exports and runs without ever reading one.
 */
@Dao
interface RuleVariableDao {

    /** Every value one rule holds, live. */
    @Query("SELECT * FROM rule_variables WHERE ruleId = :ruleId")
    fun observeFor(ruleId: String): Flow<List<RuleVariableEntity>>

    /** Every rule's values, live, for a screen that lists them all at once. */
    @Query("SELECT * FROM rule_variables")
    fun observeAll(): Flow<List<RuleVariableEntity>>

    @Query("SELECT value FROM rule_variables WHERE ruleId = :ruleId AND name = :name")
    suspend fun get(ruleId: String, name: String): String?

    @Upsert
    suspend fun set(entity: RuleVariableEntity)

    @Query("DELETE FROM rule_variables WHERE ruleId = :ruleId AND name = :name")
    suspend fun remove(ruleId: String, name: String)
}
