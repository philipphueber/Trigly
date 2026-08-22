package app.phueber.trigly.triggers

import android.app.NotificationManager
import android.view.accessibility.AccessibilityEvent
import app.phueber.trigly.triggers.accessibility.UiEvent
import app.phueber.trigly.triggers.accessibility.matchesUiEvent
import app.phueber.trigly.triggers.notification.PostedNotification
import app.phueber.trigly.triggers.notification.isDndOn
import app.phueber.trigly.triggers.notification.matchesNotification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMatcherTest {

    private val notification = PostedNotification(
        key = "k",
        packageName = "com.example.chat",
        title = "Alice",
        text = "Are you coming?",
        postedAtMillis = 1_000,
        ongoing = false,
    )

    @Test
    fun `no filters matches anything that is not ongoing`() {
        assertTrue(matchesNotification(notification, null, null, includeOngoing = false))
    }

    @Test
    fun `ongoing notifications are excluded unless asked for`() {
        val ongoing = notification.copy(ongoing = true)

        assertFalse(matchesNotification(ongoing, null, null, includeOngoing = false))
        assertTrue(matchesNotification(ongoing, null, null, includeOngoing = true))
    }

    @Test
    fun `package filter is exact, not a prefix`() {
        assertTrue(matchesNotification(notification, "com.example.chat", null, false))
        assertFalse(matchesNotification(notification, "com.example", null, false))
    }

    @Test
    fun `text matching spans title and body and ignores case`() {
        assertTrue(matchesNotification(notification, null, "alice", false))
        assertTrue(matchesNotification(notification, null, "COMING", false))
        assertFalse(matchesNotification(notification, null, "goodbye", false))
    }

    @Test
    fun `a missing title or body does not crash the match`() {
        val bare = notification.copy(title = null, text = null)

        assertFalse(matchesNotification(bare, null, "anything", false))
        assertTrue(matchesNotification(bare, null, null, false))
    }

    @Test
    fun `every do not disturb filter counts as on`() {
        assertTrue(isDndOn(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(isDndOn(NotificationManager.INTERRUPTION_FILTER_NONE))
        assertTrue(isDndOn(NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }

    @Test
    fun `only filter-all counts as off`() {
        assertFalse(isDndOn(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(isDndOn(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }
}

class UiEventMatcherTest {

    private val click = UiEvent(
        eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
        packageName = "com.example.app",
        className = "android.widget.Button",
        text = "Send",
        atMillis = 1_000,
    )

    @Test
    fun `the event type must match`() {
        assertTrue(matchesUiEvent(click, AccessibilityEvent.TYPE_VIEW_CLICKED, null, null))
        assertFalse(
            matchesUiEvent(click, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null, null)
        )
    }

    @Test
    fun `filters narrow by package and text`() {
        val type = AccessibilityEvent.TYPE_VIEW_CLICKED

        assertTrue(matchesUiEvent(click, type, "com.example.app", "send"))
        assertFalse(matchesUiEvent(click, type, "com.other.app", null))
        assertFalse(matchesUiEvent(click, type, null, "cancel"))
    }

    @Test
    fun `an event with no text cannot match a text filter`() {
        val silent = click.copy(text = null)

        assertFalse(matchesUiEvent(silent, AccessibilityEvent.TYPE_VIEW_CLICKED, null, "send"))
        assertTrue(matchesUiEvent(silent, AccessibilityEvent.TYPE_VIEW_CLICKED, null, null))
    }
}

class SmsMatcherTest {

    @Test
    fun `no filters matches any message`() {
        assertTrue(matchesSms("+49123", "hello", null, null))
    }

    @Test
    fun `sender and body filters are substring and case insensitive`() {
        assertTrue(matchesSms("+49123456", "Your code is 4321", "49123", "code"))
        assertTrue(matchesSms("Bank", "Your CODE is 4321", null, "code"))
        assertFalse(matchesSms("+49123456", "hello", "999", null))
    }

    @Test
    fun `a null sender or body cannot match a filter`() {
        assertFalse(matchesSms(null, "hello", "anyone", null))
        assertFalse(matchesSms("+49123", null, null, "anything"))
    }
}

class DistanceTest {

    @Test
    fun `distance to the same point is zero`() {
        assertTrue(distanceMeters(52.52, 13.405, 52.52, 13.405) < 0.001)
    }

    @Test
    fun `one degree of latitude is about 111 kilometres`() {
        val d = distanceMeters(52.0, 13.0, 53.0, 13.0)
        assertTrue("was $d", d in 111_000.0..111_500.0)
    }

    @Test
    fun `a short hop is accurate to within a metre or so`() {
        // Roughly 100 m north at Berlin's latitude.
        val d = distanceMeters(52.5200, 13.4050, 52.5209, 13.4050)
        assertTrue("was $d", d in 99.0..101.5)
    }

    @Test
    fun `distance is symmetric`() {
        val there = distanceMeters(52.52, 13.405, 48.137, 11.575)
        val back = distanceMeters(48.137, 11.575, 52.52, 13.405)
        assertTrue(kotlin.math.abs(there - back) < 0.001)
    }
}
