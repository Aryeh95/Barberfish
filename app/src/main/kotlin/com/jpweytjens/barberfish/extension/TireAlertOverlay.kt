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
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.jpweytjens.barberfish.datatype.TirePosition
import com.jpweytjens.barberfish.datatype.TirePressure
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.formatFixed
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import timber.log.Timber

enum class TireAlertKind(val badge: String) {
    RAPID_DROP("RAPID LOSS"),
    LOW_PRESSURE("LOW PRESSURE"),
}

data class TireAlert(
    val position: TirePosition,
    val kind: TireAlertKind,
    val pressureText: String,
    val unitLabel: String,
    val detailText: String,
)

/**
 * Watches one tire's pressure stream for rapid air loss (rolling drop
 * rate >= [TireAlertConfig.dropRatePsiPerMin] over 20–120 s and at least 1.5 PSI total drop) or
 * crossing into the red low-pressure band (>= 3 steps below target), rate-limited by a cooldown per
 * tire.
 */
internal class TireLeakDetector(
    private val position: TirePosition,
    private val config: TireAlertConfig,
) {
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var lastAlertMs: Long = Long.MIN_VALUE / 2

    fun onSample(rawValue: Double, nowMs: Long): TireAlert? {
        if (nowMs - lastAlertMs < COOLDOWN_MS) {
            record(rawValue, nowMs)
            return null
        }
        val psi = rawValue / config.unit.fromPsi
        record(rawValue, nowMs)
        val alert = rapidDropAlert(rawValue, psi, nowMs) ?: lowPressureAlert(rawValue)
        if (alert != null) lastAlertMs = nowMs
        return alert
    }

    private fun rapidDropAlert(rawValue: Double, psi: Double, nowMs: Long): TireAlert? {
        val rateLimit = config.dropRatePsiPerMin
        val anchor =
            if (rateLimit > 0.0) samples.firstOrNull { (ts, _) -> nowMs - ts >= MIN_WINDOW_MS }
            else null
        val elapsedMin = anchor?.let { (nowMs - it.first) / 60_000.0 } ?: 0.0
        val dropPsi = anchor?.let { it.second - psi } ?: 0.0
        return if (
            elapsedMin > 0.0 && dropPsi >= MIN_TOTAL_DROP_PSI && dropPsi / elapsedMin >= rateLimit
        ) {
            val dropDisplay = formatFixed(dropPsi * config.unit.fromPsi, config.unit.decimals)
            TireAlert(
                position = position,
                kind = TireAlertKind.RAPID_DROP,
                pressureText = formatFixed(rawValue, config.unit.decimals),
                unitLabel = config.unit.label,
                detailText = "-$dropDisplay ${config.unit.label}",
            )
        } else {
            null
        }
    }

    private fun lowPressureAlert(rawValue: Double): TireAlert? {
        val target =
            when (position) {
                TirePosition.FRONT -> config.frontTarget
                TirePosition.REAR ->
                    if (config.rearTarget > 0.0) config.rearTarget else config.frontTarget
            }
        val band =
            if (target > 0.0 && rawValue < target) {
                (TirePressure.tireColor(rawValue, target, config.bandPercent)
                        as? FieldColor.Deviation)
                    ?.band ?: 0
            } else {
                0
            }
        return if (band >= CRITICAL_BAND) {
            TireAlert(
                position = position,
                kind = TireAlertKind.LOW_PRESSURE,
                pressureText = formatFixed(rawValue, config.unit.decimals),
                unitLabel = config.unit.label,
                detailText = "Target ${formatFixed(target, config.unit.decimals)}",
            )
        } else {
            null
        }
    }

    private fun record(rawValue: Double, nowMs: Long) {
        val psi = rawValue / config.unit.fromPsi
        samples.addLast(nowMs to psi)
        while (samples.isNotEmpty() && nowMs - samples.first().first > MAX_WINDOW_MS) {
            samples.removeFirst()
        }
    }

    private companion object {
        const val MIN_WINDOW_MS = 20_000L
        const val MAX_WINDOW_MS = 120_000L
        const val MIN_TOTAL_DROP_PSI = 1.5
        const val CRITICAL_BAND = 3
        const val COOLDOWN_MS = 180_000L
    }
}

@SuppressLint("ViewConstructor")
private class TireAlertView(context: Context, private val alert: TireAlert) : View(context) {
    private val dm = resources.displayMetrics
    private val pad = dp(8f)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1212.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt() }
    private val titlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.BOLD)
            textSize = sp(17f)
            color = Color.WHITE
        }
    private val valuePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            textSize = sp(30f)
            color = 0xFFFF6E6E.toInt()
        }
    private val detailPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.BOLD)
            textSize = sp(16f)
            color = 0xFFFEE08B.toInt()
        }

    private val titleText = "${alert.position.label.uppercase()} · ${alert.kind.badge}"
    private val mainText = "${alert.pressureText} ${alert.unitLabel}"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val margin = dp(6f)
        val headerH = dp(28f)
        val valueH = valuePaint.fontMetrics.let { it.descent - it.ascent }
        val totalH = (headerH + pad * 2 + valueH).toInt()
        val totalW = (availW - margin * 2).coerceAtLeast(1f).toInt()
        setMeasuredDimension(totalW, totalH)
    }

    override fun onDraw(canvas: Canvas) {
        val w = measuredWidth.toFloat()
        val h = measuredHeight.toFloat()
        val r = dp(6f)
        val headerH = dp(28f)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        canvas.drawRoundRect(0f, 0f, w, headerH, r, r, barPaint)
        canvas.drawRect(0f, headerH - r, w, headerH, barPaint)
        canvas.drawText(titleText, pad, textBaseline(titlePaint, 0f, headerH), titlePaint)
        val rowBase = textBaseline(valuePaint, headerH + pad, h - pad)
        canvas.drawText(mainText, pad, rowBase, valuePaint)
        val detailW = detailPaint.measureText(alert.detailText)
        canvas.drawText(alert.detailText, w - pad - detailW, rowBase, detailPaint)
    }

    private fun textBaseline(paint: Paint, top: Float, bottom: Float): Float {
        val fm = paint.fontMetrics
        return (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)
}

/** Non-touchable alert window shown briefly at the top of the ride/map screen on a tire alert. */
class TireAlertOverlay(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = this.context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val hide = Runnable { remove() }

    fun show(alert: TireAlert) {
        handler.post {
            remove()
            if (!Settings.canDrawOverlays(context)) {
                Timber.w("Tire alert overlay: no permission to draw over other apps")
                return@post
            }
            val panel = TireAlertView(context, alert)
            val topMargin =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        TOP_MARGIN_DP,
                        context.resources.displayMetrics,
                    )
                    .toInt()
            val params =
                WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT,
                    )
                    .apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = topMargin
                    }
            runCatching { windowManager.addView(panel, params) }
                .onSuccess {
                    view = panel
                    handler.postDelayed(hide, DISPLAY_MS)
                }
                .onFailure { Timber.w(it, "Tire alert overlay: could not draw") }
        }
    }

    fun dismiss() {
        handler.post { remove() }
    }

    private fun remove() {
        handler.removeCallbacks(hide)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private companion object {
        const val DISPLAY_MS = 5_000L
        const val TOP_MARGIN_DP = 12f
    }
}

internal suspend fun runTireAlertOverlay(context: Context, karooSystem: KarooSystemService) {
    val overlay = TireAlertOverlay(context)
    try {
        context.streamTireAlertConfig().collectLatest { cfg ->
            if (!cfg.enabled) {
                overlay.dismiss()
                return@collectLatest
            }
            val frontDetector = TireLeakDetector(TirePosition.FRONT, cfg)
            val rearDetector = TireLeakDetector(TirePosition.REAR, cfg)
            val frontAlerts =
                karooSystem.streamDataFlow(TirePosition.FRONT.streamId).mapNotNull { state ->
                    val v =
                        (state as? StreamState.Streaming)
                            ?.dataPoint
                            ?.values
                            ?.get(DataType.Field.SINGLE) ?: return@mapNotNull null
                    frontDetector.onSample(v, SystemClock.elapsedRealtime())
                }
            val rearAlerts =
                karooSystem.streamDataFlow(TirePosition.REAR.streamId).mapNotNull { state ->
                    val v =
                        (state as? StreamState.Streaming)
                            ?.dataPoint
                            ?.values
                            ?.get(DataType.Field.SINGLE) ?: return@mapNotNull null
                    rearDetector.onSample(v, SystemClock.elapsedRealtime())
                }
            merge(frontAlerts, rearAlerts).collect { alert -> overlay.show(alert) }
        }
    } finally {
        overlay.dismiss()
    }
}
