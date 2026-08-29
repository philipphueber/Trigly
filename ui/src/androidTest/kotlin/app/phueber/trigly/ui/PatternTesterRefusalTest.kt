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
 * a search this test provokes keeps running on its own abandoned thread, in
 * the background, for an unmeasured time after the dialog has already
 * reported the refusal, in the same instrumentation process
 * `PatternTesterTest`'s own tests run in.
 *
 * **What used to be here.** This file first shipped with a comment warning
 * that `AndroidJUnitRunner` might run this class before `PatternTesterTest`,
 * alphabetically, and leave its search "occupying" `RegexGuard` for
 * `PatternTesterTest`'s own patterns. That framed *ordering* as the hazard.
 * It was not: the real fault, which the connected gate caught in `:core`'s
 * `RegexOnDeviceTest` rather than here, was that `RegexGuard` used to clear
 * its one busy flag only when an abandoned search finished on its own, which
 * a search that never finishes never does, so once poisoned it refused every
 * pattern, forever, regardless of order. `RegexBudget.kt` documents the fix:
 * a search that times out is abandoned and replaced, and only its own
 * identity is refused from then on, not every identity.
 *
 * **What is left.** `RegexGuard` still remembers every pattern that has ever
 * timed out, and still caps how many abandoned threads may exist at once, for
 * the life of the process, not per test class: see `MAX_ABANDONED_THREADS`
 * and `MAX_KNOWN_BAD_PATTERNS` in `core/RegexBudget.kt`. If some other class
 * in this same instrumentation process searched this exact pattern with the
 * same case sensitivity, it would inherit `RegexRefusal.KNOWN_BAD` instead of
 * timing out itself; if several classes each abandoned a distinct bad
 * pattern, a later one could see `RegexRefusal.EXHAUSTED` even for an honest
 * search. **What either failure would look like:** one of `PatternTesterTest`'s
 * dialogs showing "NO MATCH", or a different `REFUSED` label than the pattern
 * it is testing should ever produce on its own, rather than the sustained
 * hang the old bug caused. `PatternTesterTest`'s own patterns (`hello`,
 * `wor`, `[0-9]+`, `[a-z]+`, `abc`) do not collide with this file's
 * `.*.*.*b`, so neither risk is live between these two classes today; keeping
 * it that way is a convention, not something `connectedDebugAndroidTest` can
 * enforce the way `core/build.gradle.kts`'s `forkEvery = 1` enforces it for
 * the JVM suite's own refusal classes.
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
