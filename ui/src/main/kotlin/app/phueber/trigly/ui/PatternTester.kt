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
import androidx.compose.runtime.LaunchedEffect
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
import app.phueber.trigly.core.RegexRefusal
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.TextMatchMode
import app.phueber.trigly.core.matchRangesIn
import app.phueber.trigly.core.regexErrorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The background search's answer for one exact pattern, mode and sample: see
 * [PatternTesterDialog].
 *
 * Carries the inputs it was computed for, [forCurrent], [forMode] and
 * [forSample], rather than only the answer. A result that no longer matches
 * the pattern, mode and sample on screen is stale, and a stale verdict is
 * worse than none: it is what the fourth verdict state exists to rule out for
 * a refused pattern, and it would be just as misleading for an ordinary
 * match. Comparing the result to the current input, rather than trusting that
 * a newer computation always lands before the input changes again, is what
 * makes a stale verdict impossible to display rather than merely unlikely.
 */
private data class TestResult(
    val forCurrent: String,
    val forMode: TextMatchMode,
    val forSample: String,
    val outcome: TextFilter.Outcome?,
    val ranges: List<IntRange>,
)

/**
 * Try a pattern against text you type, and see exactly what the rule will.
 *
 * A regular expression written into a field and never run is a guess. The
 * editor already refuses one that does not *compile*, which catches a stray
 * bracket and nothing else. A pattern can be perfectly valid and match the
 * wrong thing, or nothing, and the only way that surfaces today is a rule that
 * silently never fires.
 *
 * **The verdict comes from the engine's own code.** `TextFilter.of(...)` builds
 * the same filter a trigger would, and its `outcome` is `matches` with the one
 * extra answer `matches` folds into "no": whether the pattern was refused for
 * doing too much work rather than actually failing to match. So what this says
 * is what will happen, including the case-insensitivity and the "contains a
 * match" rather than "matches the whole string" semantics that are easy to
 * assume the other way round. The highlight is decoration on top of that
 * answer, from [matchRangesIn]; nothing here re-implements matching, because a
 * tester that disagrees with the engine would be worse than none.
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
    // Compiling only costs what the pattern itself is long, never what the
    // sample is, so this stays synchronous: it is what a bracket left open
    // shows instantly, and it is not the unbounded half of this feature.
    val compileError = if (isRegex) regexErrorOrNull(current) else null
    val highlight = rememberRegexHighlight(enabled = isRegex)

    // The engine's own answer, not a second implementation of it. `of` throws on
    // a pattern that will not compile, which is the same failure Save reports.
    val filter = remember(current, mode) {
        runCatching { TextFilter.of(current.ifEmpty { null }, mode) }.getOrNull()
    }

    // Running the pattern against the sample is the unbounded half: it is what
    // RegexGuard bounds, not what makes it free. Off the main thread and
    // behind a frame, because this reruns on every keystroke in either field.
    // TextFilter.outcome is TextFilter.matches' own code, just with the third
    // answer TextFilter.matches folds into "no": see TextFilter's KDoc for why
    // that fold happens and what it costs.
    //
    // LaunchedEffect is keyed on the values that decide the answer, so Compose
    // cancels a stale computation before it can overwrite `result` with a
    // stale one. Cancelling this coroutine does not stop a stuck search,
    // though: RegexGuard runs it on its own thread, which a coroutine
    // cancellation cannot reach, the same reason a timeout on this coroutine
    // could not either. RegexGuard is what bounds how long this dialog waits
    // for an answer regardless. That only protects the write, though: it says
    // nothing about the value already on screen while a newer one is still
    // being computed, which is why `fresh` below re-checks `result` against
    // this exact recomposition's inputs rather than trusting the write to
    // have already happened. See TestResult's KDoc for why that check exists.
    var result by remember { mutableStateOf<TestResult?>(null) }
    LaunchedEffect(filter, current, mode, sample) {
        val computed = withContext(Dispatchers.Default) {
            TestResult(
                forCurrent = current,
                forMode = mode,
                forSample = sample,
                outcome = filter?.outcome(sample),
                ranges = matchRangesIn(current, mode, sample),
            )
        }
        result = computed
    }
    val fresh = result?.takeIf {
        it.forCurrent == current && it.forMode == mode && it.forSample == sample
    }
    val outcome = fresh?.outcome
    val refusal = (outcome as? TextFilter.Outcome.REFUSED)?.reason
    val matches = when {
        filter == null -> null // does not compile; known without running anything
        outcome == null -> null // no fresh answer yet for this exact input
        else -> outcome == TextFilter.Outcome.MATCHED
    }
    val ranges = fresh?.ranges.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                // "Try", to match the button that opens this dialog: see
                // ConfigFieldEditor.kt's TextPatternField for why it is not "Test".
                text = "TRY ${label.uppercase()}",
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
                    compileError = compileError != null,
                    refusal = refusal,
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
 * The answer, in words, including the states that are neither yes nor no.
 *
 * A blank pattern is not a failed match. It is a filter with no opinion, which
 * lets everything through. Saying "no match" there would be a lie about what the
 * rule does. [refusal] is the fourth such state: the pattern compiled, but
 * `TextFilter` would not run it against [hasSample]'s text. It carries which of
 * [RegexRefusal]'s four reasons that was, since they are four different true
 * statements and only one of them, [RegexRefusal.TIMED_OUT], is actually about
 * this sample taking too long: see [refusalText] for the wording of each one.
 * A rule built on a pattern that is ever refused would just never fire,
 * silently; this dialog is the one place that says why. See `RegexBudget.kt`
 * in `:core` for the bound itself.
 *
 * [matches] being null and neither [compileError] nor a missing sample being
 * the reason means the answer for this exact pattern and sample has not come
 * back from the background search yet. That gap is real now that the search
 * runs off the main thread instead of every recomposition; showing nothing
 * wrong for a frame is the honest alternative to blocking the thread the
 * dialog is drawn on.
 */
@Composable
private fun Verdict(
    matches: Boolean?,
    hasPattern: Boolean,
    compileError: Boolean,
    refusal: RegexRefusal?,
    hasSample: Boolean,
    hits: Int,
) {
    val (text, colour) = when {
        !hasPattern -> "EMPTY PATTERN · MATCHES ANYTHING" to
            MaterialTheme.colorScheme.onSurfaceVariant
        compileError -> "PATTERN DOES NOT COMPILE" to MaterialTheme.colorScheme.error
        !hasSample -> "TYPE SOME SAMPLE TEXT" to MaterialTheme.colorScheme.onSurfaceVariant
        refusal != null -> refusalText(refusal) to MaterialTheme.colorScheme.error
        matches == null -> "CHECKING" to MaterialTheme.colorScheme.onSurfaceVariant
        // `extra.accent` rather than `colorScheme.primary` throughout: primary
        // is a fill colour and fails AA contrast as label text. See Palette.kt.
        matches && hits > 0 -> "MATCHES · $hits ${if (hits == 1) "HIT" else "HITS"}" to
            MaterialTheme.extra.accent
        // Matched, but with nothing to underline: a zero-width pattern such as
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
 * [Verdict]'s label for each [RegexRefusal]. Only [RegexRefusal.TIMED_OUT] is
 * about this sample specifically; the other three say so, rather than reusing
 * "took too long" for a search that was never even tried against this text.
 */
private fun refusalText(reason: RegexRefusal): String = when (reason) {
    RegexRefusal.TIMED_OUT -> "REFUSED · TOOK TOO LONG ON THIS SAMPLE"
    RegexRefusal.KNOWN_BAD -> "REFUSED · ALREADY TOOK TOO LONG ONCE THIS SESSION"
    RegexRefusal.BUSY -> "REFUSED · ANOTHER SEARCH IS RUNNING RIGHT NOW"
    RegexRefusal.EXHAUSTED -> "REFUSED · TOO MANY SEARCHES ARE ALREADY STUCK"
}

/**
 * The sample with matched spans marked.
 *
 * Uses the theme's own primary rather than a new colour, and marks with a
 * background so the matched characters stay readable. Inverting the text would
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
