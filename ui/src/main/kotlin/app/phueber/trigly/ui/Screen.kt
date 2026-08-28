package app.phueber.trigly.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Where the app is. Two destinations do not justify a navigation library and the
 * dependency it brings; a sealed type plus one `BackHandler` is the whole
 * feature.
 *
 * The notification inspector used to be a third. It is now opened as a dialog
 * over the editor, from the `Inspect` button on the block of whichever component
 * reads notifications — which is where someone actually is when a notification
 * rule is not doing what they expected, and which cannot cost them a
 * half-written rule the way navigating away does. Nothing navigates to it, so it
 * is not a destination.
 */
sealed interface Screen {
    data object RuleList : Screen

    /** Null [ruleId] means a rule that does not exist yet. */
    data class RuleEditor(val ruleId: String?) : Screen

    /**
     * Every app-scope variable, reached from the overflow beside "New rule":
     * both are about the whole rule set rather than one rule. See
     * `RulesScreen`'s `MoreMenu` for why it lives there and not in the header.
     */
    data object SavedValues : Screen

    /**
     * Today, one switch: whether Android's backup may carry the rules, the
     * saved values, and any webhook token. Reached from the same overflow as
     * [SavedValues], for the reason `MoreMenu`'s own KDoc gives. It is
     * already the home for "not about one rule", and a screen there has room
     * for a second setting later without a second menu having to exist first.
     */
    data object Settings : Screen
}

/**
 * What a back press means, as a value rather than as a branch inside the
 * handler. Null is "nothing left to go back to" — the app closes.
 *
 * Extracted because this is the part with a real decision in it, and because the
 * decision is the one thing here a JVM test can check. The rule it encodes:
 * **the rule list is the bottom of the stack.** Back from the list leaves the
 * app; the editor, the saved-values screen and the settings screen all go
 * back to it.
 */
fun backTarget(screen: Screen): Screen? = when (screen) {
    Screen.RuleList -> null
    is Screen.RuleEditor -> Screen.RuleList
    Screen.SavedValues -> Screen.RuleList
    Screen.Settings -> Screen.RuleList
}

private const val LIST_TAG = "list"
private const val EDITOR_TAG = "editor"
private const val SAVED_VALUES_TAG = "saved_values"
private const val SETTINGS_TAG = "settings"

/**
 * Saves the destination across a configuration change.
 *
 * Without it, rotating the phone inside the editor drops the user back on the
 * rule list. That is worth fixing on its own, but it is also load-bearing for
 * "a new rule starts empty": the editor's ViewModel outlives the screen, so the
 * draft is discarded when the editor is genuinely *left*, and a rotation must
 * therefore not look like leaving.
 *
 * A list of strings rather than a `Parcelable`: the whole type is a tag plus a
 * nullable id. The empty string stands in for "no id", because a `listSaver`
 * entry may not be null.
 */
val ScreenSaver: Saver<Screen, Any> = listSaver(
    save = { screen ->
        when (screen) {
            Screen.RuleList -> listOf(LIST_TAG)
            is Screen.RuleEditor -> listOf(EDITOR_TAG, screen.ruleId.orEmpty())
            Screen.SavedValues -> listOf(SAVED_VALUES_TAG)
            Screen.Settings -> listOf(SETTINGS_TAG)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            EDITOR_TAG -> Screen.RuleEditor(saved.getOrNull(1)?.takeIf { it.isNotEmpty() })
            SAVED_VALUES_TAG -> Screen.SavedValues
            SETTINGS_TAG -> Screen.Settings
            else -> Screen.RuleList
        }
    },
)
