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
    /** "F50 - R10", or gear numbers when the sizes are not reported. */
    val label: String
        get() {
            val f = frontTeeth ?: front
            val r = rearTeeth ?: rear
            return listOfNotNull(f?.let { "F$it" }, r?.let { "R$it" }).joinToString(" - ")
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
 * the gear in use filled, and the gears in teeth with their ratio. A gear on either end of the
 * cassette is filled and titled in that end's color.
 */
@SuppressLint("ViewConstructor") // only ever built in code
internal class ShiftOverlayView(
    context: Context,
    private val reading: GearReading,
    private val end: CassetteEnd?,
) : View(context) {
    private val metrics = resources.displayMetrics
    private val stroke = dp(GEAR_STROKE_DP)

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
    private val title = text(TITLE_SP, Typeface.DEFAULT_BOLD, end?.color ?: Color.WHITE)
    private val countText = text(COUNT_SP, Typeface.DEFAULT_BOLD, Color.WHITE)
    private val gearText = text(GEARS_SP, Typeface.MONOSPACE, Color.WHITE)
    private val ratioText = text(RATIO_SP, Typeface.MONOSPACE, RATIO_COLOR)

    private val showsBars = reading.rearCount != null && reading.rear != null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pad = dp(PAD_DP)
        var height = pad + dp(TITLE_BAR_DP) + pad
        if (showsBars) height += dp(GEARS_DP) + lineHeight(countText) + pad
        height += lineHeight(gearText) + pad
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val pad = dp(PAD_DP)
        val radius = dp(CORNER_DP)
        canvas.drawRoundRect(0f, 0f, w, height.toFloat(), radius, radius, background)

        val barHeight = dp(TITLE_BAR_DP)
        canvas.drawRoundRect(0f, 0f, w, barHeight, radius, radius, titleBar)
        canvas.drawRect(0f, barHeight - radius, w, barHeight, titleBar)
        canvas.drawText(end?.title ?: "GEARS", pad, baseline(title, 0f, barHeight), title)

        var y = barHeight + pad
        if (showsBars) {
            drawGears(canvas, pad, y, w - pad * 2, dp(GEARS_DP))
            y += dp(GEARS_DP) + lineHeight(countText) + pad
        }

        val line = lineHeight(gearText)
        canvas.drawText(reading.label, pad, baseline(gearText, y, y + line), gearText)
        reading.ratio?.let {
            val ratio = String.format(Locale.US, "%.2f", it)
            canvas.drawText(
                ratio,
                w - pad - ratioText.measureText(ratio),
                baseline(gearText, y, y + line),
                ratioText,
            )
        }
    }

    /** Ki2's GearsView geometry: chainrings rise left to right, the cassette falls. */
    private fun drawGears(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val frontCount = reading.frontCount?.takeIf { reading.front != null } ?: 0
        val rearCount = reading.rearCount ?: return
        val space = width * GEAR_SPACE
        val setSpace = width * SET_SPACE
        val total = frontCount + rearCount
        val gearWidth = (width - space * (total + frontCount) - setSpace) / total
        val usable = height - stroke * 2
        val barTop = top + stroke
        val bottom = barTop + usable

        var x = left + space
        if (frontCount > 0) {
            val bump = if (frontCount == 1) 0f else (1 - FRONT_MIN) * usable / (frontCount - 1)
            for (i in 1..frontCount) {
                val start = barTop + bump * (frontCount - i)
                bar(
                    canvas,
                    x,
                    start,
                    gearWidth,
                    bottom,
                    if (i == reading.front) selected else unselected,
                )
                x += gearWidth + space
            }
            drawCount(canvas, left + space, x - space, bottom, reading.front, frontCount)
        }

        val rearLeft = left + (gearWidth + space * 2) * frontCount + setSpace
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
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            typeface = face
            color = textColor
        }

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

    private fun baseline(paint: Paint, top: Float, bottom: Float): Float {
        val m = paint.fontMetrics
        return (top + bottom) / 2 - (m.ascent + m.descent) / 2
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)

    private companion object {
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
            val panel = ShiftOverlayView(context, reading, end)
            val margin = dp(SIDE_MARGIN_DP)
            val params =
                WindowManager.LayoutParams(
                    context.resources.displayMetrics.widthPixels - margin * 2,
                    WindowManager.LayoutParams.WRAP_CONTENT,
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
        const val SIDE_MARGIN_DP = 4
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
