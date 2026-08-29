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
 * Fed fake entries and a fake version, not [shippedDependencies] grouped by
 * [groupIntoProjects] and a real `versionName`, so a dependency bump or a
 * version bump cannot break this test.
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
            appVersion = "9.9.9-test",
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

    @Test
    fun the_version_renders() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithText("9.9.9-test", substring = true).assertIsDisplayed()
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
