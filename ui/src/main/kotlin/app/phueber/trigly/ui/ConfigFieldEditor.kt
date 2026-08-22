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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ConfigField

/**
 * Renders one declared [ConfigField] and reports edits back out.
 *
 * This is what the whole schema exists for: 46 component types share these six
 * widgets instead of 46 hand-written forms. Adding a component means declaring
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
        }

        // The blank-means-something hint only helps while the field is empty.
        // AppPackage is absent on purpose: its picker shows the blank meaning as
        // the field's own value ("Any app"), so repeating it below would say the
        // same thing twice.
        val blankHint = when (field) {
            is ConfigField.Text -> field.blankMeaning
            else -> null
        }
        if (value.isNullOrEmpty() && blankHint != null) {
            Hint(blankHint)
        }

        // Where the caveats that used to live in KDoc reach the user.
        field.help?.let { Hint(it) }
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
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * Labels are uppercased here rather than at each call site.
 *
 * They are tags in this design, not sentences — and doing it in one place means a
 * new field kind cannot arrive in the wrong case. Help text and warnings are
 * deliberately *not* uppercased: they are prose, and prose in capitals is
 * unreadable.
 */
private fun fieldLabel(label: String, required: Boolean) =
    (if (required) "$label *" else label).uppercase()

private fun numericLabel(label: String, required: Boolean, unit: String?) =
    fieldLabel(if (unit == null) label else "$label ($unit)", required)
