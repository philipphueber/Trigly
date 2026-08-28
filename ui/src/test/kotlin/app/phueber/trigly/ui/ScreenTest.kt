package app.phueber.trigly.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Navigation, as the two facts that can be checked without a device: what a back
 * press means, and that a destination survives being written to a Bundle and read
 * back.
 *
 * Both used to be implicit — the destination lived in a plain `remember`, and back
 * on the rule list was whatever the framework did with no callback registered.
 * Neither was stated anywhere, which is why neither could be wrong on purpose.
 */
class ScreenTest {

    /** `canBeSaved` is only consulted for real Bundle types; strings always pass. */
    private val scope = SaverScope { true }

    @Test
    fun `back from the rule list leaves the app`() {
        assertNull(
            "the list is the bottom of the stack; back there must close the app",
            backTarget(Screen.RuleList),
        )
    }

    @Test
    fun `back from the editor returns to the list, never to another rule`() {
        assertEquals(Screen.RuleList, backTarget(Screen.RuleEditor("rule-1")))
        assertEquals(Screen.RuleList, backTarget(Screen.RuleEditor(null)))
    }

    @Test
    fun `back from saved values or settings returns to the list`() {
        assertEquals(Screen.RuleList, backTarget(Screen.SavedValues))
        assertEquals(Screen.RuleList, backTarget(Screen.Settings))
    }

    @Test
    fun `the destination survives a configuration change`() {
        listOf(
            Screen.RuleList,
            Screen.RuleEditor("rule-1"),
            Screen.RuleEditor(null),
            Screen.SavedValues,
            Screen.Settings,
        ).forEach { screen ->
            val saved = with(ScreenSaver) { scope.save(screen) }
            assertEquals(screen, ScreenSaver.restore(requireNotNull(saved)))
        }
    }

    /**
     * The empty string is how "no id yet" is stored, so it has to come back as
     * null rather than as a rule whose id happens to be blank — that would send
     * the editor looking in the repository for a rule that cannot be there.
     */
    @Test
    fun `an unsaved rule restores with a null id, not a blank one`() {
        val saved = with(ScreenSaver) { scope.save(Screen.RuleEditor(null)) }
        val restored = ScreenSaver.restore(requireNotNull(saved)) as Screen.RuleEditor
        assertNull(restored.ruleId)
    }
}
