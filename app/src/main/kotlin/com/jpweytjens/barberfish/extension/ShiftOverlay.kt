package com.jpweytjens.barberfish.extension

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import com.jpweytjens.barberfish.datatype.shared.DrivetrainAlert
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import timber.log.Timber

/** Highlight shown on the shift overlay: either cassette end or a cross-chained combination. */
enum class CassetteEnd(val title: String, val color: Int) {
    SMALLEST("SMALLEST COG", 0xFFD32F2F.toInt()),
    LARGEST("LARGEST COG", 0xFF2386D9.toInt()),
    CROSS_CHAIN("CROSS CHAIN", 0xFFFFA726.toInt()),
}

/**
 * Drivetrain battery levels from [DataType.Type.SHIFTING_BATTERY], whose fields report
 * [BatteryStatus] ordinals. Surfaces a compact warning badge when any derailleur is low/critical.
 */
data class DrivetrainBattery(
    val front: BatteryStatus? = null,
    val rear: BatteryStatus? = null,
    val overall: BatteryStatus? = null,
) {
    val isCritical: Boolean
        get() =
            rear == BatteryStatus.CRITICAL ||
                front == BatteryStatus.CRITICAL ||
                overall == BatteryStatus.CRITICAL

    val isLowOrCritical: Boolean
        get() =
            isCritical ||
                rear == BatteryStatus.LOW ||
                front == BatteryStatus.LOW ||
                overall == BatteryStatus.LOW

    val badgeText: String?
        get() =
            when {
                rear == BatteryStatus.CRITICAL -> "R BATT CRIT"
                front == BatteryStatus.CRITICAL -> "F BATT CRIT"
                overall == BatteryStatus.CRITICAL -> "BATT CRIT"
                rear == BatteryStatus.LOW -> "R BATT LOW"
                front == BatteryStatus.LOW -> "F BATT LOW"
                overall == BatteryStatus.LOW -> "BATT LOW"
                else -> null
            }

    val badgeColor: Int
        get() = if (isCritical) 0xFFFF5252.toInt() else 0xFFFFA726.toInt()
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

    /** Compact field/slot string ("50-10", "2-12", or "5.00"), omitting the F/R prefixes. */
    fun compactLabel(format: GearDisplayFormat, withFront: Boolean = true): String? =
        when (format) {
            GearDisplayFormat.TEETH -> {
                val f = (frontTeeth ?: front)?.takeIf { withFront && (frontCount ?: 2) > 1 }
                val r = rearTeeth ?: rear
                listOfNotNull(f, r).joinToString("-").ifEmpty { null }
            }
            GearDisplayFormat.NUMBERS -> {
                val f = front?.takeIf { withFront && (frontCount ?: 2) > 1 }
                listOfNotNull(f, rear).joinToString("-").ifEmpty { null }
            }
            GearDisplayFormat.RATIO ->
                ratio?.let { String.format(Locale.US, "%.2f", it) }
                    ?: compactLabel(GearDisplayFormat.TEETH, withFront)
        }

    val ratio: Double?
        get() =
            if (frontTeeth != null && rearTeeth != null && rearTeeth > 0) {
                frontTeeth.toDouble() / rearTeeth
            } else null

    /** Which end of the cassette this reading sits on, or null between the ends. */
    fun cassetteEnd(): CassetteEnd? {
        val gear = rear
        val count = rearCount?.takeIf { it > 1 }
        val teeth = rearTeeth
        return when {
            gear == null || (gear != 1 && gear != count) -> null
            teeth != null && teeth >= LARGE_COG_TEETH -> CassetteEnd.LARGEST
            teeth != null && teeth <= SMALL_COG_TEETH -> CassetteEnd.SMALLEST
            gear == 1 -> CassetteEnd.LARGEST
            else -> CassetteEnd.SMALLEST
        }
    }

    /**
     * True on extreme cross-chain combinations of a multi-ring setup: big ring with the two largest
     * cogs, or small ring with the two smallest cogs.
     */
    fun isCrossChained(): Boolean {
        val f = front
        val fMax = frontCount?.takeIf { it >= 2 }
        val r = rear
        val rMax = rearCount?.takeIf { it >= MIN_CASSETTE_FOR_CROSS }
        return if (f != null && fMax != null && r != null) {
            rMax != null && isExtremeCombination(f, fMax, r, rMax)
        } else {
            false
        }
    }

    private fun isExtremeCombination(f: Int, fMax: Int, r: Int, rMax: Int): Boolean {
        val reversed = isNumberedFromSmallest(r, rMax)
        val onLargestTwo = if (reversed) r >= rMax - 1 else r <= 2
        val onSmallestTwo = if (reversed) r <= 2 else r >= rMax - 1
        return (f == fMax && onLargestTwo) || (f == 1 && onSmallestTwo)
    }

    private fun isNumberedFromSmallest(rearGear: Int, rearMax: Int): Boolean {
        val teeth = rearTeeth ?: return false
        return (rearGear == 1 && teeth <= SMALL_COG_TEETH) ||
            (rearGear == rearMax && teeth >= LARGE_COG_TEETH)
    }

    /** Alert state for field/slot coloring: cassette ends first, then cross-chain. */
    fun drivetrainAlert(): DrivetrainAlert? =
        when (cassetteEnd()) {
            CassetteEnd.SMALLEST -> DrivetrainAlert.SMALLEST_COG
            CassetteEnd.LARGEST -> DrivetrainAlert.LARGEST_COG
            else -> if (isCrossChained()) DrivetrainAlert.CROSS_CHAIN else null
        }

    companion object {
        // No cassette's largest cog is under 20 teeth or its smallest over 14.
        private const val LARGE_COG_TEETH = 20
        private const val SMALL_COG_TEETH = 14
        private const val MIN_CASSETTE_FOR_CROSS = 6
    }
}

/**
 * Decides when the shift overlay shows: on a shift that lands on the smallest or the largest cog,
 * on ends plus cross-chaining, or on every shift. Never for the first reading after a derailleur
 * connects, which is not a shift, and never while the gears stay put.
 */
internal class ShiftDetector(
    private val trigger: ShiftTrigger = ShiftTrigger.END_COGS,
    private val warnLowBattery: Boolean = false,
) {
    constructor(
        everyShift: Boolean
    ) : this(if (everyShift) ShiftTrigger.EVERY_SHIFT else ShiftTrigger.END_COGS)

    private var last: GearReading? = null
    private var batteryWarned = false

    /** The overlay to show for [reading]: null for none, else whether it carries a highlight. */
    fun onReading(
        reading: GearReading,
        battery: DrivetrainBattery = DrivetrainBattery(),
    ): Shift? {
        if (!battery.isLowOrCritical) batteryWarned = false
        val connected = reading.rear != null && reading.rear > 0
        val previous = last
        last = reading.takeIf { connected }
        return if (!connected || previous == null) {
            null
        } else {
            classifyShift(previous, reading, battery)
        }
    }

    private fun classifyShift(
        previous: GearReading,
        reading: GearReading,
        battery: DrivetrainBattery,
    ): Shift? {
        val rearShift = previous.rear != reading.rear
        if (!rearShift && !didFrontShift(previous, reading)) return null
        val end = if (rearShift) reading.cassetteEnd() else null
        val cross = trigger == ShiftTrigger.ENDS_AND_CROSS && reading.isCrossChained()
        val firstLowBattShift = consumeLowBatteryWarning(battery)
        return when {
            end != null -> Shift(end)
            cross -> Shift(CassetteEnd.CROSS_CHAIN)
            trigger == ShiftTrigger.EVERY_SHIFT || firstLowBattShift -> Shift(null)
            else -> null
        }
    }

    // Ignore initial front-derailleur connection (null -> gear) and temporary dropouts.
    private fun didFrontShift(previous: GearReading, reading: GearReading): Boolean =
        previous.front != null && reading.front != null && previous.front != reading.front

    private fun consumeLowBatteryWarning(battery: DrivetrainBattery): Boolean {
        val shouldWarn = warnLowBattery && battery.isLowOrCritical && !batteryWarned
        if (shouldWarn) batteryWarned = true
        return shouldWarn
    }

    data class Shift(val end: CassetteEnd?)
}

/**
 * The shift overlay's panel, after Ki2's dark overlay and GearsView (github.com/valterc/ki2,
 * Copyright (c) 2022 Valter C., MIT licence): a title bar, the chainrings and cassette as bars with
 * the gear in use filled, and the gears with their ratio.
 */
@SuppressLint("ViewConstructor") // only ever built in code
internal class ShiftOverlayView(
    context: Context,
    private val reading: GearReading,
    private val end: CassetteEnd?,
    private val config: ShiftOverlayConfig,
    private val fillsWindow: Boolean = false,
    battery: DrivetrainBattery = DrivetrainBattery(),
) : View(context) {
    private val metrics = resources.displayMetrics
    private val scale = config.size.scale
    private val stroke = dp(GEAR_STROKE_DP)
    private val pad = dp(PAD_DP)

    private val showsBars = config.showBars && reading.rearCount != null && reading.rear != null
    private val showsFront = config.showChainrings && reading.front != null
    private val ratio =
        reading.ratio?.takeIf { config.showRatio }?.let { String.format(Locale.US, "%.2f", it) }
    private val batteryBadge = battery.badgeText?.takeIf { config.batteryAlert }
    // The overlay is never empty: with every part off, the gears are still written.
    private val gears =
        reading.label(if (showsBars) config.gearText else config.textGears, showsFront)
            ?: reading.label(ShiftGearText.TEETH, showsFront).takeIf {
                !config.showHeader && !showsBars && ratio == null
            }

    private var panelWidth = 0
    private var panelHeight = 0

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND }
    private val titleBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TITLE_BAR }
    private val selected = gearPaint(GEAR_FILL)
    private val selectedCog = gearPaint(end?.color ?: GEAR_FILL)
    private val unselected =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GEAR_BORDER
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
    private val title = text(TITLE_SP, LABEL_BOLD, end?.color ?: Color.WHITE)
    private val badgePaint = text(BADGE_SP, LABEL_BOLD, battery.badgeColor)
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
        if (showsBars) height += dp(GEARS_DP) + lineHeightOf(countText) + pad
        if (hasTextRow) height += lineHeightOf(gearText) + pad
        val available = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SIDE_MARGIN_DP, metrics)
        val width =
            if (showsBars) (available - margin * 2) * config.size.widthFraction else textWidth()
        panelWidth = width.coerceAtMost(available).toInt()
        panelHeight = height.toInt()
        if (fillsWindow) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec),
            )
        } else {
            setMeasuredDimension(panelWidth, panelHeight)
        }
    }

    /** The narrowest panel that fits the title (plus any battery badge) and the text row. */
    private fun textWidth(): Float {
        val row =
            listOfNotNull(
                gears?.let { gearText.measureText(it) },
                ratio?.let { ratioText.measureText(it) },
            )
        val rowWidth = row.sum() + if (row.size > 1) pad * 2 else 0f
        val badgeW = batteryBadge?.let { badgePaint.measureText(it) + pad * 2 } ?: 0f
        val titleWidth = if (config.showHeader) title.measureText(titleText) + badgeW else 0f
        return maxOf(rowWidth, titleWidth) + pad * 2
    }

    val panelBounds: Rect
        get() {
            if (!fillsWindow) return Rect(0, 0, panelWidth, panelHeight)
            val margin = dp(SIDE_MARGIN_DP).toInt()
            val (x, y) =
                overlayOrigin(
                    measuredWidth to measuredHeight,
                    panelWidth to panelHeight,
                    margin,
                    config,
                )
            return Rect(x, y, x + panelWidth, y + panelHeight)
        }

    override fun onDraw(canvas: Canvas) {
        val bounds = panelBounds
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        drawPanel(canvas)
    }

    private fun drawPanel(canvas: Canvas) {
        val w = panelWidth.toFloat()
        val radius = dp(CORNER_DP)
        canvas.drawRoundRect(0f, 0f, w, panelHeight.toFloat(), radius, radius, background)

        var y = pad
        if (config.showHeader) {
            val barHeight = dp(TITLE_BAR_DP)
            canvas.drawRoundRect(0f, 0f, w, barHeight, radius, radius, titleBar)
            canvas.drawRect(0f, barHeight - radius, w, barHeight, titleBar)
            canvas.drawText(titleText, pad, baseline(title, 0f, barHeight), title)
            batteryBadge?.let { badge ->
                val bx = w - pad - badgePaint.measureText(badge)
                canvas.drawText(badge, bx, baseline(badgePaint, 0f, barHeight), badgePaint)
            }
            y = barHeight + pad
        }

        if (showsBars) {
            drawGears(canvas, pad, y, w - pad * 2, dp(GEARS_DP))
            y += dp(GEARS_DP) + lineHeightOf(countText) + pad
        }

        if (hasTextRow) {
            val line = lineHeightOf(gearText)
            val base = baseline(gearText, y, y + line)
            gears?.let { canvas.drawText(it, pad, base, gearText) }
            val rightText = ratio ?: batteryBadge?.takeIf { !config.showHeader }
            val rightPaint = if (ratio != null) ratioText else badgePaint
            rightText?.let {
                canvas.drawText(it, w - pad - rightPaint.measureText(it), base, rightPaint)
            }
        }
    }

    /** Ki2's GearsView geometry: chainrings rise left to right, the cassette falls. */
    private fun drawGears(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val frontCount = reading.frontCount?.takeIf { showsFront } ?: 0
        val rearCount = reading.rearCount ?: return
        val space = width * GEAR_SPACE
        val setSpace = if (frontCount > 0) width * SET_SPACE else 0f
        val total = frontCount + rearCount
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
                val ringPaint =
                    if (i == reading.front) {
                        if (end == CassetteEnd.CROSS_CHAIN) selectedCog else selected
                    } else unselected
                canvas.bar(x, barTop + bump * (frontCount - i), gearWidth, bottom, ringPaint)
                x += gearWidth + space
            }
            canvas.drawCount(start + space, x - space, bottom, reading.front, frontCount)
        }

        val rearLeft = start + (gearWidth + space * 2) * frontCount + setSpace
        x = rearLeft
        val bump = if (rearCount == 1) 0f else usable * REAR_DROP / (rearCount - 1)
        for (i in 1..rearCount) {
            canvas.bar(
                x,
                barTop + bump * (i - 1),
                gearWidth,
                bottom,
                if (i == reading.rear) selectedCog else unselected,
            )
            x += gearWidth + space
        }
        canvas.drawCount(rearLeft, x - space, bottom, reading.rear, rearCount)
    }

    private fun Canvas.bar(
        x: Float,
        top: Float,
        width: Float,
        bottom: Float,
        paint: Paint,
    ) {
        val r = width / 2
        drawRoundRect(x, top, x + width, bottom, r, r, paint)
    }

    private fun gearPaint(fill: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fill
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = stroke
        }

    private fun Canvas.drawCount(
        from: Float,
        to: Float,
        top: Float,
        gear: Int?,
        count: Int,
    ) {
        val label = "${gear ?: "-"}/$count"
        val x = (from + to) / 2 - countText.measureText(label) / 2
        val line = lineHeightOf(countText)
        drawText(label, x, baseline(countText, top + stroke, top + stroke + line), countText)
    }

    private fun text(sp: Float, face: Typeface, textColor: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * scale, metrics)
            typeface = face
            color = textColor
        }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value * scale, metrics)

    private companion object {
        val VALUE_FONT: Typeface = Typeface.create("relative", Typeface.NORMAL)
        val LABEL_BOLD: Typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.BOLD)

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
        const val BADGE_SP = 13f
        const val COUNT_SP = 13f
        const val GEARS_SP = 28f
        const val RATIO_SP = 22f
    }
}

private fun lineHeightOf(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

private fun baseline(paint: Paint, top: Float, bottom: Float): Float {
    val m = paint.fontMetrics
    return (top + bottom) / 2 - (m.ascent + m.descent) / 2
}

/**
 * Shows [ShiftOverlayView] over the ride screen for a few seconds, the way Ki2 shows shifting: a
 * window that never takes focus or touches and hides itself.
 */
class ShiftOverlay(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = this.context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val hide = Runnable { remove() }

    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    fun show(
        reading: GearReading,
        end: CassetteEnd?,
        config: ShiftOverlayConfig,
        battery: DrivetrainBattery = DrivetrainBattery(),
    ) {
        handler.post {
            remove()
            if (!allowed()) {
                Timber.w("Shift overlay: no permission to draw over other apps")
                return@post
            }
            val panel = ShiftOverlayView(context, reading, end, config, fillsWindow = true, battery)
            val params =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
            runCatching { windowManager.addView(panel, params) }
                .onSuccess {
                    view = panel
                    handler.postDelayed(hide, config.seconds * MS_PER_S)
                }
                .onFailure { Timber.w(it, "Shift overlay: could not draw") }
        }
    }

    /** Removes any currently displayed overlay window immediately. */
    fun dismiss() {
        handler.post { remove() }
    }

    private fun remove() {
        handler.removeCallbacks(hide)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private companion object {
        const val MS_PER_S = 1_000L
    }
}

/**
 * Top-left corner in pixels of a [panel] (`width to height`) overlay on a [screen] (`width to
 * height`) display.
 */
internal fun overlayOrigin(
    screen: Pair<Int, Int>,
    panel: Pair<Int, Int>,
    margin: Int,
    config: ShiftOverlayConfig,
): Pair<Int, Int> {
    val (screenWidth, screenHeight) = screen
    val (panelWidth, panelHeight) = panel
    val x =
        when (config.align) {
            ShiftOverlayAlign.LEFT -> margin
            ShiftOverlayAlign.CENTER -> (screenWidth - panelWidth) / 2
            ShiftOverlayAlign.RIGHT -> screenWidth - panelWidth - margin
        }
    val y = (screenHeight - panelHeight).coerceAtLeast(0) * config.height.coerceIn(0, 100) / 100
    return x.coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0)) to y
}

@Suppress("LongParameterList")
internal fun overlayOrigin(
    screenWidth: Int,
    screenHeight: Int,
    panelWidth: Int,
    panelHeight: Int,
    margin: Int,
    config: ShiftOverlayConfig,
): Pair<Int, Int> =
    overlayOrigin(screenWidth to screenHeight, panelWidth to panelHeight, margin, config)

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

private fun Double?.toBatteryStatus(): BatteryStatus? =
    this?.toInt()
        ?.let { BatteryStatus.entries.getOrNull(it) }
        ?.takeIf {
            it != BatteryStatus.INVALID
        }

/** Streams electronic drivetrain battery levels, emitting an empty reading before the first fix. */
internal fun KarooSystemService.streamDrivetrainBattery(): Flow<DrivetrainBattery> =
    gearValues(DataType.Type.SHIFTING_BATTERY)
        .map { values ->
            DrivetrainBattery(
                front =
                    values
                        ?.get(DataType.Field.SHIFTING_BATTERY_STATUS_FRONT_DERAILLEUR)
                        .toBatteryStatus(),
                rear =
                    values
                        ?.get(DataType.Field.SHIFTING_BATTERY_STATUS_REAR_DERAILLEUR)
                        .toBatteryStatus(),
                overall = values?.get(DataType.Field.SHIFTING_BATTERY_STATUS).toBatteryStatus(),
            )
        }
        .onStart { emit(DrivetrainBattery()) }

/** Shows the shift overlay while it is switched on. Runs for the life of the extension service. */
internal suspend fun runShiftOverlay(context: Context, karooSystem: KarooSystemService) {
    val overlay = ShiftOverlay(context)
    try {
        context.streamShiftOverlayConfig().collectLatest { config ->
            if (!config.enabled) {
                overlay.dismiss()
                return@collectLatest
            }
            val detector =
                ShiftDetector(trigger = config.trigger, warnLowBattery = config.batteryAlert)
            combine(karooSystem.streamGears(), karooSystem.streamDrivetrainBattery()) {
                    gears,
                    battery ->
                    gears to battery
                }
                .collect { (reading, battery) ->
                    detector.onReading(reading, battery)?.let {
                        overlay.show(reading, it.end, config, battery)
                    }
                }
        }
    } finally {
        overlay.dismiss()
    }
}
