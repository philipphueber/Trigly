package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which notification, and which of its buttons.
 *
 * Both halves used to be unanswerable: the notification was a key nobody could
 * type and the button was an index nobody could know. The interesting behaviour
 * now is the *order* of preference, because that is what decides whether a rule
 * keeps working when an app is updated, translated, or reorganised.
 */
class NotificationTargetTest {

    private fun button(
        index: Int,
        label: String?,
        semantic: Int? = null,
        takesText: Boolean = false,
    ) = NotificationButton(index, label, semantic, takesText)

    private fun notification(
        key: String,
        pkg: String,
        posted: Long,
        buttons: List<NotificationButton> = emptyList(),
    ) = ActiveNotification(key, pkg, title = "T", text = "X", postedAtMillis = posted, buttons = buttons)

    // --- choosing the notification -----------------------------------------

    private val music = notification("k1", "com.example.music", posted = 100)
    private val chat = notification("k2", "com.example.chat", posted = 200)
    private val newerMusic = notification("k3", "com.example.music", posted = 300)

    @Test
    fun `a configured package wins over the notification that fired the rule`() {
        // The case that justifies the package existing at all: a Bluetooth
        // trigger acting on a media notification.
        val chosen = chooseNotification(
            active = listOf(music, chat),
            wantedPackage = "com.example.music",
            triggeringKey = "k2",
        )

        assertEquals("k1", chosen?.key)
    }

    @Test
    fun `the newest notification from that app is chosen`() {
        val chosen = chooseNotification(
            active = listOf(music, newerMusic),
            wantedPackage = "com.example.music",
            triggeringKey = null,
        )

        // Arbitrary would mean the rule behaves differently on different days.
        assertEquals("k3", chosen?.key)
    }

    @Test
    fun `with no package it falls back to the triggering notification`() {
        val chosen = chooseNotification(listOf(music, chat), wantedPackage = null, triggeringKey = "k2")

        assertEquals("k2", chosen?.key)
    }

    @Test
    fun `a blank package is treated as no package, not as an app named nothing`() {
        val chosen = chooseNotification(listOf(chat), wantedPackage = "  ", triggeringKey = "k2")

        assertEquals("k2", chosen?.key)
    }

    @Test
    fun `nothing matching gives nothing, not an arbitrary notification`() {
        assertNull(chooseNotification(listOf(music), "com.example.absent", triggeringKey = null))
        assertNull(chooseNotification(listOf(music), wantedPackage = null, triggeringKey = "gone"))
        assertNull(chooseNotification(emptyList(), wantedPackage = null, triggeringKey = null))
    }

    // --- choosing the button ------------------------------------------------

    private val reply = button(0, "Reply", semantic = 1, takesText = true)
    private val archive = button(1, "Archive", semantic = 5)
    private val snooze = button(2, "Snooze")

    @Test
    fun `meaning is preferred over label and position`() {
        // The app has reordered *and* relabelled; the meaning still finds it.
        val reordered = listOf(button(0, "Zzz"), button(1, "Put away", semantic = 5))

        val chosen = chooseButton(reordered, wantedSemantic = 5, wantedLabel = "Archive", storedIndex = 0)

        assertEquals(1, chosen?.index)
    }

    @Test
    fun `label is used when the app declares no meaning`() {
        val chosen = chooseButton(listOf(reply, archive, snooze), null, "Snooze", storedIndex = 0)

        assertEquals(2, chosen?.index)
    }

    @Test
    fun `label matching ignores case`() {
        val chosen = chooseButton(listOf(archive), null, "ARCHIVE", storedIndex = null)

        assertEquals(1, chosen?.index)
    }

    /**
     * `SEMANTIC_ACTION_NONE` means "the app said nothing", which is not something
     * to match on — every undeclared button would match every other one.
     */
    @Test
    fun `a semantic action of none is not treated as a meaning`() {
        val undeclared = listOf(button(0, "First"), button(1, "Second"))

        val chosen = chooseButton(undeclared, wantedSemantic = SEMANTIC_ACTION_NONE, wantedLabel = "Second", storedIndex = null)

        assertEquals(1, chosen?.index)
    }

    @Test
    fun `a rule saved with only an index still resolves`() {
        val chosen = chooseButton(listOf(reply, archive, snooze), null, null, storedIndex = 2)

        assertEquals(2, chosen?.index)
    }

    /**
     * The important refusal. Pressing whatever now sits in position 1 because the
     * button that was there is gone would be a rule doing something nobody asked
     * for, silently.
     */
    @Test
    fun `nothing matching presses nothing`() {
        val chosen = chooseButton(listOf(archive), wantedSemantic = 99, wantedLabel = "Reply", storedIndex = null)

        assertNull(chosen)
    }

    @Test
    fun `an index past the end resolves to nothing rather than throwing`() {
        assertNull(chooseButton(listOf(archive), null, null, storedIndex = 7))
    }

    @Test
    fun `a reply button is still found, so the caller can refuse it by name`() {
        // Filtering it out here would leave the action saying "no such button"
        // about one the user can plainly see.
        val chosen = chooseButton(listOf(reply), null, "Reply", storedIndex = null)

        assertEquals(true, chosen?.takesText)
    }
}
