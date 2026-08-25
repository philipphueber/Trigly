package app.phueber.trigly.core

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.storage.RoomRuleRepository
import app.phueber.trigly.core.storage.TriglyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trigger tree's storage path: a [TriggerNode] that nests, which
 * `RuleStorageTest` never exercises because its rule's trigger is a plain
 * [TriggerNode.One]. A silent bug here is the same failure `RuleStorageTest`
 * guards against for the simple case — a rule that comes back different from
 * how it was saved, or vanishes outright — but for a tree specifically: nesting
 * and child order are both semantic, since a [TriggerNode.Group]'s children are
 * an ordered list and reshuffling them, or flattening a level, changes what the
 * rule means.
 *
 * These tests write through the normal path — [Rule.toEntity] filling
 * `triggerJson` — so they say nothing about the legacy `TRIGGER` rows plus
 * `conditionsJson` shape a pre-version-3 database still has on disk; that is
 * `MigrationTest`'s job.
 *
 * In-memory database per test, so nothing leaks between runs — the on-disk one
 * would, and the project convention is to run a new instrumented spec twice.
 */
@RunWith(AndroidJUnit4::class)
class GateStorageTest {

    private lateinit var database: TriglyDatabase
    private lateinit var repository: RoomRuleRepository

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
    fun a_deeply_nested_trigger_tree_survives_a_round_trip() = runTest {
        val tree = TriggerNode.Group(
            TriggerNode.Op.ALL,
            listOf(
                TriggerNode.Group(
                    TriggerNode.Op.ANY,
                    listOf(
                        TriggerNode.One(ComponentSpec("wifi_state", mapOf("state" to "connected"))),
                        TriggerNode.One(ComponentSpec("bluetooth_connected")),
                    ),
                ),
                TriggerNode.One(ComponentSpec("time_window", mapOf("from" to "22:00", "to" to "07:00"))),
                TriggerNode.Group(
                    TriggerNode.Op.ALL,
                    listOf(
                        TriggerNode.One(ComponentSpec("headset_plug", mapOf("state" to "plugged"))),
                        TriggerNode.Group(
                            TriggerNode.Op.ANY,
                            listOf(
                                TriggerNode.One(ComponentSpec("power_connection", mapOf("state" to "connected"))),
                                TriggerNode.One(ComponentSpec("screen_state", mapOf("state" to "on"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val rule = Rule(
            id = "rule-1",
            name = "Deeply nested",
            trigger = tree,
            actions = listOf(ComponentSpec("speak", mapOf("text" to "Charging"))),
            enabled = true,
        )

        repository.upsert(rule)

        assertEquals(listOf(rule), repository.rules().first())
    }

    @Test
    fun a_group_of_one_child_survives_a_round_trip() = runTest {
        // The editor should never build this — a group of one is a box drawn
        // around nothing — but an imported file can, and storage must not
        // collapse or otherwise reshape it behind the user's back.
        val rule = Rule(
            id = "rule-1",
            name = "Group of one",
            trigger = TriggerNode.Group(
                TriggerNode.Op.ANY,
                listOf(TriggerNode.One(ComponentSpec("bluetooth_connected"))),
            ),
            actions = listOf(ComponentSpec("speak", mapOf("text" to "Connected"))),
            enabled = true,
        )

        repository.upsert(rule)

        assertEquals(listOf(rule), repository.rules().first())
    }

    @Test
    fun child_order_within_a_group_is_preserved() = runTest {
        // A group's children are an ordered OR/AND, not a set — a rule that
        // came back with them shuffled would read as a different rule, even
        // though the equality check above would already catch it. This test
        // names the exact thing that must stay stable.
        val rule = Rule(
            id = "rule-1",
            name = "Ordered group",
            trigger = TriggerNode.Group(
                TriggerNode.Op.ANY,
                listOf(
                    TriggerNode.One(ComponentSpec("power_connection")),
                    TriggerNode.One(ComponentSpec("headset_plug")),
                    TriggerNode.One(ComponentSpec("bluetooth_connected")),
                ),
            ),
            actions = listOf(ComponentSpec("speak", mapOf("text" to "Charging"))),
            enabled = true,
        )

        repository.upsert(rule)

        val stored = repository.rules().first().single().trigger as TriggerNode.Group
        assertEquals(
            listOf("power_connection", "headset_plug", "bluetooth_connected"),
            stored.children.map { (it as TriggerNode.One).spec.type },
        )
    }
}
