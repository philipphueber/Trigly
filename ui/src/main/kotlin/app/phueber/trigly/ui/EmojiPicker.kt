package app.phueber.trigly.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Picks an icon for a home-screen shortcut off a curated set of emoji, in place
 * of typing one — which means either finding the keyboard's emoji tab from
 * inside a form field with no text cursor to summon it from, or copying one in
 * from somewhere else and hoping it renders the same way on every device. That
 * is what `ConfigField.Emoji` exists for, the same trade [AppPackage] and
 * [SoundUri] already make: a value stored as plain text, picked from a list
 * because nobody should have to produce it from memory.
 *
 * **Deliberately a few dozen, not the ~3,700 Unicode defines.** An icon is
 * chosen by scanning — the way someone scans a row of house keys — and a list
 * long enough to contain everything is a list nobody can scan, which defeats
 * the point of a picker. What is here favours what a shortcut icon is actually
 * *for*: naming the trigger or action behind it (a bell, a car, a moon) rather
 * than expressing an emotion, and is ordered in loose themed runs — alerts and
 * time, places, transport, sky, devices, media, people, status — so a related
 * choice sits near the one just ruled out.
 *
 * **An emoji is not one character.** Several entries below are a base glyph
 * plus a variation selector (`✈️`, `☁️`), and others are eventually going to
 * want a ZWJ sequence (skin tone, a joined pair). Every value here is handled
 * as an opaque `String` for exactly that reason — compared whole, stored whole,
 * rendered whole — and never indexed into by character. `emoji[0]` would slice
 * a sequence like that in half and produce something that renders as either
 * garbage or a different, unintended glyph.
 */
private val CuratedEmoji: List<String> = listOf(
    // Alerts & time
    "🔔", "🔕", "⏰", "⏱️", "⏲️", "⌛",
    // Places
    "🏠", "🏢", "🏫", "🏥", "🏋️", "🛒",
    // Transport
    "🚗", "🚕", "🚲", "🚌", "🚆", "✈️",
    // Sky & weather
    "☀️", "🌙", "⭐", "☁️", "🌧️", "❄️",
    // Devices & connectivity
    "📱", "💻", "🔋", "📶", "📡", "🔌",
    // Sound & media
    "🎵", "🎧", "📷", "🎬", "📺", "🔊",
    // People & movement
    "📞", "💬", "✉️", "🚶", "🏃", "🧘",
    // Status & symbols
    "✅", "⚠️", "🔒", "🔓", "❤️", "🔥",
)

/**
 * The gap eaten by [EmojiCell]'s own 4dp padding on each side, added on top of
 * the 48dp touch-target minimum. This is the column width the grid below hands
 * `GridCells.Adaptive`, not the cell's own size — the cell measures out 8dp
 * smaller once its padding comes out, landing it exactly on 48dp square.
 */
private val EmojiCellMinSize = 56.dp

/**
 * The dialog: a scrollable grid of [CuratedEmoji], plus the clear row
 * [AppPickerDialog] and the other pickers carry for a field whose blankness is
 * a real setting.
 *
 * No search field and no typed escape hatch, unlike the app and Bluetooth
 * pickers. There is no name to search an emoji by, and typing one into a text
 * box is not a gap this dialog needs to cover — the system keyboard already
 * does that job. What this replaces is finding that keyboard's emoji tab from a
 * form field with no text cursor, not typing itself.
 */
@Composable
fun EmojiPickerDialog(
    title: String,
    /** Shown as the first row when the field is optional; null hides it. */
    clearLabel: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.uppercase(), style = MaterialTheme.typography.titleMedium) },
        confirmButton = { BlockTextButton("Cancel", onClick = onDismiss) },
        text = {
            Column {
                if (clearLabel != null) {
                    EmojiClearRow(label = clearLabel, onClick = { onPick(null) })
                    BlockDivider()
                }

                // Adaptive, not Fixed(6): measured on a phone-width dialog, six
                // fixed columns leave each cell around 33dp square once its 4dp
                // padding comes out — well under Android's 48dp touch-target
                // minimum. `Modifier.minimumInteractiveComponentSize()` on the
                // cell can't fix that: `GridCells.Fixed` hands every item a fixed
                // *maxWidth* constraint equal to the column width, and a
                // min-size modifier cannot grow a layout past the max its parent
                // constrains it to — unlike [OverflowingTouchTarget] in
                // Blocks.kt, which works because a Row leaves its cross axis
                // unconstrained. `Adaptive` instead picks however many columns
                // of at least [EmojiCellMinSize] actually fit, so the column
                // width itself — the real ceiling on the cell below — is never
                // narrower than a touch target. Six columns was never the
                // point; a scannable rack of square cells was, and this keeps
                // that on any width this dialog renders at.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = EmojiCellMinSize),
                    modifier = Modifier.heightIn(max = 360.dp).padding(top = 8.dp),
                ) {
                    // Keyed by position, not by value: a curated list is short
                    // enough that duplicate emoji are a mistake rather than a
                    // feature, but nothing here depends on the values being
                    // unique.
                    items(CuratedEmoji) { emoji ->
                        EmojiCell(emoji = emoji, onClick = { onPick(emoji) })
                    }
                }
            }
        },
    )
}

@Composable
private fun EmojiClearRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelMedium)
    }
}

/** One tappable cell. Square, so the grid reads as a rack rather than a list. */
@Composable
private fun EmojiCell(emoji: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        shape = BlockShape,
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .hardShadow(BlockShape),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            // Rendered as one unit, whatever it is made of underneath — see the
            // "not one character" note above.
            Text(text = emoji, fontSize = 24.sp)
        }
    }
}

/**
 * What `ConfigField.Emoji` renders as.
 *
 * The stored value is shown exactly as saved, never checked against
 * [CuratedEmoji] the way [AppPackageField] checks a package name against the
 * installed list. There is nothing to check: an emoji renders itself, with no
 * lookup that could fail. That is also what keeps a shortcut whose icon was
 * later dropped from this file's curated set from reading as broken or
 * empty — the box still shows precisely the string that was picked, list or
 * no list.
 */
@Composable
fun EmojiField(
    label: String,
    emoji: String?,
    blankMeaning: String?,
    onPick: (String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    PickerValueBox(
        label = label,
        primary = emoji ?: blankMeaning ?: "Choose an icon",
        secondary = null,
        onClick = { picking = true },
    )

    if (picking) {
        EmojiPickerDialog(
            title = label.removeSuffix(" *"),
            // Only offered when blankness is a real setting for this field.
            clearLabel = blankMeaning,
            onPick = { picked ->
                picking = false
                onPick(picked)
            },
            onDismiss = { picking = false },
        )
    }
}
