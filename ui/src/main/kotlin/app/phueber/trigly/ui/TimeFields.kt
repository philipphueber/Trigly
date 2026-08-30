package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import java.util.Calendar
import java.util.Locale

/**
 * The controls that replaced numbers standing in for time.
 *
 * A duration was a box of milliseconds, a moment was a box of *epoch*
 * milliseconds, and a time of day was two boxes. In each case the stored value
 * was right and the control was the engine's view of it rather than a person's,
 * so all three keep the storage exactly as it was: nothing saved, exported or
 * imported changes.
 *
 * Built from the same vocabulary as the rest of the editor ([PickerValueBox] for
 * a value that opens a dialog, [BlockToggleChip] for a small exclusive choice)
 * rather than new lookalikes, which is what keeps a border width from drifting
 * between one field kind and the next.
 */

// --- duration -------------------------------------------------------------

/**
 * A number and a unit, over a value stored in milliseconds.
 *
 * The unit is derived on load by [DurationUnit.bestFor] rather than stored,
 * because it is presentation and not data: 1800000 comes back as "30 min"
 * whatever was originally typed, which is the number the user was thinking of.
 * A second key holding the unit could disagree with the first, and there would be
 * no way to say which was right.
 *
 * Switching unit re-expresses what is stored and never edits it. So tapping
 * "min" on a 30-second value shows 0.5, not 30.
 */
@Composable
fun DurationField(
    field: ConfigField.Duration,
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    val stored = value?.toLongOrNull()
    var unit by remember(field.key) {
        mutableStateOf(stored?.let(DurationUnit::bestFor) ?: field.preferred)
    }

    val shown = when {
        stored == null -> ""
        stored % unit.millis == 0L -> (stored / unit.millis).toString()
        else -> (stored.toDouble() / unit.millis).toString()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown,
            onValueChange = { typed ->
                if (typed.isEmpty()) {
                    onValueChange(null)
                } else {
                    // Decimal keyboards show a comma as the decimal key on many
                    // non-English locales, but toDoubleOrNull() only ever accepts a
                    // period. Left alone, that comma keystroke is silently dropped.
                    // The field is controlled, so a rejected value never even shows
                    // in the box. Normalize before parsing; do not "simplify" this
                    // away, it is the whole fix.
                    typed.replace(',', '.').toDoubleOrNull()?.let { amount ->
                        val millis = (amount * unit.millis).toLong()
                        onValueChange(
                            (field.maxMillis?.let(millis::coerceAtMost) ?: millis).toString()
                        )
                    }
                }
            },
            label = {
                Text(
                    text = fieldLabel(field.label, field.required),
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.padding(top = 6.dp)) {
            DurationUnit.entries.forEach { candidate ->
                BlockToggleChip(
                    text = candidate.label,
                    selected = candidate == unit,
                    onClick = { unit = candidate },
                )
            }
        }

        field.maxMillis?.let { Hint("At most ${describeDuration(it)}.") }
    }
}

/** A duration as a person would say it. */
fun describeDuration(millis: Long): String {
    val unit = DurationUnit.bestFor(millis)
    return "${millis / unit.millis} ${unit.label}"
}

// --- a moment in time -----------------------------------------------------

/**
 * A date control and a time control over one epoch-millisecond value.
 *
 * Two dialogs rather than one combined picker, because Material offers them
 * separately and because they answer separate questions: the common edit is "same
 * day, an hour later", which should not mean re-picking the date.
 *
 * Nothing is stored until a pick is confirmed. An editor that defaulted to "now"
 * would quietly save a rule meaning "the moment I opened this", which is the
 * class of silent wrongness this whole sweep is about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimestampField(
    field: ConfigField.Timestamp,
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    val stored = value?.toLongOrNull()
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    val calendar = remember(stored) {
        Calendar.getInstance().apply { stored?.let { timeInMillis = it } }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            PickerValueBox(
                label = fieldLabel(field.label, field.required),
                primary = if (stored != null) {
                    formatDate(calendar)
                } else {
                    field.blankMeaning ?: "Pick a date"
                },
                secondary = null,
                onClick = { pickingDate = true },
                modifier = Modifier.weight(1f),
            )
            PickerValueBox(
                // Through fieldLabel like the date box above: date and time are one
                // stored moment, so marking only one half required would lie about
                // the other.
                label = fieldLabel("Time", field.required),
                primary = if (stored != null) formatTime(calendar) else "Pick a time",
                secondary = null,
                onClick = { pickingTime = true },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = stored)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                BlockTextButton("Set") {
                    state.selectedDateMillis?.let { picked ->
                        onValueChange(withDateOf(picked, stored).toString())
                    }
                    pickingDate = false
                }
            },
            dismissButton = {
                Row {
                    // Blankness is a setting here (the calendar app picks the
                    // time itself), so the picker must not be a one-way door.
                    if (stored != null) {
                        BlockTextButton("Clear") {
                            onValueChange(null)
                            pickingDate = false
                        }
                    }
                    BlockTextButton("Cancel") { pickingDate = false }
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (pickingTime) {
        val state = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = {
                Text(
                    text = field.label.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = { TimePicker(state = state) },
            confirmButton = {
                BlockTextButton("Set") {
                    onValueChange(withTimeOf(state.hour, state.minute, stored).toString())
                    pickingTime = false
                }
            },
            dismissButton = { BlockTextButton("Cancel") { pickingTime = false } },
        )
    }
}

/**
 * The picked day, keeping whatever time of day was already stored.
 *
 * The date picker reports midnight UTC for the chosen day, so the day is read out
 * as year/month/day and re-applied locally. Taking the returned instant directly
 * would shift the day by one either side of UTC.
 */
private fun withDateOf(pickedMillis: Long, stored: Long?): Long {
    val day = Calendar.getInstance().apply { timeInMillis = pickedMillis }
    return Calendar.getInstance().apply {
        stored?.let { timeInMillis = it }
        set(Calendar.YEAR, day.get(Calendar.YEAR))
        set(Calendar.MONTH, day.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, day.get(Calendar.DAY_OF_MONTH))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** The picked time of day, keeping whatever date was already stored. */
private fun withTimeOf(hour: Int, minute: Int, stored: Long?): Long =
    Calendar.getInstance().apply {
        stored?.let { timeInMillis = it }
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

// --- a time of day --------------------------------------------------------

/** One time picker over the hour and minute keys the component already stored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOfDayField(
    field: ConfigField.TimeOfDay,
    hour: String?,
    minute: String?,
    onChange: (hour: String, minute: String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val storedHour = hour?.toIntOrNull()
    val storedMinute = minute?.toIntOrNull() ?: 0

    PickerValueBox(
        label = fieldLabel(field.label, field.required),
        primary = storedHour?.let { clockFace(it, storedMinute) } ?: "Pick a time",
        secondary = null,
        onClick = { picking = true },
    )

    if (picking) {
        val state = rememberTimePickerState(
            // Eight in the morning rather than midnight: an alarm defaulted to
            // 00:00 is a value nobody chose that still looks chosen.
            initialHour = storedHour ?: 8,
            initialMinute = storedMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            title = {
                Text(
                    text = field.label.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = { TimePicker(state = state) },
            confirmButton = {
                BlockTextButton("Set") {
                    onChange(state.hour.toString(), state.minute.toString())
                    picking = false
                }
            },
            dismissButton = { BlockTextButton("Cancel") { picking = false } },
        )
    }
}

private fun clockFace(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour, minute)

private fun formatDate(calendar: Calendar): String =
    String.format(Locale.getDefault(), "%1\$td %1\$tb %1\$tY", calendar)

private fun formatTime(calendar: Calendar): String =
    clockFace(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
