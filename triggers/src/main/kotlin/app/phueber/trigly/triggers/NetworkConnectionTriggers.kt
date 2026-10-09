package app.phueber.trigly.triggers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Base for a trigger that asks "does the phone use this kind of network for
 * its internet connection now?".
 *
 * **The question is about the default network, not about any network of the
 * transport.** Android keeps the cellular link up while Wi-Fi is connected on
 * most devices ("mobile data always active" is on by default), so a callback
 * on every network with `TRANSPORT_CELLULAR` says "connected to mobile data"
 * nearly all the time, also at home on Wi-Fi. That is the opposite of what a
 * person means by "on mobile data". The default network is the one apps use,
 * and it changes from cellular to Wi-Fi at the moment a person would say the
 * phone "went onto Wi-Fi". One rule answers both components, so they cannot
 * disagree with each other: at most one of them holds at a time.
 *
 * A VPN does not hide the answer. The platform adds the transports of the
 * networks under a VPN to the capabilities of the VPN network, so a phone on
 * a VPN over Wi-Fi still has `TRANSPORT_WIFI` on its default network.
 *
 * `CONNECTIVITY_ACTION` is not used. It is deprecated, and from API 26 it is
 * not delivered to a receiver registered by a background app. The network
 * callback has no such limit, and it needs only `ACCESS_NETWORK_STATE`, which
 * is normal protection level and is declared in this module's manifest.
 *
 * Not a [BroadcastTrigger], because there is no broadcast. It uses the same
 * [StateTracker], so a repeated reading is collapsed the same way.
 */
abstract class DefaultNetworkTrigger(
    private val context: Context,
    private val onConnected: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    /** The [TriggerEvent.triggerType] to stamp on emitted events. */
    protected abstract val eventType: String

    /** One of the `NetworkCapabilities.TRANSPORT_*` constants. */
    protected abstract val transport: Int

    final override fun events(): Flow<TriggerEvent> = callbackFlow {
        val manager = connectivity()
        val tracker = StateTracker(suppressInitialState = true)
        val lock = Any()
        var current: Network? = null

        fun report(connected: Boolean) {
            val key = if (connected) CONNECTED else DISCONNECTED
            val accepted = synchronized(lock) { tracker.accept(key) }
            if (accepted && connected == onConnected) {
                trySend(TriggerEvent(eventType, now(), mapOf(PAYLOAD_STATE to key)))
            }
        }

        // Seed the tracker with the state at registration. Without it, a phone
        // with no network at all gets no first callback, so the first real
        // change ("Wi-Fi connected") would be taken as the initial state and
        // dropped. With the seed, the callback that the platform sends at once
        // for an existing default network repeats the seed and is collapsed.
        report(isConnected(manager))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                synchronized(lock) { current = network }
                report(caps.hasTransport(transport))
            }

            override fun onLost(network: Network) {
                // Only the loss of the network we report about is a change. A
                // late onLost for a network that is no longer the default must
                // not undo the reading of the new one.
                val wasCurrent = synchronized(lock) {
                    (current == network).also { if (it) current = null }
                }
                if (wasCurrent) report(false)
            }
        }
        manager.registerDefaultNetworkCallback(callback)

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }

    /**
     * Read the same thing the callback reports, from the manager directly.
     *
     * No default network, or a default network that went away between the two
     * calls, is "not connected", and that is a true answer, not an unknown
     * one. A manager that is missing or refuses (no `ACCESS_NETWORK_STATE`)
     * throws, and the `runCatching` turns that into null: "cannot answer" is
     * not "not connected".
     */
    override suspend fun currentlyHolds(): Boolean? = runCatching {
        isConnected(connectivity())
    }.getOrNull()?.let { it == onConnected }

    private fun connectivity(): ConnectivityManager =
        checkNotNull(context.getSystemService(ConnectivityManager::class.java)) {
            "no ConnectivityManager on this device"
        }

    private fun isConnected(manager: ConnectivityManager): Boolean {
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(transport)
    }

    companion object {
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val CONNECTED = "connected"
        const val DISCONNECTED = "disconnected"
    }
}

/**
 * Fires when the phone starts or stops using a Wi-Fi network for its internet
 * connection.
 *
 * Not the same as `wifi_state`, which is the radio. The radio can be on with no
 * network connected. And a Wi-Fi network that is connected but that the
 * platform does not use as default (for example, it has no internet and
 * mobile data took over) does not count. See [DefaultNetworkTrigger].
 */
class WifiConnectedTrigger(
    context: Context,
    onConnected: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : DefaultNetworkTrigger(context, onConnected, now) {

    override val eventType = TYPE
    override val transport = NetworkCapabilities.TRANSPORT_WIFI

    companion object {
        const val TYPE = "wifi_connected"
    }
}

/**
 * Fires when the phone starts or stops using mobile data for its internet
 * connection.
 *
 * "Using" is the point. The cellular link often stays up under a Wi-Fi
 * connection, and this component then says "not connected", because no app
 * sends its data over it. See [DefaultNetworkTrigger].
 */
class MobileDataConnectedTrigger(
    context: Context,
    onConnected: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : DefaultNetworkTrigger(context, onConnected, now) {

    override val eventType = TYPE
    override val transport = NetworkCapabilities.TRANSPORT_CELLULAR

    companion object {
        const val TYPE = "mobile_data_connected"
    }
}

class WifiConnectedTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = WifiConnectedTrigger.TYPE

    override val displayName = "Wi-Fi connection"
    override val category = Category.RADIOS

    override val configFields = listOf(
        stateChoice(
            label = "Fires when the phone",
            onValue = DefaultNetworkTrigger.CONNECTED, onLabel = "connects to Wi-Fi",
            offValue = DefaultNetworkTrigger.DISCONNECTED, offLabel = "disconnects from Wi-Fi",
            help = "Counts a Wi-Fi network only when the phone uses it for the " +
                "internet. A Wi-Fi network without internet does not count.",
        ),
    )

    override val supportsCondition = true

    override val variables = networkStateVariables

    override fun create(config: Map<String, String>): Trigger = WifiConnectedTrigger(
        context = context,
        onConnected = parseConnected(config),
    )
}

class MobileDataConnectedTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = MobileDataConnectedTrigger.TYPE

    override val displayName = "Mobile data connection"
    override val category = Category.RADIOS

    override val configFields = listOf(
        stateChoice(
            label = "Fires when the phone",
            onValue = DefaultNetworkTrigger.CONNECTED, onLabel = "starts to use mobile data",
            offValue = DefaultNetworkTrigger.DISCONNECTED, offLabel = "stops using mobile data",
            help = "Counts mobile data only when the phone uses it for the " +
                "internet. While Wi-Fi is connected, this is 'not connected'.",
        ),
    )

    override val supportsCondition = true

    override val variables = networkStateVariables

    override fun create(config: Map<String, String>): Trigger = MobileDataConnectedTrigger(
        context = context,
        onConnected = parseConnected(config),
    )
}

private val networkStateVariables = listOf(
    VariableSpec(
        key = DefaultNetworkTrigger.PAYLOAD_STATE,
        label = "State",
        kind = VariableKind.STATE,
        sample = DefaultNetworkTrigger.CONNECTED,
        help = "One of '${DefaultNetworkTrigger.CONNECTED}' or " +
            "'${DefaultNetworkTrigger.DISCONNECTED}'.",
    ),
)

private fun parseConnected(config: Map<String, String>): Boolean = parseTarget(
    config = config,
    key = DefaultNetworkTrigger.CONFIG_STATE,
    onWord = DefaultNetworkTrigger.CONNECTED,
    offWord = DefaultNetworkTrigger.DISCONNECTED,
)
