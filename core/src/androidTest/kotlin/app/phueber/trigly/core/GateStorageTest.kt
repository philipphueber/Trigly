package app.phueber.trigly.core

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.storage.ComponentEntity
import app.phueber.trigly.core.storage.ComponentRole
import app.phueber.trigly.core.storage.RoomRuleRepository
import app.phueber.trigly.core.storage.RuleEntity
import app.phueber.trigly.core.storage.TriglyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gate's storage path: several trigger edges and a nested condition tree,
 * neither of which existed before gates and neither of which `RuleStorageTest`
 * exercises. A silent bug here is the same failure `RuleStorageTest` guards
 * against for the single-trigger case — a rule that comes back different from
 * how it was saved, or vanishes outright — but for the new shape specifically:
 * edge order (semantic, because the first level is an ordered OR, not a set)
 * and the condition tree (semantic, because nesting changes what the gate
 * means).
 *
 * In-memory database per test, so nothing leaks between runs — the on-disk one
 * would, and the project convention is to run a new instrumented spec twice.
 */
@RunWith(AndroidJUnit4::class)
class GateStorageTest {

    private lateinit var database: TriglyDatabase
    private lateinit var repository: RoomRuleRepository

    private val conditions = ConditionNode.All(
        listOf(
            ConditionNode.Check(ComponentSpec("time_window", mapOf("from" to "22:00", "to" to "07:00"))),
            ConditionNode.Any(
                listOf(
                    ConditionNode.Check(ComponentSpec("wifi_state", mapOf("state" to "connected"))),
                    ConditionNode.Check(ComponentSpec("bluetooth_connected")),
                ),
            ),
        ),
    )

    private val rule = Rule(
        id = "rule-1",
        name = "Several edges, nested conditions",
        gate = Gate(
            triggers = listOf(
                ComponentSpec("power_connection", mapOf("state" to "connected")),
                ComponentSpec("headset_plug", mapOf("state" to "plugged")),
                ComponentSpec("bluetooth_connected"),
            ),
            conditions = conditions,
        ),
        actions = listOf(ComponentSpec("speak", mapOf("text" to "Charging"))),
        enabled = true,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TriglyDatabase::class.java,
        ).build()
        repository = RoomRuleRepository(database.rules())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun a_rule_with_several_edges_and_nested_conditions_survives_a_round_trip() = runTest {
        repository.upsert(rule)

        assertEquals(listOf(rule), repository.rules().first())
    }

    @Test
    fun edge_order_is_preserved() = runTest {
        // The first level is an ordered OR, not a set — a rule that came back
        // with its edges shuffled would read as a different rule, even though a
        // naive equality check on the wrong collection type would miss it.
        repository.upsert(rule)

        val stored = repository.rules().first().single()
        assertEquals(
            listOf("power_connection", "headset_plug", "bluetooth_connected"),
            stored.gate.triggers.map { it.type },
        )
    }

    @Test
    fun a_rule_with_no_conditions_loads_with_null_conditions() = runTest {
        repository.upsert(rule.copy(gate = rule.gate.copy(conditions = null)))

        assertNull(repository.rules().first().single().gate.conditions)
    }

    @Test
    fun unreadable_conditions_json_degrades_to_no_conditions_rather_than_losing_the_rule() =
        runTest {
            // Written directly through the DAO rather than through the
            // repository: the repository always produces valid JSON from a real
            // ConditionNode, so a corrupt column can only arise from something
            // outside this app's own write path — a partial restore, or a
            // hand-edited database. toRuleOrNull must not let that cost the user
            // the whole rule, only its conditions.
            database.rules().save(
                RuleEntity(
                    id = "rule-1",
                    name = "Corrupt conditions",
                    enabled = true,
                    position = 0,
                    conditionsJson = "not json at all",
                ),
                listOf(
                    ComponentEntity(
                        ruleId = "rule-1",
                        role = ComponentRole.TRIGGER,
                        ordinal = 0,
                        type = "screen_state",
                        configJson = "{}",
                    ),
                ),
            )

            val loaded = repository.rules().first().single()
            assertEquals("Corrupt conditions", loaded.name)
            assertNull(loaded.gate.conditions)
        }
}
