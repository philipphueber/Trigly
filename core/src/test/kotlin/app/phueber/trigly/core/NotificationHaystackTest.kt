package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The string a text filter is matched against.
 *
 * Tiny enough to look not worth testing, and it is the single most misunderstood
 * value in the app: two callers depend on it agreeing — the matcher that decides
 * whether a rule fires, and the inspector screen that tells someone why it did
 * not. Pinning the awkward cases here is what makes the screen's claim true
 * rather than a second guess at the same formatting.
 */
class NotificationHaystackTest {

    @Test
    fun title_and_text_are_matched_as_one_string() {
        assertEquals("Alarm Time to go", notificationHaystack("Alarm", "Time to go"))
    }

    /**
     * The case that makes an anchored pattern behave unexpectedly: a notification
     * with no title still contributes the separator, so `^Time` does not match
     * text that visibly starts with "Time".
     */
    @Test
    fun a_missing_title_still_contributes_its_separator() {
        assertEquals(" Time to go", notificationHaystack(null, "Time to go"))
    }

    @Test
    fun a_missing_text_leaves_a_trailing_separator() {
        assertEquals("Alarm ", notificationHaystack("Alarm", null))
    }

    /** Both absent is a space, not empty — the same rule, applied twice. */
    @Test
    fun both_absent_is_the_separator_alone() {
        assertEquals(" ", notificationHaystack(null, null))
    }
}
