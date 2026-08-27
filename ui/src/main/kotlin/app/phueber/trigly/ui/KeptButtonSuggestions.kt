package app.phueber.trigly.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.phueber.trigly.actions.DeclaredKeptButton
import app.phueber.trigly.core.TextSuggestions

/**
 * The names a "Press a kept button" action can be pointed at, and how to
 * re-read them.
 *
 * A function rather than a list, for the reason [LocalActiveNotifications] is
 * one: half of the answer is what is kept in this process right now, and that
 * changes while the editor is open. The other half comes from the rules and is
 * captured in the closure.
 */
val LocalKeptButtons = staticCompositionLocalOf<() -> List<KeptButton>> {
    { emptyList() }
}

/**
 * One offered name, and where it comes from.
 *
 * [detail] is not decoration. "Kept now" and "kept by the rule Evening" are
 * different facts, and the difference is the one a person needs: the first says
 * pressing will work this minute, the second says it will work once that rule
 * has run. A list that showed only names would hide that.
 *
 * It is also what the row leads with, because a picker row uppercases its
 * headline and [name] must survive as typed: a variable name is compared
 * exactly, so a row reading `BEDTIME_OFF` would name something that does not
 * exist.
 */
data class KeptButton(val name: String, val detail: String)

/**
 * The two sources merged, live ones first.
 *
 * Live first because a name that is kept right now is the one somebody is most
 * likely testing, and because it is the only half that proves anything. A name
 * that appears in both is listed once, as the live one: "kept now" is the
 * stronger statement, and saying it twice would read as two different buttons.
 */
fun keptButtons(
    keptNow: List<String>,
    declared: List<DeclaredKeptButton>,
): List<KeptButton> {
    val live = keptNow.map { KeptButton(it, "Kept now") }
    val liveNames = keptNow.toSet()
    val fromRules = declared
        .filterNot { it.name in liveNames }
        .map { KeptButton(it.name, "Kept by the rule ${it.ruleName}") }
    return live + fromRules
}

/**
 * How the editor draws one [TextSuggestions] source: what the button beside the
 * box says, and what the dialog says once it is open.
 *
 * The wording lives here rather than on the enum because it is presentation, and
 * `:core` declares the source without knowing what a dialog is. One source
 * today, so this is a `when` with one arm; a second source adds an arm and
 * nothing else.
 */
fun suggestionWording(source: TextSuggestions): SuggestionWording = when (source) {
    TextSuggestions.KEPT_BUTTON_NAMES -> SuggestionWording(
        buttonLabel = "Choose a kept button",
        title = "Kept buttons",
        searchLabel = "SEARCH NAMES",
        // Says both halves of why the list can be empty, because both are
        // ordinary rather than faulty, and the fix is different for each.
        placeholder = "No name to offer yet. Either no rule keeps a button " +
            "under a name, or the rule that keeps one has not run since Trigly " +
            "last started. You can also type the name yourself.",
    )
}

/** See [suggestionWording]. */
data class SuggestionWording(
    val buttonLabel: String,
    val title: String,
    val searchLabel: String,
    val placeholder: String,
)
