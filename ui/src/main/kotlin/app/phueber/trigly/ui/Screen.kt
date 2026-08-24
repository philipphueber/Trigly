package app.phueber.trigly.ui

/**
 * Where the app is. Two destinations do not justify a navigation library and the
 * dependency it brings; a sealed type plus `BackHandler` is the whole feature.
 */
sealed interface Screen {
    data object RuleList : Screen

    /** Null [ruleId] means a rule that does not exist yet. */
    data class RuleEditor(val ruleId: String?) : Screen

    /**
     * A diagnostic, not a feature: what Trigly can see on the notifications
     * currently posted. Reachable from the rule list because that is where
     * someone is when a notification rule is not doing what they expected.
     *
     * A third destination still does not justify a navigation library — the
     * sealed type and `BackHandler` carry it exactly as they did at two.
     */
    data object NotificationInspector : Screen
}
