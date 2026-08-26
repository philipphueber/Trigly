package app.phueber.trigly.core

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [InMemoryVariableStore]: the working default every test and preview gets.
 *
 * Not a stub. [VariableStore]'s own kdoc explains why: "this device has no
 * variables" is not a real state, so a default that refused would make every
 * test that forgot to wire a store silently test nothing.
 */
class InMemoryVariableStoreTest {

    @Test
    fun `a value that was never set reads back as null`() = runTest {
        val store = InMemoryVariableStore()

        assertNull(store.get("trip_count"))
    }

    @Test
    fun `set then get returns what was set`() = runTest {
        val store = InMemoryVariableStore()

        store.set("trip_count", "3")

        assertEquals("3", store.get("trip_count"))
    }

    @Test
    fun `setting a name a second time overwrites the first value`() = runTest {
        val store = InMemoryVariableStore()

        store.set("trip_count", "3")
        store.set("trip_count", "4")

        assertEquals("4", store.get("trip_count"))
    }

    @Test
    fun `removing a name that is there makes it read back as null`() = runTest {
        val store = InMemoryVariableStore()
        store.set("trip_count", "3")

        store.remove("trip_count")

        assertNull(store.get("trip_count"))
    }

    @Test
    fun `removing a name that was never there is not an error`() = runTest {
        val store = InMemoryVariableStore()

        store.remove("does_not_exist")

        assertNull(store.get("does_not_exist"))
    }

    @Test
    fun `all emits the current map and then every change`() = runTest {
        val store = InMemoryVariableStore()

        store.all().test {
            assertEquals(emptyMap<String, String>(), awaitItem())

            store.set("trip_count", "3")
            assertEquals(mapOf("trip_count" to "3"), awaitItem())

            store.set("last_seen", "07:15")
            assertEquals(mapOf("trip_count" to "3", "last_seen" to "07:15"), awaitItem())

            store.remove("trip_count")
            assertEquals(mapOf("last_seen" to "07:15"), awaitItem())
        }
    }

    @Test
    fun `a store built with initial values starts from them`() = runTest {
        val store = InMemoryVariableStore(initial = mapOf("trip_count" to "1"))

        assertEquals("1", store.get("trip_count"))
    }
}
