package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name a shared folder arrives under on someone else's device.
 *
 * The sibling of [SharedRuleFileNameTest], and worth its own tests for the one
 * thing that is not shared with it: a folder file has to be told apart from a
 * rule file by its name alone, because that name is all the receiving end
 * gets.
 */
class SharedFolderFileNameTest {

    @Test
    fun `a plain folder name becomes a slug`() {
        assertEquals("trigly-folder-car.json", sharedFolderFileName("Car"))
    }

    @Test
    fun `the same slugging a rule name gets`() {
        assertEquals("trigly-folder-week-end.json", sharedFolderFileName("Week -> End!!"))
        assertEquals("trigly-folder-night.json", sharedFolderFileName("  night  "))
        assertEquals("trigly-folder-shift-2-of-3.json", sharedFolderFileName("Shift 2 of 3"))
    }

    /**
     * A folder named only in a script the slug strips must still produce a
     * usable name, and one that still says what the file holds. The
     * alternative is `trigly-folder-.json`, or worse, a hidden file.
     */
    @Test
    fun `a name with nothing left after stripping falls back to the folder word`() {
        assertEquals("trigly-folder.json", sharedFolderFileName("日本語"))
        assertEquals("trigly-folder.json", sharedFolderFileName("!!!"))
        assertEquals("trigly-folder.json", sharedFolderFileName(""))
    }

    /**
     * The reason a folder file is not named the way a rule file is. One rule
     * and a folder of thirty are both a `.json` of rules, and a person who
     * shares both from the same phone must not send two files that overwrite
     * each other on arrival.
     */
    @Test
    fun `a folder and a rule of the same name are different files`() {
        assertNotEquals(sharedRuleFileName("Car"), sharedFolderFileName("Car"))
    }

    /** A folder name has no length limit. A file name does. */
    @Test
    fun `a very long name is truncated and still ends in json`() {
        val name = sharedFolderFileName("a".repeat(300))

        assertTrue("was ${name.length} chars: $name", name.length <= 80)
        assertTrue(name.endsWith(".json"))
    }
}
