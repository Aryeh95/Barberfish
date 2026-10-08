package com.jpweytjens.barberfish.datatype.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withRotation
import com.jpweytjens.barberfish.R
import io.hammerhead.karooext.models.ViewConfig

/** Share of the height the speed row takes; the summary line gets the rest. */
internal const val HEADWIND_SPEED_SHARE = 0.62f

/** Gap between the left column and the numbers, as a share of the arrow size. */
internal const val HEADWIND_GAP_RATIO = 0.08f

/** Gap between the corner icon and the arrow below it, as a share of the icon size. */
internal const val HEADWIND_ICON_GAP_RATIO = 0.15f

/** Sizes for the Headwind-style value, in px, before any shrink to fit the width. */
internal data class HeadwindStyleGeometry(
    val speedBandPx: Float,
    val summaryBandPx: Float,
    val iconPx: Float,
    val arrowTopPx: Float,
    val arrowPx: Float,
)

/**
 * The speed row takes [HEADWIND_SPEED_SHARE] of [heightPx] and the summary the rest. The arrow
 * spans the height, or, with a corner icon of [iconPx], the height left below the icon.
 */
internal fun headwindStyleGeometry(heightPx: Int, iconPx: Float): HeadwindStyleGeometry {
    val h = heightPx.toFloat()
    val speedBand = h * HEADWIND_SPEED_SHARE
    val arrowTop = if (iconPx > 0f) iconPx * (1f + HEADWIND_ICON_GAP_RATIO) else 0f
    return HeadwindStyleGeometry(speedBand, h - speedBand, iconPx, arrowTop, h - arrowTop)
}

/** Widest-case texts the fit is measured against, so the layout holds still as values change. */
internal const val HEADWIND_SPEED_PROBE = "88.8"
internal const val HEADWIND_SUMMARY_PROBE = "-88▲88"

/** Share of the value height the corner icon takes when the header is off. */
internal const val HEADWIND_ICON_SHARE = 0.22f

/** [text], or [probe] when that is longer: the width the layout is fitted for. */
internal fun fitText(text: String, probe: String): String =
    if (text.length > probe.length) text else probe

/**
 * The Wind slot in karoo-headwind's Tailwind & ride speed layout: a wind arrow rotated by
 * [angleDeg] on the left, [speed] on top and the [summary] line below, both right-aligned against
 * each other, everything in [color]. With [iconRes] (header off) the icon sits at the top of the
 * left column, tinted [iconTint], and the arrow below it. The group is aligned per [alignment] and
 * shrunk as one to fit [cellWidthPx]. The fit uses widest-case probes, so the arrow and digits hold
 * still as the values change width.
 */
@Suppress("LongParameterList")
fun renderHeadwindStyleBitmap(
    speed: String,
    summary: String,
    angleDeg: Float?,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    color: Int,
    iconTint: Int,
    iconRes: Int?,
    alignment: ViewConfig.Alignment,
    context: Context,
): Bitmap {
    val width = cellWidthPx.toInt().coerceAtLeast(1)
    val geo =
        headwindStyleGeometry(
            bitmapHeightPx,
            if (iconRes != null) bitmapHeightPx * HEADWIND_ICON_SHARE else 0f,
        )
    val text = HeadwindText(color)
    val speedPx = text.sizeForBand(geo.speedBandPx, 0.9f)
    val summaryPx = text.sizeForBand(geo.summaryBandPx, 0.8f)
    val column = maxOf(geo.arrowPx, geo.iconPx) * (1f + HEADWIND_GAP_RATIO)
    val textW =
        maxOf(
            text.width(fitText(speed, HEADWIND_SPEED_PROBE), speedPx),
            text.width(fitText(summary, HEADWIND_SUMMARY_PROBE), summaryPx),
        )
    val scale = (width / (column + textW)).coerceAtMost(1f)
    val groupW = (column + textW) * scale
    val left =
        when (alignment) {
            ViewConfig.Alignment.LEFT -> 0f
            ViewConfig.Alignment.CENTER -> (width - groupW) / 2f
            ViewConfig.Alignment.RIGHT -> width - groupW
        }

    val bitmap = createBitmap(width, bitmapHeightPx)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    canvas.drawHeadwindGlyphs(context, geo, left, scale, angleDeg, color, iconRes, iconTint)
    val right = left + groupW
    text.drawRow(canvas, speed, speedPx * scale, right, 0f, geo.speedBandPx)
    text.drawRow(canvas, summary, summaryPx * scale, right, geo.speedBandPx, geo.summaryBandPx)
    return bitmap
}

/** The value font for the Headwind layout: right-aligned, rows centred on their digits. */
private class HeadwindText(private val color: Int) {
    private val bounds = Rect()

    fun paint(sizePx: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            textSize = sizePx
            this.color = color
            letterSpacing = LETTER_SPACING
            textAlign = Paint.Align.RIGHT
        }

    /** Font size at which a digit fills [fill] of [bandPx]. */
    fun sizeForBand(bandPx: Float, fill: Float): Float {
        paint(100f).getTextBounds("0", 0, 1, bounds)
        return bandPx * fill * 100f / bounds.height().coerceAtLeast(1)
    }

    fun width(text: String, sizePx: Float): Float = paint(sizePx).measureText(text)

    /**
     * [text] right-aligned at [right], centred in its band on the digit "0" rather than on its own
     * bounds, so signs and trend glyphs never move the baseline.
     */
    @Suppress("LongParameterList")
    fun drawRow(
        canvas: Canvas,
        text: String,
        sizePx: Float,
        right: Float,
        top: Float,
        band: Float,
    ) {
        val p = paint(sizePx)
        p.getTextBounds("0", 0, 1, bounds)
        canvas.drawText(text, right, top + band / 2f - (bounds.top + bounds.bottom) / 2f, p)
    }
}

/** The corner icon (header off) and the rotated arrow in the left column. */
@Suppress("LongParameterList")
private fun Canvas.drawHeadwindGlyphs(
    context: Context,
    geo: HeadwindStyleGeometry,
    left: Float,
    scale: Float,
    angleDeg: Float?,
    color: Int,
    iconRes: Int?,
    iconTint: Int,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    if (iconRes != null) {
        val size = (geo.iconPx * scale).toInt().coerceAtLeast(1)
        val icon = tintedGlyph(context, iconRes, iconTint, size)
        drawBitmap(icon, null, RectF(left, 0f, left + size, size.toFloat()), paint)
    }
    if (angleDeg != null) {
        val size = geo.arrowPx * scale
        val top = geo.arrowTopPx + (geo.arrowPx - size) / 2f
        val arrow =
            tintedGlyph(context, R.drawable.ic_wind_arrow, color, size.toInt().coerceAtLeast(1))
        withRotation(angleDeg, left + size / 2f, top + size / 2f) {
            drawBitmap(arrow, null, RectF(left, top, left + size, top + size), paint)
        }
    }
}

private fun tintedGlyph(context: Context, res: Int, tint: Int, sizePx: Int): Bitmap =
    ContextCompat.getDrawable(context, res)!!.mutate()
        .apply { setTint(tint) }
        .toBitmap(sizePx, sizePx)
