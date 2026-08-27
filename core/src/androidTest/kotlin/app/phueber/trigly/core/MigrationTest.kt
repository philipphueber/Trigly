package app.phueber.trigly.core

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.storage.MIGRATION_1_2
import app.phueber.trigly.core.storage.MIGRATION_2_3
import app.phueber.trigly.core.storage.MIGRATION_3_4
import app.phueber.trigly.core.storage.MIGRATION_4_5
import app.phueber.trigly.core.storage.MIGRATION_5_6
import app.phueber.trigly.core.storage.RoomRuleVariableStore
import app.phueber.trigly.core.storage.RoomRuleRepository
import app.phueber.trigly.core.storage.RoomVariableStore
import app.phueber.trigly.core.storage.TRIGLY_MIGRATIONS
import app.phueber.trigly.core.storage.TriglyDatabase
import app.phueber.trigly.core.storage.toRuleOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Four migrations have ever shipped. Each must get right what Room cannot
 * check for itself: that a database actually written by the *old* version — a
 * real user's rules, not a fixture built fresh at the new version — survives
 * the upgrade with its rows intact and its meaning preserved. A wrong
 * migration is indistinguishable from a fine one until an existing install
 * updates, which is exactly the moment nobody is watching for it.
 *
 * Built from the committed schema JSON in `core/schemas/` via
 * [MigrationTestHelper], which needs those files as instrumented-test assets;
 * they are copied into `core/src/androidTest/assets/` under the same
 * `<database>/<version>.json` layout Room's schema export already uses; no
 * `build.gradle.kts` change is needed since Android's default source sets
 * already merge `src/androidTest/assets` for the test APK.
 *
 * Deliberately not `Room.databaseBuilder` with `fallbackToDestructiveMigration`
 * — that tests the wrong thing, a database that never went through the old
 * version at all.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:JUnitRule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TriglyDatabase::class.java,
    )

    // --- 1 -> 2 -------------------------------------------------------------

    @Test
    fun migration_1_to_2_keeps_old_rows_with_a_null_conditionsJson() {
        // A version-1 database built from the real schema, not from the current
        // entities — this is what a phone that installed version 1 and never
        // reinstalled actually has on disk.
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position) VALUES (?, ?, ?, ?)",
                arrayOf("r1", "Old rule", 1, 0),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "TRIGGER", 0, "screen_state", "{}"),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "ACTION", 0, "speak", "{}"),
            )
            close()
        }

        // runMigrationsAndValidate checks the result against Room's own idea of
        // what version 2 should look like (the exported schema), not just
        // against whatever MIGRATION_1_2 happens to produce.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        migrated.query("SELECT conditionsJson FROM rules WHERE id = 'r1'").use { cursor ->
            assertTrue("the old row should still be there", cursor.moveToFirst())
            // An old row never mentioned conditions and must not be misread as
            // having any — this null is what makes it keep firing unconditionally.
            assertTrue("an old row's conditionsJson should read as null", cursor.isNull(0))
        }
        migrated.query("SELECT COUNT(*) FROM components WHERE ruleId = 'r1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun migration_1_to_2_lets_a_new_row_store_a_conditions_tree() {
        helper.createDatabase(TEST_DB, 1).apply { close() }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        // The exact column the migration exists to add, written the way a
        // version-2 build actually wrote it — a `check`/`all`/`any` tree, not
        // today's trigger-tree shape, which did not exist yet. This proves the
        // migrated table accepts a real value, not just that the ALTER TABLE
        // ran without error. `ConditionNode` and `RuleJson.encodeConditions`
        // are gone from the domain model, so the literal JSON stands in for
        // them — it is the shape they used to produce, and that shape, not the
        // type that once modelled it, is what a real database can still hold.
        val json = """{"node":"check","type":"wifi_state","config":{}}"""
        migrated.execSQL(
            "INSERT INTO rules (id, name, enabled, position, conditionsJson) VALUES (?, ?, ?, ?, ?)",
            arrayOf("r2", "New rule", 1, 0, json),
        )

        migrated.query("SELECT conditionsJson FROM rules WHERE id = 'r2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(json, cursor.getString(0))
        }
    }

    // --- 2 -> 3 -------------------------------------------------------------

    @Test
    fun migration_2_to_3_adds_a_null_triggerJson_and_leaves_old_rows_alone() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, conditionsJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "Old gate", 1, 0, null),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "TRIGGER", 0, "screen_state", "{}"),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        migrated.query("SELECT triggerJson FROM rules WHERE id = 'r1'").use { cursor ->
            assertTrue("the old row should still be there", cursor.moveToFirst())
            assertTrue("an old row's triggerJson should read as null", cursor.isNull(0))
        }
        migrated.query("SELECT COUNT(*) FROM components WHERE ruleId = 'r1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // The migration itself touches nothing in `components` — it is a
            // bare ALTER TABLE. The legacy TRIGGER row is still exactly there.
            assertEquals(1, cursor.getInt(0))
        }
    }

    /**
     * The important one: a real pre-version-3 shape — several `TRIGGER` rows
     * (an implicit OR) plus a nested `conditionsJson` tree (ANDed on top) —
     * migrated and then read back through the actual mapper
     * (`toRuleOrNull`'s legacy path), not reconstructed by hand in the test.
     *
     * This is what `TRIGGER` rows plus `conditionsJson` *meant*, expressed as
     * the [TriggerNode] the legacy path must produce:
     * `Group(ALL, [Group(ANY, [edge1, edge2]), conditionsTree])`.
     */
    @Test
    fun migration_2_to_3_reconstructs_the_old_trigger_and_conditions_as_one_tree() = runTest {
        val dbName = "$TEST_DB-reconstruct"
        val legacyConditionsJson = """
            {"node":"all","children":[
              {"node":"check","type":"time_window","config":{"from":"22:00","to":"07:00"}},
              {"node":"any","children":[
                {"node":"check","type":"wifi_state","config":{"state":"connected"}},
                {"node":"check","type":"bluetooth_connected","config":{}}
              ]}
            ]}
        """.trimIndent()

        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, conditionsJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "Old gate", 1, 0, legacyConditionsJson),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "TRIGGER", 0, "power_connection", """{"state":"connected"}"""),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "TRIGGER", 1, "headset_plug", """{"state":"plugged"}"""),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "ACTION", 0, "speak", """{"text":"Charging"}"""),
            )
            close()
        }
        // Validates the migrated schema against Room's exported version-3 JSON.
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).close()

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TriglyDatabase::class.java,
            dbName,
        ).addMigrations(*TRIGLY_MIGRATIONS).build()

        try {
            val loaded = database.rules().findRule("r1")?.toRuleOrNull()
            assertNotNull("a migrated row must still map to a valid rule", loaded)

            val expectedTrigger = TriggerNode.Group(
                TriggerNode.Op.ALL,
                listOf(
                    TriggerNode.Group(
                        TriggerNode.Op.ANY,
                        listOf(
                            TriggerNode.One(ComponentSpec("power_connection", mapOf("state" to "connected"))),
                            TriggerNode.One(ComponentSpec("headset_plug", mapOf("state" to "plugged"))),
                        ),
                    ),
                    TriggerNode.Group(
                        TriggerNode.Op.ALL,
                        listOf(
                            TriggerNode.One(
                                ComponentSpec("time_window", mapOf("from" to "22:00", "to" to "07:00")),
                            ),
                            TriggerNode.Group(
                                TriggerNode.Op.ANY,
                                listOf(
                                    TriggerNode.One(ComponentSpec("wifi_state", mapOf("state" to "connected"))),
                                    TriggerNode.One(ComponentSpec("bluetooth_connected")),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            assertEquals(expectedTrigger, loaded!!.trigger)
            assertEquals(listOf(ComponentSpec("speak", mapOf("text" to "Charging"))), loaded.actions)
        } finally {
            database.close()
        }
    }

    /**
     * The legacy columns are a one-way door: the moment the user saves a
     * migrated rule again, [app.phueber.trigly.core.Rule.toEntity] fills
     * `triggerJson` and the row never touches its `TRIGGER` rows or
     * `conditionsJson` again. This is the test that would fail if a reader
     * silently kept relying on the legacy columns instead of healing them.
     */
    @Test
    fun saving_a_migrated_rule_fills_triggerJson_and_heals_conditionsJson() = runTest {
        val dbName = "$TEST_DB-heal"
        val legacyConditionsJson = """{"node":"check","type":"wifi_state","config":{}}"""

        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, conditionsJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "Old gate", 1, 0, legacyConditionsJson),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "TRIGGER", 0, "power_connection", "{}"),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "ACTION", 0, "speak", "{}"),
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).close()

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TriglyDatabase::class.java,
            dbName,
        ).addMigrations(*TRIGLY_MIGRATIONS).build()

        try {
            fun triggerAndConditionsJson(): Pair<String?, String?> =
                database.openHelper.readableDatabase
                    .query("SELECT triggerJson, conditionsJson FROM rules WHERE id = 'r1'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        val trigger = if (cursor.isNull(0)) null else cursor.getString(0)
                        val conditions = if (cursor.isNull(1)) null else cursor.getString(1)
                        trigger to conditions
                    }

            val (triggerBefore, conditionsBefore) = triggerAndConditionsJson()
            assertEquals(null, triggerBefore)
            assertEquals(legacyConditionsJson, conditionsBefore)

            val repository = RoomRuleRepository(database.rules())
            val loaded = repository.rules().first().single()
            repository.upsert(loaded)

            val (triggerAfter, conditionsAfter) = triggerAndConditionsJson()
            assertFalse("saving a migrated rule must fill triggerJson", triggerAfter.isNullOrBlank())
            assertEquals(null, conditionsAfter)
        } finally {
            database.close()
        }
    }

    // --- 3 -> 4 -------------------------------------------------------------

    @Test
    fun migration_3_to_4_adds_a_null_folder_and_leaves_old_rows_alone() {
        // A version-3 database built from the real schema, not from the
        // current entities — this is what a phone that installed up to
        // version 3 and never reinstalled actually has on disk. Every such row
        // predates the concept of a folder, so null is the only honest value
        // for it.
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, triggerJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "Old rule", 1, 0, """{"type":"screen_state","config":{}}"""),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "ACTION", 0, "speak", "{}"),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        migrated.query("SELECT folder FROM rules WHERE id = 'r1'").use { cursor ->
            assertTrue("the old row should still be there", cursor.moveToFirst())
            assertTrue("an old row's folder should read as null", cursor.isNull(0))
        }
        migrated.query("SELECT COUNT(*) FROM components WHERE ruleId = 'r1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // A bare ALTER TABLE touches nothing in `components`.
            assertEquals(1, cursor.getInt(0))
        }
    }

    /**
     * The real path, end to end: a version-3 row migrated to 4, read back
     * through [app.phueber.trigly.core.storage.toRuleOrNull] as an ungrouped
     * rule ([Rule.folder] `== null`), then saved back into a folder and read
     * again — proving the new column round-trips through the actual
     * repository, not just through raw SQL.
     */
    @Test
    fun migration_3_to_4_lets_a_migrated_rule_be_filed_into_a_folder() = runTest {
        val dbName = "$TEST_DB-folder"

        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, triggerJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "Old rule", 1, 0, """{"type":"screen_state","config":{}}"""),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "ACTION", 0, "speak", "{}"),
            )
            close()
        }
        // Validates the migrated schema against Room's exported version-4 JSON.
        helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4).close()

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TriglyDatabase::class.java,
            dbName,
        ).addMigrations(*TRIGLY_MIGRATIONS).build()

        try {
            val repository = RoomRuleRepository(database.rules())
            val loaded = repository.rules().first().single()
            assertEquals(null, loaded.folder)

            repository.upsert(loaded.copy(folder = "Car"))

            val reloaded = repository.rules().first().single()
            assertEquals("Car", reloaded.folder)
        } finally {
            database.close()
        }
    }

    // --- 4 -> 5 -------------------------------------------------------------

    @Test
    fun migration_4_to_5_creates_the_variables_table_and_leaves_rules_alone() {
        // A version-4 database built from the real schema, not from the
        // current entities. This is what a phone that installed up to
        // version 4 and never reinstalled actually has on disk. No such row
        // has ever heard of an app variable, so the table has to appear empty
        // rather than assume anything about what it should hold.
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, triggerJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "Old rule", 1, 0, """{"type":"screen_state","config":{}}"""),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "ACTION", 0, "speak", "{}"),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        migrated.query("SELECT name FROM rules WHERE id = 'r1'").use { cursor ->
            assertTrue("the old rule should still be there", cursor.moveToFirst())
            assertEquals("Old rule", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM components WHERE ruleId = 'r1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // The migration creates a table; it does not touch an existing one.
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM variables").use { cursor ->
            assertTrue("the new table should exist and start empty", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    /**
     * The real path, end to end: a version-4 database migrated to 5, then a
     * variable is set, overwritten, and removed through the actual
     * [RoomVariableStore] rather than through raw SQL. This proves the new
     * table round-trips through the port `set_variable` and `variable_check`
     * will use, not just through the migration's own `CREATE TABLE`. The
     * pre-existing rule is read back too, to show the migration left it alone.
     */
    @Test
    fun migration_4_to_5_lets_a_real_database_round_trip_a_variable() = runTest {
        val dbName = "$TEST_DB-variables"

        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, triggerJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "Old rule", 1, 0, """{"type":"screen_state","config":{}}"""),
            )
            execSQL(
                "INSERT INTO components (ruleId, role, ordinal, type, configJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "ACTION", 0, "speak", "{}"),
            )
            close()
        }
        // Validates the migrated schema against Room's exported version-5 JSON.
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TriglyDatabase::class.java,
            dbName,
        ).addMigrations(*TRIGLY_MIGRATIONS).build()

        try {
            val store = RoomVariableStore(database.variables())

            assertNull("nothing has been set yet", store.get("trip_count"))

            store.set("trip_count", "3")
            assertEquals("3", store.get("trip_count"))

            store.set("trip_count", "4")
            assertEquals("overwriting keeps the same name", "4", store.get("trip_count"))

            store.remove("trip_count")
            assertNull("removed reads back as null, not as an error", store.get("trip_count"))

            // The migration touched only `variables`. The rule from before the
            // upgrade is exactly as it was.
            val rule = database.rules().findRule("r1")?.toRuleOrNull()
            assertNotNull("a migrated row must still map to a valid rule", rule)
            assertEquals("Old rule", rule!!.name)
        } finally {
            database.close()
        }
    }
    @Test
    fun migration_5_to_6_creates_the_rule_variables_table_and_leaves_rules_alone() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, triggerJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "Old rule", 1, 0, """{"type":"screen_state","config":{}}"""),
            )
            execSQL(
                "INSERT INTO variables (name, value, updatedAtMillis) VALUES (?, ?, ?)",
                arrayOf("trip_count", "7", 1L),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        migrated.query("SELECT name FROM rules WHERE id = 'r1'").use { cursor ->
            assertTrue("the old rule should still be there", cursor.moveToFirst())
            assertEquals("Old rule", cursor.getString(0))
        }
        // The shared scope is a different table and is not touched. A value a
        // person already had must not move to the new scope, which nothing
        // could then read as `{{app.trip_count}}`.
        migrated.query("SELECT value FROM variables WHERE name = 'trip_count'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("7", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM rule_variables").use { cursor ->
            assertTrue("the new table should exist and start empty", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    /**
     * The property the whole scope exists for, proved through the real store
     * rather than through raw SQL: two rules both keep a `count`, neither sees
     * the other's, and neither is the shared `{{app.count}}`.
     *
     * The cascade is checked in the same test because it is the other half of
     * the same design. A rule's private values are unreachable once the rule is
     * gone, since nothing else could name them, so leaving them behind would be
     * a leak no screen lists and no rule can use.
     */
    @Test
    fun migration_5_to_6_keeps_two_rules_values_apart_and_deletes_them_with_the_rule() = runTest {
        val dbName = "$TEST_DB-rule-variables"

        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, triggerJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r1", "First", 1, 0, """{"type":"screen_state","config":{}}"""),
            )
            execSQL(
                "INSERT INTO rules (id, name, enabled, position, triggerJson) VALUES (?, ?, ?, ?, ?)",
                arrayOf("r2", "Second", 1, 1, """{"type":"screen_state","config":{}}"""),
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TriglyDatabase::class.java,
            dbName,
        ).addMigrations(*TRIGLY_MIGRATIONS).build()

        try {
            val store = RoomRuleVariableStore(database.ruleVariables())
            val shared = RoomVariableStore(database.variables())

            assertNull("nothing has been set yet", store.get("r1", "count"))

            store.set("r1", "count", "1")
            store.set("r2", "count", "99")

            assertEquals("1", store.get("r1", "count"))
            assertEquals("the same name, a different rule, a different value", "99", store.get("r2", "count"))
            assertNull("and the shared scope is untouched", shared.get("count"))

            store.remove("r1", "count")
            assertNull(store.get("r1", "count"))
            assertEquals("removing one rule's value leaves the other's", "99", store.get("r2", "count"))

            // The cascade. Deleting the rule takes its private values with it.
            database.rules().deleteRule("r2")
            assertNull(
                "a deleted rule's values must not outlive it",
                store.get("r2", "count"),
            )
        } finally {
            database.close()
        }
    }

}

private const val TEST_DB = "migration-test"
