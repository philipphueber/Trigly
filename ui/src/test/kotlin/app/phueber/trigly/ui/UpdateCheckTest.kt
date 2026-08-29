package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs on the JVM against the real `org.json` (android.jar ships stubs that
 * throw), the same reasoning `RuleJsonTest` gives for itself: this parses a
 * real network response shape, which is close enough for one string field.
 *
 * `checkForUpdate` itself, the only impure function in `UpdateCheck.kt`, is
 * not tested here: it makes a real network call, and a unit test that
 * depends on a live GitHub API answering the same way every run would be
 * exactly the kind of flaky test this project's own testing rules warn
 * against. `parseLatestReleaseTag` and `isNewerVersion` are what
 * `checkForUpdate` actually decides with, and both are pure, so the decision
 * logic is fully covered without a real request.
 */
class UpdateCheckTest {

    @Test
    fun `a tag with no v prefix is read as is`() {
        assertEquals("1.2.3", parseLatestReleaseTag("""{"tag_name": "1.2.3"}"""))
    }

    @Test
    fun `a leading v is dropped`() {
        assertEquals("1.2.3", parseLatestReleaseTag("""{"tag_name": "v1.2.3"}"""))
    }

    @Test
    fun `other fields on the same response are ignored`() {
        val response = """{"tag_name": "0.2.0", "name": "Trigly 0.2.0", "draft": false}"""
        assertEquals("0.2.0", parseLatestReleaseTag(response))
    }

    @Test
    fun `malformed json is null, not a thrown exception`() {
        assertNull(parseLatestReleaseTag("not json"))
    }

    @Test
    fun `json missing tag_name is null`() {
        assertNull(parseLatestReleaseTag("""{"name": "Trigly 0.2.0"}"""))
    }

    @Test
    fun `a later patch version is newer`() {
        assertTrue(isNewerVersion(current = "0.2.0", latest = "0.2.1"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(isNewerVersion(current = "0.2.0", latest = "0.2.0"))
    }

    @Test
    fun `an earlier version is not newer`() {
        assertFalse(isNewerVersion(current = "0.2.0", latest = "0.1.9"))
    }

    /**
     * Plain string comparison gets this backwards: `"0.10.0" < "0.9.0"` as
     * text. Each part must compare as a number.
     */
    @Test
    fun `a double-digit minor version is newer than a single-digit one`() {
        assertTrue(isNewerVersion(current = "0.9.0", latest = "0.10.0"))
    }

    @Test
    fun `a missing trailing part counts as zero`() {
        assertFalse(isNewerVersion(current = "1.2.0", latest = "1.2"))
        assertTrue(isNewerVersion(current = "1.2", latest = "1.2.1"))
    }

    @Test
    fun `a non numeric part counts as zero rather than throwing`() {
        assertFalse(isNewerVersion(current = "0.2.0", latest = "0.2.0-beta"))
    }
}
