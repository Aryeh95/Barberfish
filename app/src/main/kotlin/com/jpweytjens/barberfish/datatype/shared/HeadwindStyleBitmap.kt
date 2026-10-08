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

/** Sizes for the Headwind-style value, in px, before any shrink to fit the width. */
internal data class HeadwindStyleGeometry(
    val speedBandPx: Float,
    val summaryBandPx: Float,
    val arrowTopPx: Float,
    val arrowPx: Float,
)

/**
 * The speed row takes [HEADWIND_SPEED_SHARE] of [heightPx] and the summary the rest. The arrow
 * spans the height below [topReservePx], the space kept clear for the header icon when the header
 * row is hidden (0 with the header shown).
 */
internal fun headwindStyleGeometry(heightPx: Int, topReservePx: Float): HeadwindStyleGeometry {
    val h = heightPx.toFloat()
    val speedBand = h * HEADWIND_SPEED_SHARE
    val top = topReservePx.coerceIn(0f, h / 2f)
    return HeadwindStyleGeometry(speedBand, h - speedBand, top, h - top)
}

/** Widest-case texts the fit is measured against, so the layout holds still as values change. */
internal const val HEADWIND_SPEED_PROBE = "88.8"
internal const val HEADWIND_SUMMARY_PROBE = "-88▲88"

/** [text], or [probe] when that is longer: the width the layout is fitted for. */
internal fun fitText(text: String, probe: String): String =
    if (text.length > probe.length) text else probe

/**
 * The Wind slot in karoo-headwind's Tailwind & ride speed layout: a wind arrow rotated by
 * [angleDeg] on the left, [speed] on top and the [summary] line below, both right-aligned against
 * each other, everything in [color]. With the header hidden, [topReservePx] keeps the top of the
 * left column clear for the header icon and the arrow sits below it; [rightInsetPx] keeps the
 * numbers clear of a header icon on the right (LEFT alignment puts the icons there). The group is
 * aligned per [alignment] and shrunk as one to fit [cellWidthPx]. The fit uses widest-case probes,
 * so the arrow and digits hold still as the values change width.
 */
@Suppress("LongParameterList")
fun renderHeadwindStyleBitmap(
    speed: String,
    summary: String,
    angleDeg: Float?,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    color: Int,
    topReservePx: Float,
    rightInsetPx: Float,
    alignment: ViewConfig.Alignment,
    context: Context,
): Bitmap {
    val width = cellWidthPx.toInt().coerceAtLeast(1)
    val geo = headwindStyleGeometry(bitmapHeightPx, topReservePx)
    val text = HeadwindText(color)
    val speedPx = text.sizeForBand(geo.speedBandPx, 0.9f)
    val summaryPx = text.sizeForBand(geo.summaryBandPx, 0.8f)
    val column = geo.arrowPx * (1f + HEADWIND_GAP_RATIO)
    val textW =
        maxOf(
            text.width(fitText(speed, HEADWIND_SPEED_PROBE), speedPx),
            text.width(fitText(summary, HEADWIND_SUMMARY_PROBE), summaryPx),
        )
    val room = (width - rightInsetPx).coerceAtLeast(1f)
    val scale = (room / (column + textW)).coerceAtMost(1f)
    val groupW = (column + textW) * scale
    val left =
        when (alignment) {
            ViewConfig.Alignment.LEFT -> 0f
            ViewConfig.Alignment.CENTER -> (room - groupW) / 2f
            ViewConfig.Alignment.RIGHT -> room - groupW
        }

    val bitmap = createBitmap(width, bitmapHeightPx)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    if (angleDeg != null) canvas.drawHeadwindArrow(context, geo, left, scale, angleDeg, color)
    val right = left + groupW
    text.drawRow(canvas, speed, speedPx * scale, right, 0f, geo.speedBandPx)
    text.drawRow(canvas, summary, summaryPx * scale, right, geo.speedBandPx, geo.summaryBandPx)
    return bitmap
}

/** The value font for the Headwind layout: right-aligned, rows centred on their digits. */
private class HeadwindText(private val textColor: Int) {
    private val bounds = Rect()

    fun paint(sizePx: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            textSize = sizePx
            color = textColor
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

/** The arrow in the left column, rotated by [angleDeg] about its own centre. */
@Suppress("LongParameterList")
private fun Canvas.drawHeadwindArrow(
    context: Context,
    geo: HeadwindStyleGeometry,
    left: Float,
    scale: Float,
    angleDeg: Float,
    color: Int,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    val size = geo.arrowPx * scale
    val top = geo.arrowTopPx + (geo.arrowPx - size) / 2f
    val arrow = tintedGlyph(context, R.drawable.ic_wind_arrow, color, size.toInt().coerceAtLeast(1))
    withRotation(angleDeg, left + size / 2f, top + size / 2f) {
        drawBitmap(arrow, null, RectF(left, top, left + size, top + size), paint)
    }
}

private fun tintedGlyph(context: Context, res: Int, tint: Int, sizePx: Int): Bitmap =
    ContextCompat.getDrawable(context, res)!!.mutate()
        .apply { setTint(tint) }
        .toBitmap(sizePx, sizePx)
