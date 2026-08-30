package app.phueber.trigly.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentTool
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.IntentTargetCheck
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerEvent

/**
 * Sends an intent the person defined in advance: an action string, an optional
 * target app and class, an optional data address, an optional MIME type, and
 * up to three string extras. See `docs/actions.md`'s "Firing a predefined
 * intent" for the whole design; this KDoc states the decisions at the point
 * each one is made, the same as `OpenUrlAction` and `HttpRequestAction` do for
 * theirs.
 *
 * **This is deliberately the arbitrary-intent primitive `open_url` and
 * `http_request` each refuse to become.** Every other action in this module
 * narrows what it will send: a scheme, a host, a duration. A rule's config
 * can arrive from an import or a shared recipe, and an unbounded primitive
 * there is a way to put an attacker-chosen command on a stranger's phone.
 * This action's whole reason to exist is to send an arbitrary command,
 * so narrowing the scheme is not available. What is available, and what this
 * does instead:
 *
 * - **A `file:` data address is refused outright**, in [execute], for the
 *   same reason `open_url` refuses every scheme but http and https: it can
 *   expose local content, and on a modern API level handing one to another
 *   app throws `FileUriExposedException` rather than merely failing quietly.
 *   Nothing else about the data address, action string, or extras is
 *   refused. An arbitrary `geo:`, `tel:`, `market:`, or an app's own custom
 *   scheme is exactly the point.
 * - **An intent that would reach Trigly's own package is refused outright**,
 *   in both [execute] and `checkIntentTarget`, through [decideIntentTargetCheck].
 *   Trigly has exported components of its own. `ShortcutTargetActivity`
 *   starts whatever rule has a shortcut trigger matching the id carried in
 *   the intent that starts it, and that id is not a secret: an imported
 *   rule's own `shortcutId` sits in the rule file in plain view, per
 *   `docs/architecture.md`'s "Importing a rule". Firing an intent back into
 *   `ShortcutTargetActivity` from inside a rule would let that rule trigger
 *   any other rule this way, a confused-deputy route an import should not be
 *   able to open. The refusal is unconditional rather than "only when
 *   the target component happens to be exported", because a non-exported
 *   receiver still answers its *own app's* broadcast: `AlarmWakeReceiver` is
 *   `exported="false"` and would still run if a rule matched its action
 *   string with no package set at all. See [decideIntentTargetCheck] for how
 *   this is detected even then.
 * - **No field for an `Intent` flag exists, at all.** `FLAG_GRANT_READ_URI_PERMISSION`
 *   and friends are never set by this action and never offered to configure,
 *   which is a stronger guarantee than refusing a particular flag value would
 *   be: there is no path through this schema that ever calls
 *   `Intent.addFlags` with anything other than `FLAG_ACTIVITY_NEW_TASK`, which
 *   [launchForRule] adds unconditionally and which grants nothing.
 * - **Typed extras (int, boolean, long) are deliberately left out.** Every
 *   extra this action sends is a string. Most of the commands the motivating
 *   capture concept describes take string parameters, and a typed extra can
 *   be added later (new config keys, no migration) without this having
 *   guessed wrong about which types mattered. Not built because it was
 *   forgotten; built this far and stopped on purpose.
 */
class FireIntentAction(
    private val context: Context,
    private val spec: FireIntentSpec,
    private val resolver: IntentResolver = SystemIntentResolver(context),
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        dataAddressRefusal(spec.dataUri)?.let { return ActionResult.Failure(it) }

        when (decideIntentTargetCheck(context.packageName, spec, resolver, packageVisibilityFilteringApplies())) {
            IntentTargetCheck.REFUSED_SELF_TARGET -> return ActionResult.Failure(
                "Trigly refuses to send an intent back to its own app."
            )

            // Earned only when Trigly could see every app that might have
            // answered and asked all of them. See decideIntentTargetCheck.
            // A broadcast in particular never confirms delivery on its own
            // (see sendBroadcastForRule), so this is the one honest way to
            // avoid reporting success for a broadcast nobody received.
            IntentTargetCheck.WOULD_NOT_RESOLVE -> return ActionResult.Failure(
                "No app on this device accepts this intent."
            )

            // HIDDEN_BY_VISIBILITY is a limit on Trigly's own PackageManager
            // queries, not on the system's ability to resolve a real send.
            // See docs/actions.md for what was verified about that
            // difference. So this proceeds exactly as WOULD_RESOLVE does: the
            // dispatch below is what actually finds out.
            IntentTargetCheck.WOULD_RESOLVE, IntentTargetCheck.HIDDEN_BY_VISIBILITY -> Unit
        }

        val intent = spec.toAndroidIntent()
        return when (spec.sendAs) {
            SendAs.ACTIVITY -> context.launchForRule(intent)
            SendAs.BROADCAST -> context.sendBroadcastForRule(intent)
            SendAs.SERVICE -> context.startServiceForRule(intent)
        }
    }

    companion object {
        const val TYPE = "fire_intent"
        const val CONFIG_ACTION = "action"
        const val CONFIG_PACKAGE = "package"
        const val CONFIG_CLASS_NAME = "className"
        const val CONFIG_DATA_URI = "dataUri"
        const val CONFIG_MIME_TYPE = "mimeType"

        /**
         * How many extra name/value pairs this action offers, a bounded
         * number of fixed fields rather than a delimited list. A delimited
         * "name=value per line" field would need its own escaping rules for a
         * value that contains `=` or a newline, and a variable substituted
         * into it could produce exactly that by accident. Three atomic pairs
         * have no such failure mode, at the cost of a hard limit; the
         * capture concept this action grew from shows one or two named
         * values per command, so three is generous rather than tight, and
         * raising it later needs no migration. An unused slot's keys are
         * simply absent from older config.
         */
        const val EXTRA_SLOT_COUNT = 3

        fun extraKeyField(index: Int): String = "extraKey$index"
        fun extraValueField(index: Int): String = "extraValue$index"
    }
}

/** Which platform call this action ends up making. See `docs/actions.md`. */
enum class SendAs(val configValue: String, val displayName: String) {
    ACTIVITY("activity", "Open (start an activity)"),
    BROADCAST("broadcast", "Send (a broadcast)"),
    SERVICE("service", "Start (a service)"),
    ;

    companion object {
        const val CONFIG_KEY = "sendAs"
        val DEFAULT = BROADCAST

        fun parseOrNull(raw: String?): SendAs? =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }

        fun parse(raw: String?): SendAs = parseOrNull(raw)
            ?: error("$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, was '$raw'")
    }
}

/**
 * A `fire_intent` config, parsed once. Deliberately plain data with no Android
 * type in it: [buildFireIntentSpec] and everything that reads one of these are
 * then unit-testable on the JVM, where a real `android.content.Intent` is a
 * stub that throws. [toAndroidIntent] is the one place this becomes real, and
 * it is not unit-tested, the same as every other action's `execute()` that
 * touches a real `Intent` (see `OpenUrlAction`, `ComposeEmailAction`).
 */
data class FireIntentSpec(
    val action: String,
    val targetPackage: String?,
    val className: String?,
    val dataUri: String?,
    val mimeType: String?,
    val extras: List<Pair<String, String>>,
    val sendAs: SendAs,
)

/**
 * Parses a `fire_intent` config into a [FireIntentSpec], or throws for a
 * config `create()` must refuse outright (see `ConfigField.unfilled` for the
 * separate, gentler case of a field nobody has typed into yet).
 *
 * Two cross-field rules live here rather than only in a factory's `create()`,
 * because [FireIntentActionFactory.checkIntentTarget] needs the exact same
 * parse before it can ask anything:
 *
 * - **A class name needs an app to go with it.** `ComponentName` is a
 *   package plus a class; a class name with no package cannot become one.
 * - **Starting a service needs an explicit app and class, unconditionally.**
 *   Android has required an explicit intent for `startService()` since API
 *   21 and throws `IllegalArgumentException` for an implicit one (see
 *   [startServiceForRule]). Refusing this here, before a rule ever starts,
 *   reports it as the config problem it is rather than as a failed run.
 */
fun buildFireIntentSpec(config: Map<String, String>): FireIntentSpec {
    val action = config[FireIntentAction.CONFIG_ACTION]?.takeIf { it.isNotBlank() }
        ?: error("${FireIntentAction.TYPE} needs '${FireIntentAction.CONFIG_ACTION}'")
    val sendAs = SendAs.parse(config[SendAs.CONFIG_KEY])
    val targetPackage = config[FireIntentAction.CONFIG_PACKAGE]?.takeIf { it.isNotBlank() }
    val className = config[FireIntentAction.CONFIG_CLASS_NAME]?.takeIf { it.isNotBlank() }

    require(className == null || targetPackage != null) {
        "'${FireIntentAction.CONFIG_CLASS_NAME}' needs '${FireIntentAction.CONFIG_PACKAGE}' set too"
    }
    require(sendAs != SendAs.SERVICE || (targetPackage != null && className != null)) {
        "starting a service needs an exact app and class name; Android refuses an implicit service start"
    }

    val extras = (1..FireIntentAction.EXTRA_SLOT_COUNT).mapNotNull { index ->
        val key = config[FireIntentAction.extraKeyField(index)]?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        key to (config[FireIntentAction.extraValueField(index)] ?: "")
    }

    return FireIntentSpec(
        action = action,
        targetPackage = targetPackage,
        className = className,
        dataUri = config[FireIntentAction.CONFIG_DATA_URI]?.takeIf { it.isNotBlank() },
        mimeType = config[FireIntentAction.CONFIG_MIME_TYPE]?.takeIf { it.isNotBlank() },
        extras = extras,
        sendAs = sendAs,
    )
}

/**
 * Why a data address is refused, or null when it is not.
 *
 * The one scheme this action refuses, for the same reason `open_url` refuses
 * every scheme but http and https: `file:` can expose local content, and
 * handing one to another app throws `FileUriExposedException` on a modern API
 * level rather than merely failing. Everything else (`content:`, `geo:`,
 * `tel:`, `market:`, an app's own custom scheme) is exactly the point of this
 * action and is not narrowed at all.
 */
fun dataAddressRefusal(dataUri: String?): String? {
    if (dataUri.isNullOrBlank()) return null
    val scheme = dataUri.trim().substringBefore(':', missingDelimiterValue = "").lowercase()
    return if (scheme == "file") {
        "This action refuses a file: address. Handing a local file to another app " +
            "this way can expose it and crashes on a modern phone. Use a content: " +
            "address instead."
    } else {
        null
    }
}

/**
 * The `PackageManager` surface [decideIntentTargetCheck] needs, kept behind a
 * seam for the same reason `NotificationController` is one: a JVM unit test
 * cannot call a real `PackageManager` method, only read one of its constants.
 * [SystemIntentResolver] is the real implementation; a fake in
 * `FireIntentActionTest` stands in for it so the four-answer decision itself
 * is unit-tested, not merely exercised on a device.
 */
interface IntentResolver {
    /** Whether Trigly's `PackageManager` can see anything about this package at all. */
    fun isPackageVisible(packageName: String): Boolean

    /**
     * Package names of every component that would answer [spec] as
     * [FireIntentSpec.sendAs], including Trigly's own if it matches. Empty
     * means either "nothing does" or "Trigly cannot see whatever does".
     * [decideIntentTargetCheck] is what tells those two apart.
     */
    fun resolvingPackages(spec: FireIntentSpec): Set<String>
}

/** The real [IntentResolver], backed by this device's `PackageManager`. */
class SystemIntentResolver(private val context: Context) : IntentResolver {

    override fun isPackageVisible(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (notFound: PackageManager.NameNotFoundException) {
        false
    }

    override fun resolvingPackages(spec: FireIntentSpec): Set<String> {
        val intent = spec.toAndroidIntent()
        val packageManager = context.packageManager
        return when (spec.sendAs) {
            SendAs.ACTIVITY -> packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }

            // ResolveInfo still carries a broadcast receiver's manifest entry
            // as `activityInfo`, a legacy shape the framework never renamed.
            SendAs.BROADCAST -> packageManager.queryBroadcastReceivers(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }

            SendAs.SERVICE -> packageManager.queryIntentServices(intent, 0)
                .mapNotNull { it.serviceInfo?.packageName }
        }.toSet()
    }
}

/**
 * Whether Android's package visibility rules are in force on this device.
 *
 * Filtering was introduced in API 30 and applies to every app whose own
 * `targetSdk` is 30 or above (Trigly's is 35), so the only device where it
 * does not apply at all is one running an older platform. `PackageManager`
 * itself has no "is filtering active" query; this is the platform fact that
 * answers it, not a guess.
 */
internal fun packageVisibilityFilteringApplies(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/**
 * The four-answer decision behind both `checkIntentTarget` and [FireIntentAction.execute]'s
 * own pre-flight refusal. Pure and Android-free but for the [resolver] seam, so
 * it is fully unit-tested in `FireIntentActionTest` rather than only exercised
 * by hand on a device.
 *
 * **Why an empty [IntentResolver.resolvingPackages] is not automatically
 * [IntentTargetCheck.WOULD_NOT_RESOLVE].** On API 30+, `queryIntentActivities`,
 * `queryBroadcastReceivers` and `queryIntentServices` (and `getPackageInfo`,
 * which [IntentResolver.isPackageVisible] calls) all answer *nothing* for an
 * app Trigly has not declared in `<queries>`, whether or not that app is
 * actually installed and would actually accept the intent. `open_app` already
 * documents the same fact for `getLaunchIntentForPackage`; this is the same
 * platform behaviour; verified against the framework's documented contract for
 * `<queries>` rather than freshly measured on a device in this change, since a
 * device-level check belongs to the connected suite this change does not run.
 * So an empty result names [IntentTargetCheck.HIDDEN_BY_VISIBILITY], not
 * [IntentTargetCheck.WOULD_NOT_RESOLVE], whenever [filteringApplies] and Trigly
 * cannot otherwise prove the app does not exist. It can prove that only when an
 * explicit package was named and [IntentResolver.isPackageVisible] can see it.
 * A visible package's own manifest is not filtered further, so a query that
 * still comes back empty against it is a real, earned "no".
 *
 * **Why this is not the same question a real send answers**, and the
 * important asymmetry `execute` relies on: naming a specific package or
 * component and *starting* it is resolved by the system's own full view of
 * installed apps, not by Trigly's filtered view. Package visibility restricts
 * what `PackageManager` will tell an app that *asks*; it does not restrict
 * what the system will do for an app that already knows the exact target and
 * *starts* it. So [IntentTargetCheck.HIDDEN_BY_VISIBILITY] is not a reason to
 * refuse sending. Only [IntentTargetCheck.REFUSED_SELF_TARGET] and an *earned*
 * [IntentTargetCheck.WOULD_NOT_RESOLVE] are.
 *
 * **Self-targeting is checked two ways**, because a person can reach Trigly's
 * own package two ways: by naming it explicitly, which [FireIntentSpec.targetPackage]
 * catches directly, and by leaving the target implicit and letting the action
 * string alone match one of Trigly's own manifest components: a real route,
 * since a non-exported receiver still answers its own app's broadcast. The
 * second is caught because [resolver] is asked regardless of whether an
 * explicit package was set, and [ownPackageName] is checked against whatever
 * it finds.
 */
fun decideIntentTargetCheck(
    ownPackageName: String,
    spec: FireIntentSpec,
    resolver: IntentResolver,
    filteringApplies: Boolean,
): IntentTargetCheck {
    if (spec.targetPackage == ownPackageName) return IntentTargetCheck.REFUSED_SELF_TARGET

    val matches = resolver.resolvingPackages(spec)
    if (ownPackageName in matches) return IntentTargetCheck.REFUSED_SELF_TARGET
    if (matches.isNotEmpty()) return IntentTargetCheck.WOULD_RESOLVE

    val explicitPackage = spec.targetPackage
    return when {
        explicitPackage != null && resolver.isPackageVisible(explicitPackage) -> IntentTargetCheck.WOULD_NOT_RESOLVE
        filteringApplies -> IntentTargetCheck.HIDDEN_BY_VISIBILITY
        else -> IntentTargetCheck.WOULD_NOT_RESOLVE
    }
}

/**
 * Builds the real `Intent` [spec] describes. Not unit-tested, the same as
 * every other action's construction of a real `Intent` (see [FireIntentSpec]).
 */
internal fun FireIntentSpec.toAndroidIntent(): Intent {
    val intent = Intent(action)

    if (targetPackage != null && className != null) {
        intent.component = ComponentName(targetPackage, className)
    } else if (targetPackage != null) {
        intent.setPackage(targetPackage)
    }

    val uri = dataUri?.toUri()
    when {
        uri != null && mimeType != null -> intent.setDataAndType(uri, mimeType)
        uri != null -> intent.data = uri
        mimeType != null -> intent.type = mimeType
    }

    // `extras` here is this FireIntentSpec's own property, deliberately not
    // read through `intent.apply { ... }`: that receiver shadows the name
    // with `Intent.getExtras()`, a `Bundle?` with no `forEach` at all, which
    // is a compile error rather than a silent wrong answer. That is worth a
    // comment since the fix (not using `apply`) is not obvious from the diff alone.
    for ((key, value) in extras) {
        intent.putExtra(key, value)
    }

    return intent
}

/**
 * Only an activity target needs the overlay permission (see
 * [ACTIVITY_START_REQUIREMENTS]). A broadcast is never blocked by the
 * background-activity-start ban at all, and a service start relies on
 * Trigly's own foreground service instead, which needs no permission a
 * settings screen could grant; see [startServiceForRule]. A top-level
 * function, not inlined into [FireIntentActionFactory.requirementsFor],
 * so `FireIntentActionTest` can pin this decision without building a
 * `Context`.
 *
 * `SendAs.parseOrNull` rather than [SendAs.parse]: this is asked
 * speculatively, including while a rule is still being edited, and must not
 * throw for a config that is not finished yet.
 */
internal fun requirementsForSendAs(config: Map<String, String>): List<ComponentRequirement> =
    if (SendAs.parseOrNull(config[SendAs.CONFIG_KEY]) == SendAs.ACTIVITY) {
        ACTIVITY_START_REQUIREMENTS
    } else {
        emptyList()
    }

class FireIntentActionFactory(private val context: Context) : ActionFactory {
    override val type = FireIntentAction.TYPE

    override val displayName = "Fire an intent"
    override val category = ActionCategory.ADVANCED

    override val configFields: List<ConfigField> = buildList {
        add(
            ConfigField.Choice(
                key = SendAs.CONFIG_KEY,
                label = "Send as",
                options = SendAs.entries.map { ConfigField.Option(it.configValue, it.displayName) },
                default = SendAs.DEFAULT.configValue,
                help = "An activity opens on screen and needs \"Display over other apps\" " +
                    "to work while Trigly is in the background, the same as every other " +
                    "action that opens something. A broadcast and a service do not need " +
                    "that permission, but neither confirms as reliably that something " +
                    "actually happened; see the warning above.",
            )
        )
        add(
            ConfigField.Text(
                key = FireIntentAction.CONFIG_ACTION,
                label = "Intent action",
                required = true,
                placeholder = "android.intent.action.SEND",
                help = "The exact action string the other app declared it accepts.",
            )
        )
        add(
            ConfigField.AppPackage(
                key = FireIntentAction.CONFIG_PACKAGE,
                label = "App",
                required = false,
                blankMeaning = "Let Android choose which app answers. A broadcast with " +
                    "no app chosen here may not reach a receiver declared in another " +
                    "app's manifest; Android has limited that since Android 8.",
            )
        )
        add(
            ConfigField.Text(
                key = FireIntentAction.CONFIG_CLASS_NAME,
                label = "Class name",
                required = false,
                placeholder = "com.example.app.CommandReceiver",
                help = "The exact class inside the app to start. Needs an app chosen " +
                    "above. Required when \"Send as\" is a service: Android refuses to " +
                    "start a service without one.",
            )
        )
        add(
            ConfigField.Text(
                key = FireIntentAction.CONFIG_DATA_URI,
                label = "Data address",
                required = false,
                placeholder = "geo:0,0?q=coffee",
                help = "Any address the other app reads, except file:, which this " +
                    "action refuses. $URL_SUBSTITUTION_HELP",
                substitution = Substitution.URL,
            )
        )
        add(
            ConfigField.Text(
                key = FireIntentAction.CONFIG_MIME_TYPE,
                label = "MIME type",
                required = false,
                blankMeaning = "Worked out from the data address, the same as Android does.",
            )
        )
        for (index in 1..FireIntentAction.EXTRA_SLOT_COUNT) {
            add(
                ConfigField.Text(
                    key = FireIntentAction.extraKeyField(index),
                    label = "Extra $index name",
                    required = false,
                    help = if (index == 1) {
                        "A named value the other app reads out of the intent, for " +
                            "example \"message\"."
                    } else {
                        null
                    },
                )
            )
            add(
                ConfigField.Text(
                    key = FireIntentAction.extraValueField(index),
                    label = "Extra $index value",
                    required = false,
                    substitution = Substitution.TEXT,
                )
            )
        }
    }

    override fun requirementsFor(config: Map<String, String>): List<ComponentRequirement> =
        requirementsForSendAs(config)

    override val warning: String =
        "An activity target is blocked in the background exactly like every " +
            "other action that opens something. $BACKGROUND_START_WARNING A " +
            "broadcast never confirms that anything received it: reporting " +
            "success only means Trigly could not prove otherwise. A service " +
            "needs the exact app and class, because Android refuses an " +
            "implicit service start."

    /**
     * Not [ComponentTool.Test]. Running an arbitrary intent to see if it
     * works is exactly the guess this whole action exists to remove. See
     * [checkIntentTarget] instead, and its own KDoc for why it never sends
     * anything. Losing the generic Test button here is the visible choice
     * `ActionFactory.toolsFor` asks an override to make, not an oversight.
     */
    override fun toolsFor(config: Map<String, String>): List<ComponentTool> =
        listOf(ComponentTool.CheckIntentTarget)

    /**
     * The Test seam's entry point. Call `Registry.checkIntentTarget(ComponentSpec(type, config))`
     * rather than this directly. That is what lets the editor ask the
     * question without knowing this factory's name. Pass [config] with every
     * `{{...}}` reference already resolved to a sample value, the same way
     * the generic Test flow resolves one before calling `create()`; this has
     * no other way to see what a variable would hold.
     *
     * Returns null, meaning "nothing to check yet", when [config] cannot even
     * build a [FireIntentSpec] for the chosen send mode: a required field
     * still blank, or "service" chosen with no class name yet. That is a
     * different silence from every other action's null: there, it means "this
     * action has no such question"; here it means "not enough of an answer
     * has been typed in to ask it". Both read the same way to the editor:
     * draw no result.
     */
    override fun checkIntentTarget(config: Map<String, String>): IntentTargetCheck? {
        val spec = runCatching { buildFireIntentSpec(config) }.getOrNull() ?: return null
        return decideIntentTargetCheck(
            ownPackageName = context.packageName,
            spec = spec,
            resolver = SystemIntentResolver(context),
            filteringApplies = packageVisibilityFilteringApplies(),
        )
    }

    override fun create(config: Map<String, String>): Action =
        FireIntentAction(context, buildFireIntentSpec(config))
}
