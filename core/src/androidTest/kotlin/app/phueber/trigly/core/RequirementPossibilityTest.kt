package app.phueber.trigly.core

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which requirements are *permanent* on a given device.
 *
 * This is the policy behind hiding components in the editor's pickers, and it is
 * the part worth pinning down in a test: getting it wrong either offers a
 * trigger that can never fire, or — worse, and silently — hides a working one.
 * The device conditions are read for real, which is why this is instrumented.
 */
@RunWith(AndroidJUnit4::class)
class RequirementPossibilityTest {

    private val checker = RequirementChecker(
        InstrumentationRegistry.getInstrumentation().targetContext
    )

    @Test
    fun an_api_level_above_this_device_is_permanent() {
        assertFalse(
            checker.isPossible(ComponentRequirement.MinApiLevel(Build.VERSION.SDK_INT + 1))
        )
    }

    @Test
    fun an_api_level_this_device_meets_is_fine() {
        assertTrue(checker.isPossible(ComponentRequirement.MinApiLevel(Build.VERSION.SDK_INT)))
    }

    @Test
    fun hardware_the_device_lacks_is_permanent() {
        assertFalse(
            checker.isPossible(ComponentRequirement.SystemFeature("trigly.test.no.such.feature"))
        )
    }

    @Test
    fun a_permission_is_never_permanent_even_when_it_is_not_granted() {
        // The instrumented app has not been granted this, so `isSatisfied` is
        // false — and `isPossible` must still be true, because a prompt fixes it.
        val readSms = ComponentRequirement.RuntimePermission("android.permission.READ_SMS")

        assertFalse("expected an ungranted permission", checker.isSatisfied(readSms))
        assertTrue(checker.isPossible(readSms))
    }

    @Test
    fun special_access_is_never_permanent() {
        SpecialAccessKind.entries.forEach { kind ->
            assertTrue(
                "$kind is granted in settings, so it can never be permanent",
                checker.isPossible(ComponentRequirement.SpecialAccess(kind)),
            )
        }
    }

    /**
     * Every special access has a settings screen the Grant button sends the user
     * to, and a wrong action string fails in the worst possible way: the button
     * is there, it is tappable, and it drops the user at the top of Settings with
     * nothing to explain why. The strings are hand-written constants — one of them
     * spells it `android.settings.action.…` rather than `android.settings.…` —
     * so they are worth resolving for real rather than reading twice.
     *
     * The `package:` URI is included exactly where the kind says the screen takes
     * one, because handing it to a screen that does not makes the intent
     * unresolvable, which is the same failure this is looking for.
     */
    @Test
    fun every_special_access_has_a_settings_screen_that_resolves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        SpecialAccessKind.entries.forEach { kind ->
            val intent = Intent(kind.settingsAction)
            if (kind.packageScoped) {
                intent.data = Uri.parse("package:${context.packageName}")
            }

            assertTrue(
                "$kind: nothing on this device handles ${kind.settingsAction}" +
                    if (kind.packageScoped) " with a package URI" else "",
                intent.resolveActivity(context.packageManager) != null,
            )
        }
    }

    @Test
    fun a_play_restriction_does_not_hide_a_component() {
        // It says Google will not publish this, not that the device cannot run
        // it. Trigly is meant to be sideloadable, so hiding these would remove
        // working features from exactly the people who want them.
        assertTrue(
            checker.isPossible(ComponentRequirement.PolicyRestricted("Play policy on SMS"))
        )
    }

    @Test
    fun a_descriptor_is_unavailable_if_any_requirement_is_permanent() {
        val descriptor = ComponentDescriptor(
            type = "test",
            displayName = "Test",
            category = "Test",
            requirements = listOf(
                ComponentRequirement.RuntimePermission("android.permission.READ_SMS"),
                ComponentRequirement.MinApiLevel(Build.VERSION.SDK_INT + 1),
            ),
            configFields = emptyList(),
            warning = null,
        )

        assertFalse(checker.isAvailable(descriptor))
        assertTrue(
            checker.impossible(descriptor)
                .all { it is ComponentRequirement.MinApiLevel }
        )
    }
}
