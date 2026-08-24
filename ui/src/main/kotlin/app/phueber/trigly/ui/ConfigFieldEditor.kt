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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TextMatchMode
import app.phueber.trigly.core.regexErrorOrNull

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
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (field) {
            is ConfigField.Choice -> ChoiceField(field, value, onValueChange)
            is ConfigField.Flag -> FlagField(field, value, onValueChange)

            is ConfigField.Text -> TextField(
                label = fieldLabel(field.label, field.required),
                value = value,
                placeholder = field.placeholder,
                keyboard = KeyboardType.Text,
                multiline = field.multiline,
                onValueChange = onValueChange,
            )

            // The one field kind that is not a text box: picking from the
            // installed apps is the whole reason AppPackage is its own kind.
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
        // The three picker kinds are absent on purpose: a picker shows the blank
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
