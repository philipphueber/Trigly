package app.phueber.trigly.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The blocky vocabulary, in one place.
 *
 * Everything here is flat, square and hard-edged: a filled rectangle with a 2dp
 * border, no elevation, no gradient, no rounding. Material's own components are
 * used underneath wherever they carry behaviour worth keeping (focus, ripple,
 * accessibility roles) — what changes is the skin.
 *
 * Having them here rather than inline is what keeps the two screens honest: a
 * border width or a padding is defined once, so the screens cannot drift apart.
 */

/** Border width for every block. Thick enough to read as a drawn line. */
private val BlockBorder = 2.dp

/**
 * The solid slab at the top of a screen.
 *
 * Deliberately painted *behind* the status bar rather than below it: with
 * edge-to-edge the app owns those pixels, and a full-bleed band of colour is the
 * point of the design. The inset is applied to the slab's *content*, so the text
 * still clears the clock.
 */
@Composable
fun BlockHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (leading == null) 12.dp else 4.dp),
            )
            actions?.invoke()
        }
    }
}

/**
 * A bordered rectangle. The unit the screens are built from.
 *
 * [onClick] is optional so the same block can be inert (a form section) or a
 * whole tappable row (a rule in the list) without two components that must be
 * kept looking identical.
 */
@Composable
fun BlockCard(
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val border = BorderStroke(BlockBorder, MaterialTheme.colorScheme.outline)
    if (onClick == null) {
        Surface(color = fill, border = border, modifier = modifier.fillMaxWidth()) {
            content()
        }
    } else {
        Surface(
            onClick = onClick,
            color = fill,
            border = border,
            modifier = modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

/** The 2dp line that splits a block into stacked cells. */
@Composable
fun BlockDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        thickness = BlockBorder,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}

/**
 * A filled, square, full-width-by-default action.
 *
 * The label is uppercased here rather than at every call site, so a button
 * cannot be added in the wrong case.
 */
@Composable
fun BlockButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(
            horizontal = 24.dp,
            vertical = 16.dp,
        ),
        modifier = modifier,
    ) {
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The same shape, unfilled: a secondary action that still reads as a block.
 *
 * [fillWidth] opts into spanning the column, for a button that stands alone in
 * the layout rather than sitting beside something. It has to widen the label as
 * well as the surface — `textAlign` only centres text inside the width the label
 * already has, so a wrap-content label would sit hard against the left edge of a
 * full-width box. Off by default: a button in a row (Delete rule beside Save, the
 * choice dropdown under its label) must stay the width of its text.
 */
@Composable
fun BlockOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    fillWidth: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = contentColor,
        border = BorderStroke(BlockBorder, contentColor),
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

/**
 * A text-only action for use inside a coloured slab, where a border would clash.
 *
 * Inherits the surrounding content colour by default rather than taking
 * Material's `primary`. That default is what a `TextButton` normally wants, and
 * it is exactly wrong here: inside the orange header slab, primary-on-primary is
 * invisible. Call sites that want the accent ask for it.
 */
@Composable
fun BlockTextButton(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        modifier = modifier,
    ) {
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * ON / OFF as a square cell, in place of Material's pill switch.
 *
 * Built on `toggleable` with `Role.Switch` rather than drawn from scratch, so it
 * keeps the switch semantics that accessibility services and the instrumented
 * tests rely on — only the shape is ours.
 */
@Composable
fun BlockToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = if (checked) on else Color.Transparent,
        contentColor = if (checked) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(BlockBorder, if (checked) on else off),
        modifier = modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    ) {
        Text(
            text = if (checked) "ON" else "OFF",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * "There is something to know about this one."
 *
 * Not a warning in itself — a promise that the editor will explain, which is
 * what lets a 28-item list stay readable without hiding that a caveat exists.
 */
@Composable
fun CaveatBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .border(BlockBorder, MaterialTheme.extra.caution)
            .semantics { contentDescription = CAVEAT_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.extra.caution,
        )
    }
}

/** Read by the accessibility layer and by the instrumented test. */
internal const val CAVEAT_DESCRIPTION = "Has a caveat"

/**
 * The strip at the bottom of a screen.
 *
 * Owns the navigation-bar inset so the buttons inside it never have to think
 * about it, and paints a border along its top edge because the content scrolls
 * underneath — without the line, a half-scrolled card looks like it belongs to
 * the bar.
 */
@Composable
fun BlockBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        BlockDivider()
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * A value on a scale, dragged rather than typed.
 *
 * Material's slider underneath, for the drag handling, keyboard support and the
 * accessibility semantics that make it announce as a seek control — but wearing
 * the block skin, because the default is a rounded pill with a circular thumb
 * and nothing else in this app is round.
 *
 * The track doubles as a level meter: the filled portion *is* the value, which
 * is the whole reason to use a slider instead of a number box. The thumb is a
 * hard bar rather than a dot so it reads as a handle you grab and not as a
 * decoration sitting on top of the fill.
 *
 * Rounds to whole numbers on the way out. Every scale this is used for counts in
 * whole units, and a stored "73.41999" would be both wrong-looking and a
 * needlessly long string in the config map.
 *
 * The opt-in is for `SliderState` and the thumb/track slots, still experimental
 * in Material3 1.3. Scoped to the two functions that need it rather than turned
 * on for the module: the day it changes, the compiler should point here and not
 * at everything. The plain `Slider` overload is stable, but it only takes
 * colours — and a rounded pill in the right colours is still a rounded pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockSlider(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        valueRange = min.toFloat()..max.toFloat(),
        modifier = modifier,
        thumb = { BlockSliderThumb() },
        track = { state -> BlockSliderTrack(state) },
    )
}

@Composable
private fun BlockSliderThumb() {
    Box(
        modifier = Modifier
            .size(width = 14.dp, height = 30.dp)
            .background(MaterialTheme.colorScheme.primary)
            .border(BlockBorder, MaterialTheme.colorScheme.outline)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockSliderTrack(state: SliderState) {
    val span = (state.valueRange.endInclusive - state.valueRange.start)
    // A zero-width range cannot happen — ConfigField.Slider rejects min >= max —
    // but this composable is reusable, and dividing by zero here would paint NaN.
    val fraction = if (span > 0f) {
        ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .background(MaterialTheme.colorScheme.surface)
            .border(BlockBorder, MaterialTheme.colorScheme.outline)
            .padding(BlockBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
