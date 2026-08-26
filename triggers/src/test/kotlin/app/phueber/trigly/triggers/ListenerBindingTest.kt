package app.phueber.trigly.triggers

import app.phueber.trigly.triggers.notification.keepListenerBound
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The binding watcher, on virtual time.
 *
 * Worth testing on the JVM even though the thing it guards is a platform
 * binding, because every mistake available here is silent on a device: asking
 * too eagerly costs a working listener a gap it did not need, and not asking at
 * all looks exactly like a notification that has not arrived yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenerBindingTest {

    private val grace = 15_000L
    private val retry = 300_000L

    /**
     * The normal path, and the one a careless implementation breaks. A fresh
     * process starts with nothing bound and the system binds it a moment later,
     * so asking on sight would mean asking on every single app start, and
     * `requestRebind` unbinds first.
     */
    @Test
    fun `does not ask when the system binds within the grace period`() = runTest {
        val connected = MutableStateFlow(false)
        var asked = 0

        val job = launch { keepListenerBound(connected, { true }, { asked++ }, grace, retry) }

        advanceTimeBy(grace / 2)
        connected.value = true
        advanceTimeBy(grace * 4)

        assertEquals(0, asked)
        job.cancelAndJoin()
    }

    @Test
    fun `asks once the grace period passes with nothing bound`() = runTest {
        val connected = MutableStateFlow(false)
        var asked = 0

        val job = launch { keepListenerBound(connected, { true }, { asked++ }, grace, retry) }

        advanceTimeBy(grace / 2)
        assertEquals("asked before the grace period was up", 0, asked)

        advanceTimeBy(grace)
        assertEquals(1, asked)

        job.cancelAndJoin()
    }

    /** The fallback keeps trying, because one refusal is not proof of the next. */
    @Test
    fun `keeps asking while nothing binds`() = runTest {
        val connected = MutableStateFlow(false)
        var asked = 0

        val job = launch { keepListenerBound(connected, { true }, { asked++ }, grace, retry) }

        advanceTimeBy(grace + retry * 3 + 1)

        assertEquals(4, asked)
        job.cancelAndJoin()
    }

    /**
     * The property `collectLatest` is there for: a binding cancels the retry
     * loop outright rather than leaving it to notice on its next tick.
     */
    @Test
    fun `stops asking as soon as the listener binds`() = runTest {
        val connected = MutableStateFlow(false)
        var asked = 0

        val job = launch { keepListenerBound(connected, { true }, { asked++ }, grace, retry) }

        advanceTimeBy(grace + 1)
        assertEquals(1, asked)

        connected.value = true
        advanceTimeBy(retry * 10)

        assertEquals("kept asking after the listener bound", 1, asked)
        job.cancelAndJoin()
    }

    /**
     * Without the grant the platform would refuse anyway, and a rule that needs
     * access it does not have already says so as an unmet requirement. Asking
     * would be noise on top of a message the user has.
     */
    @Test
    fun `never asks while notification access is not granted`() = runTest {
        val connected = MutableStateFlow(false)
        var asked = 0

        val job = launch { keepListenerBound(connected, { false }, { asked++ }, grace, retry) }

        advanceTimeBy(grace + retry * 5)

        assertEquals(0, asked)
        job.cancelAndJoin()
    }

    /**
     * A grant that arrives while the engine is already running is picked up on
     * the next tick, so the watcher does not have to be restarted to become
     * useful.
     */
    @Test
    fun `starts asking once access is granted later`() = runTest {
        val connected = MutableStateFlow(false)
        var granted = false
        var asked = 0

        val job = launch { keepListenerBound(connected, { granted }, { asked++ }, grace, retry) }

        advanceTimeBy(grace + retry)
        assertEquals(0, asked)

        granted = true
        advanceTimeBy(retry)

        assertEquals(1, asked)
        job.cancelAndJoin()
    }
}
