package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.InMemoryRuleVariableStore
import app.phueber.trigly.core.RunScope
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.VariableScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The percent [GetVolumeAction] stores, and what it does when it cannot read.
 *
 * The arithmetic is the whole risk here, and no test on a device can reach it:
 * an emulator reports the step count its own audio policy holds, so a test
 * cannot ask for a maximum of zero or a minimum above zero. [FakeVolumeLevels]
 * chooses the numbers instead.
 */
class VolumePercentTest {

    @Test
    fun `a stream that starts at zero maps onto its maximum`() {
        assertEquals(0, volumePercentOf(current = 0, min = 0, max = 15))
        assertEquals(100, volumePercentOf(current = 15, min = 0, max = 15))
        // 7 of 15 is 46.67, and the nearest whole percent is 47.
        assertEquals(47, volumePercentOf(current = 7, min = 0, max = 15))
    }

    /**
     * The case a percent measured from zero gets wrong. At index 1 the stream
     * is as quiet as it can be, so it reads as 0 and not as 7.
     */
    @Test
    fun `the bottom of the range is zero even when the minimum is not`() {
        assertEquals(0, volumePercentOf(current = 1, min = 1, max = 15))
        assertEquals(100, volumePercentOf(current = 15, min = 1, max = 15))
        // 7 steps above a minimum of 1, over a span of 14, is exactly 50.
        assertEquals(50, volumePercentOf(current = 8, min = 1, max = 15))
    }

    /**
     * A muted stream reports index 0 whatever its minimum is, so the raw
     * difference goes negative. A rule must never see a percent below zero.
     */
    @Test
    fun `an index below the minimum still reads as zero`() {
        assertEquals(0, volumePercentOf(current = 0, min = 1, max = 15))
    }

    @Test
    fun `half a percent rounds up`() {
        // 1 of 8 is 12.5.
        assertEquals(13, volumePercentOf(current = 1, min = 0, max = 8))
        // 3 of 8 is 37.5.
        assertEquals(38, volumePercentOf(current = 3, min = 0, max = 8))
    }

    /**
     * Rounding to the nearest is what [volumeIndexFor] does in the other
     * direction. A rule that sets a percent and reads it back on the same
     * stream must get its own number where the steps allow one.
     */
    @Test
    fun `setting a percent and reading it back agrees where a step exists`() {
        val max = 15
        listOf(0, 20, 40, 60, 80, 100).forEach { asked ->
            val index = volumeIndexFor(percent = asked, maxIndex = max)
            val readBack = volumePercentOf(current = index, min = 0, max = max)
            val allowed = 100f / max / 2
            assertTrue(
                "$asked% became index $index and read back as $readBack",
                readBack != null && kotlin.math.abs(readBack - asked) <= allowed,
            )
        }
    }

    @Test
    fun `a stream with no steps has no percent`() {
        assertNull(volumePercentOf(current = 0, min = 0, max = 0))
    }

    @Test
    fun `a stream whose minimum equals its maximum has no percent`() {
        assertNull(volumePercentOf(current = 5, min = 5, max = 5))
    }
}

class GetVolumeActionTest {

    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)
    private val ruleStore = InMemoryRuleVariableStore()

    /** One reading per stream, so reading the wrong one shows up as a number. */
    private val perStream = mapOf(
        VolumeStream.MEDIA to VolumeReading.Level(current = 15, min = 0, max = 15),
        VolumeStream.RING to VolumeReading.Level(current = 0, min = 0, max = 7),
        VolumeStream.ALARM to VolumeReading.Level(current = 3, min = 0, max = 12),
        VolumeStream.NOTIFICATION to VolumeReading.Level(current = 2, min = 0, max = 8),
    )

    private fun action(
        levels: VolumeLevels,
        stream: VolumeStream = VolumeStream.MEDIA,
        name: String = "volume",
    ) = GetVolumeAction(levels = levels, stream = stream, name = name, ruleStore = ruleStore)

    // --- the value that is stored ------------------------------------------

    @Test
    fun `each stream is read on its own`() = runTest {
        val expected = mapOf(
            VolumeStream.MEDIA to "100",
            VolumeStream.RING to "0",
            VolumeStream.ALARM to "25",
            VolumeStream.NOTIFICATION to "25",
        )

        VolumeStream.entries.forEach { stream ->
            val levels = FakeVolumeLevels(perStream)

            val result = withContext(RunScope("rule-1")) {
                action(levels, stream = stream, name = stream.configValue).execute(event)
            }

            assertEquals(listOf(stream), levels.reads)
            assertEquals(
                "${stream.configValue} read the wrong stream",
                ActionResult.Success(
                    outputs = mapOf(GetVolumeAction.OUTPUT_PERCENT to expected.getValue(stream)),
                ),
                result,
            )
            assertEquals(expected.getValue(stream), ruleStore.get("rule-1", stream.configValue))
        }
    }

    @Test
    fun `the top of the range stores 100`() = runTest {
        val levels = FakeVolumeLevels(
            mapOf(VolumeStream.MEDIA to VolumeReading.Level(current = 15, min = 1, max = 15)),
        )

        withContext(RunScope("rule-1")) { action(levels).execute(event) }

        assertEquals("100", ruleStore.get("rule-1", "volume"))
    }

    @Test
    fun `the bottom of a range that starts above zero stores 0`() = runTest {
        val levels = FakeVolumeLevels(
            mapOf(VolumeStream.MEDIA to VolumeReading.Level(current = 1, min = 1, max = 15)),
        )

        withContext(RunScope("rule-1")) { action(levels).execute(event) }

        assertEquals("0", ruleStore.get("rule-1", "volume"))
    }

    /**
     * Bare digits, so `{{mine.volume}} > 50` is a number against a number. A
     * percent sign would compare as text and fail every numeric test.
     */
    @Test
    fun `the stored value carries no percent sign`() = runTest {
        val levels = FakeVolumeLevels(
            mapOf(VolumeStream.MEDIA to VolumeReading.Level(current = 7, min = 0, max = 15)),
        )

        withContext(RunScope("rule-1")) { action(levels).execute(event) }

        val stored = ruleStore.get("rule-1", "volume")
        assertEquals("47", stored)
        assertTrue("'$stored' should be digits only", stored!!.all { it.isDigit() })
    }

    /** The value belongs to one rule. That isolation is the scope's whole point. */
    @Test
    fun `the value goes to this rule and nowhere else`() = runTest {
        val levels = FakeVolumeLevels(perStream)

        withContext(RunScope("rule-1")) { action(levels).execute(event) }

        assertEquals("100", ruleStore.get("rule-1", "volume"))
        assertNull("another rule must not see it", ruleStore.get("rule-2", "volume"))
    }

    // --- what it does when it cannot read ----------------------------------

    @Test
    fun `a maximum of zero fails and stores nothing`() = runTest {
        val levels = FakeVolumeLevels(
            mapOf(VolumeStream.MEDIA to VolumeReading.Level(current = 0, min = 0, max = 0)),
        )

        val result = withContext(RunScope("rule-1")) { action(levels).execute(event) }

        assertTrue(result is ActionResult.Failure)
        assertTrue(
            "the reason should name the missing steps: $result",
            (result as ActionResult.Failure).reason.contains("no volume steps"),
        )
        assertNull("a failed read must leave no number behind", ruleStore.get("rule-1", "volume"))
    }

    @Test
    fun `a read that throws fails and stores nothing`() = runTest {
        val levels = FakeVolumeLevels(perStream).apply {
            throwsWith = IllegalStateException("the audio service is gone")
        }

        val result = withContext(RunScope("rule-1")) { action(levels).execute(event) }

        assertTrue(result is ActionResult.Failure)
        assertEquals(
            "the cause should reach the log",
            "the audio service is gone",
            (result as ActionResult.Failure).cause?.message,
        )
        assertNull(ruleStore.get("rule-1", "volume"))
    }

    /**
     * A stale value would look exactly like a fresh one, so an overwrite that
     * failed must not leave the old number in place under a new reading.
     */
    @Test
    fun `a failed read does not overwrite what was there`() = runTest {
        val store = InMemoryRuleVariableStore(mapOf("rule-1" to mapOf("volume" to "60")))
        val levels = FakeVolumeLevels(
            mapOf(VolumeStream.MEDIA to VolumeReading.Failed("This device has no audio service.")),
        )
        val action = GetVolumeAction(levels, VolumeStream.MEDIA, "volume", store)

        val result = withContext(RunScope("rule-1")) { action.execute(event) }

        assertEquals(
            ActionResult.Failure("This device has no audio service."),
            result,
        )
        assertEquals("60", store.get("rule-1", "volume"))
    }

    @Test
    fun `outside a rule run it says so instead of writing`() = runTest {
        val levels = FakeVolumeLevels(perStream)

        val result = action(levels).execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue(
            "the reason should say a rule has to be running: $result",
            (result as ActionResult.Failure).reason.contains("while a rule is running"),
        )
        assertTrue("nothing should have been read", levels.reads.isEmpty())
    }

    // --- the factory --------------------------------------------------------

    @Test
    fun `the factory builds the chosen stream`() = runTest {
        val levels = FakeVolumeLevels(perStream)
        val factory = GetVolumeActionFactory(levels, ruleStore)

        val action = factory.create(
            mapOf(VolumeStream.CONFIG_KEY to "alarm", GetVolumeAction.CONFIG_NAME to "before"),
        )
        withContext(RunScope("rule-1")) { action.execute(event) }

        assertEquals(listOf(VolumeStream.ALARM), levels.reads)
        assertEquals("25", ruleStore.get("rule-1", "before"))
    }

    @Test
    fun `the factory refuses a name no rule could read back`() {
        val factory = GetVolumeActionFactory(FakeVolumeLevels(), ruleStore)

        assertThrows(IllegalArgumentException::class.java) {
            factory.create(
                mapOf(VolumeStream.CONFIG_KEY to "media", GetVolumeAction.CONFIG_NAME to " "),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(
                mapOf(VolumeStream.CONFIG_KEY to "media", GetVolumeAction.CONFIG_NAME to "a b"),
            )
        }
    }

    @Test
    fun `the factory refuses a stream it does not offer`() {
        val factory = GetVolumeActionFactory(FakeVolumeLevels(), ruleStore)

        assertThrows(IllegalStateException::class.java) {
            factory.create(
                mapOf(VolumeStream.CONFIG_KEY to "voice", GetVolumeAction.CONFIG_NAME to "before"),
            )
        }
    }

    /**
     * The editor offers the typed name under the scope the action really writes.
     * A mismatch here is a name the picker gets wrong on every rule.
     */
    @Test
    fun `the write declaration names this rule's own scope`() {
        val factory = GetVolumeActionFactory(FakeVolumeLevels(), ruleStore)
        val write = factory.variableWrites.single()

        assertEquals(GetVolumeAction.CONFIG_NAME, write.nameKey)
        assertNull("this action offers no scope field", write.scopeKey)
        assertEquals(VariableScope.MINE, write.defaultNamespace)
    }

    /** The two volume actions must offer one set of streams, not two. */
    @Test
    fun `the stream choice is the same one set_volume offers`() {
        val get = GetVolumeActionFactory(FakeVolumeLevels(), ruleStore)
            .configFields
            .filterIsInstance<ConfigField.Choice>()
            .single { it.key == VolumeStream.CONFIG_KEY }

        assertEquals(
            VolumeStream.entries.map { it.configValue to it.displayName },
            get.options.map { it.value to it.label },
        )
    }
}
