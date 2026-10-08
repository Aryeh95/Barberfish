package com.jpweytjens.barberfish.datatype.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import com.jpweytjens.barberfish.R
import io.hammerhead.karooext.models.ViewConfig

/** Gap between the two rows, as in [renderTwoRowValueBitmap]. */
internal const val SPEED_WIND_ROW_GAP_PX = 4f

/** Share of a row's band a digit fills. Same value as TWO_ROW_DIGIT_FILL in BitmapValue.kt. */
internal const val SPEED_WIND_DIGIT_FILL = 0.86f

/** Width the total wind speed is fitted for, so one- and two-digit speeds share a size. */
internal const val SPEED_WIND_SPEED_PROBE = "88"

/**
 * Font size for the total wind speed above the arrow: the main font, shrunk only if the probe would
 * not fit the arrow's box. Never larger than [fontPx].
 */
internal fun windSpeedFontPx(fontPx: Float, probeWidthPx: Float, boxPx: Int): Float =
    if (probeWidthPx > boxPx) fontPx * boxPx / probeWidthPx else fontPx

/** Where the speed-over-wind stack puts things, in px. */
internal data class SpeedWindGeometry(
    val bandPx: Float,
    val rowGapPx: Float,
    val boxPx: Int,
    val gapPx: Int,
    val textLeftPx: Int,
)

/**
 * The stack splits the value height into two bands like Ride Remaining. The wind arrow's box is a
 * square of one band, at the left edge, with the usual gap before the number column.
 */
internal fun speedWindGeometry(bitmapHeightPx: Int, density: Float): SpeedWindGeometry {
    val band = ((bitmapHeightPx - SPEED_WIND_ROW_GAP_PX) / 2f).coerceAtLeast(1f)
    val box = windArrowBoxPx(band.toInt())
    val gap = (WIND_ARROW_GAP_DP * density).toInt()
    return SpeedWindGeometry(band, SPEED_WIND_ROW_GAP_PX, box, gap, box + gap)
}

/**
 * The Wind slot with Show speed: [speedText] on the top row and [windText] on the bottom row, with
 * the wind arrow rotated by [angleDeg] in a fixed column left of the wind number, as
 * [renderWindArrowValueBitmap] places it. Both numbers share one font, sized so a digit fills
 * [SPEED_WIND_DIGIT_FILL] of a band and shrunk only if the wider row does not fit, and share one
 * edge per [alignment]. A null [angleDeg] (calm) leaves the arrow column empty so nothing moves.
 * [windSpeedText], the total wind speed, sits in the arrow column of the top row: wind strength
 * over wind direction. Colour stays on the wind number; speed and arrow take the header colour.
 */
// Suppressed: matches the sibling renderers (renderWindArrowValueBitmap, renderTwoRowValueBitmap),
// one parameter per independent input.
@Suppress("LongParameterList")
fun renderSpeedWindValueBitmap(
    speedText: String,
    windText: String,
    angleDeg: Float?,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    speedColor: Int,
    windColor: Int,
    arrowColor: Int,
    alignment: ViewConfig.Alignment,
    context: Context,
    windSpeedText: String? = null,
): Bitmap {
    val geo = speedWindGeometry(bitmapHeightPx, context.resources.displayMetrics.density)
    val width = cellWidthPx.toInt().coerceAtLeast(1)

    fun paintAt(sizePx: Float, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            textSize = sizePx
            this.color = color
            letterSpacing = LETTER_SPACING
            textAlign =
                when (alignment) {
                    ViewConfig.Alignment.LEFT -> Paint.Align.LEFT
                    ViewConfig.Alignment.CENTER -> Paint.Align.CENTER
                    ViewConfig.Alignment.RIGHT -> Paint.Align.RIGHT
                }
        }

    val bounds = Rect()
    paintAt(100f, speedColor).getTextBounds("0", 0, 1, bounds)
    var fontPx =
        if (bounds.height() > 0) 100f * geo.bandPx * SPEED_WIND_DIGIT_FILL / bounds.height()
        else geo.bandPx
    val column = (width - geo.textLeftPx).coerceAtLeast(1).toFloat()
    val probe = paintAt(fontPx, speedColor)
    val needed = maxOf(probe.measureText(speedText), probe.measureText(windText))
    if (needed > column) fontPx *= column / needed

    val bitmap = createBitmap(width, bitmapHeightPx)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    val xPos =
        when (alignment) {
            ViewConfig.Alignment.LEFT -> geo.textLeftPx.toFloat()
            ViewConfig.Alignment.CENTER -> (geo.textLeftPx + width) / 2f
            ViewConfig.Alignment.RIGHT -> width.toFloat()
        }

    fun drawRow(text: String, bandTop: Float, color: Int) {
        val paint = paintAt(fontPx, color)
        paint.getTextBounds(text, 0, text.length, bounds)
        val center = bandTop + geo.bandPx / 2f
        canvas.drawText(text, xPos, center - (bounds.top + bounds.bottom) / 2f, paint)
    }
    val windTop = geo.bandPx + geo.rowGapPx
    drawRow(speedText, 0f, speedColor)
    drawRow(windText, windTop, windColor)
    windSpeedText?.let { canvas.drawWindSpeed(it, geo, fontPx, arrowColor) }

    if (angleDeg != null) {
        // The developer's arrow, rasterised 1:1 into its own box through the public renderer.
        val arrow =
            renderWindArrowValueBitmap(
                angleDeg = angleDeg,
                text = "",
                fontSizePx = 1f,
                bitmapHeightPx = geo.boxPx,
                cellWidthPx = geo.boxPx.toFloat(),
                textColor = arrowColor,
                arrowColor = arrowColor,
                alignment = ViewConfig.Alignment.LEFT,
                context = context,
            )
        canvas.drawBitmap(arrow, 0f, windTop + (geo.bandPx - geo.boxPx) / 2f, null)
    }
    return bitmap
}

/**
 * The value bitmap for a Wind slot with Show speed. Barberfish layout: speed and arrow take the
 * header colour, the wind number the value colour; in Fill mode both are the on-fill pick. Headwind
 * layout: everything in the value colour, the corner icon (header off) in the icon tint.
 */
@Suppress("LongParameterList")
internal fun speedWindValueBitmap(
    field: FieldState,
    valueText: String,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    colors: ColorConfig,
    sizeConfig: ViewSizeConfig,
    alignment: ViewConfig.Alignment,
    context: Context,
): Bitmap =
    if (field.headwindLayout) {
        val (topReserve, rightInset) = headerIconReserve(field, sizeConfig, alignment, context)
        renderHeadwindStyleBitmap(
            speed = valueText,
            summary = field.secondary.orEmpty(),
            angleDeg = field.windArrowDeg,
            bitmapHeightPx = bitmapHeightPx,
            cellWidthPx = cellWidthPx,
            color = colors.valueText.toArgb(),
            topReservePx = topReserve,
            rightInsetPx = rightInset,
            alignment = alignment,
            context = context,
        )
    } else
        renderSpeedWindValueBitmap(
            speedText = field.speedRow.orEmpty().replace(',', '.'),
            windText = valueText,
            angleDeg = field.windArrowDeg,
            bitmapHeightPx = bitmapHeightPx,
            cellWidthPx = cellWidthPx,
            speedColor = colors.headerText.toArgb(),
            windColor = colors.valueText.toArgb(),
            arrowColor = colors.headerText.toArgb(),
            alignment = alignment,
            context = context,
            windSpeedText = field.windSpeedRow,
        )

/** The total wind speed centred in the arrow column of the top row, in the header colour. */
private fun Canvas.drawWindSpeed(text: String, geo: SpeedWindGeometry, fontPx: Float, color: Int) {
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            letterSpacing = LETTER_SPACING
            textAlign = Paint.Align.CENTER
            this.color = color
            textSize = fontPx
        }
    val probe = if (text.length > SPEED_WIND_SPEED_PROBE.length) text else SPEED_WIND_SPEED_PROBE
    paint.textSize = windSpeedFontPx(fontPx, paint.measureText(probe), geo.boxPx)
    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    drawText(text, geo.boxPx / 2f, geo.bandPx / 2f - (bounds.top + bounds.bottom) / 2f, paint)
}

/**
 * Value height for [field]: the layout's usual value height, plus the header's height when the
 * field draws without its header, so the digits take the whole slot.
 */
internal fun speedWindBitmapHeightPx(
    field: FieldState,
    sizeConfig: ViewSizeConfig,
    density: Float,
): Int {
    val value = (sizeConfig.valueBitmapHeightDp * density).toInt()
    return if (field.hideHeader) value + (sizeConfig.headerMinHeightDp * density).toInt() else value
}

/** Gap between the header icon and the arrow below it, in dp. */
internal const val HEADER_ICON_ARROW_GAP_DP = 2f

/**
 * Bottom of the header icon from the cell top, in px, plus a small gap: the icon is centred in the
 * HUD header band, which reserves two label lines.
 */
internal fun headerIconBottomPx(sizeConfig: ViewSizeConfig, density: Float): Float {
    // HUD slots reserve two label lines; a lone full-width column uses its layout's own count.
    val lines = if (sizeConfig.colSpan < FULL_WIDTH_SPAN / 2) 2 else sizeConfig.labelMaxLines
    val band = headerHeightPx(sizeConfig.headerFontSize.value, lines, density).toFloat()
    val icon = sizeConfig.headerIconSize.value * density
    return (band + icon) / 2f + HEADER_ICON_ARROW_GAP_DP * density
}

/** Span of a full-width cell in the 60-unit grid. */
private const val FULL_WIDTH_SPAN = 60

/**
 * Space the Headwind layout keeps clear for the header icon when the header row is hidden, as
 * (above the arrow, right of the numbers). The icon sits on the left above the arrow, except with
 * LEFT alignment, where the header puts its icons on the right. Nothing when no icon is drawn.
 */
internal fun headerIconReserve(
    field: FieldState,
    sizeConfig: ViewSizeConfig,
    alignment: ViewConfig.Alignment,
    context: Context,
): Pair<Float, Float> {
    val density = context.resources.displayMetrics.density
    val iconShown =
        field.hideHeader &&
            sizeConfig.showIcons &&
            (field.secondaryIconRes ?: field.iconRes) != null
    return when {
        !iconShown -> 0f to 0f
        alignment == ViewConfig.Alignment.LEFT ->
            0f to (sizeConfig.headerIconSize.value + 2 * HEADER_ICON_ARROW_GAP_DP) * density
        else -> headerIconBottomPx(sizeConfig, density) to 0f
    }
}

/**
 * Header off: the value box starts at the cell top and takes the header's height. In the Headwind
 * layout the header row stays in place with its label hidden, so the wind icon sits exactly where
 * the other slots' icons do. Any other layout (the plain wind state the slot falls back to) hides
 * the whole header row, since its full-height arrow has no room left for the icon.
 */
internal fun hideFieldHeader(rv: RemoteViews, field: FieldState) {
    rv.setViewVisibility(R.id.header_ref, View.GONE)
    if (!field.headwindLayout) {
        rv.setViewVisibility(R.id.field_header, View.GONE)
        return
    }
    rv.setViewVisibility(R.id.field_label, View.INVISIBLE)
    rv.setViewVisibility(R.id.field_icon_secondary, View.GONE)
    (field.secondaryIconRes ?: field.iconRes)?.let { rv.setImageViewResource(R.id.field_icon, it) }
}
