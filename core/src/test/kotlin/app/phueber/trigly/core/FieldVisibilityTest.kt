package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which fields the editor draws, given what has been filled in.
 *
 * The case that matters is the *untouched* rule: the gating key is absent from
 * the config while the editor is plainly showing its default. Reading only the
 * stored value would hide `play_alert`'s "keep sounding for" on every new alert,
 * because "repeat" had not been written down yet — a field missing from a form
 * nobody had touched, which is the sort of bug that gets reported as "the
 * duration is gone".
 */
class FieldVisibilityTest {

    private val playback = ConfigField.Choice(
        key = "playback",
        label = "Play it",
        options = listOf(
            ConfigField.Option("once", "play it once"),
            ConfigField.Option("repeat", "repeat for a set time"),
        ),
        default = "repeat",
    )

    private val duration = ConfigField.Duration(
        key = "durationMillis",
        label = "Keep sounding for",
        defaultMillis = 3_000,
        shownWhen = FieldCondition(key = "playback", value = "repeat"),
    )

    private val fields = listOf(playback, duration)

    private fun keysWith(config: Map<String, String>) =
        fields.shownWith(config).map { it.key }

    @Test
    fun `an unconditional field is always shown`() {
        assertEquals(listOf("playback"), listOf(playback).shownWith(emptyMap()).map { it.key })
    }

    @Test
    fun `a matching stored value shows the field`() {
        assertEquals(listOf("playback", "durationMillis"), keysWith(mapOf("playback" to "repeat")))
    }

    @Test
    fun `a non-matching stored value hides it`() {
        assertEquals(listOf("playback"), keysWith(mapOf("playback" to "once")))
    }

    @Test
    fun `an untouched rule falls back to the sibling's default`() {
        // Nothing stored yet, and the editor is showing "repeat" — so the
        // duration must be there, not hidden until someone touches the choice.
        assertEquals(listOf("playback", "durationMillis"), keysWith(emptyMap()))
    }

    @Test
    fun `a sibling with no default and nothing stored hides the field`() {
        // Honest rather than convenient: there is no value, so the condition is
        // not met. A text field gating another is unusual, and guessing "shown"
        // would put a field on screen for a state nobody has chosen.
        val text = ConfigField.Text(key = "mode", label = "Mode")
        val gated = ConfigField.Number(
            key = "amount",
            label = "Amount",
            shownWhen = FieldCondition(key = "mode", value = "manual"),
        )

        assertEquals(listOf("mode"), listOf(text, gated).shownWith(emptyMap()).map { it.key })
    }

    @Test
    fun `a condition can accept several values`() {
        val gated = ConfigField.Number(
            key = "amount",
            label = "Amount",
            shownWhen = FieldCondition(key = "playback", isAnyOf = setOf("once", "repeat")),
        )

        assertEquals(
            listOf("playback", "amount"),
            listOf(playback, gated).shownWith(mapOf("playback" to "once")).map { it.key },
        )
    }

    @Test
    fun `a condition naming a key no sibling declares leaves the field visible`() {
        // A typo must look like a condition that does nothing, not like a field
        // that vanished — the second is far harder to diagnose from a screenshot.
        val gated = ConfigField.Number(
            key = "amount",
            label = "Amount",
            shownWhen = FieldCondition(key = "playbck", value = "repeat"),
        )

        assertEquals(
            listOf("playback", "amount"),
            listOf(playback, gated).shownWith(emptyMap()).map { it.key },
        )
    }

    @Test
    fun `order is preserved, so hiding one field does not reshuffle the form`() {
        val first = ConfigField.Text(key = "a", label = "A")
        val hidden = ConfigField.Text(
            key = "b",
            label = "B",
            shownWhen = FieldCondition(key = "playback", value = "once"),
        )
        val last = ConfigField.Text(key = "c", label = "C")

        assertEquals(
            listOf("a", "playback", "c"),
            listOf(first, hidden, playback, last)
                .shownWith(mapOf("playback" to "repeat"))
                .map { it.key },
        )
    }
}
