package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
 *
 * [packageName] narrows the edge to one app; blank keeps the original "any app"
 * behaviour every saved rule from before this field existed already has. It is
 * also what the passive form needs — see [currentlyHolds] — because "is it
 * installed" is a question about a specific app, not about installs in general.
 */
class PackageChangeTrigger(
    context: Context,
    private val onInstalled: Boolean,
    private val packageName: String? = null,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE

    override val actions = listOf(
        if (onInstalled) Intent.ACTION_PACKAGE_ADDED else Intent.ACTION_PACKAGE_REMOVED
    )

    override val dataSchemes = listOf("package")

    override fun read(intent: Intent): Reading? {
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return null
        val changedPackage = intent.data?.schemeSpecificPart ?: return null
        if (packageName != null && changedPackage != packageName) return null

        return Reading(
            payload = mapOf(
                PAYLOAD_PACKAGE to changedPackage,
                PAYLOAD_STATE to if (onInstalled) INSTALLED else REMOVED,
            ),
        )
    }

    /**
     * The passive form: is [packageName] installed right now — or, when this is
     * configured for removal, is it currently absent? [onInstalled] is the
     * trigger's own direction, and the condition respects it rather than always
     * asking "is it installed": a rule built around "when this app is
     * uninstalled" wants its condition twin to ask the same thing, not its
     * opposite.
     *
     * Null with no [packageName] configured, because "is it installed" has no
     * "it" to ask about — the edge is happy watching every app on the device,
     * but a level needs a subject, and inventing one (or defaulting to true)
     * would be a guess dressed up as an answer.
     */
    override suspend fun currentlyHolds(): Boolean? {
        val target = packageName ?: return null

        val installed = try {
            appContext.packageManager.getPackageInfo(target, 0)
            true
        } catch (notFound: PackageManager.NameNotFoundException) {
            // Package visibility (API 30+) makes a genuinely absent package and
            // one that IS installed but simply not visible to this app raise the
            // exact same exception — deliberately, so that a lookup cannot be
            // used to fingerprint what else is on the device. Trigly declares no
            // <queries> entry for arbitrary packages and holds no
            // QUERY_ALL_PACKAGES, so below API 30 "not found" is trustworthy and
            // genuinely means absent, but at 30 and above this app cannot tell
            // "absent" apart from "hidden from me" — and answering false on a
            // guess is exactly the lie `Trigger.currentlyHolds` warns against.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return null
            false
        } catch (unexpected: Exception) {
            return null
        }

        return installed == onInstalled
    }

    companion object {
        const val TYPE = "app_install_state"
        const val CONFIG_STATE = "state"
        const val CONFIG_PACKAGE = "package"
        const val PAYLOAD_PACKAGE = "package"
        const val PAYLOAD_STATE = "state"
        const val INSTALLED = "installed"
        const val REMOVED = "removed"
    }
}

class PackageChangeTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = PackageChangeTrigger.TYPE

    override val displayName = "App installed or removed"
    override val category = Category.APPS

    override val configFields = listOf(
        stateChoice(
            label = "Fires when an app is",
            onValue = "installed", onLabel = "installed",
            offValue = "removed", offLabel = "uninstalled",
            help = "This trigger ignores app updates. It fires only on an install or a removal.",
        ),
        packageFilter(
            help = "Leave this field blank to watch every app. As a trigger, a blank field " +
                "means any app. As a condition, you must name an app. A condition needs " +
                "an app to check.",
        ),
    )

    override fun create(config: Map<String, String>): Trigger = PackageChangeTrigger(
        context = context,
        onInstalled = parseTarget(
            config = config,
            key = PackageChangeTrigger.CONFIG_STATE,
            onWord = PackageChangeTrigger.INSTALLED,
            offWord = PackageChangeTrigger.REMOVED,
        ),
        packageName = config[PackageChangeTrigger.CONFIG_PACKAGE]?.takeIf { it.isNotBlank() },
    )

    override val supportsCondition = true
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

    override val displayName = "Work profile"
    override val category = Category.APPS

    override val configFields = listOf(
        stateChoice("Fires when the work profile becomes", "available", "available", "unavailable", "paused"),
    )

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
