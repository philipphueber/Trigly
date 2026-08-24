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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rules are hand-built user data, so storage losing or mangling them is the worst
 * bug this project can have. In-memory database per test, so nothing leaks
 * between runs — the on-disk one would, and the project convention is to run new
 * instrumented specs twice.
 */
@RunWith(AndroidJUnit4::class)
class RuleStorageTest {

    private lateinit var database: TriglyDatabase
    private lateinit var repository: RoomRuleRepository

    private val rule = Rule(
        id = "rule-1",
        name = "Charger connected",
        trigger = ComponentSpec("power_connection", mapOf("state" to "connected")),
        actions = listOf(
            ComponentSpec("speak", mapOf("text" to "Charging")),
            ComponentSpec("vibrate", mapOf("durationMillis" to "200")),
        ),
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
    fun a_saved_rule_comes_back_identical() = runTest {
        repository.upsert(rule)

        assertEquals(listOf(rule), repository.rules().first())
    }

    @Test
    fun action_order_survives_storage() = runTest {
        // Order is semantic — actions run in sequence — and a relational store
        // has no inherent ordering, so this is the assertion that matters most.
        repository.upsert(rule)

        assertEquals(
            listOf("speak", "vibrate"),
            repository.rules().first().single().actions.map { it.type },
        )
    }

    @Test
    fun reordered_actions_replace_the_old_order() = runTest {
        repository.upsert(rule)

        repository.upsert(rule.copy(actions = rule.actions.reversed()))

        val stored = repository.rules().first().single()
        assertEquals(listOf("vibrate", "speak"), stored.actions.map { it.type })
        // And no duplicates left behind by the replace.
        assertEquals(2, stored.actions.size)
    }

    @Test
    fun editing_a_rule_does_not_create_a_second_one() = runTest {
        repository.upsert(rule)
        repository.upsert(rule.copy(name = "Renamed"))

        val all = repository.rules().first()
        assertEquals(1, all.size)
        assertEquals("Renamed", all.single().name)
    }

    @Test
    fun removing_an_action_removes_its_row() = runTest {
        repository.upsert(rule)

        repository.upsert(rule.copy(actions = listOf(rule.actions.first())))

        assertEquals(1, repository.rules().first().single().actions.size)
    }

    @Test
    fun config_with_awkward_characters_survives() = runTest {
        val awkward = rule.copy(
            actions = listOf(
                ComponentSpec(
                    "http_request",
                    mapOf(
                        "url" to "https://example.com/a?b=1&c=2",
                        "body" to """{"nested":"json","quote":"\""}""",
                        "note" to "emoji 🔔 ünïcode\nnewline",
                    ),
                )
            )
        )

        repository.upsert(awkward)

        assertEquals(awkward, repository.rules().first().single())
    }

    @Test
    fun an_empty_config_stays_empty() = runTest {
        val bare = rule
            .withTrigger(ComponentSpec("screen_state", emptyMap()))
            .copy(actions = listOf(ComponentSpec("cancel_notification", emptyMap())))

        repository.upsert(bare)

        assertEquals(bare, repository.rules().first().single())
    }

    @Test
    fun deleting_a_rule_removes_it_and_its_components() = runTest {
        repository.upsert(rule)

        repository.delete(rule.id)

        assertTrue(repository.rules().first().isEmpty())
        // The cascade is what keeps orphaned component rows from accumulating.
        assertNull(database.rules().findRule(rule.id))
    }

    @Test
    fun rules_keep_their_position_across_an_edit() = runTest {
        val first = rule.copy(id = "a", name = "First")
        val second = rule.copy(id = "b", name = "Second")
        repository.upsert(first)
        repository.upsert(second)

        // Editing the first must not move it to the end of the list.
        repository.upsert(first.copy(name = "First edited"))

        assertEquals(
            listOf("First edited", "Second"),
            repository.rules().first().map { it.name },
        )
    }

    @Test
    fun a_new_rule_is_appended_rather_than_prepended() = runTest {
        repository.upsert(rule.copy(id = "a", name = "First"))
        repository.upsert(rule.copy(id = "b", name = "Second"))

        assertEquals(listOf("First", "Second"), repository.rules().first().map { it.name })
    }

    @Test
    fun the_disabled_flag_persists() = runTest {
        repository.upsert(rule.copy(enabled = false))

        assertEquals(false, repository.rules().first().single().enabled)
    }

    @Test
    fun an_exported_document_can_be_imported_back() = runTest {
        // The phone-switch path end to end: store, export, wipe, import.
        repository.upsert(rule.copy(id = "a", name = "First"))
        repository.upsert(rule.copy(id = "b", name = "Second"))
        val document = RuleJson.encode(repository.rules().first())

        repository.delete("a")
        repository.delete("b")
        assertTrue(repository.rules().first().isEmpty())

        RuleJson.decode(document).withFreshIds().forEach { repository.upsert(it) }

        val restored = repository.rules().first()
        assertEquals(listOf("First", "Second"), restored.map { it.name })
        assertEquals(
            listOf("speak", "vibrate"),
            restored.first().actions.map { it.type },
        )
    }
}
