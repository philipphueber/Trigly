package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.TextMatchMode
import app.phueber.trigly.core.matchRangesIn
import app.phueber.trigly.core.regexErrorOrNull

/**
 * Try a pattern against text you type, and see exactly what the rule will.
 *
 * A regular expression written into a field and never run is a guess. The
 * editor already refuses one that does not *compile*, which catches a stray
 * bracket and nothing else — a pattern can be perfectly valid and match the
 * wrong thing, or nothing, and the only way that surfaces today is a rule that
 * silently never fires.
 *
 * **The verdict comes from the engine's own code.** `TextFilter.of(...).matches`
 * is what the trigger will call, so what this says is what will happen —
 * including the case-insensitivity and the "contains a match" rather than "matches
 * the whole string" semantics that are easy to assume the other way round. The
 * highlight is decoration on top of that answer, from [matchRangesIn]; nothing
 * here re-implements matching, because a tester that disagrees with the engine
 * would be worse than none.
 *
 * The pattern is editable here on purpose. Testing is iterating, and a dialog
 * that showed you the pattern was wrong but made you close it to fix it would
 * put the fixing somewhere the feedback is not.
 */
@Composable
fun PatternTesterDialog(
    label: String,
    pattern: String?,
    mode: TextMatchMode,
    onPatternChange: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Survives rotation but is deliberately not stored in the rule: the sample is
    // scratch paper for the person writing the pattern, not part of what fires.
    var sample by rememberSaveable { mutableStateOf("") }

    val isRegex = mode == TextMatchMode.REGEX
    val current = pattern.orEmpty()
    val compileError = if (isRegex) regexErrorOrNull(current) else null
    val highlight = rememberRegexHighlight(enabled = isRegex)

    // The engine's own answer, not a second implementation of it. `of` throws on
    // a pattern that will not compile, which is the same failure Save reports.
    val filter = remember(current, mode) {
        runCatching { TextFilter.of(current.ifEmpty { null }, mode) }.getOrNull()
    }
    val matches = filter?.matches(sample)
    val ranges = remember(current, mode, sample) { matchRangesIn(current, mode, sample) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "TEST ${label.uppercase()}",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        confirmButton = { BlockTextButton("Done", onClick = onDismiss) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = current,
                    onValueChange = { onPatternChange(it.ifEmpty { null }) },
                    label = {
                        Text(
                            text = if (isRegex) "PATTERN (REGEX)" else "PATTERN (CONTAINS)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    singleLine = true,
                    isError = compileError != null,
                    visualTransformation = highlight,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                compileError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    label = {
                        Text("SAMPLE TEXT", style = MaterialTheme.typography.labelMedium)
                    },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                Verdict(
                    matches = matches,
                    hasPattern = current.isNotEmpty(),
                    hasSample = sample.isNotEmpty(),
                    hits = ranges.size,
                )

                if (ranges.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(
                            2.dp,
                            MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(
                            text = withMatchesMarked(sample, ranges),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(10.dp)
                                .heightIn(max = 140.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }

                Hint(
                    if (isRegex) {
                        "Matching ignores case and looks for the pattern anywhere in " +
                            "the text. Anchor with ^ and $ to require the whole string."
                    } else {
                        "Matching ignores case and looks for the text anywhere in it."
                    }
                )
            }
        },
    )
}

/**
 * The answer, in words, including the two states that are neither yes nor no.
 *
 * A blank pattern is not a failed match — it is a filter with no opinion, which
 * lets everything through. Saying "no match" there would be a lie about what the
 * rule does.
 */
@Composable
private fun Verdict(matches: Boolean?, hasPattern: Boolean, hasSample: Boolean, hits: Int) {
    val (text, colour) = when {
        !hasPattern -> "EMPTY PATTERN — MATCHES ANYTHING" to
            MaterialTheme.colorScheme.onSurfaceVariant
        matches == null -> "PATTERN DOES NOT COMPILE" to MaterialTheme.colorScheme.error
        !hasSample -> "TYPE SOME SAMPLE TEXT" to MaterialTheme.colorScheme.onSurfaceVariant
        // `extra.accent` rather than `colorScheme.primary` throughout: primary
        // is a fill colour and fails AA contrast as label text. See Palette.kt.
        matches && hits > 0 -> "MATCHES · $hits ${if (hits == 1) "HIT" else "HITS"}" to
            MaterialTheme.extra.accent
        // Matched, but with nothing to underline — a zero-width pattern such as
        // `a*`. Both halves are true and saying only one of them would mislead.
        matches -> "MATCHES · NOTHING TO HIGHLIGHT" to MaterialTheme.extra.accent
        else -> "NO MATCH" to MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

/**
 * The sample with matched spans marked.
 *
 * Uses the theme's own primary rather than a new colour, and marks with a
 * background so the matched characters stay readable — inverting the text would
 * make a one-character match hard to see against a monospaced run.
 */
@Composable
private fun withMatchesMarked(sample: String, ranges: List<IntRange>): AnnotatedString {
    val mark = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    return buildAnnotatedString {
        var cursor = 0
        // Sorted and clipped, because a caller could in principle hand over
        // overlapping or stale ranges and AnnotatedString would throw on them.
        ranges.sortedBy { it.first }.forEach { range ->
            val start = range.first.coerceIn(0, sample.length)
            val end = (range.last + 1).coerceIn(start, sample.length)
            if (start < cursor) return@forEach
            append(sample.substring(cursor, start))
            withStyleSpan(mark) { append(sample.substring(start, end)) }
            cursor = end
        }
        append(sample.substring(cursor))
    }
}

/** `withStyle` under a name that does not read like a Compose modifier. */
private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSpan(
    style: SpanStyle,
    block: () -> Unit,
) {
    val index = pushStyle(style)
    block()
    pop(index)
}
