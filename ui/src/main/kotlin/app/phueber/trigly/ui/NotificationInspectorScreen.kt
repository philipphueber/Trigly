package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.NotificationButton
import app.phueber.trigly.core.SEMANTIC_ACTION_NONE
import app.phueber.trigly.core.notificationHaystack

/**
 * What Trigly can actually see on the notifications currently on screen.
 *
 * Every notification rule is written against fields nobody can inspect from
 * outside the app: which package posted it, what the platform considers the
 * *title* versus the *text*, whether it is flagged ongoing, and what its buttons
 * are called underneath their icons. Guessing those and finding out from a rule
 * that silently never fires is the loop this screen exists to cut.
 *
 * **It shows the strings the matchers use, not a tidied version of them.** The
 * joined haystack comes from [notificationHaystack], the same function
 * `matchesNotification` calls, so a pattern that behaves oddly can be compared
 * against the exact text it was tested against — including the space a missing
 * title still contributes. A screen that reconstructed an approximation would be
 * worse than nothing, because the entire point of it is to be believed.
 *
 * Stateless like the other screens, so the instrumented test drives it with
 * fabricated notifications rather than whatever the emulator happens to be
 * showing.
 */
@Composable
fun NotificationInspectorScreen(
    notifications: List<ActiveNotification>,
    listenerConnected: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    describeApp: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BlockHeader(
            title = "What Trigly sees",
            actions = { BlockTextButton("Refresh", onClick = onRefresh) },
        )

        when {
            // The two empty states are different problems with different fixes,
            // and telling them apart is the whole reason this app has a
            // requirement model at all.
            !listenerConnected -> Explanation(
                "Trigly cannot read notifications yet. Grant notification access " +
                    "from a rule that needs it, then come back."
            )

            notifications.isEmpty() -> Explanation(
                "Nothing is showing. Only notifications posted right now can be " +
                    "inspected — there is no history to look through. Make the one " +
                    "you care about appear, then tap Refresh."
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = notifications, key = { it.key }) { notification ->
                    NotificationBlock(notification, describeApp)
                }
            }
        }

        BlockBottomBar {
            BlockButton(text = "Back", onClick = onBack, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        BlockCard(fill = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun NotificationBlock(
    notification: ActiveNotification,
    describeApp: (String) -> String,
) {
    BlockCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = describeApp(notification.packageName).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // The package, not the label, is what a rule stores.
            Mono(notification.packageName)

            Field("Title", notification.title)
            Field("Text", notification.text)

            // The one non-obvious thing on this screen, and the reason it exists:
            // text filters match the two joined, not either alone.
            Field(
                label = "Text filters match",
                value = notificationHaystack(notification.title, notification.text),
            )

            Field(
                label = "Ongoing",
                value = if (notification.ongoing) "yes" else "no",
            )

            if (notification.buttons.isEmpty()) {
                Field("Buttons", "none")
            } else {
                Text(
                    text = "BUTTONS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                notification.buttons.forEach { ButtonRow(it) }
            }
        }
    }
}

@Composable
private fun ButtonRow(button: NotificationButton) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = button.label ?: "(no label)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        // What the rule will actually match on, in the order it tries them.
        Mono(
            buildString {
                append(
                    button.semanticAction
                        ?.takeIf { it != SEMANTIC_ACTION_NONE }
                        ?.let { "meaning $it" }
                        ?: "no meaning"
                )
                if (button.takesText) append(" · reply box")
            }
        )
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Quoted, and monospaced, because leading and trailing spaces matter here
        // and are invisible otherwise — a missing title leaves one at the front of
        // the haystack, which is exactly the kind of thing this screen is for.
        Mono(if (value == null) "(none)" else "\"$value\"")
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
