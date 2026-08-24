package app.phueber.trigly.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Database(
    entities = [RuleEntity::class, ComponentEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TriglyDatabase : RoomDatabase() {
    abstract fun rules(): RuleDao

    companion object {
        const val NAME = "trigly.db"
    }
}

/**
 * Adds the gate's condition tree to the rules table.
 *
 * One nullable column, and null is the honest representation of "this rule has no
 * conditions" — which is every rule that existed before gates. The tree is stored
 * as JSON rather than relationally: it is a *tree*, and the components table is a
 * flat list with an ordinal, which cannot express nesting without becoming a
 * parent-pointer scheme nobody would enjoy reading.
 *
 * Several trigger edges needed no migration at all. The components table already
 * carries a role and an ordinal, so a second `TRIGGER` row is a shape it always
 * supported.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rules ADD COLUMN conditionsJson TEXT")
    }
}

/**
 * The app's rule storage, as a [RuleRepository].
 *
 * Returns the interface rather than the database so Room stays an implementation
 * detail of `:core`. `:ui` assembles the app but has no business knowing what
 * the storage engine is, and keeping `room-runtime` off its compile classpath is
 * what enforces that rather than merely asking for it.
 */
fun ruleRepository(context: Context): RuleRepository =
    RoomRuleRepository(triglyDatabase(context).rules())

internal fun triglyDatabase(context: Context): TriglyDatabase =
    Room.databaseBuilder(context, TriglyDatabase::class.java, TriglyDatabase.NAME)
        // Deliberately no fallbackToDestructiveMigration: these are rules the user
        // built by hand, and silently deleting them on a schema change is not an
        // acceptable failure mode. A missing migration should fail loudly in
        // development instead.
        .addMigrations(MIGRATION_1_2)
        .build()

/**
 * The durable [RuleRepository]. Replaces `InMemoryRuleRepository` in the app;
 * the in-memory one stays for tests.
 */
internal class RoomRuleRepository(private val dao: RuleDao) : RuleRepository {

    override fun rules(): Flow<List<Rule>> =
        dao.observeRules().map { rows -> rows.mapNotNull { it.toRuleOrNull() } }

    override suspend fun upsert(rule: Rule) {
        // Keeps an edited rule where it was in the list, and puts a new one at
        // the end.
        val position = dao.positionOf(rule.id) ?: dao.nextPosition()
        dao.save(rule.toEntity(position), rule.toComponentEntities())
    }

    override suspend fun delete(ruleId: String) {
        dao.deleteRule(ruleId)
    }

    /** Bulk insert for import. Each rule keeps its own position at the end. */
    suspend fun insertAll(rules: List<Rule>) {
        rules.forEach { upsert(it) }
    }
}
