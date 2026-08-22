package app.phueber.trigly.ui

/**
 * Where the app is. Two destinations do not justify a navigation library and the
 * dependency it brings; a sealed type plus `BackHandler` is the whole feature.
 */
sealed interface Screen {
    data object RuleList : Screen

    /** Null [ruleId] means a rule that does not exist yet. */
    data class RuleEditor(val ruleId: String?) : Screen
}
