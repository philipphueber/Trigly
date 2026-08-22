package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/**
 * Fires when an app is installed or uninstalled.
 *
 * The package broadcasts carry their subject as a `package:` URI, and are simply
 * not delivered unless the filter declares that scheme — hence [dataSchemes].
 *
 * An *upgrade* arrives as a remove followed by an add, both flagged
 * `EXTRA_REPLACING`. Users do not think of updating an app as uninstalling it,
 * so those are filtered out.
 *
 * On API 30+ package visibility rules restrict what can be *looked up* about
 * other apps, but the broadcast itself still arrives with its package name.
 */
class PackageChangeTrigger(
    context: Context,
    private val onInstalled: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE

    override val actions = listOf(
        if (onInstalled) Intent.ACTION_PACKAGE_ADDED else Intent.ACTION_PACKAGE_REMOVED
    )

    override val dataSchemes = listOf("package")

    override fun read(intent: Intent): Reading? {
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return null
        val packageName = intent.data?.schemeSpecificPart ?: return null

        return Reading(
            payload = mapOf(
                PAYLOAD_PACKAGE to packageName,
                PAYLOAD_STATE to if (onInstalled) INSTALLED else REMOVED,
            ),
        )
    }

    companion object {
        const val TYPE = "app_install_state"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_PACKAGE = "package"
        const val PAYLOAD_STATE = "state"
        const val INSTALLED = "installed"
        const val REMOVED = "removed"
    }
}

class PackageChangeTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = PackageChangeTrigger.TYPE

    override fun create(config: Map<String, String>): Trigger = PackageChangeTrigger(
        context = context,
        onInstalled = parseTarget(
            config = config,
            key = PackageChangeTrigger.CONFIG_STATE,
            onWord = PackageChangeTrigger.INSTALLED,
            offWord = PackageChangeTrigger.REMOVED,
        ),
    )
}

/**
 * Fires when a work profile becomes available or unavailable — which is what
 * "work profile paused" surfaces as. Delivered to the primary user; no
 * permission.
 */
class WorkProfileTrigger(
    context: Context,
    private val onAvailable: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE

    override val actions = listOf(
        if (onAvailable) {
            Intent.ACTION_MANAGED_PROFILE_AVAILABLE
        } else {
            Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE
        }
    )

    override fun read(intent: Intent) = Reading(
        payload = mapOf(PAYLOAD_STATE to if (onAvailable) AVAILABLE else UNAVAILABLE),
    )

    companion object {
        const val TYPE = "work_profile"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val AVAILABLE = "available"
        const val UNAVAILABLE = "unavailable"
    }
}

class WorkProfileTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = WorkProfileTrigger.TYPE

    override fun create(config: Map<String, String>): Trigger = WorkProfileTrigger(
        context = context,
        onAvailable = parseTarget(
            config = config,
            key = WorkProfileTrigger.CONFIG_STATE,
            onWord = WorkProfileTrigger.AVAILABLE,
            offWord = WorkProfileTrigger.UNAVAILABLE,
        ),
    )
}
