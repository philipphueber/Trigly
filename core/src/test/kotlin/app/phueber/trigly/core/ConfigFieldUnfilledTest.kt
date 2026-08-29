package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [List<ConfigField>.unfilled]: the line between a required field nobody has
 * typed anything into yet (absent) and everything else a factory's `create()`
 * can still refuse (wrong). See its own kdoc for why this distinction exists.
 */
class ConfigFieldUnfilledTest {

    private val required = ConfigField.Text(key = "name", label = "Name", required = true)
    private val optional = ConfigField.Text(key = "note", label = "Note", required = false)

    @Test
    fun `a required field with no value at all is unfilled`() {
        assertEquals(listOf(required), listOf(required).unfilled(emptyMap()))
    }

    @Test
    fun `a required field set to a blank string is still unfilled`() {
        assertEquals(listOf(required), listOf(required).unfilled(mapOf("name" to "   ")))
    }

    @Test
    fun `a required field with a real value is filled in`() {
        assertEquals(emptyList<ConfigField>(), listOf(required).unfilled(mapOf("name" to "Alex")))
    }

    @Test
    fun `an optional field with no value is not unfilled`() {
        assertEquals(emptyList<ConfigField>(), listOf(optional).unfilled(emptyMap()))
    }

    @Test
    fun `a required field a sibling currently hides is not unfilled`() {
        val gated = ConfigField.Text(
            key = "detail",
            label = "Detail",
            required = true,
            shownWhen = FieldCondition(key = "mode", value = "custom"),
        )
        val mode = ConfigField.Choice(
            key = "mode",
            label = "Mode",
            options = listOf(ConfigField.Option("simple", "Simple"), ConfigField.Option("custom", "Custom")),
            default = "simple",
        )

        // Nobody has touched "mode", so its effective value is the default,
        // "simple", and "detail" is not currently shown at all.
        assertEquals(emptyList<ConfigField>(), listOf(mode, gated).unfilled(emptyMap()))
    }

    @Test
    fun `a required field a sibling currently shows is unfilled when empty`() {
        val gated = ConfigField.Text(
            key = "detail",
            label = "Detail",
            required = true,
            shownWhen = FieldCondition(key = "mode", value = "custom"),
        )
        val mode = ConfigField.Choice(
            key = "mode",
            label = "Mode",
            options = listOf(ConfigField.Option("simple", "Simple"), ConfigField.Option("custom", "Custom")),
            default = "simple",
        )

        assertEquals(
            listOf(gated),
            listOf(mode, gated).unfilled(mapOf("mode" to "custom")),
        )
    }
}
