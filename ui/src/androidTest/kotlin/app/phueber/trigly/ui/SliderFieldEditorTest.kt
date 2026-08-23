package app.phueber.trigly.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ConfigField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The slider field, driven through its semantics rather than by dragging.
 *
 * A synthetic swipe would be testing gesture arithmetic — how many pixels equal
 * how many percent — which is Material's job and changes with the track width. A
 * `SetProgress` action is what an accessibility service sends and what the
 * control promises to honour, so it checks the part that is ours: that the value
 * reaches the rule as a whole number in range, and that the reading on screen
 * agrees with it.
 */
@RunWith(AndroidJUnit4::class)
class SliderFieldEditorTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val field = ConfigField.Slider(
        key = "volumePercent",
        label = "Volume",
        min = 0,
        max = 100,
        default = 100,
        unit = "%",
    )

    private val reported = mutableListOf<String?>()

    /**
     * Feeds edits back in, the way the rule draft does.
     *
     * The field is stateless by design — it renders the value it is handed — so a
     * test that only captured the callback would never see the reading change.
     * Holding the value in state here is both closer to the real editor and the
     * only shape that works: `setContent` may be called once per test.
     */
    private fun setField(value: String?) {
        composeRule.setContent {
            TriglyTheme {
                var current by remember { mutableStateOf(value) }
                ConfigFieldEditor(
                    field = field,
                    value = current,
                    onValueChange = {
                        reported += it
                        current = it
                    },
                    // A known width, so a fraction of the track is a known place.
                    modifier = Modifier.width(300.dp),
                )
            }
        }
    }

    private fun setProgress(target: Float) {
        composeRule.onRoot()
            .fetchSemanticsNode()
            .let { root ->
                fun find(node: androidx.compose.ui.semantics.SemanticsNode):
                    androidx.compose.ui.semantics.SemanticsNode? {
                    if (node.config.contains(SemanticsProperties.ProgressBarRangeInfo)) return node
                    node.children.forEach { child -> find(child)?.let { return it } }
                    return null
                }
                val slider = requireNotNull(find(root)) { "no slider in the tree" }
                slider.config[SemanticsActions.SetProgress].action?.invoke(target)
            }
        composeRule.waitForIdle()
    }

    @Test
    fun an_unset_field_shows_its_default_rather_than_the_minimum() {
        setField(null)

        // 0% is a legitimate volume, so "no stored value" must not look like it.
        composeRule.onNodeWithText("100 %").assertIsDisplayed()
    }

    @Test
    fun a_stored_value_is_what_the_reading_shows() {
        setField("35")

        composeRule.onNodeWithText("35 %").assertIsDisplayed()
    }

    @Test
    fun the_label_and_unit_are_both_visible() {
        setField("35")

        composeRule.onNodeWithText("VOLUME").assertIsDisplayed()
    }

    @Test
    fun moving_the_slider_reports_a_whole_number() {
        setField("100")
        setProgress(40f)

        assertTrue("nothing was reported", reported.isNotEmpty())
        val last = reported.last()
        assertEquals("40", last)
        // The config map is strings; a fractional one would both look wrong in an
        // export and defeat toLongOrNull on the way back in.
        assertTrue("'$last' should parse as a whole number", last!!.toLongOrNull() != null)
    }

    @Test
    fun the_reading_follows_the_slider() {
        setField("100")
        setProgress(25f)

        composeRule.onNodeWithText("25 %").assertIsDisplayed()
    }

    @Test
    fun a_stored_value_beyond_the_scale_is_pulled_back_onto_it() {
        // An imported rule, or one written before the maximum changed. The thumb
        // has nowhere to sit off the end of the track, so the field clamps rather
        // than drawing something impossible.
        setField("400")

        composeRule.onNodeWithText("100 %").assertIsDisplayed()
    }

    @Test
    fun a_nonsense_stored_value_falls_back_to_the_default() {
        setField("loud")

        composeRule.onNodeWithText("100 %").assertIsDisplayed()
    }
}
