package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name a shared rule arrives under on someone else's device.
 *
 * Worth testing rather than eyeballing: it is the only part of sharing that a
 * rule's own name can break, and every case below is a rule name a person can
 * legitimately type.
 */
class SharedRuleFileNameTest {

    @Test
    fun `a plain name becomes a slug`() {
        assertEquals("trigly-driving-mode.json", sharedRuleFileName("Driving mode"))
    }

    @Test
    fun `punctuation and repeated separators collapse to one dash`() {
        assertEquals("trigly-car-home.json", sharedRuleFileName("Car -> Home!!"))
    }

    @Test
    fun `leading and trailing separators are dropped`() {
        assertEquals("trigly-night.json", sharedRuleFileName("  night  "))
    }

    /**
     * A name written entirely in a script this strips must still produce a
     * usable file name. The alternative is `trigly-.json`, which is a hidden
     * file on the receiving end on any Unix-like system.
     */
    @Test
    fun `a name with nothing left after stripping falls back`() {
        assertEquals("trigly-rule.json", sharedRuleFileName("日本語"))
        assertEquals("trigly-rule.json", sharedRuleFileName("!!!"))
        assertEquals("trigly-rule.json", sharedRuleFileName(""))
    }

    /** A rule name has no length limit. A file name does. */
    @Test
    fun `a very long name is truncated and still ends in json`() {
        val name = sharedRuleFileName("a".repeat(300))

        assertTrue("was ${name.length} chars: $name", name.length <= 80)
        assertTrue(name.endsWith(".json"))
    }

    @Test
    fun `digits are kept`() {
        assertEquals("trigly-alarm-2-of-3.json", sharedRuleFileName("Alarm 2 of 3"))
    }
}
