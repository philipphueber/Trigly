package app.phueber.trigly.ui

import app.phueber.trigly.actions.DeclaredKeptButton
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [keptButtons], which merges the two halves of "what can this action press".
 *
 * The merge is worth its own test because the two halves mean different things
 * and the difference is what the person reads. What is kept right now proves
 * pressing works this minute; what a rule declares says it will work once that
 * rule has run. A merge that lost the distinction, or listed one name twice,
 * would turn a useful list into a guess.
 */
class KeptButtonSuggestionsTest {

    @Test
    fun `a name kept right now is offered as kept now`() {
        val offered = keptButtons(keptNow = listOf("bedtime_off"), declared = emptyList())

        assertEquals(listOf(KeptButton("bedtime_off", "Kept now")), offered)
    }

    @Test
    fun `a name a rule declares is offered with that rule`() {
        val offered = keptButtons(
            keptNow = emptyList(),
            declared = listOf(DeclaredKeptButton("bedtime_off", "Evening")),
        )

        assertEquals(listOf(KeptButton("bedtime_off", "Kept by the rule Evening")), offered)
    }

    @Test
    fun `a name in both halves is listed once, as the live one`() {
        val offered = keptButtons(
            keptNow = listOf("bedtime_off"),
            declared = listOf(DeclaredKeptButton("bedtime_off", "Evening")),
        )

        assertEquals(listOf(KeptButton("bedtime_off", "Kept now")), offered)
    }

    @Test
    fun `live names come before declared ones`() {
        val offered = keptButtons(
            keptNow = listOf("wifi_off"),
            declared = listOf(DeclaredKeptButton("bedtime_off", "Evening")),
        )

        assertEquals(listOf("wifi_off", "bedtime_off"), offered.map { it.name })
    }

    @Test
    fun `nothing kept and nothing declared offers nothing`() {
        assertEquals(emptyList<KeptButton>(), keptButtons(emptyList(), emptyList()))
    }
}
