package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** One of the user's rules, reduced to what a picker needs to show. */
data class RuleChoice(val id: String, val name: String, val enabled: Boolean)

/**
 * The user's rules, for the one field that points at them.
 *
 * A composition local for the same reason [LocalInstalledApps] and
 * [LocalActiveNotifications] are: the editor screen is stateless and takes its
 * data from above, and threading a rule list through every component block to
 * reach one field would be worse than a local that says what it is.
 */
val LocalRules = staticCompositionLocalOf { emptyList<RuleChoice>() }

/**
 * The id of the rule currently open in the editor, or null while it is still an
 * unsaved draft with no id yet.
 *
 * A composition local for the same reason [LocalRules] is: the picker lives
 * several composables below the editor, and this is the one piece of editor
 * state a rule-reference field needs that has nothing to do with rendering a
 * list of rules. Provided by [RuleEditorScreen] itself around its own content,
 * not by the activity — nothing above the editor has an opinion on which rule is
 * open, so [LocalRules] (populated once, for every screen) and this local (reset
 * every time the editor opens) are provided at different levels on purpose.
 */
val LocalCurrentRuleId = staticCompositionLocalOf<String?> { null }

/**
 * Picks one of the user's own rules.
 *
 * Stores the **id** and shows the *name*, which is the same trade
 * [AppPackageField] makes and for a sharper reason: a rule can be renamed, and a
 * reference that broke when someone tidied a title would fail silently — the rule
 * would simply stop being switched, with nothing on screen to say why.
 *
 * No typed-entry escape hatch, unlike the app picker. A rule id is a UUID; there
 * is no plausible value someone could type that the list would not already
 * contain, and offering a text box would only invite a typo that resolves to
 * nothing.
 *
 * The rule currently open in the editor is offered here like any other, **not**
 * filtered out. That is deliberate, not an oversight: `SetRuleEnabledAction`'s own
 * field help says a rule pointing at itself is "how a rule runs once and then
 * turns itself off," and its factory's warning exists precisely to talk someone
 * through ordering a self-disable last. Excluding it here would silently delete
 * that feature. What was actually missing was honesty about which row it is —
 * see the marker on its [PickerOption.secondary] below — since two rules can
 * share a name and picking the open one should never look like picking a
 * different one that happens to match it.
 */
@Composable
fun RulePickerDialog(
    title: String,
    clearLabel: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val rules = LocalRules.current
    val currentRuleId = LocalCurrentRuleId.current

    ValuePickerDialog(
        title = title,
        searchLabel = "SEARCH",
        options = rules.map { rule ->
            PickerOption(
                value = rule.id,
                primary = rule.name,
                secondary = buildString {
                    // Its current state, uppercase to match `BlockToggle`
                    // elsewhere — the same word, same casing, wherever it's
                    // read — because "turn it on" reads very differently next
                    // to a rule that is already on, and because two rules can
                    // share a name, at which point this is the only way to
                    // tell them apart before picking.
                    append(if (rule.enabled) "ON" else "OFF")
                    // The one row this list cannot let read as ambiguous: see
                    // the KDoc above for why it's offered at all rather than
                    // excluded.
                    if (rule.id == currentRuleId) append(" · the rule you're editing")
                },
            )
        },
        clearLabel = clearLabel,
        // Only ever shown when zero rules exist anywhere — the current rule,
        // if it has been saved before, is always in `rules` itself, so this
        // list is never empty for an existing rule. True in both the "nothing
        // saved yet" case and the (impossible in practice) "only this rule"
        // case, unlike the old copy's "other," which promised an exclusion
        // that never happens.
        placeholder = "No rules yet. Save one first, then come back.",
        onPick = onPick,
        onDismiss = onDismiss,
    )
}

/**
 * The field itself.
 *
 * A rule that has been deleted since this action was set up shows its stored id
 * rather than a blank — the id is useless to read, and that is the point: an
 * action pointing at nothing should look wrong. The action reports the same thing
 * at fire time.
 */
@Composable
fun RuleRefField(
    label: String,
    ruleId: String?,
    blankMeaning: String?,
    onPick: (String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val rules = LocalRules.current
    val currentRuleId = LocalCurrentRuleId.current
    val chosen = ruleId?.let { id -> rules.firstOrNull { it.id == id } }

    PickerValueBox(
        label = label,
        primary = when {
            chosen != null -> chosen.name
            ruleId != null -> "Rule not found"
            blankMeaning != null -> blankMeaning
            else -> "Choose a rule"
        },
        secondary = when {
            // Uppercase to match `BlockToggle`'s "ON"/"OFF" everywhere else —
            // this is a state word, not a raw identifier, so it doesn't get
            // the lowercase treatment `secondary` otherwise reserves for
            // those. Marked as the open rule for the same reason the picker
            // marks it — see `RulePickerDialog`'s KDoc.
            chosen != null -> buildString {
                append(if (chosen.enabled) "ON" else "OFF")
                if (chosen.id == currentRuleId) append(" · the rule you're editing")
            }
            // The stored id, so a dangling reference is visible rather than
            // merely empty.
            ruleId != null -> ruleId
            else -> null
        },
        onClick = { picking = true },
    )

    if (picking) {
        RulePickerDialog(
            title = label.removeSuffix(" *"),
            clearLabel = blankMeaning,
            onPick = { picked ->
                picking = false
                onPick(picked)
            },
            onDismiss = { picking = false },
        )
    }
}
