package app.phueber.trigly.core

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VariableStore.scoped]: app-scope variables as the [ScopedVariable]s the
 * editor's picker lists.
 */
class VariableStoreScopedTest {

    @Test
    fun `an empty store offers nothing`() = runTest {
        val store = InMemoryVariableStore()

        store.scoped().test {
            assertEquals(emptyList<ScopedVariable>(), awaitItem())
        }
    }

    @Test
    fun `each stored name becomes an app-scope entry with the value as its sample`() = runTest {
        val store = InMemoryVariableStore(initial = mapOf("trip_count" to "3"))

        store.scoped().test {
            val entry = awaitItem().single()

            assertEquals(VariableScope.APP, entry.scope)
            assertEquals("trip_count", entry.spec.key)
            assertEquals("trip_count", entry.spec.label)
            assertEquals("3", entry.spec.sample)
        }
    }

    @Test
    fun `an app variable is never marked always present`() = runTest {
        // A rule reading an app variable before anything has written it is the
        // ordinary case, not an edge case: the rule that sets it may simply not
        // have run yet.
        val store = InMemoryVariableStore(initial = mapOf("trip_count" to "3"))

        store.scoped().test {
            assertEquals(false, awaitItem().single().spec.alwaysPresent)
        }
    }

    @Test
    fun `entries are sorted by name`() = runTest {
        val store = InMemoryVariableStore(initial = mapOf("zebra" to "1", "apple" to "2"))

        store.scoped().test {
            val names = awaitItem().map { it.spec.key }
            assertEquals(listOf("apple", "zebra"), names)
        }
    }

    @Test
    fun `a reference reads back through the parser`() = runTest {
        val store = InMemoryVariableStore(initial = mapOf("trip_count" to "3"))

        store.scoped().test {
            val entry = awaitItem().single()
            val reference = parseTemplate(entry.reference).references.single()

            assertTrue(reference.scope == VariableScope.APP && reference.name == "trip_count")
        }
    }
}
