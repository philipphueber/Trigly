package app.phueber.trigly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.NotificationButton
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diagnostic screen, driven with fabricated notifications.
 *
 * Deliberately not against whatever the emulator is showing: the notifications on
 * a test device vary by image and by whatever ran before, and asserting on them
 * would test the image. What matters here is that the screen reports the fields
 * *as the matchers see them* and that its two empty states are distinguishable.
 */
@RunWith(AndroidJUnit4::class)
class NotificationInspectorScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private var refreshes = 0
    private var backs = 0

    private fun notification(
        key: String = "k1",
        title: String? = "A message",
        text: String? = "Hello there",
        ongoing: Boolean = false,
        buttons: List<NotificationButton> = emptyList(),
    ) = ActiveNotification(
        key = key,
        packageName = "com.example.chat",
        title = title,
        text = text,
        postedAtMillis = 1,
        buttons = buttons,
        ongoing = ongoing,
    )

    private fun show(
        notifications: List<ActiveNotification>,
        connected: Boolean = true,
    ) {
        composeRule.setContent {
            NotificationInspectorScreen(
                notifications = notifications,
                listenerConnected = connected,
                onRefresh = { refreshes++ },
                onBack = { backs++ },
                describeApp = { if (it == "com.example.chat") "Chatty" else it },
            )
        }
    }

    @Test
    fun it_names_the_app_and_shows_the_package_a_rule_would_store() {
        show(listOf(notification()))

        composeRule.onNodeWithText("CHATTY").assertIsDisplayed()
        composeRule.onNodeWithText("com.example.chat").assertIsDisplayed()
    }

    @Test
    fun it_shows_title_and_text_separately_because_the_platform_does() {
        show(listOf(notification()))

        composeRule.onNodeWithText("\"A message\"").assertIsDisplayed()
        composeRule.onNodeWithText("\"Hello there\"").assertIsDisplayed()
    }

    /**
     * The reason the screen exists. A text filter matches the two joined, and
     * nobody would guess that from the notification on screen.
     */
    @Test
    fun it_shows_the_joined_string_text_filters_actually_match() {
        show(listOf(notification()))

        composeRule.onNodeWithText("\"A message Hello there\"").assertIsDisplayed()
    }

    /**
     * And the awkward case that makes an anchored regex behave oddly: a missing
     * title still contributes its separating space, so `^Hello` does not match.
     */
    @Test
    fun a_missing_title_still_shows_the_space_it_contributes() {
        show(listOf(notification(title = null)))

        composeRule.onNodeWithText("\" Hello there\"").assertIsDisplayed()
    }

    @Test
    fun it_reports_the_ongoing_flag_that_two_triggers_turn_on() {
        show(listOf(notification(ongoing = true)))

        composeRule.onNodeWithText("ONGOING").assertIsDisplayed()
        composeRule.onNodeWithText("\"yes\"").assertIsDisplayed()
    }

    @Test
    fun it_lists_buttons_with_what_a_rule_will_match_them_by() {
        show(
            listOf(
                notification(
                    buttons = listOf(
                        NotificationButton(0, "Reply", semanticAction = 1, takesText = true),
                        NotificationButton(1, "Archive", semanticAction = 5),
                        NotificationButton(2, "Snooze"),
                    )
                )
            )
        )

        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        // A reply box cannot be pressed by a rule, and the screen says so here
        // for the same reason the picker does.
        composeRule.onNodeWithText("meaning 1 · reply box").assertIsDisplayed()
        composeRule.onNodeWithText("meaning 5").assertIsDisplayed()
        // No declared meaning means label matching is all a rule has.
        composeRule.onNodeWithText("no meaning").assertIsDisplayed()
    }

    @Test
    fun a_notification_with_no_buttons_says_so_rather_than_showing_nothing() {
        show(listOf(notification()))

        composeRule.onNodeWithText("\"none\"").assertIsDisplayed()
    }

    // --- the two empty states, which are different problems ------------------

    @Test
    fun no_access_is_reported_as_a_permission_problem() {
        show(emptyList(), connected = false)

        composeRule.onNodeWithText("Trigly cannot read notifications yet", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun access_but_nothing_posted_explains_that_capturing_is_live() {
        show(emptyList(), connected = true)

        composeRule.onNodeWithText("no history", substring = true).assertIsDisplayed()
    }

    @Test
    fun refresh_and_back_are_reported() {
        show(listOf(notification()))

        composeRule.onNodeWithText("REFRESH").performClick()
        composeRule.onNodeWithText("BACK").performClick()

        assertEquals(1, refreshes)
        assertEquals(1, backs)
    }
}
