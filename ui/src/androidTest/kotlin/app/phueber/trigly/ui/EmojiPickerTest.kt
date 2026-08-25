package app.phueber.trigly.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ConfigField.Emoji` is reachable — [app.phueber.trigly.triggers.ShortcutTrigger]
 * declares it for the launcher-shortcut icon — so the picker's cells are real
 * touch targets on a real screen, not a hypothetical.
 *
 * The cell's size is whatever a dialog's width divided into columns leaves it,
 * which is arithmetic no one can check by reading: with the six fixed columns
 * this grid used to have, a phone-width dialog left each cell 33dp square,
 * two thirds of the 48dp minimum Android expects of anything tappable. So this
 * measures the rendered cell on a device instead of trusting the arithmetic, and
 * it is the reason the grid is `Adaptive` rather than `Fixed` — see the comment
 * there.
 */
@RunWith(AndroidJUnit4::class)
class EmojiPickerTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    @Test
    fun a_cell_clears_the_48dp_touch_target_minimum() {
        composeRule.setContent {
            EmojiPickerDialog(title = "Icon", clearLabel = null, onPick = {}, onDismiss = {})
        }

        // The first curated entry. Any would do — the grid renders every cell
        // the same size — but this one is also the value ConfigSchemaContractTest
        // exercises for an Emoji field, so a rename here would surface there too.
        val cell = composeRule.onNodeWithText("🔔")
        cell.assertWidthIsAtLeast(48.dp)
        cell.assertHeightIsAtLeast(48.dp)
    }
}
