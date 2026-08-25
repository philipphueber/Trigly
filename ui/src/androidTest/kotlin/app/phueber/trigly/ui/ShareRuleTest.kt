package app.phueber.trigly.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sharing a rule, at the level only a device can answer.
 *
 * The `FileProvider` is the part worth an instrumented test. Its authority lives
 * in the manifest and is matched against a string built at runtime from the
 * package name, so a mismatch is not a compile error and nothing else in the app
 * would ever touch it. It would surface as a crash the first time somebody
 * pressed Share, which is both the worst place to find out and the one place a
 * unit test cannot look.
 */
@RunWith(AndroidJUnit4::class)
class ShareRuleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_provider_authority_in_the_manifest_matches_the_one_the_code_asks_for() {
        // Throws IllegalArgumentException if the authority is not declared, or if
        // the file is outside every path the provider was given.
        val uri = sharedRuleUri(context, "trigly-test.json", """{"version":3}""")

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
    }

    /**
     * The receiving app reads through the resolver, not the file system, so that
     * is what the test reads too. A URI that resolves to nothing readable is the
     * same failure as no URI at all.
     */
    @Test
    fun the_shared_file_can_be_read_back_through_the_resolver() {
        val json = """{"version":3,"rules":[{"name":"Driving mode"}]}"""

        val uri = sharedRuleUri(context, "trigly-driving-mode.json", json)
        val read = context.contentResolver.openInputStream(uri)!!
            .use { it.readBytes().decodeToString() }

        assertEquals(json, read)
        assertEquals("application/json", context.contentResolver.getType(uri))
    }

    @Test
    fun the_intent_is_a_chooser_around_a_send_that_carries_the_file_and_the_grant() {
        val chooser = shareRuleIntent(context, "Driving mode", """{"version":3}""")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(
            "the chooser must carry the read grant it passes on",
            chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )

        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertEquals("Driving mode", send.getStringExtra(Intent.EXTRA_TITLE))
        assertNotNull("the send has to carry the file", send.extras!!.get(Intent.EXTRA_STREAM))
        assertTrue(
            "without the grant the receiving app cannot open the URI",
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }

    /**
     * One share writes one file. The directory is cleared each time, so sharing
     * a second rule does not leave the first one behind for the next receiving
     * app to be granted along with it, and a rule deleted from the app does not
     * survive in the cache.
     */
    @Test
    fun sharing_a_second_rule_leaves_only_the_second_file() {
        sharedRuleUri(context, "trigly-first.json", """{"first":true}""")
        sharedRuleUri(context, "trigly-second.json", """{"second":true}""")

        val names = java.io.File(context.cacheDir, "shared").list()!!.toList()

        assertEquals(listOf("trigly-second.json"), names)
    }
}
