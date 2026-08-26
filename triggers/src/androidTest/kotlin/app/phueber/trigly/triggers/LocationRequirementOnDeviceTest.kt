package app.phueber.trigly.triggers

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.TriggerNode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which location grant a rule asks for, through the path the rules screen uses.
 *
 * [locationRequirements] is a pure function with its own unit tests, so what is
 * left to get wrong is the wiring: two factories, an override on each, and a
 * config key read out of stored text. A typo there costs a rule that demands
 * precise location for an area twenty kilometres across, or worse, one that
 * accepts an approximate fix for a driveway. Neither shows up in a JVM test,
 * because the factories need a `Context` and the registry is assembled from
 * them.
 */
@RunWith(AndroidJUnit4::class)
class LocationRequirementOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fine = ComponentRequirement.RuntimePermission(
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val coarse = ComponentRequirement.RuntimePermission(
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val background = ComponentRequirement.RuntimePermission(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    private fun requirementsFor(type: String, radius: String): List<ComponentRequirement> {
        val registry = Registry(triggerFactories(context), emptyList())
        val rule = Rule(
            id = "area",
            name = "In an area",
            trigger = TriggerNode.One(
                ComponentSpec(
                    type = type,
                    config = mapOf(
                        LocationTrigger.CONFIG_LATITUDE to "52.52",
                        LocationTrigger.CONFIG_LONGITUDE to "13.405",
                        LocationTrigger.CONFIG_RADIUS_METERS to radius,
                    ),
                )
            ),
            actions = emptyList(),
            enabled = true,
        )
        return registry.requirementsOf(rule)
    }

    @Test
    fun a_small_area_asks_for_precise_location() {
        listOf(LocationTrigger.TYPE, LocationCheckTriggerFactory.TYPE).forEach { type ->
            val declared = requirementsFor(type, "150")
            assertTrue("$type asked for $declared", declared.contains(fine))
            assertTrue("$type asked for $declared", !declared.contains(coarse))
        }
    }

    @Test
    fun an_area_of_kilometres_asks_only_for_approximate_location() {
        listOf(LocationTrigger.TYPE, LocationCheckTriggerFactory.TYPE).forEach { type ->
            val declared = requirementsFor(type, "20000")
            assertTrue("$type asked for $declared", declared.contains(coarse))
            assertTrue("$type asked for $declared", !declared.contains(fine))
        }
    }

    /** Whichever precision it asks for, the background grant is the other half. */
    @Test
    fun the_background_grant_is_asked_for_either_way() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        listOf("150", "20000").forEach { radius ->
            val declared = requirementsFor(LocationCheckTriggerFactory.TYPE, radius)
            assertTrue("radius $radius asked for $declared", declared.contains(background))
        }
    }
}
