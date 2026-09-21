package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a global screen action, such as Home, can do anything right now.
 *
 * The answer is settled here rather than on a phone, because the phone cannot
 * show it. `performGlobalAction(GLOBAL_ACTION_HOME)` returns true on a locked
 * device while the lock screen takes the key, so the platform's own answer is
 * "yes" in exactly the case where nothing happened. This rule is what keeps a
 * caller from reporting that as a success.
 */
class CanDriveTheScreenTest {

    @Test
    fun `a locked phone with a real lock cannot be driven`() {
        assertFalse(canDriveTheScreen(keyguardLocked = true, deviceSecure = true))
    }

    @Test
    fun `a locked phone with no lock set is still worth trying`() {
        // The keyguard is a swipe with nothing behind it, so the key reaches
        // the window behind it. Refusing here would report "locked" to somebody
        // who set no lock, about an action that would have worked.
        assertTrue(canDriveTheScreen(keyguardLocked = true, deviceSecure = false))
    }

    @Test
    fun `an unlocked phone can be driven, lock set or not`() {
        assertTrue(canDriveTheScreen(keyguardLocked = false, deviceSecure = true))
        assertTrue(canDriveTheScreen(keyguardLocked = false, deviceSecure = false))
    }

    /**
     * The shade rule is the same rule, and it delegates. Pinned so the two
     * cannot drift apart while both keep their own name and their own reasons.
     */
    @Test
    fun `pressing through the shade asks the same question`() {
        for (locked in listOf(true, false)) {
            for (secure in listOf(true, false)) {
                assertEquals(
                    "locked=$locked secure=$secure",
                    canDriveTheScreen(locked, secure),
                    canPressThroughShade(locked, secure),
                )
            }
        }
    }
}
