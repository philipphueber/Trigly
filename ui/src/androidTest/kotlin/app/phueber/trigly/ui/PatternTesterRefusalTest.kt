package app.phueber.trigly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TextMatchMode
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one verdict [PatternTesterTest] cannot test alongside everything else:
 * a pattern refused for taking too long.
 *
 * **Reachable on a device again.** This was `@Ignore`d while the bound was a
 * count of characters read, which Android's `Matcher` never produced because
 * it converts its input to a `String` before `TextFilter` ever sees it: see
 * `docs/todo.md` T24. `RegexBudget.kt`'s `RegexGuard` bounds the wall clock
 * instead, on a shared thread, which is real on this platform. `.*.*.*b` over
 * 1800 characters is measured, in that file's KDoc, at more than ten seconds
 * to finish on this exact emulator; the bound refuses it at five. [awaitText]'s
 * timeout has to clear that five seconds with room for Compose to recompose
 * afterward, hence the longer one passed here instead of the default.
 *
 * A separate class from `PatternTesterTest`, with nothing else in it, because
 * `RegexGuard` is one thread shared by the whole process: the search this
 * test provokes keeps running in the background for an unmeasured time after
 * the dialog has already reported the refusal, in the same instrumentation
 * process `PatternTesterTest`'s own tests run in, and those tests must not be
 * the ones that pay for it. Do not add another test to this file for the same
 * reason.
 *
 * **A gap this file cannot close by itself.** The JVM suite gets a fresh
 * process per test class from `core/build.gradle.kts`'s `forkEvery = 1` for
 * this exact hazard. `connectedDebugAndroidTest` has nothing equivalent: one
 * instrumentation run is one device process for every class in this module,
 * and `RegexGuard` is a singleton for as long as that process lives. If
 * `AndroidJUnitRunner` runs this module's classes in the alphabetical order
 * their names suggest, `PatternTesterRefusalTest` sorts before
 * `PatternTesterTest` and would run first, leaving its still-running search
 * occupying `RegexGuard` right when `PatternTesterTest` asks it to run an
 * ordinary pattern. **What that failure looks like:** one of
 * `PatternTesterTest`'s dialogs would show "NO MATCH" or never leave
 * "CHECKING" for a pattern that has never once failed on its own, in the same
 * run, right after this class's test. That is not flakiness; it is this same
 * ordering hazard on a platform with no `forkEvery` to answer it with. This
 * was measured, not guessed: removing `forkEvery = 1` reproduced exactly this
 * shape of failure on the JVM, in `:core`'s `ExpressionTest` and
 * `MatchRangesTest`, once the equivalent ordering put a refusal class first.
 * A real fix would have to live in `RegexGuard` itself; it is left as an open
 * question rather than something this test file should paper over.
 */
@RunWith(AndroidJUnit4::class)
class PatternTesterRefusalTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val field = ConfigField.TextPattern(key = "text", label = "Message contains")

    private fun open(pattern: String?, mode: TextMatchMode = TextMatchMode.REGEX) {
        composeRule.setContent {
            ConfigFieldEditor(
                field = field,
                value = pattern,
                onValueChange = {},
                companions = mapOf(field.modeKey to mode.configValue),
            )
        }
        composeRule.onNodeWithText("TEST").performClick()
    }

    /** See the identical helper in `PatternTesterTest` for why this waits rather than asserts. */
    private fun awaitText(text: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The fourth state, alongside an empty pattern, a pattern that will not
     * compile and a zero-width match. Refused, not "no match": those two look
     * the same from the outside, and only this message tells a person which
     * one they got.
     */
    @Test
    fun a_pattern_that_does_too_much_work_says_so_instead_of_pretending_to_miss() {
        open(".*.*.*b")

        composeRule.onNodeWithText("SAMPLE TEXT").performTextReplacement("a".repeat(1800))

        awaitText("REFUSED · TOOK TOO LONG ON THIS SAMPLE", timeoutMillis = 10_000)
        composeRule.onNodeWithText("REFUSED · TOOK TOO LONG ON THIS SAMPLE").assertIsDisplayed()
    }
}
