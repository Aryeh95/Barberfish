package com.jpweytjens.barberfish.datatype.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withTranslation
import com.jpweytjens.barberfish.extension.DoubleLabel
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.ceil

// Share of the panel height a digit fills, leaving the margin stacked rows need to read apart.
private const val PANEL_DIGIT_FILL = 0.66f

// Widest the label block may get, as a share of the row; the value keeps the rest.
private const val LABEL_BLOCK_MAX_SHARE = 0.45f

// Icon side as a share of the row height, with and without header text below it.
private const val ICON_ROW_SHARE_WITH_TEXT = 0.4f
private const val ICON_ROW_SHARE_ALONE = 0.55f

// Share of the row height the label block may fill.
private const val LABEL_BLOCK_HEIGHT_FILL = 0.92f

private const val LABEL_MAX_LINES = 2
private const val LABEL_MIN_SP = 7f
private const val LABEL_SHRINK_STEP = 0.92f
private const val LABEL_LINE_SPACING = 0.85f
private const val LABEL_VALUE_GAP_DP = 6f
private const val ICON_GAP_DP = 2f
private const val STREAM_STATE_MAX_SP = 19f
private const val STREAM_STATE_ROW_SHARE = 0.4f

/** Sizes, in px, a Double panel is drawn at. */
data class DoublePanelSpec(
    val widthPx: Int,
    val heightPx: Int,
    val label: DoubleLabel,
    val alignment: ViewConfig.Alignment,
    // The cell's header label and icon size: the label block never draws larger.
    val labelFontPx: Float,
    val iconPx: Float,
    // The largest the value draws, however tall the panel.
    val valueFontPx: Float,
    val paddingHPx: Float,
    val density: Float,
)

/**
 * One row of a vertical Double: the label block (the field's icons, its header text, or the icons
 * above the text) on the side away from the value, and the value fitted to the rest of the row,
 * centred on the row. RIGHT and CENTER alignment put the label on the left; LEFT puts the value on
 * the left and the label on the right, as the left field layout swaps its header. The label text
 * wraps to two lines and shrinks to fit the block; the value fills the row height and shrinks to
 * fit its width. Colours and stream-state placeholders follow the field layout.
 */
fun renderDoublePanelBitmap(
    field: FieldState,
    colors: ColorConfig,
    spec: DoublePanelSpec,
    context: Context,
): Bitmap {
    val w = spec.widthPx.coerceAtLeast(1)
    val h = spec.heightPx.coerceAtLeast(1)
    val bitmap = createBitmap(w, h)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    colors.background?.let { canvas.drawColor(it.toArgb()) }

    val inner = (w - 2 * spec.paddingHPx).coerceAtLeast(1f)
    val block = labelBlock(field, colors, spec, inner, context)
    val labelOnRight = spec.alignment == ViewConfig.Alignment.LEFT
    val gap = if (block != null) LABEL_VALUE_GAP_DP * spec.density else 0f
    val blockW = block?.width ?: 0f
    val valueLeft = if (labelOnRight) spec.paddingHPx else spec.paddingHPx + blockW + gap
    val valueRight = if (labelOnRight) w - spec.paddingHPx - blockW - gap else w - spec.paddingHPx
    block?.draw(
        canvas,
        left = if (labelOnRight) w - spec.paddingHPx - blockW else spec.paddingHPx,
        top = (h - block.height) / 2f,
        alignRight = labelOnRight,
    )
    ValuePainter(canvas, spec, colors, context)
        .draw(field, valueLeft, (valueRight - valueLeft).coerceAtLeast(1f))
    return bitmap
}

/** The measured label block: icons on top, wrapped header text below, either optional. */
private class LabelBlock(
    val icons: List<Bitmap>,
    val iconSide: Float,
    val iconGap: Float,
    val text: StaticLayout?,
    val textWidth: Float,
) {
    val iconsWidth: Float =
        if (icons.isEmpty()) 0f else icons.size * iconSide + (icons.size - 1) * iconGap
    val width: Float = maxOf(iconsWidth, textWidth)
    val height: Float =
        (if (icons.isEmpty()) 0f else iconSide) +
            (if (icons.isNotEmpty() && text != null) iconGap else 0f) +
            (text?.height ?: 0)

    fun draw(canvas: Canvas, left: Float, top: Float, alignRight: Boolean) {
        var y = top
        if (icons.isNotEmpty()) {
            var x = if (alignRight) left + width - iconsWidth else left
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            icons.forEach { icon ->
                canvas.drawBitmap(icon, x, y, paint)
                x += iconSide + iconGap
            }
            y += iconSide + if (text != null) iconGap else 0f
        }
        text?.let { layout ->
            // The layout spans its build width with the lines aligned inside it; shift it so the
            // lines meet the block's outer edge.
            val x = if (alignRight) left + width - layout.width else left
            canvas.withTranslation(x, y) { layout.draw(this) }
        }
    }
}

private fun labelBlock(
    field: FieldState,
    colors: ColorConfig,
    spec: DoublePanelSpec,
    innerWidth: Float,
    context: Context,
): LabelBlock? {
    val iconRes =
        if (spec.label.showsIcon) listOfNotNull(field.iconRes, field.secondaryIconRes)
        else emptyList()
    val text = if (spec.label.showsText) field.label.replace("\n", " ").uppercase().trim() else ""
    if (iconRes.isEmpty() && text.isEmpty()) return null

    val maxW = innerWidth * LABEL_BLOCK_MAX_SHARE
    val iconGap = ICON_GAP_DP * spec.density
    val share = if (text.isEmpty()) ICON_ROW_SHARE_ALONE else ICON_ROW_SHARE_WITH_TEXT
    var iconSide = minOf(spec.iconPx, spec.heightPx * share)
    if (iconRes.isNotEmpty()) {
        val rowW = iconRes.size * iconSide + (iconRes.size - 1) * iconGap
        if (rowW > maxW) iconSide *= maxW / rowW
    }
    val tint = colors.iconTint.toArgb()
    val side = iconSide.toInt().coerceAtLeast(1)
    val icons = iconRes.mapNotNull { res ->
        ContextCompat.getDrawable(context, res)
            ?.mutate()
            ?.apply { setTint(tint) }
            ?.toBitmap(side, side)
    }

    val textRoom =
        spec.heightPx * LABEL_BLOCK_HEIGHT_FILL -
            (if (icons.isEmpty()) 0f else icons.first().height + iconGap)
    val layout =
        if (text.isEmpty()) null
        else fitLabel(text, colors.headerText.toArgb(), spec, maxW, textRoom.coerceAtLeast(1f))
    val textWidth =
        layout?.let { l -> (0 until l.lineCount).maxOf { ceil(l.getLineWidth(it)) } } ?: 0f
    return LabelBlock(icons, side.toFloat(), iconGap, layout, textWidth)
}

/**
 * Header text laid out in at most two lines no wider than [maxWidth] and no taller than
 * [maxHeight], shrinking from the cell's label size until it fits whole words; at the smallest size
 * it ellipsizes.
 */
private fun fitLabel(
    text: String,
    color: Int,
    spec: DoublePanelSpec,
    maxWidth: Float,
    maxHeight: Float,
): StaticLayout {
    val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.NORMAL)
            this.color = color
        }
    val width = maxWidth.toInt().coerceAtLeast(1)
    val align =
        if (spec.alignment == ViewConfig.Alignment.LEFT) Layout.Alignment.ALIGN_OPPOSITE
        else Layout.Alignment.ALIGN_NORMAL
    fun build(maxLines: Int) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, LABEL_LINE_SPACING)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    val minPx = LABEL_MIN_SP * spec.density
    var sizePx = spec.labelFontPx
    while (true) {
        paint.textSize = sizePx
        val words = text.split(' ').filter { it.isNotEmpty() }
        val layout = build(Int.MAX_VALUE)
        val fits =
            layout.lineCount <= LABEL_MAX_LINES &&
                layout.height <= maxHeight &&
                words.all { paint.measureText(it) <= width }
        if (fits || sizePx <= minPx) return build(LABEL_MAX_LINES)
        sizePx = (sizePx * LABEL_SHRINK_STEP).coerceAtLeast(minPx)
    }
}

/** Draws a panel's value into the span from `left`, `width` wide, centred on the panel. */
private class ValuePainter(
    private val canvas: Canvas,
    private val spec: DoublePanelSpec,
    private val colors: ColorConfig,
    private val context: Context,
) {
    private val centreY = spec.heightPx / 2f
    private val zero = Rect()

    fun draw(field: FieldState, left: Float, width: Float) {
        if (field.color is FieldColor.StreamState) {
            val paint =
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.NORMAL)
                    color = colors.valueText.toArgb()
                    textSize =
                        minOf(
                            STREAM_STATE_MAX_SP * spec.density,
                            spec.heightPx * STREAM_STATE_ROW_SHARE,
                        )
                }
            drawFitted(field.primary, paint, left, width, digitBaseline = false)
            return
        }
        val text = field.primary.replace(',', '.')
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("relative", Typeface.NORMAL)
                color = colors.valueText.toArgb()
                letterSpacing = LETTER_SPACING
            }
        // Height fit: a digit fills PANEL_DIGIT_FILL of the panel, never above the value cap.
        paint.textSize = 100f
        paint.getTextBounds("0", 0, 1, zero)
        val digitPx = spec.heightPx * PANEL_DIGIT_FILL
        paint.textSize =
            minOf(
                spec.valueFontPx,
                if (zero.height() > 0) 100f * digitPx / zero.height() else digitPx,
            )
        val angle = field.windArrowDeg
        if (angle == null) drawFitted(text, paint, left, width, digitBaseline = true)
        else drawWind(text, angle, paint, left, width)
    }

    // Wind: the arrow in a square of the digit height ahead of the number, as in the field. Both
    // scale with the font, so the font is fitted to the width they take together.
    private fun drawWind(text: String, angle: Float, paint: Paint, left: Float, width: Float) {
        val arrowGap = WIND_ARROW_GAP_DP * spec.density
        paint.getTextBounds("0", 0, 1, zero)
        val perPx = (zero.height() + paint.measureText(text)) / paint.textSize
        if (perPx > 0f && paint.textSize * perPx > width - arrowGap) {
            paint.textSize = ((width - arrowGap) / perPx).coerceAtLeast(1f)
        }
        paint.getTextBounds("0", 0, 1, zero)
        val heightPx = zero.height().coerceAtLeast(1)
        val arrow =
            renderWindArrowValueBitmap(
                angleDeg = angle,
                text = text,
                fontSizePx = paint.textSize,
                bitmapHeightPx = heightPx,
                cellWidthPx = heightPx + arrowGap + paint.measureText(text),
                textColor = colors.valueText.toArgb(),
                arrowColor = colors.headerText.toArgb(),
                alignment = ViewConfig.Alignment.RIGHT,
                context = context,
            )
        val x =
            when (spec.alignment) {
                ViewConfig.Alignment.LEFT -> left
                ViewConfig.Alignment.CENTER -> left + (width - arrow.width) / 2f
                ViewConfig.Alignment.RIGHT -> left + width - arrow.width
            }
        canvas.drawBitmap(arrow, x, centreY - arrow.height / 2f, null)
    }

    /**
     * Draws [text] shrunk to fit [width], aligned in the span from [left], and centred on the
     * panel: on the digit height when [digitBaseline], so values keep one baseline as they change,
     * otherwise on the text's own bounds.
     */
    private fun drawFitted(
        text: String,
        paint: Paint,
        left: Float,
        width: Float,
        digitBaseline: Boolean,
    ) {
        val measured = paint.measureText(text)
        if (measured > width) paint.textSize *= width / measured
        val bounds = Rect()
        if (digitBaseline) paint.getTextBounds("0", 0, 1, bounds)
        else paint.getTextBounds(text, 0, text.length, bounds)
        val baseline = centreY - (bounds.top + bounds.bottom) / 2f
        paint.textAlign =
            when (spec.alignment) {
                ViewConfig.Alignment.LEFT -> Paint.Align.LEFT
                ViewConfig.Alignment.CENTER -> Paint.Align.CENTER
                ViewConfig.Alignment.RIGHT -> Paint.Align.RIGHT
            }
        val x =
            when (spec.alignment) {
                ViewConfig.Alignment.LEFT -> left
                ViewConfig.Alignment.CENTER -> left + width / 2f
                ViewConfig.Alignment.RIGHT -> left + width
            }
        canvas.drawText(text, x, baseline, paint)
    }
}
