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
    version = 4,
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
 * Adds the trigger tree to the rules table. Nothing else changes: no row is
 * rewritten, no `TRIGGER` component row is touched, and `conditionsJson` is left
 * exactly as it was.
 *
 * One nullable column, same reasoning as `MIGRATION_1_2`: null is the honest
 * value for every row that predates this column, and the tree is JSON because
 * it nests arbitrarily, which the flat, ordinal-ordered `components` table
 * cannot express without becoming a parent-pointer scheme. Composing that JSON
 * from the old rows in SQL is not attempted here — [toRuleOrNull]'s legacy path
 * does it, in Kotlin, at read time, which is the only place that already knows
 * what the old shape meant.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rules ADD COLUMN triggerJson TEXT")
    }
}

/**
 * Adds the folder a rule can be filed under.
 *
 * One nullable column, same reasoning as `MIGRATION_1_2` and `MIGRATION_2_3`:
 * null is the honest value for every row that predates this column — every
 * rule that existed before folders is, correctly, in no folder — and nothing
 * else about an existing row needs to change. There is no data to translate
 * from an old shape the way `MIGRATION_2_3` had to for the trigger tree; this
 * is a bare `ALTER TABLE`.
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rules ADD COLUMN folder TEXT")
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

/**
 * Every migration, in one place, because the alternative was caught failing.
 *
 * A test that opens the database has to register the same chain the app does: the
 * entities are always at the newest version, so a test fixture built at version 3
 * still needs 3→4 to open at all. When each call site listed migrations by hand,
 * adding 3→4 to the app left two older tests still naming only 2→3, and they
 * failed with "a migration from 3 to 4 was required but not found" — a message
 * about the fixture, not about the thing under test, which is the worst kind of
 * failure to read.
 *
 * Spreading this array is therefore the only correct way to build a
 * `TriglyDatabase`, here or in a test. Adding a migration means adding it once.
 */
internal val TRIGLY_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

internal fun triglyDatabase(context: Context): TriglyDatabase =
    Room.databaseBuilder(context, TriglyDatabase::class.java, TriglyDatabase.NAME)
        // Deliberately no fallbackToDestructiveMigration: these are rules the user
        // built by hand, and silently deleting them on a schema change is not an
        // acceptable failure mode. A missing migration should fail loudly in
        // development instead.
        .addMigrations(*TRIGLY_MIGRATIONS)
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
