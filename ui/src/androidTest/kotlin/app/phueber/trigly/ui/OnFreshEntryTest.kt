package app.phueber.trigly.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The distinction the "a new rule starts empty" fix rests on: [OnFreshEntry]
 * must fire on a genuine entry — every time the screen is opened afresh — and
 * must stay quiet across a configuration change, or the draft it is meant to
 * preserve on rotation would be wiped instead.
 *
 * Testing this here rather than through the whole activity is the point of having
 * extracted it: the behaviour that was fragile is now a single composable with
 * three checkable cases.
 */
@RunWith(AndroidJUnit4::class)
class OnFreshEntryTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    @Test
    fun it_fires_once_when_first_entered() {
        val fires = AtomicInteger(0)

        composeRule.setContent {
            OnFreshEntry { fires.incrementAndGet() }
        }
        composeRule.waitForIdle()

        assertEquals(1, fires.get())
    }

    @Test
    fun it_fires_again_on_a_genuine_re_entry() {
        val fires = AtomicInteger(0)
        var present by mutableStateOf(true)

        composeRule.setContent {
            if (present) OnFreshEntry { fires.incrementAndGet() }
        }
        composeRule.waitForIdle()
        assertEquals(1, fires.get())

        // Leaving the composition discards the saved slot, so coming back is a
        // fresh entry — exactly what navigating list → editor → list → editor is.
        composeRule.runOnUiThread { present = false }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { present = true }
        composeRule.waitForIdle()

        assertEquals("re-entering the screen must fire again", 2, fires.get())
    }

    @Test
    fun it_stays_quiet_across_a_configuration_change() {
        val fires = AtomicInteger(0)
        val restoration = StateRestorationTester(composeRule)

        restoration.setContent {
            OnFreshEntry { fires.incrementAndGet() }
        }
        composeRule.waitForIdle()
        assertEquals(1, fires.get())

        // A rotation restores the saved slot rather than starting a new one, so
        // the action must not run again — this is what keeps a draft across the
        // turn of the phone.
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals("a configuration change must not re-fire", 1, fires.get())
    }
}
