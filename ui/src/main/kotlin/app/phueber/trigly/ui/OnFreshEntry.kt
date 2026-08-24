package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Runs [action] once, when this enters composition as a genuinely new entry —
 * and *not* again when the same entry is rebuilt by a configuration change.
 *
 * This is the distinction the editor's "a new rule starts empty" depends on, and
 * getting it from the wrong side is where the previous attempt leaked drafts.
 * Resetting on *exit* looks like it catches everything, but it does not: the
 * disposal that coincides with a configuration change has to be guarded out (or a
 * rotation would wipe the draft), and any exit that is guarded out — or simply
 * does not dispose the composable — leaves the retained ViewModel dirty, so the
 * next entry inherits the last draft. Deciding on *entry* has no such gap: there
 * is one way in, and it either is a fresh entry or it is a restoration.
 *
 * `rememberSaveable` is the whole mechanism. A real (re)entry gets a fresh saved
 * slot — leaving the screen discards it, so coming back re-seeds `false` and
 * fires. A configuration change *restores* the slot, so `done` comes back `true`
 * and [action] stays quiet, leaving whatever the retained state already held.
 *
 * [action] runs during composition on purpose, not from a `LaunchedEffect`: it
 * is meant to put state right *before* the first frame reads it, so a synchronous
 * reset shows nothing stale even for a single frame. Keep it cheap and idempotent
 * — a `SnapshotStateList` clear, a `StateFlow` reassignment — not I/O.
 */
@Composable
fun OnFreshEntry(action: () -> Unit) {
    var done by rememberSaveable { mutableStateOf(false) }
    if (!done) {
        action()
        done = true
    }
}
