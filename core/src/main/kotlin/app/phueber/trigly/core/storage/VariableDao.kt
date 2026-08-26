package app.phueber.trigly.core.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the `variables` table.
 *
 * Its own file rather than a second responsibility on [RuleDao]. That DAO
 * covers two tables, `rules` and `components`, because they are one aggregate:
 * a component has no meaning without the rule it belongs to. `variables` has
 * no such relationship to a rule. It is a separate, unrelated table, and belongs
 * in a DAO of its own rather than bolted onto one that already has a name.
 */
@Dao
interface VariableDao {

    /** Every variable, live. What [RoomVariableStore.all] returns. */
    @Query("SELECT * FROM variables")
    fun observeAll(): Flow<List<VariableEntity>>

    @Query("SELECT value FROM variables WHERE name = :name")
    suspend fun get(name: String): String?

    /** Inserts [entity], or replaces the row of the same name if one exists. */
    @Upsert
    suspend fun set(entity: VariableEntity)

    /** Deletes the row named [name]. Deleting a name that is not there does nothing. */
    @Query("DELETE FROM variables WHERE name = :name")
    suspend fun remove(name: String)
}
