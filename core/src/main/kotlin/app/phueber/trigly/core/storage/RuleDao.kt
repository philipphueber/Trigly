package app.phueber.trigly.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    /**
     * The rule list. `@Transaction` is required with `@Relation` so the rules and
     * their components are read consistently — without it a concurrent save can
     * produce a rule whose components belong to a different version of itself.
     */
    @Transaction
    @Query("SELECT * FROM rules ORDER BY position ASC, name ASC")
    fun observeRules(): Flow<List<RuleWithComponents>>

    @Transaction
    @Query("SELECT * FROM rules WHERE id = :ruleId")
    suspend fun findRule(ruleId: String): RuleWithComponents?

    @Query("SELECT position FROM rules WHERE id = :ruleId")
    suspend fun positionOf(ruleId: String): Int?

    /** Appends new rules at the end rather than the top. */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM rules")
    suspend fun nextPosition(): Int

    @Upsert
    suspend fun upsertRule(rule: RuleEntity)

    @Insert
    suspend fun insertComponents(components: List<ComponentEntity>)

    @Query("DELETE FROM components WHERE ruleId = :ruleId")
    suspend fun deleteComponentsOf(ruleId: String)

    @Query("DELETE FROM rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: String)

    /**
     * Components are replaced wholesale rather than diffed. Editing a rule can
     * reorder, add and remove actions at once, so working out the minimal set of
     * row operations would be more code and more ways to be wrong, for a handful
     * of rows.
     *
     * One transaction, so a rule is never briefly missing its actions.
     */
    @Transaction
    suspend fun save(rule: RuleEntity, components: List<ComponentEntity>) {
        upsertRule(rule)
        deleteComponentsOf(rule.id)
        insertComponents(components)
    }
}
