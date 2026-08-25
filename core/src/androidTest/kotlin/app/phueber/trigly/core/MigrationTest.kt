package app.phueber.trigly.core

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.storage.MIGRATION_1_2
import app.phueber.trigly.core.storage.MIGRATION_2_3
import app.phueber.trigly.core.storage.RoomRuleRepository
import app.phueber.trigly.core.storage.TriglyDatabase
import app.phueber.trigly.core.storage.toRuleOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two migrations have ever shipped. Each must get right what Room cannot check
 * for itself: that a database actually written by the *old* version — a real
 * user's rules, not a fixture built fresh at the new version — survives the
 * upgrade with its rows intact and its meaning preserved. A wrong migration is
 * indistinguishable from a fine one until an existing install updates, which is
 * exactly the moment nobody is watching for it.
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
        ).addMigrations(MIGRATION_2_3).build()

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
        ).addMigrations(MIGRATION_2_3).build()

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
}

private const val TEST_DB = "migration-test"
