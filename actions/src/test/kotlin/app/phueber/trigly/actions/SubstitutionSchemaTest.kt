package app.phueber.trigly.actions

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Substitution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which content type counts as JSON for [HttpRequestActionFactory.substitutionsFor].
 *
 * A prefix match, because a real request carries a parameter such as `charset`
 * and that is still JSON.
 */
class JsonContentTypeTest {

    @Test
    fun `the default content type is JSON`() {
        assertTrue(isJsonContentType(HttpRequestAction.DEFAULT_CONTENT_TYPE))
    }

    @Test
    fun `a charset parameter does not stop it being JSON`() {
        assertTrue(isJsonContentType("application/json; charset=utf-8"))
    }

    @Test
    fun `matching ignores case and surrounding space`() {
        assertTrue(isJsonContentType("  APPLICATION/JSON  "))
    }

    @Test
    fun `a different content type is not JSON`() {
        assertFalse(isJsonContentType("text/plain"))
        assertFalse(isJsonContentType("application/x-www-form-urlencoded"))
    }
}

/**
 * `http_request`'s body is the one field in this module whose escaping depends
 * on a sibling field. A notification title with a quotation mark, inserted raw
 * into a JSON body, is the quiet failure `docs/variables.md` section 8 is
 * built against: the server answers 400 and nothing on screen explains why.
 *
 * [HttpRequestActionFactory] takes no `Context`, so it is exercised directly
 * here rather than through a fake platform dependency.
 */
class HttpRequestSubstitutionTest {

    private val factory = HttpRequestActionFactory()

    @Test
    fun `the body is escaped for JSON when the content type is absent`() {
        val substitutions = factory.substitutionsFor(
            mapOf(HttpRequestAction.CONFIG_URL to "https://example.com")
        )
        assertEquals(Substitution.JSON_STRING, substitutions[HttpRequestAction.CONFIG_BODY])
    }

    @Test
    fun `the body is escaped for JSON when the content type is explicitly JSON`() {
        val substitutions = factory.substitutionsFor(
            mapOf(HttpRequestAction.CONFIG_CONTENT_TYPE to "application/json")
        )
        assertEquals(Substitution.JSON_STRING, substitutions[HttpRequestAction.CONFIG_BODY])
    }

    @Test
    fun `the body is inserted as plain text for a non-JSON content type`() {
        val substitutions = factory.substitutionsFor(
            mapOf(HttpRequestAction.CONFIG_CONTENT_TYPE to "text/plain")
        )
        assertEquals(Substitution.TEXT, substitutions[HttpRequestAction.CONFIG_BODY])
    }

    @Test
    fun `the URL is always declared for percent-encoding`() {
        val url = factory.configFields.single { it.key == HttpRequestAction.CONFIG_URL }
        assertEquals(Substitution.URL, url.substitution)
    }

    @Test
    fun `the method, content type and timeout fields accept no variable`() {
        val fields = factory.configFields.associateBy { it.key }
        assertEquals(
            Substitution.NONE,
            fields.getValue(HttpRequestAction.CONFIG_METHOD).substitution,
        )
        assertEquals(
            Substitution.NONE,
            fields.getValue(HttpRequestAction.CONFIG_CONTENT_TYPE).substitution,
        )
        assertEquals(
            Substitution.NONE,
            fields.getValue(HttpRequestAction.CONFIG_TIMEOUT_MILLIS).substitution,
        )
    }
}

/**
 * The message field several actions share. Its default is [Substitution.TEXT]
 * because every field built from it today is prose, and this pins that default
 * so a future caller that is not prose has to say so explicitly rather than
 * inherit it by accident.
 */
class MessageTextSubstitutionTest {

    @Test
    fun `defaults to TEXT`() {
        val field = messageText("key", "Label")
        assertEquals(Substitution.TEXT, field.substitution)
    }

    @Test
    fun `a caller can declare something else`() {
        val field = messageText("key", "Label", substitution = Substitution.NONE)
        assertEquals(Substitution.NONE, field.substitution)
    }
}

/**
 * A sample of the other factories, chosen because they need no `Context` and so
 * can be built directly on the JVM: `set_rule_enabled` and the two notification
 * listener actions. Each covers a field kind phase 1 deliberately leaves alone:
 * [ConfigField.RuleRef], [ConfigField.Choice], [ConfigField.AppPackage],
 * [ConfigField.NotificationButton] and [ConfigField.Flag]. All of them must keep
 * reporting [Substitution.NONE] rather than silently starting to accept a
 * template nobody asked it to parse.
 */
class UnaffectedFieldSubstitutionTest {

    @Test
    fun `set_rule_enabled declares no substitutable field`() {
        val factory = SetRuleEnabledActionFactory(InMemoryRuleRepository())
        assertTrue(factory.configFields.all { it.substitution == Substitution.NONE })
    }

    @Test
    fun `dismiss_notification declares no substitutable field`() {
        val factory = DismissNotificationActionFactory(NotificationController.Unavailable)
        assertTrue(factory.configFields.all { it.substitution == Substitution.NONE })
    }

    @Test
    fun `notification_button declares no substitutable field`() {
        val factory = TriggerNotificationButtonActionFactory(NotificationController.Unavailable)
        assertTrue(factory.configFields.all { it.substitution == Substitution.NONE })
    }
}
