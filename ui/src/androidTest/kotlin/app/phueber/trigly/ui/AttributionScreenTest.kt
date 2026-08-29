package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [AttributionScreen] the way [SettingsScreenTest] drives
 * [SettingsScreen]: plain values and a stub callback, no ViewModel and no
 * `Context` behind it.
 *
 * Fed fake entries and a fake version, not [shippedDependencies] and a real
 * `versionName`, so a dependency bump or a version bump cannot break this
 * test.
 */
@RunWith(AndroidJUnit4::class)
class AttributionScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private var backTaps = 0

    private val fakeDependencies = listOf(
        Attribution("some-library", "Apache License 2.0"),
        Attribution("another-library", "Apache License 2.0"),
    )

    private val fakeLicenseText = "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"

    @Composable
    private fun Screen() {
        AttributionScreen(
            appVersion = "9.9.9-test",
            dependencies = fakeDependencies,
            licenseText = fakeLicenseText,
            onBack = { backTaps++ },
        )
    }

    @Test
    fun every_dependency_name_renders() {
        composeRule.setContent { Screen() }

        fakeDependencies.forEach { dependency ->
            composeRule.onNodeWithText(dependency.name).assertIsDisplayed()
        }
    }

    @Test
    fun the_version_renders() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText("9.9.9-test", substring = true).assertIsDisplayed()
    }

    /**
     * A short stable substring, not the whole text: the real licence file is
     * long, and this only has to prove the right resource reached the screen.
     */
    @Test
    fun the_license_text_renders() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText(fakeLicenseText, substring = true).assertIsDisplayed()
    }

    @Test
    fun back_fires_once() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backTaps)
    }
}
