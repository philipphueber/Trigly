package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure parts of the app picker.
 *
 * Everything else about it needs a `PackageManager` and belongs in an
 * instrumented test; these two decide what the picker *offers*, which is worth
 * pinning down cheaply. `looksLikeAPackageName` is deliberately loose — it gates
 * a convenience ("use what you typed"), and the factory validates for real at
 * save time — so these tests fix that looseness rather than pretending it is a
 * validator.
 */
class InstalledAppsTest {

    @Test
    fun a_dotted_identifier_is_offered() {
        assertTrue(looksLikeAPackageName("com.example.app"))
        assertTrue(looksLikeAPackageName("app.phueber.trigly"))
        assertTrue(looksLikeAPackageName("com.example.app_two"))
        assertTrue(looksLikeAPackageName("com.example.app2"))
        // Surrounding space is a paste artefact, not an intent to type prose.
        assertTrue(looksLikeAPackageName("  com.example.app  "))
    }

    @Test
    fun a_search_phrase_is_not_offered_as_a_package() {
        // The same field is the search box, so anything a person would type to
        // *find* an app must not be offered as a package name.
        assertFalse(looksLikeAPackageName("whatsapp"))
        assertFalse(looksLikeAPackageName("Google Maps"))
        assertFalse(looksLikeAPackageName("com example app"))
        assertFalse(looksLikeAPackageName(""))
        assertFalse(looksLikeAPackageName("   "))
    }

    @Test
    fun a_package_cannot_start_with_a_digit_or_a_dot() {
        assertFalse(looksLikeAPackageName("2com.example"))
        assertFalse(looksLikeAPackageName(".com.example"))
    }

    @Test
    fun punctuation_that_no_package_contains_is_rejected() {
        assertFalse(looksLikeAPackageName("com.example/app"))
        assertFalse(looksLikeAPackageName("com.example-app"))
        assertFalse(looksLikeAPackageName("com.example:app"))
    }

    @Test
    fun a_known_package_resolves_to_its_label() {
        val apps = listOf(
            InstalledApp("com.example.one", "One"),
            InstalledApp("com.example.two", "Two"),
        )

        assertEquals("Two", apps.labelFor("com.example.two"))
    }

    @Test
    fun an_unknown_package_shows_itself() {
        // A rule can name an app that is no longer installed, or one with no
        // launcher icon that was typed by hand. Showing the raw package is the
        // honest answer; showing nothing would hide what the rule targets.
        assertEquals("com.example.gone", emptyList<InstalledApp>().labelFor("com.example.gone"))
    }
}
