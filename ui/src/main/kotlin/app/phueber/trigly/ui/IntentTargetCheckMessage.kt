package app.phueber.trigly.ui

import app.phueber.trigly.core.IntentTargetCheck

/**
 * The message the rule editor shows for one [IntentTargetCheck] answer.
 *
 * Pure and Android-free on purpose, so this exact mapping is unit-tested on
 * the JVM instead of only being exercised by pressing a button on a device.
 * `RuleEditorViewModel.checkIntentTarget` is the one caller; this is pulled
 * out of it so the four messages can be read, and tested, on their own. See
 * `docs/actions.md`'s "Firing a predefined intent" for the whole design.
 *
 * **The four answers have to read as four different things, and two of them
 * are the pair worth the most care.** [IntentTargetCheck.WOULD_NOT_RESOLVE]
 * and [IntentTargetCheck.HIDDEN_BY_VISIBILITY] can both follow an empty
 * `PackageManager` query, but they are not the same finding. The first means
 * Trigly asked every app that could possibly answer and none did, so the
 * rule will not fire. The second means Android's package visibility rules
 * (API 30+) hid the answer from Trigly's own query, so the rule may work
 * perfectly and nobody, Trigly included, can tell from here. A person who
 * reads the second as the first deletes a rule that was correct, which is
 * why each message says outright whether it is reporting a fault or
 * declining to answer one.
 *
 * [IntentTargetCheck.REFUSED_SELF_TARGET] is not a platform finding at all;
 * it is Trigly's own refusal, so its message names the fix rather than
 * reporting what `PackageManager` said.
 */
internal fun describeIntentTargetCheck(check: IntentTargetCheck): String = when (check) {
    IntentTargetCheck.WOULD_RESOLVE ->
        "An app on this device would answer this intent."

    IntentTargetCheck.WOULD_NOT_RESOLVE ->
        "No app on this device would answer this intent. This rule will not work. " +
            "Check the action, the app, and the class name."

    IntentTargetCheck.HIDDEN_BY_VISIBILITY ->
        "Trigly cannot see which app would answer this intent. Android hides most " +
            "apps from other apps. This is not a failure report. The rule may still work."

    IntentTargetCheck.REFUSED_SELF_TARGET ->
        "Trigly refuses to send this intent to its own app. Change the app or the " +
            "class name so this intent points at a different app."
}
