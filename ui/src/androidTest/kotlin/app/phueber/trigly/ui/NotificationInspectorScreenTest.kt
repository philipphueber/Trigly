package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.NotificationButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        composeRule.onNodeWithText("Trigly cannot read notifications without access", substring = true)
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

    /**
     * The dialog host in `RuleEditorScreen` can hand this screen a viewport far
     * shorter than a phone's own screen — several capture blocks, each several
     * lines of monospaced fields. `assertIsDisplayed` on Back would pass either
     * way: Compose calls a node "displayed" as long as it isn't zero-sized or
     * fully clipped, which says nothing about whether it still sits above the
     * viewport's own bottom edge. Measuring its actual position is the only way
     * to catch a bottom bar that has been pushed past it — which is exactly how
     * this shipped.
     *
     * This exercises the screen's own `Column`/`weight`/`LazyColumn` layout under
     * a bounded, smaller-than-content viewport. It does not exercise the dialog
     * host's window/inset handling in `RuleEditorScreen.kt` — that would need the
     * real `Dialog` measured against a real device's system bars, which is
     * outside what this file, or a JVM/emulator-only run, can see.
     */
    @Test
    fun the_back_control_stays_inside_the_viewport_even_with_a_long_list() {
        val viewportHeight = 480.dp
        composeRule.setContent {
            Box(Modifier.testTag("viewport").size(320.dp, viewportHeight)) {
                NotificationInspectorScreen(
                    notifications = (1..6).map {
                        notification(
                            key = "k$it",
                            text = "A fairly long message body, long enough to wrap " +
                                "across more than one line on a narrow screen",
                        )
                    },
                    listenerConnected = true,
                    onRefresh = {},
                    onBack = {},
                    describeApp = { it },
                )
            }
        }

        val viewportBottom = composeRule.onNodeWithTag("viewport").getBoundsInRoot().bottom
        val backBottom = composeRule.onNodeWithText("BACK").getBoundsInRoot().bottom

        assertTrue(
            "Back's bottom edge ($backBottom) sits past the viewport's own bottom " +
                "edge ($viewportBottom) — it has been pushed off screen.",
            backBottom <= viewportBottom,
        )
    }
}
