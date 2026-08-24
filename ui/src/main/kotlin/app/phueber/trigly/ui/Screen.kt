package app.phueber.trigly.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Where the app is. Two destinations do not justify a navigation library and the
 * dependency it brings; a sealed type plus one `BackHandler` is the whole feature.
 */
sealed interface Screen {
    data object RuleList : Screen

    /** Null [ruleId] means a rule that does not exist yet. */
    data class RuleEditor(val ruleId: String?) : Screen
}

/**
 * What a back press means, as a value rather than as a branch inside the
 * handler. Null is "nothing left to go back to" — the app closes.
 *
 * Extracted because this is the part with a real decision in it, and because the
 * decision is the one thing here a JVM test can check. The rule it encodes:
 * **the rule list is the bottom of the stack.** Back from the list leaves the
 * app; it never re-opens a rule.
 */
fun backTarget(screen: Screen): Screen? = when (screen) {
    Screen.RuleList -> null
    is Screen.RuleEditor -> Screen.RuleList
}

private const val LIST_TAG = "list"
private const val EDITOR_TAG = "editor"

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
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            EDITOR_TAG -> Screen.RuleEditor(saved.getOrNull(1)?.takeIf { it.isNotEmpty() })
            else -> Screen.RuleList
        }
    },
)
