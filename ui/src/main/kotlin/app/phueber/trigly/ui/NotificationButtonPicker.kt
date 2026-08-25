package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NotificationButton
import app.phueber.trigly.core.SEMANTIC_ACTION_NONE

/**
 * The notifications the picker can capture from, and how to re-read them.
 *
 * A function rather than a list, unlike the app and sound locals: notifications
 * come and go while the editor is open, so a snapshot taken once at launch would
 * be stale by the time anyone opened the picker. This is read each time the
 * dialog opens.
 */
val LocalActiveNotifications = staticCompositionLocalOf<() -> List<ActiveNotification>> {
    { emptyList() }
}

/**
 * Picks a button off a notification that is on screen right now.
 *
 * This replaced "Which button — counted from zero, left to right", which was the
 * worst field in the app: an index into a list the editor could not show you,
 * belonging to a notification that probably was not posted while you were
 * editing.
 *
 * **Capturing is inherently live.** `getActiveNotifications` can only report what
 * is currently posted — there is no history to browse — so the honest design
 * tells the user to make the notification happen and then look, rather than
 * pretending to offer a catalogue.
 *
 * What gets stored is *not* the notification's key. A key is regenerated each
 * time an app posts, so a rule pinned to one would work once. The picker records
 * the **package** and the **button's meaning and label**, all three of which
 * survive the notification being reposted tomorrow.
 */
@Composable
fun NotificationButtonPicker(
    field: ConfigField.NotificationButton,
    label: String?,
    semantic: String?,
    packageName: String?,
    onPick: (label: String?, semantic: String?, packageName: String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val read = LocalActiveNotifications.current
    val apps = LocalInstalledApps.current

    Column(modifier = Modifier.fillMaxWidth()) {
        PickerValueBox(
            label = fieldLabel(field.label, field.required),
            primary = label ?: "Capture a button",
            // The app is what makes the choice legible a month later: "Snooze" on
            // its own does not say whose Snooze.
            secondary = packageName?.let { apps.labelFor(it) },
            onClick = { picking = true },
        )

        if (label != null) {
            Hint(
                if (packageName != null) {
                    "Pressed on the newest notification from that app."
                } else {
                    "Pressed on the notification that fired the rule."
                }
            )
        }
    }

    if (picking) {
        // Read on open, not on compose: the point is what is posted *now*.
        val snapshot = remember(picking) { read() }

        ValuePickerDialog(
            title = "Capture a button",
            searchLabel = "SEARCH NOTIFICATIONS",
            options = snapshot.flatMap { notification ->
                notification.buttons.map { button ->
                    button.asOption(notification, apps.labelFor(notification.packageName))
                }
            },
            // Blankness is a setting: no button means the action does nothing, so
            // the row that clears it has to be there.
            clearLabel = "No button chosen",
            placeholder = "No notification with buttons is showing. Make the one " +
                "you want appear, then open this again.",
            onPick = { encoded ->
                picking = false
                if (encoded == null) {
                    onPick(null, null, null)
                } else {
                    val chosen = snapshot.firstNotNullOfOrNull { notification ->
                        notification.buttons
                            .firstOrNull { it.optionValue(notification) == encoded }
                            ?.let { notification to it }
                    }
                    chosen?.let { (notification, button) ->
                        onPick(
                            button.label,
                            button.semanticAction?.takeIf { it != SEMANTIC_ACTION_NONE }?.toString(),
                            notification.packageName,
                        )
                    }
                }
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * One button as a picker row.
 *
 * A reply button is marked rather than hidden. Hiding it would leave someone
 * hunting for the Reply they can see on screen; saying it needs text they cannot
 * supply explains the absence. The action refuses these too — this is the earlier
 * half of the same honesty.
 *
 * Such a row also renders disabled and refuses the tap, so "cannot be pressed"
 * is learned before the dialog closes rather than at Save. That is presentation
 * only, not validation — `create()` stays the one place that actually refuses
 * the value, and it still would if this row were somehow picked.
 */
private fun NotificationButton.asOption(
    notification: ActiveNotification,
    appLabel: String,
): PickerOption = PickerOption(
    value = optionValue(notification),
    primary = label ?: "Button ${index + 1}",
    secondary = buildString {
        append(appLabel)
        notification.title?.let { append(" · $it") }
        if (takesText) append(" · needs typed text, cannot be pressed")
    },
    enabled = !takesText,
)

/**
 * A row identity that is unique within one snapshot and used for nothing else.
 *
 * Not stored anywhere: the picker resolves it straight back to a button and
 * records the durable identifiers instead. It contains the key precisely because
 * the key is unique right now, which is all a dialog needs.
 */
private fun NotificationButton.optionValue(notification: ActiveNotification): String =
    "${notification.key}#$index"

/** Reads the live notifications, for the local above. */
@Composable
fun rememberActiveNotifications(read: () -> List<ActiveNotification>): State<() -> List<ActiveNotification>> =
    remember { mutableStateOf(read) }
