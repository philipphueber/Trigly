package app.phueber.trigly.actions

import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.IntentTargetCheck
import app.phueber.trigly.core.SpecialAccessKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything here is pure: [buildFireIntentSpec], [dataAddressRefusal] and
 * [decideIntentTargetCheck] never touch a real `android.content.Intent` or a
 * real `PackageManager`, which is what lets the refusals and the four-answer
 * Test decision be checked on the JVM. [FireIntentAction.execute] itself is
 * not tested here, the same as `OpenUrlAction.execute` and
 * `ComposeEmailAction.execute` are not: it builds a real `Intent`, which is a
 * stub that throws in this module's unit tests.
 */
class FireIntentSpecTest {

    private fun config(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun `a blank or missing action is refused`() {
        assertThrows(IllegalStateException::class.java) {
            buildFireIntentSpec(config(SendAs.CONFIG_KEY to "broadcast"))
        }
        assertThrows(IllegalStateException::class.java) {
            buildFireIntentSpec(
                config(
                    FireIntentAction.CONFIG_ACTION to "   ",
                    SendAs.CONFIG_KEY to "broadcast",
                )
            )
        }
    }

    @Test
    fun `send mode parses case insensitively and rejects an unknown value`() {
        assertEquals(SendAs.ACTIVITY, SendAs.parse("Activity"))
        assertEquals(SendAs.BROADCAST, SendAs.parse("BROADCAST"))
        assertEquals(SendAs.SERVICE, SendAs.parse("service"))

        val error = assertThrows(IllegalStateException::class.java) { SendAs.parse("intent") }
        assertTrue(error.message!!.contains("broadcast"))
    }

    @Test
    fun `absent send mode is refused rather than silently defaulted`() {
        // A saved rule always has a value here, seeded by the Choice field's
        // own default the moment the component is added. See
        // ConfigField.defaultValue. A config with none at all is not a
        // real rule; SendAs.parse says so loudly, the same as
        // RingerMode.parse and VolumeStream.parse do for their own fields.
        assertThrows(IllegalStateException::class.java) { SendAs.parse(null) }
    }

    @Test
    fun `a class name with no app to go with it is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildFireIntentSpec(
                config(
                    FireIntentAction.CONFIG_ACTION to "android.intent.action.SEND",
                    SendAs.CONFIG_KEY to "broadcast",
                    FireIntentAction.CONFIG_CLASS_NAME to "com.example.Receiver",
                )
            )
        }
    }

    @Test
    fun `a service target needs both an exact app and class`() {
        val action = FireIntentAction.CONFIG_ACTION to "com.example.action.PLAY"

        assertThrows(IllegalArgumentException::class.java) {
            buildFireIntentSpec(config(action, SendAs.CONFIG_KEY to "service"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildFireIntentSpec(
                config(action, SendAs.CONFIG_KEY to "service", FireIntentAction.CONFIG_PACKAGE to "com.example")
            )
        }

        // Both present: builds without throwing.
        val spec = buildFireIntentSpec(
            config(
                action,
                SendAs.CONFIG_KEY to "service",
                FireIntentAction.CONFIG_PACKAGE to "com.example",
                FireIntentAction.CONFIG_CLASS_NAME to "com.example.PlayerService",
            )
        )
        assertEquals(SendAs.SERVICE, spec.sendAs)
        assertEquals("com.example", spec.targetPackage)
        assertEquals("com.example.PlayerService", spec.className)
    }

    @Test
    fun `an activity or broadcast target needs nothing explicit`() {
        val spec = buildFireIntentSpec(
            config(
                FireIntentAction.CONFIG_ACTION to "android.intent.action.SEND",
                SendAs.CONFIG_KEY to "broadcast",
            )
        )
        assertEquals(null, spec.targetPackage)
        assertEquals(null, spec.className)
    }

    @Test
    fun `extras skip a blank key but keep a blank value`() {
        val spec = buildFireIntentSpec(
            config(
                FireIntentAction.CONFIG_ACTION to "com.example.action.SET",
                SendAs.CONFIG_KEY to "broadcast",
                FireIntentAction.extraKeyField(1) to "message",
                FireIntentAction.extraValueField(1) to "hello",
                FireIntentAction.extraKeyField(2) to "",
                FireIntentAction.extraValueField(2) to "orphaned, should not appear",
                FireIntentAction.extraKeyField(3) to "silent",
                // No value for slot 3: a real, present, empty-string extra.
            )
        )

        assertEquals(listOf("message" to "hello", "silent" to ""), spec.extras)
    }

    @Test
    fun `everything the schema offers lands in the right field`() {
        val spec = buildFireIntentSpec(
            config(
                FireIntentAction.CONFIG_ACTION to "android.intent.action.VIEW",
                SendAs.CONFIG_KEY to "activity",
                FireIntentAction.CONFIG_PACKAGE to "com.example.maps",
                FireIntentAction.CONFIG_CLASS_NAME to "com.example.maps.MainActivity",
                FireIntentAction.CONFIG_DATA_URI to "geo:0,0?q=coffee",
                FireIntentAction.CONFIG_MIME_TYPE to "text/plain",
            )
        )

        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals(SendAs.ACTIVITY, spec.sendAs)
        assertEquals("com.example.maps", spec.targetPackage)
        assertEquals("com.example.maps.MainActivity", spec.className)
        assertEquals("geo:0,0?q=coffee", spec.dataUri)
        assertEquals("text/plain", spec.mimeType)
        assertEquals(emptyList<Pair<String, String>>(), spec.extras)
    }
}

/**
 * `file:` is the one scheme this action refuses. Everything else, including
 * every other scheme `open_url` and `play_alert` refuse for their own
 * reasons, passes through, because sending an arbitrary command to an
 * arbitrary app is this action's whole reason to exist.
 */
class DataAddressRefusalTest {

    @Test
    fun `a file address is refused`() {
        val reason = dataAddressRefusal("file:///storage/emulated/0/secret.txt")
        assertTrue(reason != null && reason.contains("file:"))
    }

    @Test
    fun `scheme matching ignores case and surrounding space`() {
        assertTrue(dataAddressRefusal("  FILE:///secret.txt  ") != null)
    }

    @Test
    fun `every other scheme passes through unexamined`() {
        assertNull(dataAddressRefusal("https://example.com"))
        assertNull(dataAddressRefusal("http://example.com"))
        assertNull(dataAddressRefusal("content://media/external/images/1"))
        assertNull(dataAddressRefusal("geo:0,0?q=coffee"))
        assertNull(dataAddressRefusal("tel:+15551234567"))
        assertNull(dataAddressRefusal("market://details?id=com.example"))
        assertNull(dataAddressRefusal("someapp://do-something"))
    }

    @Test
    fun `blank and absent addresses are not refused`() {
        assertNull(dataAddressRefusal(null))
        assertNull(dataAddressRefusal(""))
        assertNull(dataAddressRefusal("   "))
    }
}

/** [requirementsForSendAs] needs no `Context`, so the per-mode decision is checked directly. */
class FireIntentRequirementsTest {

    private val overlay = listOf(ComponentRequirement.SpecialAccess(SpecialAccessKind.OVERLAY))

    @Test
    fun `only an activity target needs the overlay permission`() {
        assertEquals(overlay, requirementsForSendAs(mapOf(SendAs.CONFIG_KEY to "activity")))
        assertEquals(emptyList<ComponentRequirement>(), requirementsForSendAs(mapOf(SendAs.CONFIG_KEY to "broadcast")))
        assertEquals(emptyList<ComponentRequirement>(), requirementsForSendAs(mapOf(SendAs.CONFIG_KEY to "service")))
    }

    @Test
    fun `an unfinished or unknown send mode asks for nothing rather than throwing`() {
        assertEquals(emptyList<ComponentRequirement>(), requirementsForSendAs(emptyMap()))
        assertEquals(emptyList<ComponentRequirement>(), requirementsForSendAs(mapOf(SendAs.CONFIG_KEY to "nonsense")))
    }
}

/**
 * The Test seam's four answers, and the seam a real send's own pre-check
 * shares with it. [FakeIntentResolver] stands in for `PackageManager` so the
 * decision is checked without a device (see [IntentResolver]'s own KDoc).
 */
class IntentTargetCheckTest {

    private val own = "app.phueber.trigly"

    private fun spec(
        targetPackage: String? = null,
        className: String? = null,
        sendAs: SendAs = SendAs.BROADCAST,
    ) = FireIntentSpec(
        action = "com.example.action.DO_THING",
        targetPackage = targetPackage,
        className = className,
        dataUri = null,
        mimeType = null,
        extras = emptyList(),
        sendAs = sendAs,
    )

    @Test
    fun `naming Trigly's own package explicitly is refused before anything is asked`() {
        val resolver = FakeIntentResolver(matches = emptySet())
        val result = decideIntentTargetCheck(own, spec(targetPackage = own), resolver, filteringApplies = true)

        assertEquals(IntentTargetCheck.REFUSED_SELF_TARGET, result)
        assertTrue("must not have asked the system anything", resolver.resolvingPackagesCalls == 0)
    }

    @Test
    fun `an implicit intent that would only match Trigly's own manifest is also refused`() {
        // No package named at all, but the action string happens to match one
        // of Trigly's own components: the AlarmWakeReceiver case.
        val resolver = FakeIntentResolver(matches = setOf(own))
        val result = decideIntentTargetCheck(own, spec(), resolver, filteringApplies = true)

        assertEquals(IntentTargetCheck.REFUSED_SELF_TARGET, result)
    }

    @Test
    fun `a real match that is not Trigly's own package resolves`() {
        val resolver = FakeIntentResolver(matches = setOf("com.example.other"))
        val result = decideIntentTargetCheck(own, spec(), resolver, filteringApplies = true)

        assertEquals(IntentTargetCheck.WOULD_RESOLVE, result)
    }

    @Test
    fun `an explicit, visible package with nothing matching is a real no`() {
        val resolver = FakeIntentResolver(matches = emptySet(), visiblePackages = setOf("com.example.other"))
        val result = decideIntentTargetCheck(
            own, spec(targetPackage = "com.example.other"), resolver, filteringApplies = true,
        )

        assertEquals(IntentTargetCheck.WOULD_NOT_RESOLVE, result)
    }

    @Test
    fun `an explicit package Trigly cannot see is hidden, not refused as absent, when filtering applies`() {
        val resolver = FakeIntentResolver(matches = emptySet(), visiblePackages = emptySet())
        val result = decideIntentTargetCheck(
            own, spec(targetPackage = "com.example.invisible"), resolver, filteringApplies = true,
        )

        assertEquals(IntentTargetCheck.HIDDEN_BY_VISIBILITY, result)
    }

    @Test
    fun `below API 30, an invisible package is a real no, since nothing is filtered there`() {
        val resolver = FakeIntentResolver(matches = emptySet(), visiblePackages = emptySet())
        val result = decideIntentTargetCheck(
            own, spec(targetPackage = "com.example.invisible"), resolver, filteringApplies = false,
        )

        assertEquals(IntentTargetCheck.WOULD_NOT_RESOLVE, result)
    }

    @Test
    fun `an implicit intent matching nothing is hidden rather than a claimed no, when filtering applies`() {
        val resolver = FakeIntentResolver(matches = emptySet())
        val result = decideIntentTargetCheck(own, spec(), resolver, filteringApplies = true)

        assertEquals(IntentTargetCheck.HIDDEN_BY_VISIBILITY, result)
    }

    @Test
    fun `an implicit intent matching nothing on a pre-filtering device is a real no`() {
        val resolver = FakeIntentResolver(matches = emptySet())
        val result = decideIntentTargetCheck(own, spec(), resolver, filteringApplies = false)

        assertEquals(IntentTargetCheck.WOULD_NOT_RESOLVE, result)
    }

    private class FakeIntentResolver(
        private val matches: Set<String>,
        private val visiblePackages: Set<String> = emptySet(),
    ) : IntentResolver {
        var resolvingPackagesCalls = 0
            private set

        override fun isPackageVisible(packageName: String): Boolean = packageName in visiblePackages

        override fun resolvingPackages(spec: FireIntentSpec): Set<String> {
            resolvingPackagesCalls++
            return matches
        }
    }
}
