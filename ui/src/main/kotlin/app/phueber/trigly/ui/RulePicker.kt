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
 */
@Composable
fun RulePickerDialog(
    title: String,
    clearLabel: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val rules = LocalRules.current

    ValuePickerDialog(
        title = title,
        searchLabel = "SEARCH",
        options = rules.map { rule ->
            PickerOption(
                value = rule.id,
                primary = rule.name,
                // Its current state, because "turn it on" reads very differently
                // next to a rule that is already on — and because two rules can
                // share a name, at which point this is the only way to tell them
                // apart before picking.
                secondary = if (rule.enabled) "on" else "off",
            )
        },
        clearLabel = clearLabel,
        placeholder = "No other rules yet. Save one first, then come back.",
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
            chosen != null -> if (chosen.enabled) "on" else "off"
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
