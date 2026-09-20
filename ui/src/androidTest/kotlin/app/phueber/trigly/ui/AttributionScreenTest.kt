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
 * [SettingsScreen]: plain values and stub callbacks, no ViewModel and no
 * `Context` behind it.
 *
 * Fed fake entries, not [shippedDependencies] grouped by [groupIntoProjects],
 * so a dependency bump cannot break this test.
 *
 * The version and the "Check for updates" button are not asserted here any
 * more, because they are not on this screen any more. `SettingsScreenTest`
 * carries those assertions now, beside the screen that shows them. See
 * `AppVersionCard` in `SettingsScreen.kt`.
 */
@RunWith(AndroidJUnit4::class)
class AttributionScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private var backTaps = 0
    private var openedUrl: String? = null

    private val fakeProjects = listOf(
        AttributionProject("Some Project", "Apache License 2.0", artifactCount = 3, url = "https://example.com/some-project"),
        AttributionProject("Another Project", "Apache License 2.0", artifactCount = 1, url = "https://example.com/another-project"),
    )

    private val fakeLicenseUrl = "https://example.com/license"
    private val fakeRepositoryUrl = "https://example.com/repository"

    @Composable
    private fun Screen() {
        AttributionScreen(
            projects = fakeProjects,
            licenseUrl = fakeLicenseUrl,
            repositoryUrl = fakeRepositoryUrl,
            onOpenUrl = { openedUrl = it },
            onBack = { backTaps++ },
        )
    }

    @Test
    fun every_project_name_renders() {
        composeRule.setContent { Screen() }

        fakeProjects.forEach { project ->
            composeRule.onNodeWithText(project.name).assertIsDisplayed()
        }
    }

    @Test
    fun every_project_artifact_count_renders() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText("3 artifacts", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 artifact", substring = true).assertIsDisplayed()
    }

    /**
     * The version and the update check are gone from this screen; the licence
     * line the card still carries is what proves the card itself survived
     * losing them.
     */
    @Test
    fun the_license_line_still_renders() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText("This app uses the Apache License 2.0.").assertIsDisplayed()
    }

    /**
     * The other half of the move, asserted where it can actually fail: an
     * edit that put the version back here would leave it on two screens at
     * once, which is the arrangement the move exists to end.
     */
    @Test
    fun the_version_and_its_update_check_are_not_on_this_screen() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText("Version", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("CHECK FOR UPDATES").assertDoesNotExist()
    }

    /**
     * Tapping a project's row is what opens that project's own page, not
     * something else on the row: this is the whole reason `AttributionProject`
     * carries a `url` at all, see `Attribution.kt`.
     */
    @Test
    fun a_row_reports_the_url_it_would_open() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText(fakeProjects[0].name).performClick()

        assertEquals(fakeProjects[0].url, openedUrl)
    }

    @Test
    fun a_different_row_reports_its_own_url() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText(fakeProjects[1].name).performClick()

        assertEquals(fakeProjects[1].url, openedUrl)
    }

    /**
     * The licence is linked to, not bundled as text any more: see
     * `AttributionScreen`'s own KDoc for why, and `Attribution.kt` for
     * `Attribution.scmUrl` and `AttributionProject.url`, the fields this
     * design change added.
     */
    @Test
    fun the_license_link_is_present_and_opens_the_license_url() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText("License text").performClick()

        assertEquals(fakeLicenseUrl, openedUrl)
    }

    /** Trigly is Apache 2.0 itself; this is its own row, see README.md. */
    @Test
    fun the_repository_link_is_present_and_opens_the_repository_url() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText("Trigly on GitHub").performClick()

        assertEquals(fakeRepositoryUrl, openedUrl)
    }

    @Test
    fun back_fires_once() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backTaps)
    }
}
