package app.phueber.trigly.triggers

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * `wifi_connected` and `mobile_data_connected` on a real device, with the
 * Wi-Fi radio switched off and on by `svc`.
 *
 * Instrumented, because both halves read `ConnectivityManager`, and a JVM stub
 * sees neither the permission nor which network the platform made the
 * default. The emulator has both a Wi-Fi network and a cellular network, so
 * switching the radio moves the default from one to the other. That is the
 * exact change a person means by "the phone went onto mobile data".
 *
 * Every test starts from Wi-Fi on and mobile data on, and puts that back
 * after, so a second run back to back starts from the same state as the first.
 */
@RunWith(AndroidJUnit4::class)
class NetworkConnectionOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }
    }

    private fun wifi(state: String): Trigger = WifiConnectedTriggerFactory(context)
        .create(mapOf(DefaultNetworkTrigger.CONFIG_STATE to state))

    private fun mobile(state: String): Trigger = MobileDataConnectedTriggerFactory(context)
        .create(mapOf(DefaultNetworkTrigger.CONFIG_STATE to state))

    /**
     * Waits for [answer] to reach [expected]. Joining a Wi-Fi network takes a
     * few seconds on the emulator, so the bound is 30 s. The assertion after
     * the wait is what fails, with the value it was stuck on.
     */
    private suspend fun settle(expected: Boolean?, answer: suspend () -> Boolean?): Boolean? {
        repeat(300) {
            val now = answer()
            if (now == expected) return now
            delay(100)
        }
        return answer()
    }

    @Before
    fun startOnWifi() = runBlocking {
        shell("svc data enable")
        shell("svc wifi enable")
        assertEquals(
            "precondition: the emulator must use its Wi-Fi network as default",
            true,
            settle(true) { wifi(DefaultNetworkTrigger.CONNECTED).currentlyHolds() },
        )
    }

    @After
    fun restoreWifi() = runBlocking {
        shell("svc data enable")
        shell("svc wifi enable")
        settle(true) { wifi(DefaultNetworkTrigger.CONNECTED).currentlyHolds() }
        Unit
    }

    /**
     * Both components, both directions, in both states. One instance of each
     * is asked across the change, which is how the engine asks.
     *
     * The mobile data half is the point of the design: on Wi-Fi the cellular
     * link stays up on the emulator, so a component that asked "is there a
     * cellular network" would say true here. This one must say false.
     */
    @Test
    fun the_level_follows_the_default_network() = runBlocking {
        val onWifi = wifi(DefaultNetworkTrigger.CONNECTED)
        val offWifi = wifi(DefaultNetworkTrigger.DISCONNECTED)
        val onMobile = mobile(DefaultNetworkTrigger.CONNECTED)
        val offMobile = mobile(DefaultNetworkTrigger.DISCONNECTED)

        assertEquals(false, offWifi.currentlyHolds())
        assertEquals(
            "on Wi-Fi the phone does not use mobile data, even with the link up",
            false,
            onMobile.currentlyHolds(),
        )
        assertEquals(true, offMobile.currentlyHolds())

        shell("svc wifi disable")

        assertEquals(
            "with the radio off, Wi-Fi is not the network in use",
            false,
            settle(false) { onWifi.currentlyHolds() },
        )
        assertEquals(true, offWifi.currentlyHolds())
        assertEquals(
            "and mobile data took over",
            true,
            settle(true) { onMobile.currentlyHolds() },
        )
        assertEquals(false, offMobile.currentlyHolds())
    }

    /**
     * The edge half: a rule that waits for "connects to Wi-Fi" and one that
     * waits for "stops using mobile data" both fire when the radio comes back.
     * Collection starts with the radio off, so each component has seen the
     * other state first, which is how a real rule sees it.
     */
    @Test
    fun turning_wifi_on_fires_both_edges() = runBlocking {
        shell("svc wifi disable")
        assertEquals(true, settle(true) { mobile(DefaultNetworkTrigger.CONNECTED).currentlyHolds() })

        val wifiCame = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(EDGE_TIMEOUT_MS) { wifi(DefaultNetworkTrigger.CONNECTED).events().first() }
        }
        val mobileWent = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(EDGE_TIMEOUT_MS) { mobile(DefaultNetworkTrigger.DISCONNECTED).events().first() }
        }
        // Let both callbacks register before the change they wait for.
        delay(REGISTER_MS)
        shell("svc wifi enable")

        val wifiEvent: TriggerEvent? = wifiCame.await()
        val mobileEvent: TriggerEvent? = mobileWent.await()
        assertNotNull("no 'connected to Wi-Fi' event within the timeout", wifiEvent)
        assertEquals(WifiConnectedTrigger.TYPE, wifiEvent!!.triggerType)
        assertEquals(DefaultNetworkTrigger.CONNECTED, wifiEvent.payload[DefaultNetworkTrigger.PAYLOAD_STATE])
        assertNotNull("no 'stopped using mobile data' event within the timeout", mobileEvent)
        assertEquals(MobileDataConnectedTrigger.TYPE, mobileEvent!!.triggerType)
        assertEquals(DefaultNetworkTrigger.DISCONNECTED, mobileEvent.payload[DefaultNetworkTrigger.PAYLOAD_STATE])
    }

    /**
     * Enabling a rule while the state already holds is not an event. The
     * platform calls `onCapabilitiesChanged` at once for the existing default
     * network; that call must be taken as the state at the start, or every
     * "connected to Wi-Fi" rule fires the moment it is switched on.
     */
    @Test
    fun registering_while_connected_does_not_fire() = runBlocking {
        val event = withTimeoutOrNull(QUIET_MS) {
            wifi(DefaultNetworkTrigger.CONNECTED).events().first()
        }
        assertNull("the rule fired only for being enabled", event)
    }

    private companion object {
        const val EDGE_TIMEOUT_MS = 30_000L
        const val REGISTER_MS = 1_000L
        const val QUIET_MS = 3_000L
    }
}
