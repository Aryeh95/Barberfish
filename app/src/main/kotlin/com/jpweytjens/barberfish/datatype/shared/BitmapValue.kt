package com.jpweytjens.barberfish.datatype.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.roundToInt

private const val MIN_BITMAP_HEIGHT_PX = 30
internal const val LETTER_SPACING = -0.04f

/**
 * Prefix glued to the climb value in the stacked Ride Remaining field; rendered as the ascent arrow
 * icon (see [renderStackedValueBitmap]).
 */
const val ASCENT_MARKER = "↗ "

/** Prefix glued to Ride Remaining's descent value; rendered as the descent arrow icon. */
const val DESCENT_MARKER = "↘ "

private val VALUE_MARKERS = listOf(ASCENT_MARKER, DESCENT_MARKER)

/** [text] without its leading marker glyph, which an icon replaces. */
internal fun stripValueMarker(text: String): String =
    VALUE_MARKERS.firstOrNull { text.startsWith(it) }?.let { text.removePrefix(it) } ?: text

// Bitmap height as a fraction of the value font base sp. Sized to the
// visible cap (~0.7 × textSize) plus a small buffer; tight enough to fit
// 5×1's baseline_box without ImageView fitCenter downscaling.
internal const val VALUE_BITMAP_HEIGHT_RATIO = 0.74f

/**
 * Constant bitmap height per layout: `VALUE_BITMAP_HEIGHT_RATIO × valueFontBaseSp × density`.
 *
 * Independent of `fontSizeForCell` shrinks so the baseline stays stable across content-driven font
 * changes.
 */
fun valueBitmapHeightPx(valueFontBaseSp: Int, density: Float): Int {
    val raw = (VALUE_BITMAP_HEIGHT_RATIO * valueFontBaseSp * density).toInt()
    return raw.coerceAtLeast(MIN_BITMAP_HEIGHT_PX)
}

// Fraction of a row's band the digits fill, leaving a small vertical margin so the top row
// doesn't clip against the bitmap's top edge and the rows stay readable.
private const val TWO_ROW_DIGIT_FILL = 0.86f

/**
 * Render stacked value [rows] into a full-cell-width `ARGB_8888` bitmap. The rows share the
 * standard single-row value height ([bitmapHeightPx]) split into equal bands, so the stacked field
 * keeps the same value footprint as the single-row fields. Each row is aligned to the field edge
 * per [alignment] (matching the single-row fields) and vertically centered in its band. The font is
 * sized so a digit fills [TWO_ROW_DIGIT_FILL] of a band, then shrunk to fit the cell width.
 *
 * Each row may carry a leading icon ([icons], pre-tinted, by row) drawn inline before its number as
 * [icon][gap][number] — e.g. the route glyph before the distance and the ascent arrow before the
 * climb in Ride Remaining. A row whose text starts with a marker ([ASCENT_MARKER],
 * [DESCENT_MARKER]) has the glyph stripped (the icon replaces it); a row with an icon but no marker
 * keeps its full number. Rows without an icon draw their whole string aligned to the field edge.
 * Every row is expected to be non-empty.
 */
// Suppressed: like renderWindArrowValueBitmap and renderHeaderBitmap, one parameter per
// independent input; no grouping type would earn its keep.
@Suppress("LongParameterList")
fun renderStackedValueBitmap(
    rows: List<String>,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    color: Int,
    alignment: ViewConfig.Alignment,
    rowGapPx: Float = 4f,
    icons: List<Bitmap?> = emptyList(),
): Bitmap {
    val width = cellWidthPx.toInt().coerceAtLeast(1)
    val count = rows.size.coerceAtLeast(1)
    val bandPx = ((bitmapHeightPx - rowGapPx * (count - 1)) / count).coerceAtLeast(1f)

    fun paintAt(sizePx: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            textSize = sizePx
            this.color = color
            letterSpacing = LETTER_SPACING
        }

    val bounds = Rect()
    // Height-fit: a digit fills most of one band (margin avoids top/bottom clipping).
    paintAt(100f).getTextBounds("0", 0, 1, bounds)
    var fontPx =
        if (bounds.height() > 0) 100f * (bandPx * TWO_ROW_DIGIT_FILL) / bounds.height() else bandPx
    // Width-fit: shrink so the widest row fits the cell.
    run {
        val p = paintAt(fontPx)
        val needed = rows.maxOfOrNull { p.measureText(it) } ?: 0f
        if (needed > width) fontPx *= width / needed
    }
    val paint = paintAt(fontPx)
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    val iconGapPx = fontPx * 0.12f

    val bitmap = Bitmap.createBitmap(width, bitmapHeightPx, Bitmap.Config.ARGB_8888)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)

    // Draw a row vertically centered within the band starting at [bandTop]. With an [icon] it is
    // drawn as [icon][gap][number] (a marker glyph stripped if present); otherwise the whole
    // string is drawn aligned to the field edge.
    fun drawRow(text: String, bandTop: Float, icon: Bitmap?) {
        val center = bandTop + bandPx / 2f
        if (icon == null) {
            paint.textAlign = alignment.paintAlign()
            paint.getTextBounds(text, 0, text.length, bounds)
            canvas.drawText(
                text,
                alignment.startX(width, 0f),
                center - (bounds.top + bounds.bottom) / 2f,
                paint,
            )
            return
        }
        val number = stripValueMarker(text)
        paint.textAlign = Paint.Align.LEFT
        paint.getTextBounds(number, 0, number.length, bounds)
        val numW = paint.measureText(number)
        val iconSize = bounds.height() * 1.3f
        val unitLeft = alignment.startX(width, iconSize + iconGapPx + numW)
        val iconTop = center - iconSize / 2f
        canvas.drawBitmap(
            icon,
            null,
            RectF(unitLeft, iconTop, unitLeft + iconSize, iconTop + iconSize),
            iconPaint,
        )
        canvas.drawText(
            number,
            unitLeft + iconSize + iconGapPx,
            center - (bounds.top + bounds.bottom) / 2f,
            paint,
        )
    }
    rows.forEachIndexed { i, row -> drawRow(row, i * (bandPx + rowGapPx), icons.getOrNull(i)) }
    return bitmap
}

/** Paint alignment matching the field's [ViewConfig.Alignment]. */
private fun ViewConfig.Alignment.paintAlign(): Paint.Align =
    when (this) {
        ViewConfig.Alignment.LEFT -> Paint.Align.LEFT
        ViewConfig.Alignment.CENTER -> Paint.Align.CENTER
        ViewConfig.Alignment.RIGHT -> Paint.Align.RIGHT
    }

/**
 * Left edge of content [contentW] wide aligned in [width]. With a zero width it is the anchor a
 * [paintAlign]-aligned string is drawn at.
 */
private fun ViewConfig.Alignment.startX(width: Int, contentW: Float): Float =
    when (this) {
        ViewConfig.Alignment.LEFT -> 0f
        ViewConfig.Alignment.CENTER -> (width - contentW) / 2f
        ViewConfig.Alignment.RIGHT -> width - contentW
    }

// Share of the value height a digit fills when values sit side by side, as tall as the single
// value they replace.
private const val SIDE_BY_SIDE_DIGIT_FILL = 0.92f

// Gap between side-by-side values, and between an icon and its number, in ems.
private const val SIDE_BY_SIDE_GAP_EM = 0.45f
private const val SIDE_BY_SIDE_ICON_GAP_EM = 0.12f

/** Values drawn side by side, each with an optional pre-tinted leading icon ([icons], by value). */
data class SideBySideValues(val values: List<String>, val icons: List<Bitmap?> = emptyList())

/**
 * Render values side by side into one value-height bitmap, the horizontal counterpart of
 * [renderStackedValueBitmap]: each as [icon][gap][number] (a marker glyph stripped, the icon
 * replacing it), the row aligned to the field edge per [alignment] and vertically centred. The font
 * fills the height like a single value and shrinks so the row fits [cellWidthPx].
 */
fun renderSideBySideValueBitmap(
    values: SideBySideValues,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    color: Int,
    alignment: ViewConfig.Alignment,
): Bitmap {
    val width = cellWidthPx.toInt().coerceAtLeast(1)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            this.color = color
            letterSpacing = LETTER_SPACING
            textAlign = Paint.Align.LEFT
            textSize = 100f
        }
    val zero = Rect()
    paint.getTextBounds("0", 0, 1, zero)
    paint.textSize =
        if (zero.height() > 0) 100f * bitmapHeightPx * SIDE_BY_SIDE_DIGIT_FILL / zero.height()
        else bitmapHeightPx.toFloat()
    val numbers = values.values.map(::stripValueMarker)
    val icons = numbers.indices.map { values.icons.getOrNull(it) }
    fun totalWidth(): Float {
        paint.getTextBounds("0", 0, 1, zero)
        val icon = zero.height() * 1.1f
        val units =
            numbers.indices.sumOf { i ->
                val iconW =
                    if (icons[i] != null) icon + paint.textSize * SIDE_BY_SIDE_ICON_GAP_EM else 0f
                (iconW + paint.measureText(numbers[i])).toDouble()
            }
        val gaps = (numbers.size - 1).coerceAtLeast(0)
        return units.toFloat() + paint.textSize * SIDE_BY_SIDE_GAP_EM * gaps
    }
    val needed = totalWidth()
    if (needed > width) paint.textSize *= width / needed
    paint.getTextBounds("0", 0, 1, zero)
    val iconSize = zero.height() * 1.1f
    val centre = bitmapHeightPx / 2f
    val baseline = centre - (zero.top + zero.bottom) / 2f
    var x = alignment.startX(width, totalWidth())
    val bitmap = createBitmap(width, bitmapHeightPx)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    numbers.forEachIndexed { i, number ->
        icons[i]?.let { icon ->
            val top = centre - iconSize / 2f
            canvas.drawBitmap(icon, null, RectF(x, top, x + iconSize, top + iconSize), iconPaint)
            x += iconSize + paint.textSize * SIDE_BY_SIDE_ICON_GAP_EM
        }
        canvas.drawText(number, x, baseline, paint)
        x += paint.measureText(number) + paint.textSize * SIDE_BY_SIDE_GAP_EM
    }
    return bitmap
}

/**
 * Render `text` into an `ARGB_8888` bitmap with the baseline pinned to the bitmap's bottom edge
 * (`bounds.bottom` ≈ 0 for digits).
 *
 * `bitmap.density = Bitmap.DENSITY_NONE` so RemoteViews renders at native pixel size with no
 * scaling. Width is clamped to `cellWidthPx`.
 */
fun renderValueBitmap(
    text: String,
    fontSizePx: Float,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    color: Int,
    alignment: ViewConfig.Alignment,
): Bitmap {
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            textSize = fontSizePx
            this.color = color
            letterSpacing = LETTER_SPACING
            textAlign =
                when (alignment) {
                    ViewConfig.Alignment.LEFT -> Paint.Align.LEFT
                    ViewConfig.Alignment.CENTER -> Paint.Align.CENTER
                    ViewConfig.Alignment.RIGHT -> Paint.Align.RIGHT
                }
        }
    val measuredWidth = paint.measureText(text)
    val cellW = cellWidthPx.toInt().coerceAtLeast(1)
    val width = measuredWidth.toInt().coerceIn(1, cellW)

    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    val baselineY = (bitmapHeightPx - bounds.bottom).toFloat()

    val bitmap = Bitmap.createBitmap(width, bitmapHeightPx, Bitmap.Config.ARGB_8888)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)

    val xPos =
        when (alignment) {
            ViewConfig.Alignment.LEFT -> 0f
            ViewConfig.Alignment.CENTER -> width / 2f
            ViewConfig.Alignment.RIGHT -> width.toFloat()
        }
    canvas.drawText(text, xPos, baselineY, paint)
    return bitmap
}

/**
 * Render an all-caps header [text] into an `ARGB_8888` bitmap that reproduces the native header
 * TextView: `lines=[maxLines]`, font padding off, per-layout [lineSpacingMult] (0.6 in 2-col 5-row
 * cells, 0.7 elsewhere). The reservation is the TextView height `lineH + (maxLines − 1) ×
 * round(lineH × mult)` with `lineH` from `Paint.getFontMetricsInt`; shorter text centers in it
 * (gravity center_vertical) and [translationYPx] shifts the block (−3 px at 5-row Large). See
 * docs/sdk-findings.md § "Native header and value sizing". `density = DENSITY_NONE` so RemoteViews
 * renders 1:1.
 */
fun renderHeaderBitmap(
    text: String,
    fontSizePx: Float,
    maxLines: Int,
    availableWidthPx: Int,
    color: Int,
    alignment: ViewConfig.Alignment,
    lineSpacingMult: Float,
    translationYPx: Int = 0,
): Bitmap {
    val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.NORMAL)
            textSize = fontSizePx
            this.color = color
        }
    val width = availableWidthPx.coerceAtLeast(1)
    val upper = text.uppercase()
    val align =
        when (alignment) {
            ViewConfig.Alignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            ViewConfig.Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            ViewConfig.Alignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }

    val layout =
        StaticLayout.Builder.obtain(upper, 0, upper.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, lineSpacingMult)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    val fm = paint.fontMetricsInt
    val lineHeight = fm.descent - fm.ascent
    val reservedHeight = lineHeight + (maxLines - 1) * (lineHeight * lineSpacingMult).roundToInt()
    val bitmapHeight = maxOf(reservedHeight, layout.height).coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(width, bitmapHeight, Bitmap.Config.ARGB_8888)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    // A negative translation may clip the top of the first line's ascent area;
    // native clips identically at the cell edge and cap tops sit well below it.
    val centerOffset = ((reservedHeight - layout.height) / 2f).coerceAtLeast(0f)
    canvas.translate(0f, centerOffset + translationYPx)
    layout.draw(canvas)
    return bitmap
}
