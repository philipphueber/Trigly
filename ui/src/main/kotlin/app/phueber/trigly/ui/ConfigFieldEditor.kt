package app.phueber.trigly.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ScopedVariable
import app.phueber.trigly.core.SampleLookup
import app.phueber.trigly.core.Substituted
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TextMatchMode
import app.phueber.trigly.core.parseTemplate
import app.phueber.trigly.core.regexErrorOrNull
import app.phueber.trigly.core.substitute

/**
 * Renders one declared [ConfigField] and reports edits back out.
 *
 * This is what the whole schema exists for: 47 component types share these seven
 * widgets instead of 47 hand-written forms. Adding a component means declaring
 * its fields; nothing here changes.
 *
 * Values are strings throughout, matching how config is stored. Numeric fields
 * restrict the keyboard but do not reject input — the factory decides what is
 * valid at save time, and blocking keystrokes here would fight the user
 * mid-number ("-" and "." are both invalid prefixes of valid input).
 */
@Composable
fun ConfigFieldEditor(
    field: ConfigField,
    value: String?,
    onValueChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The companion values for field kinds that own more than one config key.
     *
     * A map rather than a single extra value, because "one companion" turned out
     * to be an accident of the first kind that needed one. A match mode, a
     * minute, a longitude — and now a notification's package and a button's
     * meaning, which is three at once. Keyed by config key so a field reads and
     * writes the ones it declared and cannot reach another field's.
     *
     * Defaulted, so every single-key kind and every caller that renders one is
     * unaffected.
     */
    companions: Map<String, String?> = emptyMap(),
    onCompanionChange: (key: String, value: String?) -> Unit = { _, _ -> },
    /**
     * What this rule can offer a `{{variable}}` reference. See
     * `docs/variables.md` section 12. Read only by [ConfigField.Text], and only
     * once its own [ConfigField.Text.substitution] says it accepts one at all;
     * every other kind ignores this.
     *
     * Defaulted to empty, the same reason [companions] is: a caller that has no
     * variables to offer, or predates this feature, draws exactly what it always
     * has, with no picker and no preview.
     */
    availableVariables: List<ScopedVariable> = emptyList(),
    /**
     * How a substituted value is escaped for the preview shown under the field.
     *
     * This is *not* [ConfigField.substitution] restated: the declaration says
     * whether a field accepts a reference at all, which is what decides whether
     * a picker is drawn. This is the engine's own answer for the field as it is
     * *configured right now*: `ComponentFactory.substitutionsFor`. That matters
     * because `http_request`'s body escapes differently once its content type is JSON,
     * and a preview using the declared encoding instead would show the wrong
     * thing for exactly the field this whole feature most needs to get right.
     * Defaults to the declaration, for a caller that has not looked this up.
     */
    previewEncoding: Substitution = field.substitution,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (field) {
            is ConfigField.Choice -> ChoiceField(field, value, onValueChange)
            is ConfigField.Flag -> FlagField(field, value, onValueChange)

            is ConfigField.Text -> SubstitutableTextField(
                field = field,
                value = value,
                availableVariables = availableVariables,
                previewEncoding = previewEncoding,
                onValueChange = onValueChange,
            )

            // The one field kind that is not a text box: picking from the
            // installed apps is the whole reason AppPackage is its own kind.
            // Draws nothing at all. The value is minted when the component is
            // added and there is no decision here for a person to make; a
            // read-only box showing a UUID would be noise that looks like a
            // setting.
            is ConfigField.GeneratedId -> Unit

            is ConfigField.AppPackage -> AppPackageField(
                label = fieldLabel(field.label, field.required),
                packageName = value?.ifEmpty { null },
                blankMeaning = field.blankMeaning,
                onPick = onValueChange,
            )

            // Same reasoning again, with the identifier being one of our own:
            // a rule id is a UUID, so the list is the only way in.
            is ConfigField.RuleRef -> RuleRefField(
                label = fieldLabel(field.label, field.required),
                ruleId = value?.ifEmpty { null },
                blankMeaning = field.blankMeaning,
                onPick = onValueChange,
            )

            // Same reasoning as AppPackage: stored like text, unreachable by
            // typing. A URI nobody can compose, and a MAC address nobody knows.
            is ConfigField.SoundUri -> SoundUriField(
                label = fieldLabel(field.label, field.required),
                uri = value?.ifEmpty { null },
                blankMeaning = field.blankMeaning,
                onPick = onValueChange,
            )

            is ConfigField.BluetoothAddress -> BluetoothAddressField(
                label = fieldLabel(field.label, field.required),
                address = value?.ifEmpty { null },
                blankMeaning = field.blankMeaning,
                onPick = onValueChange,
            )

            // Same reasoning as AppPackage, for a shortcut's icon: picked off a
            // curated grid rather than typed, because nobody should have to leave
            // the app to find their keyboard's emoji tab.
            is ConfigField.Emoji -> EmojiField(
                label = fieldLabel(field.label, field.required),
                emoji = value?.ifEmpty { null },
                blankMeaning = field.blankMeaning,
                onPick = onValueChange,
            )

            // Three kinds that keep the stored value and change the control: a
            // duration in ms, a moment in epoch ms, and a time of day split
            // across two keys. See TimeFields.kt.
            is ConfigField.Duration -> DurationField(
                field = field,
                value = value,
                onValueChange = onValueChange,
            )

            is ConfigField.Timestamp -> TimestampField(
                field = field,
                value = value,
                onValueChange = onValueChange,
            )

            is ConfigField.TimeOfDay -> TimeOfDayField(
                field = field,
                hour = value,
                minute = companions[field.minuteKey],
                onChange = { hour, minute ->
                    onValueChange(hour)
                    onCompanionChange(field.minuteKey, minute)
                },
            )

            is ConfigField.Coordinates -> CoordinatesField(
                field = field,
                latitude = value,
                longitude = companions[field.longitudeKey],
                onChange = { lat, lon ->
                    onValueChange(lat)
                    onCompanionChange(field.longitudeKey, lon)
                },
            )

            is ConfigField.NotificationButton -> NotificationButtonPicker(
                field = field,
                label = value,
                semantic = companions[field.semanticKey],
                packageName = companions[field.packageKey],
                onPick = { label, semantic, pkg ->
                    onValueChange(label)
                    onCompanionChange(field.semanticKey, semantic)
                    onCompanionChange(field.packageKey, pkg)
                },
            )

            is ConfigField.Number -> TextField(
                label = numericLabel(field.label, field.required, field.unit),
                value = value,
                placeholder = field.default?.toString(),
                keyboard = KeyboardType.Number,
                multiline = false,
                onValueChange = onValueChange,
            )

            is ConfigField.Decimal -> TextField(
                label = numericLabel(field.label, field.required, field.unit),
                value = value,
                placeholder = field.default?.toString(),
                // Decimal keyboard so latitudes can carry a sign and a point.
                keyboard = KeyboardType.Decimal,
                multiline = false,
                onValueChange = onValueChange,
            )

            is ConfigField.TextPattern -> TextPatternField(
                field = field,
                value = value,
                mode = TextMatchMode.parse(companions[field.modeKey]),
                onValueChange = onValueChange,
                onModeChange = { onCompanionChange(field.modeKey, it) },
            )

            is ConfigField.Slider -> SliderField(
                field = field,
                value = value,
                onValueChange = onValueChange,
            )
        }

        // The blank-means-something hint only helps while the field is empty.
        // The picker kinds are absent on purpose: a picker shows the blank
        // meaning as the field's own value ("Any app", "Use the tone above"), so
        // repeating it below would say the same thing twice.
        val blankHint = when (field) {
            is ConfigField.Text -> field.blankMeaning
            is ConfigField.TextPattern -> field.blankMeaning
            else -> null
        }
        if (value.isNullOrEmpty() && blankHint != null) {
            Hint(blankHint)
        }

        // Where the caveats that used to live in KDoc reach the user.
        field.help?.let { Hint(it) }
    }
}

/**
 * A text filter and the mode that decides how it matches.
 *
 * The mode toggle sits *in* the field's label row rather than below it, because
 * it changes what the box above means — a control that reads "CONTAINS / REGEX"
 * next to the words "Title or text contains" answers the question the label
 * raises.
 *
 * Two things only happen in regex mode. The pattern is syntax-coloured, and it
 * is checked on every keystroke with the error shown underneath. The check is
 * the same `Regex(...)` the factory will run at save time, so what is shown here
 * is exactly what would otherwise be a failure at Save — moved to the moment the
 * mistake is made, when the cursor is still next to it.
 */
@Composable
private fun TextPatternField(
    field: ConfigField.TextPattern,
    value: String?,
    mode: TextMatchMode,
    onValueChange: (String?) -> Unit,
    onModeChange: (String) -> Unit,
) {
    val isRegex = mode == TextMatchMode.REGEX
    val highlight = rememberRegexHighlight(enabled = isRegex)
    val error = if (isRegex) regexErrorOrNull(value.orEmpty()) else null
    var testing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = fieldLabel(field.label, field.required),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Next to the mode toggle, because it answers the question the toggle
            // raises: a valid regex and a *correct* one are different claims, and
            // only one of them the editor could check until now.
            BlockTextButton("Test") { testing = true }
            TextMatchMode.entries.forEach { option ->
                BlockToggleChip(
                    text = option.configValue,
                    selected = option == mode,
                    onClick = { onModeChange(option.configValue) },
                )
            }
        }

        if (testing) {
            PatternTesterDialog(
                label = field.label,
                pattern = value,
                mode = mode,
                onPatternChange = onValueChange,
                onDismiss = { testing = false },
            )
        }

        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = { onValueChange(it.ifEmpty { null }) },
            singleLine = true,
            isError = error != null,
            visualTransformation = highlight,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                // A pattern is not prose: capitalising it or correcting it is
                // actively harmful, and both are on by default.
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TextField(
    label: String,
    value: String?,
    placeholder: String?,
    keyboard: KeyboardType,
    multiline: Boolean,
    onValueChange: (String?) -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        // Empty is reported as null so the key is dropped rather than stored as
        // "", which several components would read as a real (empty) filter.
        onValueChange = { onValueChange(it.ifEmpty { null }) },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A [ConfigField.Text], with the picker and the preview `docs/variables.md`
 * section 12 asks for, drawn only when the field's own declaration accepts a
 * reference at all.
 *
 * A separate composable from the plain [TextField] above rather than a branch
 * inside it, because a field that does not accept a variable has no use for
 * either the cursor tracking below or [availableVariables], and should draw
 * exactly what it always has.
 *
 * The box itself keeps the same shape [TextField] draws, label floated inside
 * it and all: that is what a component's existing screen tests already find
 * by that label text and type into. "Insert variable" and the preview sit
 * underneath, alongside where the blank-meaning hint already lands, rather
 * than disturbing the box a field's tests already know.
 */
@Composable
private fun SubstitutableTextField(
    field: ConfigField.Text,
    value: String?,
    availableVariables: List<ScopedVariable>,
    previewEncoding: Substitution,
    onValueChange: (String?) -> Unit,
) {
    val acceptsVariables = field.substitution != Substitution.NONE

    // Tracked locally so a picked reference is inserted at the cursor rather
    // than always appended. [value] stays the source of truth for the config;
    // this only remembers where in it the cursor was.
    var fieldValue by remember(field.key) { mutableStateOf(TextFieldValue(value.orEmpty())) }
    if (fieldValue.text != value.orEmpty()) {
        // The text moved for a reason other than typing here: this rule just
        // loaded, or a fresh component was chosen for this block. There is no
        // cursor from before worth keeping in that case.
        fieldValue = TextFieldValue(value.orEmpty())
    }

    var picking by remember(field.key) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                // Empty is reported as null, the same convention the plain
                // [TextField] above follows, so the key is dropped rather than
                // stored as "".
                onValueChange(it.text.ifEmpty { null })
            },
            label = {
                Text(
                    text = fieldLabel(field.label, field.required),
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            placeholder = field.placeholder?.let { { Text(it) } },
            singleLine = !field.multiline,
            minLines = if (field.multiline) 2 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )

        if (acceptsVariables) {
            BlockTextButton("Insert variable", modifier = Modifier.padding(top = 4.dp)) {
                picking = true
            }
            VariablePreview(
                value = fieldValue.text,
                available = availableVariables,
                encoding = previewEncoding,
            )
        }
    }

    if (picking) {
        VariablePickerDialog(
            available = availableVariables,
            onPick = { picked ->
                val insertion = picked.reference
                val selection = fieldValue.selection
                val newText =
                    fieldValue.text.replaceRange(selection.start, selection.end, insertion)
                fieldValue = TextFieldValue(newText, TextRange(selection.start + insertion.length))
                onValueChange(newText.ifEmpty { null })
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The field's own value with every reference resolved against a sample, or the
 * reason it could not be. Shown only once the value names a reference at all:
 * a plain string has nothing to preview.
 *
 * Marked plainly as a sample, never as the value itself. `docs/variables.md`
 * section 2 names the failure this exists to close: a preview that looked like
 * real data would teach the wrong lesson about what the field actually sends.
 */
@Composable
private fun VariablePreview(
    value: String,
    available: List<ScopedVariable>,
    encoding: Substitution,
) {
    val template = parseTemplate(value)
    if (!template.hasReferences) return

    when (val resolved = template.substitute(SampleLookup(available), encoding)) {
        is Substituted.Ok -> Text(
            text = "Sample: ${resolved.value}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        is Substituted.Failed -> Text(
            text = resolved.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ChoiceField(
    field: ConfigField.Choice,
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = field.options.firstOrNull { it.value == (value ?: field.default) }

    Column {
        Text(
            text = fieldLabel(field.label, field.required),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BlockOutlineButton(
            text = selected?.label ?: "Choose…",
            onClick = { expanded = true },
            modifier = Modifier.padding(top = 6.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    onClick = {
                        onValueChange(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FlagField(
    field: ConfigField.Flag,
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlockToggle(
            checked = value?.toBooleanStrictOrNull() ?: field.default,
            onCheckedChange = { onValueChange(it.toString()) },
        )
        Text(
            text = field.label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
internal fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * A slider with its current value spelled out beside the label.
 *
 * The number is shown because a slider alone answers "roughly where" and not
 * "what exactly", and someone reproducing a rule on another phone needs the
 * digits. It sits in the label row rather than under the track so the reading
 * does not move as the thumb does.
 *
 * A missing or unparseable stored value falls back to the declared default
 * rather than to the minimum. Zero volume is a legitimate setting, so treating
 * "no value yet" as 0 would silently turn a new alert silent — the field's own
 * default is the only honest starting position.
 */
@Composable
private fun SliderField(
    field: ConfigField.Slider,
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    val current = (value?.toLongOrNull() ?: field.default)
        .coerceIn(field.min, field.max)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = fieldLabel(field.label, required = false),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (field.unit == null) "$current" else "$current ${field.unit}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extra.accent,
            )
        }

        BlockSlider(
            value = current.toInt(),
            min = field.min.toInt(),
            max = field.max.toInt(),
            onValueChange = { onValueChange(it.toString()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Labels are uppercased here rather than at each call site.
 *
 * They are tags in this design, not sentences — and doing it in one place means a
 * new field kind cannot arrive in the wrong case. Help text and warnings are
 * deliberately *not* uppercased: they are prose, and prose in capitals is
 * unreadable.
 */
internal fun fieldLabel(label: String, required: Boolean) =
    (if (required) "$label *" else label).uppercase()

private fun numericLabel(label: String, required: Boolean, unit: String?) =
    fieldLabel(if (unit == null) label else "$label ($unit)", required)
