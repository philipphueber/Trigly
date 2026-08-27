package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TextSuggestions
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
import app.phueber.trigly.core.normalizeVariableName
import app.phueber.trigly.core.variableNameProblem

/**
 * Keeps a notification's button so a rule can press it after the notification is
 * gone.
 *
 * **The job this exists for.** `notification_button` finds its target in the
 * live list, so it can only press a button still on screen. Some buttons are
 * only worth pressing later. Digital Wellbeing's Bedtime notification carries
 * "Turn off for now", and the moment somebody wants that pressed is the moment
 * they pick the phone up, by which time the notification has often been swiped
 * away. Capturing when it appears and pressing when it is wanted splits those
 * two moments into the two rules they really are.
 *
 * `CapturedButtonOutlivesDismissalTest` is why this works: a `PendingIntent` is
 * a token the system holds for the app that made it, and dismissing the
 * notification that carried it does not invalidate it.
 *
 * **What it cannot do, said here because the field's caveat is where a person
 * reads it.** A `PendingIntent` cannot be written down. It is not a URI or an
 * id, so it cannot go in a variable, in the database, or in an exported rule,
 * and it cannot be rebuilt after Trigly's process ends. A capture therefore
 * lives in memory until the process does not. The engine's foreground service is
 * what usually keeps that from mattering; `docs/todo.md`'s R1 covers the one
 * cause nothing here can fix.
 *
 * The name is the only new decision. It is a variable name rather than free
 * text, checked by [variableNameProblem], because a later action has to name the
 * same thing and a name with a `{` or a space in it invites a reference that
 * cannot be written.
 *
 * The button is chosen exactly as `notification_button` chooses it, through the
 * shared [resolveButtonTarget], so the two agree about what "the Turn off
 * button" means. Two of that function's outcomes are dead ends here rather than
 * fallbacks: a notification whose app draws its own buttons offers no token to
 * keep, and neither does a button that does not match. Pressing can fall back to
 * the screen for those; keeping cannot, because there is nothing to keep.
 */
class CaptureNotificationButtonAction(
    private val controller: NotificationController,
    private val name: String,
    private val buttonLabel: String?,
    private val semanticAction: Int?,
    private val targetPackage: String?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val kept = normalizeVariableName(name)
        variableNameProblem(kept)?.let { return ActionResult.Failure(it) }

        return when (
            val found = resolveButtonTarget(
                controller = controller,
                event = event,
                targetPackage = targetPackage,
                buttonLabel = buttonLabel,
                semanticAction = semanticAction,
                legacyIndex = null,
            )
        ) {
            is ButtonTarget.Ready ->
                when (
                    val result =
                        controller.captureActionButton(found.key, found.button.index, kept)
                ) {
                    is ActionResult.Success -> ActionResult.Success(
                        outputs = mapOf(OUTPUT_NAME to kept),
                    )

                    is ActionResult.Failure -> result
                }

            // Deliberately not the screen fallback `notification_button` uses
            // for these two. The screen can press a button the system does not
            // expose; it cannot hand over a token to keep.
            is ButtonTarget.NoExposedButtons -> ActionResult.Failure(
                "${found.reason} There is nothing to keep: a button Trigly can " +
                    "only reach through the screen cannot be pressed later."
            )

            is ButtonTarget.NoMatch -> ActionResult.Failure(found.reason)

            is ButtonTarget.Refused -> ActionResult.Failure(found.reason)
        }
    }

    companion object {
        const val TYPE = "capture_notification_button"
        const val CONFIG_NAME = "name"
        const val CONFIG_BUTTON = "button"
        const val CONFIG_BUTTON_SEMANTIC = "buttonSemantic"
        const val CONFIG_PACKAGE = "package"

        /** The output key the factory declares for the name it kept the button under. */
        const val OUTPUT_NAME = "captured"
    }
}

class CaptureNotificationButtonActionFactory(
    private val controller: NotificationController,
) : ActionFactory {
    override val type = CaptureNotificationButtonAction.TYPE

    override val displayName = "Keep a notification button"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        ConfigField.Text(
            key = CaptureNotificationButtonAction.CONFIG_NAME,
            label = "Keep it as",
            required = true,
            help = "A name for this button, so another rule can press it later. " +
                "A name has no spaces and no '|', '{' or '}'.",
        ),
        ConfigField.AppPackage(
            key = CaptureNotificationButtonAction.CONFIG_PACKAGE,
            label = "App",
            blankMeaning = "The notification that fired the rule",
            help = "Keeps a button from that app's newest notification. Leave it " +
                "unset to use the one the trigger reported.",
        ),
        // Required, and there is no "first button" default on purpose.
        // `chooseButton` returns nothing rather than guessing when no button is
        // named, because pressing some other button is worse than saying it
        // could not be found. Keeping the wrong button is worse still: the
        // mistake is only discovered later, when the wrong thing is pressed.
        ConfigField.Text(
            key = CaptureNotificationButtonAction.CONFIG_BUTTON,
            label = "Button",
            required = true,
            help = "The words on the button, such as 'Turn off for now'. Matched " +
                "without regard to case.",
        ),
    )

    /**
     * The name the button was kept under, so the rule that captures can hand it
     * to a later action without the name being written twice. Reported on
     * success only, because there is nothing kept when it fails.
     */
    override val variables = listOf(
        VariableSpec(
            key = CaptureNotificationButtonAction.OUTPUT_NAME,
            label = "Kept as",
            kind = VariableKind.TEXT,
            sample = "bedtime_off",
            help = "The name this action kept the button under.",
            alwaysPresent = false,
        ),
    )

    override val warning: String =
        "A kept button lives in memory only. It cannot be saved, exported or " +
            "put in a variable, and it is gone if Android stops Trigly, so keep " +
            "and press within the same day rather than across a restart. The " +
            "owning app can also withdraw the button, and pressing it then " +
            "reports that rather than doing nothing."

    override fun create(config: Map<String, String>): Action {
        val rawName = config[CaptureNotificationButtonAction.CONFIG_NAME].orEmpty()
        val problem = variableNameProblem(rawName)
        require(problem == null) { problem.orEmpty() }

        return CaptureNotificationButtonAction(
            controller = controller,
            name = normalizeVariableName(rawName),
            buttonLabel = config[CaptureNotificationButtonAction.CONFIG_BUTTON],
            semanticAction = config[CaptureNotificationButtonAction.CONFIG_BUTTON_SEMANTIC]
                ?.toIntOrNull(),
            targetPackage = config[CaptureNotificationButtonAction.CONFIG_PACKAGE]
                ?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * One name a rule keeps a button under, and the rule that keeps it.
 *
 * [ruleName] is half the answer, not decoration: two rules can keep two
 * different buttons under names that read alike, and "bedtime_off, kept by
 * Evening" is what makes the right one pickable a month later.
 */
data class DeclaredKeptButton(val name: String, val ruleName: String)

/**
 * Every name the rules in [rules] keep a button under, in rule order and
 * without repeats.
 *
 * **Why the editor needs this at all.** A kept button lives in memory, so
 * [NotificationController.capturedNames] answers "what is kept right now",
 * which is empty until the keeping rule has run and empty again after Trigly
 * restarts. That is exactly the state somebody is in while they build the
 * pressing rule. The names the rules *declare* are knowable at any time, and
 * together the two lists cover both "it is kept, here it is" and "nothing is
 * kept yet, but this is the name you chose".
 *
 * A name that is a `{{...}}` reference is skipped rather than offered. It is not
 * a name; it is an instruction to work one out at run time, and offering it as a
 * choice would put a reference to *this* rule's outputs into a rule that has
 * none of them.
 *
 * Pure, and in this file rather than in the UI, because the config key and the
 * action type are declared here. A copy of either in `:ui` would be a second
 * spelling that drifts the day a key is renamed.
 */
fun declaredKeptButtons(rules: List<Rule>): List<DeclaredKeptButton> {
    val seen = mutableSetOf<String>()
    val found = mutableListOf<DeclaredKeptButton>()
    for (rule in rules) {
        for (action in rule.actions) {
            if (action.type != CaptureNotificationButtonAction.TYPE) continue
            val raw = action.config[CaptureNotificationButtonAction.CONFIG_NAME].orEmpty()
            val name = normalizeVariableName(raw)
            if (name.isEmpty() || variableNameProblem(name) != null) continue
            if (seen.add(name)) found += DeclaredKeptButton(name, rule.name)
        }
    }
    return found
}

/**
 * Presses a button [CaptureNotificationButtonAction] kept earlier.
 *
 * Needs no notification access of its own and no notification on screen: the
 * token is already held by this process, which is the entire point of having
 * kept it. It does need Trigly not to have restarted in between, and the failure
 * says exactly that rather than blaming the button.
 */
class PressCapturedButtonAction(
    private val controller: NotificationController,
    private val name: String,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult =
        controller.pressCaptured(normalizeVariableName(name))

    companion object {
        const val TYPE = "press_captured_button"
        const val CONFIG_NAME = "name"
    }
}

class PressCapturedButtonActionFactory(
    private val controller: NotificationController,
) : ActionFactory {
    override val type = PressCapturedButtonAction.TYPE

    override val displayName = "Press a kept button"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        ConfigField.Text(
            key = PressCapturedButtonAction.CONFIG_NAME,
            label = "Kept as",
            required = true,
            // Takes a reference on purpose: the capturing action reports the
            // name it used as an output, so a rule can pass it along rather
            // than repeating a literal that could drift from the other rule's.
            substitution = Substitution.TEXT,
            // The reason this is not a picker of its own kind: the value can be
            // a reference, and it can name a button nothing has kept yet, so
            // typing has to stay possible. See [ConfigField.Text.suggests].
            suggests = TextSuggestions.KEPT_BUTTON_NAMES,
            help = "The name the other rule kept the button under, such as " +
                "bedtime_off. Use the chooser to see what is kept now and what " +
                "your rules keep, or type a variable such as " +
                "{{action.captured}}.",
        ),
    )

    override val warning: String =
        "This presses a button kept earlier by 'Keep a notification button'. If " +
            "Android has stopped Trigly since, nothing is kept and this action " +
            "says so."

    override fun create(config: Map<String, String>): Action = PressCapturedButtonAction(
        controller = controller,
        name = config[PressCapturedButtonAction.CONFIG_NAME].orEmpty(),
    )
}
