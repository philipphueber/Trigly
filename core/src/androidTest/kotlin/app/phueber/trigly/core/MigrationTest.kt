package app.phueber.trigly.core

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.storage.MIGRATION_1_2
import app.phueber.trigly.core.storage.TriglyDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `MIGRATION_1_2` adds the nullable `conditionsJson` column that carries a
 * gate's condition tree. It is the one migration this project has ever
 * shipped, and the thing it must get right is the one Room cannot check for
 * itself: that a database actually written by version 1 — a real user's
 * rules, not a fixture built fresh at version 2 — survives the upgrade with
 * its rows intact and the new column reading as "no conditions" rather than
 * corrupt or absent. A wrong migration is indistinguishable from a fine one
 * until an existing install updates, which is exactly the moment nobody is
 * watching for it.
 *
 * Built from the committed schema JSON in `core/schemas/` via
 * [MigrationTestHelper], which needs those files as instrumented-test assets;
 * they are copied into `core/src/androidTest/assets/` under the same
 * `<database>/<version>.json` layout Room's schema export already uses; no
 * `build.gradle.kts` change is needed since Android's default source sets
 * already merge `src/androidTest/assets` for the test APK.
 *
 * Deliberately not `Room.databaseBuilder` with `fallbackToDestructiveMigration`
 * — that tests the wrong thing, a database that never went through version 1
 * at all.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:JUnitRule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TriglyDatabase::class.java,
    )

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

        // The exact column the migration exists to add, written the way
        // TriglyDatabase itself writes it — proves the migrated table actually
        // accepts a real value, not just that the ALTER TABLE ran without error.
        val json = RuleJson.encodeConditions(ConditionNode.Check(ComponentSpec("wifi_state")))
        migrated.execSQL(
            "INSERT INTO rules (id, name, enabled, position, conditionsJson) VALUES (?, ?, ?, ?, ?)",
            arrayOf("r2", "New rule", 1, 0, json),
        )

        migrated.query("SELECT conditionsJson FROM rules WHERE id = 'r2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(json, cursor.getString(0))
        }
    }
}

private const val TEST_DB = "migration-test"
