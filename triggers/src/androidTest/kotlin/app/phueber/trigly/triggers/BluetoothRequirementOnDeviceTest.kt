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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a Bluetooth rule tells the rules list it needs, asked of the real
 * factory on a real device.
 *
 * The regression this exists for cost a rule that could not fire and a list that
 * said nothing was missing. `BLUETOOTH_CONNECT` was declared only for a rule
 * narrowed to one device, because an "any device" rule reads neither the address
 * nor the name of the device that connected and was thought to need nothing. The
 * permission is not about reading the event. The Bluetooth stack sends
 * `ACTION_ACL_CONNECTED` with `BLUETOOTH_CONNECT` named as the receiver
 * permission, so a receiver without the grant is sent no event to read.
 *
 * Instrumented rather than a JVM test for two reasons that both matter here. The
 * factory needs a `Context`, so this is the only place the wiring from
 * [bluetoothConnectRequirements] through to [Registry.requirementsOf] can be
 * asked at all. And the answer depends on the Android version of the device it
 * runs on, which is a thing a JVM test can only pretend to have.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothRequirementOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val connect =
        ComponentRequirement.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)

    /** What this device should be asking for, whatever the configuration says. */
    private val expected: List<ComponentRequirement> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(connect) else emptyList()

    /** The configuration that was broken: no device chosen, so "any device". */
    @Test
    fun an_any_device_rule_declares_the_permission() {
        val factory = BluetoothConnectionTriggerFactory(context)

        assertEquals(expected, factory.requirementsFor(emptyMap()))
        assertEquals(expected, factory.requirements)
    }

    @Test
    fun a_rule_narrowed_to_a_device_declares_it_too() {
        val factory = BluetoothConnectionTriggerFactory(context)

        val byAddress = mapOf(
            BluetoothConnectionTrigger.CONFIG_IDENTIFY_BY to
                BluetoothConnectionTrigger.IDENTIFY_BY_ADDRESS,
            BluetoothConnectionTrigger.CONFIG_ADDRESS to "AA:BB:CC:DD:EE:FF",
        )
        val byName = mapOf(
            BluetoothConnectionTrigger.CONFIG_IDENTIFY_BY to
                BluetoothConnectionTrigger.IDENTIFY_BY_NAME,
            BluetoothConnectionTrigger.CONFIG_NAME to "headset",
        )

        assertEquals(expected, factory.requirementsFor(byAddress))
        assertEquals(expected, factory.requirementsFor(byName))
    }

    /**
     * The path the rules screen takes, end to end. `requirementsOf` is what
     * decides whether a rule is drawn as unfirable, and it asks
     * `requirementsFor` rather than `requirements`, which is exactly why
     * withholding the permission there made a rule that could not work look
     * like one that was merely waiting.
     */
    @Test
    fun the_registry_reports_it_for_an_any_device_rule() {
        val registry = Registry(triggerFactories(context), emptyList())

        val rule = Rule(
            id = "any-device",
            name = "Any device connects",
            trigger = TriggerNode.One(
                ComponentSpec(type = BluetoothConnectionTrigger.TYPE, config = emptyMap())
            ),
            actions = emptyList(),
            enabled = true,
        )

        assertEquals(expected, registry.requirementsOf(rule))
    }

    /**
     * And the version gate from the other side. Below API 31 the permission is
     * not part of the platform: it cannot be granted, so a row demanding it
     * would carry a button whose dialog can never open.
     */
    @Test
    fun below_android_12_nothing_is_demanded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return

        assertTrue(
            "This device has no BLUETOOTH_CONNECT to grant, so nothing may be demanded.",
            BluetoothConnectionTriggerFactory(context).requirementsFor(emptyMap()).isEmpty(),
        )
    }
}
