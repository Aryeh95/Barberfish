package com.jpweytjens.barberfish.extension

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import timber.log.Timber

/** The end of the cassette a shift landed on, with the color the shift overlay marks it in. */
enum class CassetteEnd(val title: String, val color: Int) {
    SMALLEST("SMALLEST COG", 0xFFD32F2F.toInt()),
    LARGEST("LARGEST COG", 0xFF2386D9.toInt()),
}

/**
 * One reading of the drivetrain, as the Karoo reports it for any electronic groupset it is paired
 * with (SRAM AXS, Shimano Di2, Campagnolo). Gears count from 1: the smallest chainring and the
 * largest cog. A count or a size the groupset does not report is null.
 */
data class GearReading(
    val front: Int? = null,
    val frontCount: Int? = null,
    val frontTeeth: Int? = null,
    val rear: Int? = null,
    val rearCount: Int? = null,
    val rearTeeth: Int? = null,
) {
    /**
     * The gears as [text] asks, "F50 - R10" or "F2 - R12", the chainring left out unless
     * [withFront]. Teeth fall back to gear numbers when the groupset does not report sizes. Null
     * for [ShiftGearText.OFF] or when there is nothing to write.
     */
    fun label(text: ShiftGearText, withFront: Boolean = true): String? {
        val (f, r) =
            when (text) {
                ShiftGearText.TEETH -> (frontTeeth ?: front) to (rearTeeth ?: rear)
                ShiftGearText.NUMBERS -> front to rear
                ShiftGearText.OFF -> return null
            }
        val parts = listOfNotNull(f?.takeIf { withFront }?.let { "F$it" }, r?.let { "R$it" })
        return parts.joinToString(" - ").ifEmpty { null }
    }

    val ratio: Double?
        get() =
            if (frontTeeth != null && rearTeeth != null && rearTeeth > 0) {
                frontTeeth.toDouble() / rearTeeth
            } else null
}

/**
 * Decides when the shift overlay shows: on a shift that lands on the smallest or the largest cog,
 * or on every shift. Never for the first reading after the groupset connects, which is not a shift,
 * and never while the gears stay put.
 *
 * The Karoo numbers rear gears from the largest cog, so gear 1 is the largest and the gear count
 * the smallest. The cog's teeth, when reported, decide instead, so a groupset that numbers the
 * other way still gets the right label. Without a gear count only the largest cog is recognised.
 */
internal class ShiftDetector(private val everyShift: Boolean) {
    private var last: GearReading? = null

    /** The overlay to show for [reading]: null for none, else whether it is at an end. */
    fun onReading(reading: GearReading): Shift? {
        val connected = reading.rear != null && reading.rear > 0
        val previous = last
        last = reading.takeIf { connected }
        if (!connected || previous == null) return null
        val rearShift = previous.rear != reading.rear
        val frontShift = previous.front != reading.front
        val end = if (rearShift) cassetteEnd(reading) else null
        return when {
            end != null -> Shift(end)
            everyShift && (rearShift || frontShift) -> Shift(null)
            else -> null
        }
    }

    data class Shift(val end: CassetteEnd?)

    private fun cassetteEnd(reading: GearReading): CassetteEnd? {
        val gear = reading.rear
        val count = reading.rearCount?.takeIf { it > 1 }
        val teeth = reading.rearTeeth
        return when {
            gear == null || (gear != 1 && gear != count) -> null
            teeth != null && teeth >= LARGE_COG_TEETH -> CassetteEnd.LARGEST
            teeth != null && teeth <= SMALL_COG_TEETH -> CassetteEnd.SMALLEST
            gear == 1 -> CassetteEnd.LARGEST
            else -> CassetteEnd.SMALLEST
        }
    }

    private companion object {
        // No cassette's largest cog is under 20 teeth or its smallest over 14.
        const val LARGE_COG_TEETH = 20
        const val SMALL_COG_TEETH = 14
    }
}

/**
 * The shift overlay's panel, after Ki2's dark overlay and GearsView (github.com/valterc/ki2,
 * Copyright (c) 2022 Valter C., MIT licence): a title bar, the chainrings and cassette as bars with
 * the gear in use filled, and the gears with their ratio. [config] picks which parts show and their
 * size. A gear on either end of the cassette is filled and titled in that end's color; with the
 * header off, the gears are written in it instead.
 *
 * With the gear bars it takes the width it is given less a margin each side (times the size's width
 * fraction); without them it is as wide as its text.
 */
@SuppressLint("ViewConstructor") // only ever built in code
internal class ShiftOverlayView(
    context: Context,
    private val reading: GearReading,
    private val end: CassetteEnd?,
    private val config: ShiftOverlayConfig,
) : View(context) {
    private val metrics = resources.displayMetrics
    private val scale = config.size.scale
    private val stroke = dp(GEAR_STROKE_DP)
    private val pad = dp(PAD_DP)

    private val showsBars = config.showBars && reading.rearCount != null && reading.rear != null
    private val showsFront = config.showChainrings && reading.front != null
    private val ratio =
        reading.ratio?.takeIf { config.showRatio }?.let { String.format(Locale.US, "%.2f", it) }
    // The overlay is never empty: with every part off, the gears are still written.
    private val gears =
        reading.label(config.gearText, showsFront)
            ?: reading.label(ShiftGearText.TEETH, showsFront).takeIf {
                !config.showHeader && !showsBars && ratio == null
            }

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND }
    private val titleBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TITLE_BAR }
    private val selected = gearPaint(GEAR_FILL)
    // The cog in use takes the end's color, so the chainring never reads as the alert.
    private val selectedCog = gearPaint(end?.color ?: GEAR_FILL)
    private val unselected =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GEAR_BORDER
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
    // The Karoo's own fonts, as Barberfish's fields use them: Relative for values, IBM Plex Sans
    // Condensed for headers and labels.
    private val title = text(TITLE_SP, LABEL_BOLD, end?.color ?: Color.WHITE)
    private val countText = text(COUNT_SP, LABEL_BOLD, Color.WHITE)
    private val gearText =
        text(GEARS_SP, VALUE_FONT, end?.color?.takeIf { !config.showHeader } ?: Color.WHITE)
    private val ratioText = text(RATIO_SP, VALUE_FONT, RATIO_COLOR)

    private val titleText: String
        get() = end?.title ?: "GEARS"

    private val hasTextRow: Boolean
        get() = gears != null || ratio != null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var height = pad
        if (config.showHeader) height += dp(TITLE_BAR_DP) + pad
        if (showsBars) height += dp(GEARS_DP) + lineHeight(countText) + pad
        if (hasTextRow) height += lineHeight(gearText) + pad
        // The gear bars spread across the screen, short of a margin each side, as Ki2's do.
        val available = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SIDE_MARGIN_DP, metrics)
        val width =
            if (showsBars) (available - margin * 2) * config.size.widthFraction else textWidth()
        setMeasuredDimension(width.coerceAtMost(available).toInt(), height.toInt())
    }

    /** The narrowest panel that fits the title and the text row. */
    private fun textWidth(): Float {
        val row =
            listOfNotNull(
                gears?.let { gearText.measureText(it) },
                ratio?.let { ratioText.measureText(it) },
            )
        val rowWidth = row.sum() + if (row.size > 1) pad * 2 else 0f
        val titleWidth = if (config.showHeader) title.measureText(titleText) else 0f
        return maxOf(rowWidth, titleWidth) + pad * 2
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val radius = dp(CORNER_DP)
        canvas.drawRoundRect(0f, 0f, w, height.toFloat(), radius, radius, background)

        var y = pad
        if (config.showHeader) {
            val barHeight = dp(TITLE_BAR_DP)
            canvas.drawRoundRect(0f, 0f, w, barHeight, radius, radius, titleBar)
            canvas.drawRect(0f, barHeight - radius, w, barHeight, titleBar)
            canvas.drawText(titleText, pad, baseline(title, 0f, barHeight), title)
            y = barHeight + pad
        }

        if (showsBars) {
            drawGears(canvas, pad, y, w - pad * 2, dp(GEARS_DP))
            y += dp(GEARS_DP) + lineHeight(countText) + pad
        }

        if (hasTextRow) {
            val line = lineHeight(gearText)
            val base = baseline(gearText, y, y + line)
            gears?.let { canvas.drawText(it, pad, base, gearText) }
            ratio?.let { canvas.drawText(it, w - pad - ratioText.measureText(it), base, ratioText) }
        }
    }

    /** Ki2's GearsView geometry: chainrings rise left to right, the cassette falls. */
    private fun drawGears(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val frontCount = reading.frontCount?.takeIf { showsFront } ?: 0
        val rearCount = reading.rearCount ?: return
        val space = width * GEAR_SPACE
        val setSpace = if (frontCount > 0) width * SET_SPACE else 0f
        val total = frontCount + rearCount
        // Bars wider than [MAX_GEAR_DP], as a lone cassette would get, stay that wide and the set
        // is centered.
        val gearWidth =
            minOf((width - space * (total + frontCount) - setSpace) / total, dp(MAX_GEAR_DP))
        val setWidth = gearWidth * total + space * (total + frontCount) + setSpace
        val start = left + (width - setWidth) / 2
        val usable = height - stroke * 2
        val barTop = top + stroke
        val bottom = barTop + usable

        var x = start + space
        if (frontCount > 0) {
            val bump = if (frontCount == 1) 0f else (1 - FRONT_MIN) * usable / (frontCount - 1)
            for (i in 1..frontCount) {
                bar(
                    canvas,
                    x,
                    barTop + bump * (frontCount - i),
                    gearWidth,
                    bottom,
                    if (i == reading.front) selected else unselected,
                )
                x += gearWidth + space
            }
            drawCount(canvas, start + space, x - space, bottom, reading.front, frontCount)
        }

        val rearLeft = start + (gearWidth + space * 2) * frontCount + setSpace
        x = rearLeft
        val bump = if (rearCount == 1) 0f else usable * REAR_DROP / (rearCount - 1)
        for (i in 1..rearCount) {
            bar(
                canvas,
                x,
                barTop + bump * (i - 1),
                gearWidth,
                bottom,
                if (i == reading.rear) selectedCog else unselected,
            )
            x += gearWidth + space
        }
        drawCount(canvas, rearLeft, x - space, bottom, reading.rear, rearCount)
    }

    private fun bar(
        canvas: Canvas,
        x: Float,
        top: Float,
        width: Float,
        bottom: Float,
        paint: Paint,
    ) {
        val r = width / 2
        canvas.drawRoundRect(x, top, x + width, bottom, r, r, paint)
    }

    private fun gearPaint(fill: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fill
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = stroke
        }

    private fun drawCount(
        canvas: Canvas,
        from: Float,
        to: Float,
        top: Float,
        gear: Int?,
        count: Int,
    ) {
        val label = "${gear ?: "-"}/$count"
        val x = (from + to) / 2 - countText.measureText(label) / 2
        val line = lineHeight(countText)
        canvas.drawText(label, x, baseline(countText, top + stroke, top + stroke + line), countText)
    }

    private fun text(sp: Float, face: Typeface, textColor: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * scale, metrics)
            typeface = face
            color = textColor
        }

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

    private fun baseline(paint: Paint, top: Float, bottom: Float): Float {
        val m = paint.fontMetrics
        return (top + bottom) / 2 - (m.ascent + m.descent) / 2
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value * scale, metrics)

    private companion object {
        val VALUE_FONT: Typeface = Typeface.create("relative", Typeface.NORMAL)
        val LABEL_BOLD: Typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.BOLD)

        // Ki2's dark theme.
        const val BACKGROUND = 0xFF102020.toInt()
        const val TITLE_BAR = 0x4F000000
        const val GEAR_FILL = 0xFFA0B4BE.toInt()
        const val GEAR_BORDER = 0xFFF0F0F0.toInt()
        const val RATIO_COLOR = 0xFFC8D2D7.toInt()
        const val GEAR_SPACE = 0.02f
        const val SET_SPACE = 0.1f
        const val FRONT_MIN = 0.7f
        const val REAR_DROP = 0.6f
        const val GEAR_STROKE_DP = 1.5f
        const val MAX_GEAR_DP = 14f
        const val SIDE_MARGIN_DP = 4f
        const val PAD_DP = 6f
        const val CORNER_DP = 5f
        const val TITLE_BAR_DP = 30f
        const val GEARS_DP = 50f
        const val TITLE_SP = 18f
        const val COUNT_SP = 13f
        const val GEARS_SP = 28f
        const val RATIO_SP = 22f
    }
}

/**
 * Shows [ShiftOverlayView] over the ride screen for a few seconds, the way Ki2 shows shifting: a
 * window that never takes focus or touches and hides itself. Needs the "display over other apps"
 * permission; without it nothing is drawn.
 */
class ShiftOverlay(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = this.context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val hide = Runnable { remove() }

    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    fun show(reading: GearReading, end: CassetteEnd?, config: ShiftOverlayConfig) {
        handler.post {
            remove()
            if (!allowed()) {
                Timber.w("Shift overlay: no permission to draw over other apps")
                return@post
            }
            val panel = ShiftOverlayView(context, reading, end, config)
            // Measured here against the screen width and the window sized to match, so the
            // system's narrower first try for wrap-content windows never squeezes the bars.
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(
                    context.resources.displayMetrics.widthPixels,
                    View.MeasureSpec.AT_MOST,
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val params =
                WindowManager.LayoutParams(
                    panel.measuredWidth,
                    panel.measuredHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
            params.gravity = config.position.gravity
            params.y = if (config.position == ShiftOverlayPosition.MIDDLE) 0 else dp(EDGE_MARGIN_DP)
            runCatching { windowManager.addView(panel, params) }
                .onSuccess {
                    view = panel
                    handler.postDelayed(hide, config.seconds * MS_PER_S)
                }
                .onFailure { Timber.w(it, "Shift overlay: could not draw") }
        }
    }

    private fun remove() {
        handler.removeCallbacks(hide)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MS_PER_S = 1_000L
        const val EDGE_MARGIN_DP = 28
    }
}

private fun KarooSystemService.gearValues(typeId: String): Flow<Map<String, Double>?> =
    streamDataFlow(typeId).map { (it as? StreamState.Streaming)?.dataPoint?.values }

/** The groupset's front and rear gears as one reading; empty until the groupset reports. */
internal fun KarooSystemService.streamGears(): Flow<GearReading> =
    combine(
        gearValues(DataType.Type.SHIFTING_FRONT_GEAR).onStart { emit(null) },
        gearValues(DataType.Type.SHIFTING_REAR_GEAR),
    ) { front, rear ->
        GearReading(
            front = front?.get(DataType.Field.SHIFTING_FRONT_GEAR)?.toInt(),
            frontCount = front?.get(DataType.Field.SHIFTING_FRONT_GEAR_MAX)?.toInt(),
            frontTeeth = front?.get(DataType.Field.SHIFTING_FRONT_GEAR_TEETH)?.toInt(),
            rear = rear?.get(DataType.Field.SHIFTING_REAR_GEAR)?.toInt(),
            rearCount = rear?.get(DataType.Field.SHIFTING_REAR_GEAR_MAX)?.toInt(),
            rearTeeth = rear?.get(DataType.Field.SHIFTING_REAR_GEAR_TEETH)?.toInt(),
        )
    }

/** Shows the shift overlay while it is switched on. Runs for the life of the extension service. */
internal suspend fun runShiftOverlay(context: Context, karooSystem: KarooSystemService) {
    val overlay = ShiftOverlay(context)
    context.streamShiftOverlayConfig().collectLatest { config ->
        if (!config.enabled) return@collectLatest
        val detector = ShiftDetector(everyShift = config.trigger == ShiftTrigger.EVERY_SHIFT)
        karooSystem.streamGears().collect { reading ->
            detector.onReading(reading)?.let { overlay.show(reading, it.end, config) }
        }
    }
}
