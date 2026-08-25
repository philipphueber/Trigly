package app.phueber.trigly.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Outcome of asking the launcher to pin a shortcut. */
sealed interface PinShortcutResult {

    /** The launcher accepted the request; it owns its own confirmation UI from here. */
    data object Requested : PinShortcutResult

    /**
     * This launcher does not implement pinning at all.
     *
     * Not every launcher does - `isRequestPinShortcutSupported` is a real
     * question with a real "no" answer, not a formality to check past. The
     * honest thing is to say so and let the caller tell the user, rather than
     * silently doing nothing and leaving them to wonder why no shortcut showed up.
     */
    data object UnsupportedByLauncher : PinShortcutResult
}

/**
 * Asks the launcher to pin a shortcut that reopens on [ShortcutTargetActivity].
 *
 * Builds against [ShortcutInfoCompat] / [ShortcutManagerCompat] rather than the
 * platform `ShortcutManager` directly: `androidx.core` is already a dependency
 * of this module, the compat layer is what actually ships the pin-request flow
 * (`requestPinShortcut`) as opposed to just the older "create and hope"
 * `ShortcutManager.addDynamicShortcuts`, and it is the one place the platform
 * API and its AndroidX mirror do not behave identically across OEMs.
 */
object ShortcutPinning {

    /** Extra carrying the id of the shortcut that was tapped. Read by [ShortcutTargetActivity]. */
    const val EXTRA_SHORTCUT_ID = "app.phueber.trigly.EXTRA_SHORTCUT_ID"

    /**
     * A square bitmap render of an emoji, or the app's own icon.
     *
     * There is no platform API that turns an emoji string into an `Icon` -
     * emoji are just text, and the launcher wants a bitmap - so this draws it:
     * 192px, which is the size a legacy (non-adaptive) launcher icon renders
     * at on the highest-density (xxxhdpi, 4x) devices for the platform's usual
     * 48dp baseline. Anything smaller would be visibly upscaled on those
     * screens; anything larger buys no more fidelity for a bitmap that only
     * exists to hand the launcher a picture.
     */
    private const val ICON_SIZE_PX = 192

    /** Starting guess for how much of the canvas the glyph should fill before measuring it. */
    private const val INITIAL_TEXT_SIZE_RATIO = 0.7f

    /** How much of the canvas a glyph may occupy after the fit-to-bounds pass, so it never touches the edge. */
    private const val MAX_FILL_RATIO = 0.82f

    /**
     * Requires an [Activity], not an application [android.content.Context]:
     * `requestPinShortcut` puts up the launcher's own confirmation dialog, and
     * that dialog is launched in the context of - and needs to be attributed
     * to - a foreground activity the user is currently looking at.
     */
    fun requestPinShortcut(
        activity: Activity,
        shortcutId: String,
        label: String,
        emoji: String,
    ): PinShortcutResult {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            return PinShortcutResult.UnsupportedByLauncher
        }

        // ACTION_VIEW because ShortcutInfo requires a non-null action on its
        // intent and will throw at build() otherwise - the platform is
        // enforcing that a shortcut always does something, even though the
        // component here ignores the action and only reads the extra.
        val intent = Intent(activity, ShortcutTargetActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SHORTCUT_ID, shortcutId)

        val shortcut = ShortcutInfoCompat.Builder(activity, shortcutId)
            .setShortLabel(label)
            .setIcon(emojiIcon(activity, emoji))
            .setIntent(intent)
            .build()

        // No result intent: nothing here needs to know whether the user
        // actually confirmed the launcher's dialog, only that the request was
        // made. If a future caller needs to react to the actual pin, this is
        // where a PendingIntent would go.
        ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
        return PinShortcutResult.Requested
    }

    private fun emojiIcon(context: Context, emoji: String): IconCompat {
        if (emoji.isBlank()) {
            // A blank emoji is the caller telling us there is nothing to draw,
            // not an error - fall back to the icon the launcher would show for
            // any other Trigly shortcut anyway.
            return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        }
        return IconCompat.createWithBitmap(renderEmojiBitmap(emoji))
    }

    private fun renderEmojiBitmap(emoji: String): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Explicit clear rather than trusting a freshly allocated bitmap to
        // already be transparent: this is the one line standing between "an
        // emoji icon" and "an emoji on an opaque square", and it costs nothing.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = ICON_SIZE_PX * INITIAL_TEXT_SIZE_RATIO
        }

        // The initial text size is only a guess: a single-codepoint emoji and
        // a multi-codepoint one (a flag, a ZWJ family sequence) do not occupy
        // anywhere near the same fraction of their nominal point size, so the
        // glyph is measured and the size corrected once rather than trusting
        // the guess and risking a clipped edge.
        val bounds = Rect()
        paint.getTextBounds(emoji, 0, emoji.length, bounds)
        val glyphExtent = maxOf(bounds.width(), bounds.height()).toFloat()
        val targetExtent = ICON_SIZE_PX * MAX_FILL_RATIO
        if (glyphExtent > targetExtent && glyphExtent > 0f) {
            paint.textSize *= targetExtent / glyphExtent
        }

        // drawText positions a baseline, not a visual centre, so centring
        // vertically means folding the font's ascent/descent into the y
        // coordinate - skipping this leaves the glyph looking low, not centred.
        val metrics = paint.fontMetrics
        val y = ICON_SIZE_PX / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(emoji, ICON_SIZE_PX / 2f, y, paint)

        return bitmap
    }
}
