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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The blocky vocabulary, in one place.
 *
 * Everything here is flat and hard-edged: a filled rectangle with a 2dp border,
 * a 3dp corner, no gradient, and no elevation in the Material sense — the weight
 * comes from [hardShadow], a solid offset copy of the block, not from a blur.
 * Material's own components are used underneath wherever they carry behaviour
 * worth keeping (focus, ripple, accessibility roles) — what changes is the skin.
 *
 * Having them here rather than inline is what keeps the two screens honest: a
 * border width or a padding is defined once, so the screens cannot drift apart.
 */

/** Border width for every block. Thick enough to read as a drawn line. */
private val BlockBorder = 2.dp

/**
 * The shape every block is cut to, from the theme.
 *
 * Read this rather than letting a `Surface` fall back to its own default. That
 * default is `RectangleShape`, which is why the blocks looked square for as long
 * as they did *without* anyone passing a shape — and why editing `Shapes` in
 * `Theme.kt` used to change the dialogs, menus, text fields and the two buttons
 * while leaving every card, toggle and chip behind. A geometry the theme cannot
 * reach is a geometry that only half-changes.
 *
 * Two kinds of thing deliberately do not use it, and the test in both cases is
 * whether the surface has an edge of its own to round:
 *
 *  · **Full-bleed chrome.** [BlockHeader] and [BlockBottomBar] run to the screen
 *    edges under the system bars, where a corner radius has nothing to sit
 *    against and reads as a rendering fault.
 *  · **Cells inside a block.** The strips that follow a [BlockDivider] — an
 *    unmet requirement under a rule, a caveat under a chosen component — fill
 *    their parent card to its inner edges. Rounding them would show the card's
 *    own fill through four notches.
 */
internal val BlockShape: Shape
    @Composable @ReadOnlyComposable get() = MaterialTheme.shapes.medium

/** Offset of a block's hard shadow. [BlockControlShadow] is the small version. */
private val BlockShadow = 4.dp

/** For a toggle or a chip, where 4dp beside a 22dp control reads as a smear. */
private val BlockControlShadow = 3.dp

/**
 * A solid offset copy of the block's own silhouette, in the ink of its border.
 *
 * Not elevation. Material's `shadowElevation` draws a soft gradient that fades
 * with distance, which is a claim about light and depth; this is a flat second
 * shape with a hard edge, which is a claim about a sticker lying on paper. The
 * design has no light source and should not start implying one — and a blurred
 * shadow under a 2dp border is the exact combination that reads as a Material
 * card wearing a costume.
 *
 * **It reserves its own space.** The `padding` comes first, so the block is laid
 * out `offset` smaller and the shadow is drawn into the strip that padding just
 * freed. That is what keeps this to one call site per component: no screen has to
 * know a shadow exists, no `Arrangement.spacedBy` needs adjusting, and nothing
 * can be clipped by a parent that was sized before the shadow was added. The
 * cost is that a block is genuinely 4dp narrower and shorter than it was.
 *
 * **The colour is `outline`, not a fixed ink**, which makes this a different idea
 * in each theme and deliberately so. In light mode a near-black offset under a
 * near-white page is a shadow. In dark mode ink on ink would be invisible, so it
 * inverts to the near-white border colour and becomes a second outline, brighter
 * than the thing casting it. Both read as weight; only one of them is a shadow,
 * and pretending otherwise would mean hard-coding a colour that vanishes at
 * night.
 *
 * Takes [shape] rather than reading [BlockShape] itself so the silhouette cannot
 * drift from the block it sits under — a shadow with different corners from its
 * own card is worse than no shadow.
 *
 * [visible] hides the shadow **without releasing its space**, which is the only
 * way a control can gain one on selection: reserve it conditionally and the
 * toggle changes size under the finger that just tapped it, shoving the row it
 * sits in sideways. So an off toggle keeps a 3dp hole where its shadow would be.
 * That hole is invisible against the page, and it is the price of the control
 * never moving.
 */
@Composable
internal fun Modifier.hardShadow(
    shape: Shape,
    offset: Dp = BlockShadow,
    visible: Boolean = true,
): Modifier {
    val ink = MaterialTheme.colorScheme.outline
    return this
        .padding(end = offset, bottom = offset)
        .drawBehind {
            if (!visible) return@drawBehind
            val silhouette = shape.createOutline(size, layoutDirection, this)
            // Punch the block's own footprint out of the shadow, leaving the L
            // that actually shows. `drawBehind` is behind the *content*, not
            // behind an opaque fill — a block with `color = Color.Transparent`
            // has nothing to hide the overlap with, so without this the shadow
            // is visible straight through the block and fills it in solid ink.
            // That is what [BlockOutlineButton] did the first time round: an
            // outlined action rendered as a dark slab with its border sitting
            // 4dp inside the bottom-right corner.
            clipPath(Path().apply { addOutline(silhouette) }, ClipOp.Difference) {
                translate(left = offset.toPx(), top = offset.toPx()) {
                    drawOutline(silhouette, ink)
                }
            }
        }
}

/**
 * The solid slab at the top of a screen.
 *
 * Deliberately painted *behind* the status bar rather than below it: with
 * edge-to-edge the app owns those pixels, and a full-bleed band of colour is the
 * point of the design. The inset is applied to the slab's *content*, so the text
 * still clears the clock.
 *
 * No [BlockShape] here, and that is not an oversight: this runs to all three
 * edges it touches, so a rounded corner would have the bare page showing through
 * a notch at the top of the screen.
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
    val shape = BlockShape
    if (onClick == null) {
        Surface(
            color = fill,
            border = border,
            shape = shape,
            modifier = modifier.fillMaxWidth().hardShadow(shape),
        ) {
            content()
        }
    } else {
        Surface(
            onClick = onClick,
            color = fill,
            border = border,
            shape = shape,
            modifier = modifier.fillMaxWidth().hardShadow(shape),
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
 * A filled, full-width-by-default action.
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
        shape = BlockShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(
            horizontal = 24.dp,
            vertical = 16.dp,
        ),
        modifier = modifier.hardShadow(BlockShape),
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
 *
 * [contentColor] draws the label *and* the border, which is why the default is
 * the ink accent and not `primary`: this button has no fill to land ink on, so
 * the orange here is text on the page and has to be legible as text.
 */
@Composable
fun BlockOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.extra.accent,
    fillWidth: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = contentColor,
        border = BorderStroke(BlockBorder, contentColor),
        shape = BlockShape,
        modifier = (if (fillWidth) modifier.fillMaxWidth() else modifier)
            .hardShadow(BlockShape),
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
        shape = BlockShape,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        modifier = modifier,
    ) {
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * ON / OFF as a hard-edged cell, in place of Material's pill switch.
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
        // On keeps the ink border every other block has and changes only its
        // fill, so the switch does not appear to gain weight when it turns on —
        // an orange border on an orange fill is a border you cannot see anyway.
        // Off has no fill, so its border is all the control has: muted, but the
        // thing that still draws the outline of a tappable box.
        border = BorderStroke(
            BlockBorder,
            if (checked) MaterialTheme.colorScheme.outline else off,
        ),
        shape = BlockShape,
        modifier = modifier
            .hardShadow(BlockShape, BlockControlShadow, visible = checked)
            .toggleable(
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
 * One of a small set of choices, as a hard-edged chip.
 *
 * `selectable` with `Role.RadioButton` rather than `toggleable` like
 * [BlockToggle]: these come in groups where exactly one is on, and a screen
 * reader should say "selected, 1 of 2" rather than announcing two independent
 * switches that happen to be wired together.
 *
 * Sized for a label row rather than for a form: this sits beside a field label,
 * so it is deliberately smaller than every other block control.
 */
@Composable
fun BlockToggleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = MaterialTheme.colorScheme.primary
    Surface(
        color = if (selected) on else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        // Same rule as [BlockToggle]: selected changes the fill, not the frame.
        border = BorderStroke(
            BlockBorder,
            if (selected) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        shape = BlockShape,
        modifier = modifier
            .padding(start = 6.dp)
            .hardShadow(BlockShape, BlockControlShadow, visible = selected)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * "There is something to know about this one" — and the only control that shows
 * what.
 *
 * The caveat prose is hidden by default everywhere it can appear: a 28-item
 * picker and a rule with six actions both turn into a wall of amber if every
 * caveat prints itself, and the sentence is worth reading precisely because it
 * is not competing with thirty others. So the glyph is what a list carries, and
 * tapping it is what brings the sentence — here, once, at the moment someone
 * asked for it.
 *
 * [shown] is fed back in rather than held here so the prose and the badge cannot
 * disagree, and so whoever owns the layout decides where the revealed sentence
 * goes. It reads as a toggle to the accessibility tree, so its open/closed state
 * is spoken rather than left to the glyph.
 */
@Composable
fun CaveatBadge(
    shown: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two sizes, deliberately not one. The glyph stays 22dp — the class doc
    // above explains why that figure is load-bearing, sitting inline in a
    // 28-item picker row and a block header alike — while the *touch* target
    // grows to 48dp, Android's accessibility minimum: this is the sole route
    // to a component's caveat prose, so a target well under half the minimum
    // is the actual bug. [OverflowingTouchTarget] is what lets the two numbers
    // differ without a fight — it reports 22dp to whatever this sits inside,
    // so no row reflows, and lets the real, bigger touch target overhang that
    // reported footprint instead of claiming space of its own. Don't collapse
    // this back to a single `.size()`: that either reintroduces the
    // accessibility gap this exists to fix, or reflows every dense list and
    // header that carries a caveat.
    OverflowingTouchTarget(visualSize = 22.dp, touchSize = 48.dp, modifier = modifier) {
        Box(
            modifier = Modifier
                .toggleable(value = shown, role = Role.Button, onValueChange = { onToggle() })
                .semantics { contentDescription = CAVEAT_DESCRIPTION },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(BlockShape)
                    .border(BlockBorder, MaterialTheme.extra.caution, BlockShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "!",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.extra.caution,
                )
            }
        }
    }
}

/** Read by the accessibility layer and by the instrumented test. */
internal const val CAVEAT_DESCRIPTION = "Has a caveat"

/**
 * Wraps a single child so it reports [visualSize] to whatever it sits inside,
 * while the child is actually measured and placed at [touchSize] — centred on,
 * and overhanging, that smaller reported footprint.
 *
 * Exists for [CaveatBadge]. Android's touch-target minimum (48dp) is more than
 * double the badge's 22dp glyph, and the glyph's size is load-bearing — it sits
 * inline in a 28-item component picker and a block header, both laid out
 * around that figure. Reserving 48dp of real layout space instead — the way
 * `Modifier.minimumInteractiveComponentSize()` or Material's own `IconButton`
 * do — would grow every row that carries a caveat: in the picker, a ~48dp row
 * would become a ~76dp one for two thirds of the triggers, which is exactly
 * the "wall" density problem the caveat badge was built to avoid in the first
 * place (see "Warnings are not errors" in the architecture doc). Compose does
 * not clip a child's hit-testing to its parent's *reported* size unless
 * something says to, so the real, bigger, genuinely-clickable box is given its
 * own child layout node here and left to overhang the smaller footprint this
 * reports upward, rather than claim it.
 */
@Composable
private fun OverflowingTouchTarget(
    visualSize: Dp,
    touchSize: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val touchPx = touchSize.roundToPx()
        val visualPx = visualSize.roundToPx()
        val placeable = measurables.single().measure(Constraints.fixed(touchPx, touchPx))
        // Coerced rather than fixed at visualPx: a Row rarely constrains its
        // cross-axis, but nothing here should assume that of every call site.
        val width = visualPx.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = visualPx.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // Centred on the reported box, which is smaller than the touch
            // target being placed — the offset is negative on both axes, and
            // that overhang is the entire point of this function.
            placeable.placeRelative((width - touchPx) / 2, (height - touchPx) / 2)
        }
    }
}

/**
 * The strip at the bottom of a screen.
 *
 * Owns the navigation-bar inset so the buttons inside it never have to think
 * about it, and paints a border along its top edge because the content scrolls
 * underneath — without the line, a half-scrolled card looks like it belongs to
 * the bar.
 *
 * Square for the same reason as [BlockHeader]: it is full-bleed, and the only
 * edge of it that is not a screen edge already carries a [BlockDivider].
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
 * the block skin, because the default is a fully rounded pill with a circular
 * thumb, and this app's 3dp is a corner rather than a curve.
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
 * colours — and a pill in the right colours is still a pill.
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
            .background(MaterialTheme.colorScheme.primary, BlockShape)
            .border(BlockBorder, MaterialTheme.colorScheme.outline, BlockShape)
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
            .background(MaterialTheme.colorScheme.surface, BlockShape)
            .border(BlockBorder, MaterialTheme.colorScheme.outline, BlockShape)
            // Clips the fill below to the trough's own corners. Without it the
            // filled portion stays a hard rectangle and pushes out through them
            // at any radius above zero — which is invisible while the shape is
            // square, and therefore exactly the kind of thing left out.
            .clip(BlockShape)
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
