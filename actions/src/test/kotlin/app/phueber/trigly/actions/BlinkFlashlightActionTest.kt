package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what a person said into a pattern.
 *
 * "Blink three times" and "blink for five seconds" are the two ways anybody
 * expresses this, and both end as a count of blinks. The arithmetic is pure so
 * the cap and the conversion are tested rather than trusted, which matters more
 * here than usual: the emulators this project tests on have no flash unit, so
 * nothing on a device can ever watch the pattern run.
 */
class BlinkPatternTest {

    @Test
    fun `a count is taken as it is`() {
        val config = mapOf(
            BlinkLimit.CONFIG_KEY to "count",
            BlinkFlashlightAction.CONFIG_TIMES to "5",
            BlinkFlashlightAction.CONFIG_ON_MILLIS to "100",
            BlinkFlashlightAction.CONFIG_OFF_MILLIS to "100",
        )

        assertEquals(BlinkPattern(times = 5, onMillis = 100, offMillis = 100), blinkPattern(config))
    }

    @Test
    fun `a length of time becomes the number of blinks that fit in it`() {
        val config = mapOf(
            BlinkLimit.CONFIG_KEY to "duration",
            BlinkFlashlightAction.CONFIG_TOTAL_MILLIS to "5000",
            BlinkFlashlightAction.CONFIG_ON_MILLIS to "200",
            BlinkFlashlightAction.CONFIG_OFF_MILLIS to "300",
        )

        assertEquals(BlinkPattern(times = 10, onMillis = 200, offMillis = 300), blinkPattern(config))
    }

    @Test
    fun `a length of time rounds down rather than overrunning it`() {
        // Five seconds of 700 ms cycles is seven blinks and a bit. The bit is
        // dropped: a pattern that ran longer than the number somebody typed
        // would be the setting failing to mean what it says.
        assertEquals(7, blinkTimesWithin(totalMillis = 5_000, cycleMillis = 700))
    }

    @Test
    fun `a length shorter than one cycle still blinks once`() {
        // Answering "blink for half a second" with darkness would read as the
        // action being broken.
        assertEquals(1, blinkTimesWithin(totalMillis = 500, cycleMillis = 1_000))
    }

    @Test
    fun `the default config is three short blinks`() {
        assertEquals(
            BlinkPattern(times = 3, onMillis = 200, offMillis = 200),
            blinkPattern(emptyMap()),
        )
    }

    @Test
    fun `an absent limit reads as a count, which is what the editor draws`() {
        assertEquals(BlinkLimit.COUNT, BlinkLimit.parse(null))
        assertEquals(BlinkLimit.COUNT, BlinkLimit.parse(""))
    }

    @Test
    fun `a limit this build does not know is refused rather than guessed`() {
        // Guessing which of the two was meant would silently change what the
        // rule does. It can only come from an edited file.
        assertThrows(IllegalStateException::class.java) { BlinkLimit.parse("forever") }
    }

    // --- the cap ---------------------------------------------------------------

    @Test
    fun `a pattern longer than the cap blinks fewer times`() {
        // 100 blinks of a 400 ms cycle is 40 seconds of holding the CPU. The
        // cap is 30, so 75 blinks fit.
        val config = mapOf(
            BlinkFlashlightAction.CONFIG_TIMES to "100",
            BlinkFlashlightAction.CONFIG_ON_MILLIS to "200",
            BlinkFlashlightAction.CONFIG_OFF_MILLIS to "200",
        )

        assertEquals(75, blinkPattern(config).times)
    }

    @Test
    fun `the capped pattern never holds the CPU past the cap`() {
        val pattern = blinkPattern(
            mapOf(
                BlinkFlashlightAction.CONFIG_TIMES to "1000",
                BlinkFlashlightAction.CONFIG_ON_MILLIS to "900",
                BlinkFlashlightAction.CONFIG_OFF_MILLIS to "100",
            )
        )

        assertTrue(
            "held for ${pattern.totalMillis} ms",
            pattern.totalMillis <= BlinkFlashlightAction.MAX_TOTAL_MILLIS,
        )
    }

    @Test
    fun `one blink survives a cycle longer than the whole cap`() {
        // Ten seconds on and ten off is one cycle of twenty seconds, which is
        // under the cap only because the last dark gap is never waited through.
        val pattern = blinkPattern(
            mapOf(
                BlinkFlashlightAction.CONFIG_TIMES to "4",
                BlinkFlashlightAction.CONFIG_ON_MILLIS to "10000",
                BlinkFlashlightAction.CONFIG_OFF_MILLIS to "10000",
            )
        )

        assertEquals(BlinkPattern(times = 1, onMillis = 10_000, offMillis = 10_000), pattern)
        assertEquals(10_000L, pattern.totalMillis)
    }

    @Test
    fun `a requested length over the cap is capped, not refused`() {
        assertEquals(
            BlinkFlashlightAction.MAX_TOTAL_MILLIS,
            blinkTotalMillis((BlinkFlashlightAction.MAX_TOTAL_MILLIS + 1).toString()),
        )
    }

    // --- the phases ------------------------------------------------------------

    @Test
    fun `a phase too short to see is raised to the shortest visible one`() {
        assertEquals(
            BlinkFlashlightAction.MIN_PHASE_MILLIS,
            blinkPhaseMillis("1", BlinkFlashlightAction.DEFAULT_ON_MILLIS),
        )
    }

    @Test
    fun `a phase is capped and defaulted like every other duration here`() {
        assertEquals(
            BlinkFlashlightAction.MAX_PHASE_MILLIS,
            blinkPhaseMillis("60000", BlinkFlashlightAction.DEFAULT_ON_MILLIS),
        )
        assertEquals(200L, blinkPhaseMillis(null, 200))
        assertEquals(200L, blinkPhaseMillis("soon", 200))
    }

    @Test
    fun `the last dark gap is not waited through`() {
        // The light is already out, so waiting there would only make the next
        // action in the rule late for no visible difference.
        val pattern = BlinkPattern(times = 3, onMillis = 200, offMillis = 300)

        assertEquals(500L, pattern.cycleMillis)
        assertEquals(3 * 500L - 300L, pattern.totalMillis)
    }

    @Test
    fun `a pattern with no blinks is not a pattern`() {
        assertThrows(IllegalArgumentException::class.java) { BlinkPattern(0, 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { BlinkPattern(1, 0, 100) }
    }
}

/**
 * Running the pattern.
 *
 * Two properties carry the weight. The timing is asserted on virtual time
 * rather than tolerated, because the whole reason the action holds the CPU is
 * that its edges land where they say they do. And the torch is off at the end
 * of every path: the pattern ending, a refused switch, and the cancellation
 * `TriggerEngine` performs when a rule is disabled while it runs. A torch left
 * burning is worse than a wake lock left held, because the user can see it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlinkFlashlightActionTest {

    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)

    @Test
    fun `it blinks the pattern, on the millisecond, and ends dark`() = runTest {
        val torch = FakeTorch { currentTime }
        val guard = FakeWakeGuard()
        val action = BlinkFlashlightAction(torch, guard, BlinkPattern(3, 200, 200))

        val result = action.execute(event)

        assertEquals(ActionResult.Success(), result)
        assertEquals(
            listOf(
                true to 0L, false to 200L,
                true to 400L, false to 600L,
                true to 800L, false to 1_000L,
            ),
            torch.edges.take(6),
        )
        assertEquals(1_000L, currentTime)
        assertFalse("the torch must not be left on", torch.isOn)
    }

    @Test
    fun `the whole pattern is one hold on the CPU, given back at the end`() = runTest {
        val guard = FakeWakeGuard()

        BlinkFlashlightAction(FakeTorch(), guard, BlinkPattern(3, 200, 200)).execute(event)

        // One hold for the pattern, not one per blink: a lock taken and given
        // back six times would let the device suspend in the gaps between them.
        assertEquals(1, guard.acquired)
        assertEquals(guard.acquired, guard.released)
        assertFalse(guard.isHeld)
    }

    @Test
    fun `the lock is asked for slightly longer than the pattern`() = runTest {
        val guard = FakeWakeGuard()
        val pattern = BlinkPattern(3, 200, 200)

        BlinkFlashlightAction(FakeTorch(), guard, pattern).execute(event)

        // A backstop shorter than the work it backs would cut a pattern short
        // and leave the torch on, which is the one outcome this action promises
        // never to produce.
        assertEquals(listOf(BlinkFlashlightAction.TYPE to 6_000L), guard.calls)
        assertEquals(6_000L, wakeTimeoutMillis(pattern.totalMillis))
    }

    @Test
    fun `disabling the rule mid-pattern switches the torch off`() = runTest {
        // The case that matters most. TriggerEngine.stopRule cancels a disabled
        // rule's job, and the light must not survive it.
        val torch = FakeTorch { currentTime }
        val guard = FakeWakeGuard()
        val action = BlinkFlashlightAction(torch, guard, BlinkPattern(20, 200, 200))
        var result: ActionResult? = null

        val job = backgroundScope.launch { result = action.execute(event) }
        advanceTimeBy(100)
        assertTrue("the torch should be on mid-blink", torch.isOn)
        job.cancel()
        job.join()

        assertFalse("a cancelled blink must not leave the torch on", torch.isOn)
        assertNull("a cancelled blink must not report a result", result)
        assertEquals("a cancelled blink must give the lock back", guard.acquired, guard.released)
    }

    @Test
    fun `a cancelled pattern does not keep blinking`() = runTest {
        val torch = FakeTorch { currentTime }
        val action = BlinkFlashlightAction(torch, FakeWakeGuard(), BlinkPattern(20, 200, 200))

        val job = backgroundScope.launch { action.execute(event) }
        advanceTimeBy(500)
        job.cancel()
        job.join()
        // Counted after the cancellation has run, because the switch-off in
        // the `finally` is part of it and is the last edge there should ever be.
        val edgesAtCancel = torch.edges.size
        advanceTimeBy(10_000)

        assertEquals(edgesAtCancel, torch.edges.size)
    }

    @Test
    fun `another app taking the camera stops the pattern and says why`() = runTest {
        // Not a torch callback. The same news arrives from the next edge, as a
        // refused switch, at most one half cycle later.
        val torch = FakeTorch { currentTime }
        torch.failWith = "Another app is using the camera, so the flashlight is not free."
        torch.failFromEdge = 2
        val action = BlinkFlashlightAction(torch, FakeWakeGuard(), BlinkPattern(10, 200, 200))

        val result = action.execute(event)

        assertEquals(
            ActionResult.Failure("Another app is using the camera, so the flashlight is not free."),
            result,
        )
        assertEquals("it must stop rather than keep trying", 2, torch.edges.size)
        assertFalse(torch.isOn)
    }

    @Test
    fun `a torch that refuses the very first switch fails without waiting`() = runTest {
        val torch = FakeTorch { currentTime }
        torch.failWith = "This device has no flashlight."
        val action = BlinkFlashlightAction(torch, FakeWakeGuard(), BlinkPattern(10, 200, 200))

        val result = action.execute(event)

        assertEquals(ActionResult.Failure("This device has no flashlight."), result)
        assertEquals("nothing should have been waited through", 0L, currentTime)
    }

    @Test
    fun `it blinks at the device's own brightness`() = runTest {
        // A blink exists to be noticed, so it never turns itself down. That is
        // also what keeps it off the API 33 call on every phone.
        val torch = FakeTorch()

        BlinkFlashlightAction(torch, FakeWakeGuard(), BlinkPattern(2, 100, 100)).execute(event)

        assertEquals(listOf(FULL_STRENGTH_PERCENT, FULL_STRENGTH_PERCENT), torch.strengths)
    }
}

/** What the blink factory declares. */
class BlinkFlashlightFactoryTest {

    private val factory = BlinkFlashlightActionFactory(FakeTorch(), FakeWakeGuard())

    @Test
    fun `a phone with no flash unit is never offered this action`() {
        assertEquals(
            listOf(ComponentRequirement.SystemFeature("android.hardware.camera.flash")),
            factory.requirements,
        )
    }

    @Test
    fun `the factory accepts a config built from its own declared schema`() {
        val config = factory.configFields.associate { field ->
            field.key to when (field) {
                is ConfigField.Choice -> field.default ?: field.options.first().value
                is ConfigField.Number -> (field.default ?: 1L).toString()
                is ConfigField.Duration -> (field.defaultMillis ?: 1_000L).toString()
                else -> "sample"
            }
        }

        factory.create(config)
    }

    @Test
    fun `the factory builds the pattern the config asks for`() = runTest {
        val torch = FakeTorch()
        val action = BlinkFlashlightActionFactory(torch, FakeWakeGuard()).create(
            mapOf(
                BlinkLimit.CONFIG_KEY to "count",
                BlinkFlashlightAction.CONFIG_TIMES to "2",
                BlinkFlashlightAction.CONFIG_ON_MILLIS to "100",
                BlinkFlashlightAction.CONFIG_OFF_MILLIS to "100",
            )
        )

        action.execute(TriggerEvent(triggerType = "interval", firedAtMillis = 0))

        assertEquals(2, torch.onCount)
    }

    @Test
    fun `the warning says the rule pauses, and where the cap is`() {
        val warning = factory.warning.orEmpty()

        assertTrue("the person building the rule reads this, not the KDoc", warning.contains("pauses"))
        assertTrue(warning.contains("30 seconds"))
        assertTrue("the promise that makes this safe to use", warning.contains("off at the end"))
    }
}
