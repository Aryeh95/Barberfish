package com.jpweytjens.barberfish.datatype.shared

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import com.jpweytjens.barberfish.extension.ZoneColorMode
import io.hammerhead.karooext.models.ViewConfig

private const val DUAL_STACK_DIGIT_FILL = 0.86f
private const val DUAL_SIDE_DIGIT_FILL = 0.90f
private const val DUAL_ROW_GAP_PX = 4f
private const val DUAL_SIDE_GAP_EM = 0.38f

/**
 * Renders a [DualValues] pair into a value bitmap: side by side or stacked, each value in its own
 * [ZoneColorMode.TEXT] color (or [colors]'s `valueText` in `BACKGROUND` / `NONE`).
 */
@Suppress("LongParameterList")
fun renderDualValuesBitmap(
    dual: DualValues,
    colorMode: ZoneColorMode,
    colors: ColorConfig,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    alignment: ViewConfig.Alignment,
    context: Context,
): Bitmap {
    val isNightMode =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val firstColor = dualItemArgb(dual.firstColor, colorMode, isNightMode, colors)
    val secondColor = dualItemArgb(dual.secondColor, colorMode, isNightMode, colors)
    val firstText = dual.first.replace(',', '.')
    val secondText = dual.second.replace(',', '.')
    return if (dual.beside) {
        renderDualBeside(
            firstText,
            firstColor,
            secondText,
            secondColor,
            bitmapHeightPx,
            cellWidthPx,
            alignment,
        )
    } else {
        renderDualStacked(
            firstText,
            firstColor,
            secondText,
            secondColor,
            bitmapHeightPx,
            cellWidthPx,
            alignment,
        )
    }
}

internal fun dualItemArgb(
    itemColor: FieldColor,
    colorMode: ZoneColorMode,
    isNightMode: Boolean,
    cellColors: ColorConfig,
): Int =
    if (colorMode == ZoneColorMode.TEXT || itemColor is FieldColor.Muted) {
        itemColor.toColorConfig(ZoneColorMode.TEXT, isNightMode).valueText.toArgb()
    } else {
        cellColors.valueText.toArgb()
    }

@Suppress("LongParameterList")
private fun renderDualBeside(
    firstText: String,
    firstColor: Int,
    secondText: String,
    secondColor: Int,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    alignment: ViewConfig.Alignment,
): Bitmap {
    val width = cellWidthPx.toInt().coerceAtLeast(1)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            letterSpacing = LETTER_SPACING
            textAlign = Paint.Align.LEFT
            textSize = 100f
        }
    val zero = Rect()
    paint.getTextBounds("0", 0, 1, zero)
    paint.textSize =
        if (zero.height() > 0) 100f * bitmapHeightPx * DUAL_SIDE_DIGIT_FILL / zero.height()
        else bitmapHeightPx.toFloat()

    fun totalWidth(): Float {
        val gap = paint.textSize * DUAL_SIDE_GAP_EM
        return paint.measureText(firstText) + gap + paint.measureText(secondText)
    }
    val needed = totalWidth()
    if (needed > width) paint.textSize *= width / needed
    paint.getTextBounds("0", 0, 1, zero)
    val baseline = bitmapHeightPx / 2f - (zero.top + zero.bottom) / 2f
    val total = totalWidth()
    val startX =
        when (alignment) {
            ViewConfig.Alignment.LEFT -> 0f
            ViewConfig.Alignment.CENTER -> (width - total) / 2f
            ViewConfig.Alignment.RIGHT -> width - total
        }
    val bitmap = createBitmap(width, bitmapHeightPx)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    paint.color = firstColor
    canvas.drawText(firstText, startX, baseline, paint)
    val secondX = startX + paint.measureText(firstText) + paint.textSize * DUAL_SIDE_GAP_EM
    paint.color = secondColor
    canvas.drawText(secondText, secondX, baseline, paint)
    return bitmap
}

@Suppress("LongParameterList")
private fun renderDualStacked(
    firstText: String,
    firstColor: Int,
    secondText: String,
    secondColor: Int,
    bitmapHeightPx: Int,
    cellWidthPx: Float,
    alignment: ViewConfig.Alignment,
): Bitmap {
    val width = cellWidthPx.toInt().coerceAtLeast(1)
    val bandPx = ((bitmapHeightPx - DUAL_ROW_GAP_PX) / 2f).coerceAtLeast(1f)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            letterSpacing = LETTER_SPACING
            textAlign =
                when (alignment) {
                    ViewConfig.Alignment.LEFT -> Paint.Align.LEFT
                    ViewConfig.Alignment.CENTER -> Paint.Align.CENTER
                    ViewConfig.Alignment.RIGHT -> Paint.Align.RIGHT
                }
            textSize = 100f
        }
    val bounds = Rect()
    paint.getTextBounds("0", 0, 1, bounds)
    var fontPx =
        if (bounds.height() > 0) 100f * (bandPx * DUAL_STACK_DIGIT_FILL) / bounds.height()
        else bandPx
    paint.textSize = fontPx
    val needed = maxOf(paint.measureText(firstText), paint.measureText(secondText))
    if (needed > width) fontPx *= width / needed
    paint.textSize = fontPx
    val anchorX =
        when (alignment) {
            ViewConfig.Alignment.LEFT -> 0f
            ViewConfig.Alignment.CENTER -> width / 2f
            ViewConfig.Alignment.RIGHT -> width.toFloat()
        }
    val bitmap = createBitmap(width, bitmapHeightPx)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)

    fun drawBand(text: String, top: Float, color: Int) {
        paint.color = color
        paint.getTextBounds("0", 0, 1, bounds)
        val baseline = top + bandPx / 2f - (bounds.top + bounds.bottom) / 2f
        canvas.drawText(text, anchorX, baseline, paint)
    }
    drawBand(firstText, 0f, firstColor)
    drawBand(secondText, bandPx + DUAL_ROW_GAP_PX, secondColor)
    return bitmap
}
