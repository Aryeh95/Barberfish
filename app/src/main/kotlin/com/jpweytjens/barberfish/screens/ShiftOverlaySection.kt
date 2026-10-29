package com.jpweytjens.barberfish.screens

import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.jpweytjens.barberfish.datatype.shared.Grey200
import com.jpweytjens.barberfish.datatype.shared.OceanBlue
import com.jpweytjens.barberfish.extension.CassetteEnd
import com.jpweytjens.barberfish.extension.GearReading
import com.jpweytjens.barberfish.extension.ShiftGearText
import com.jpweytjens.barberfish.extension.ShiftOverlay
import com.jpweytjens.barberfish.extension.ShiftOverlayAlign
import com.jpweytjens.barberfish.extension.ShiftOverlayConfig
import com.jpweytjens.barberfish.extension.ShiftOverlaySize
import com.jpweytjens.barberfish.extension.ShiftOverlayView
import com.jpweytjens.barberfish.extension.ShiftTrigger
import kotlin.math.roundToInt
import timber.log.Timber

private val SHIFT_OVERLAY_SECONDS = listOf(2 to "2 s", 3 to "3 s", 5 to "5 s")

// Behind the settings preview, standing in for a ride page.
private val PREVIEW_BACKDROP = Color(0xFF5C6B73)

// The slider's stops between its ends: every 5%.
private const val HEIGHT_SNAP = 5
private const val HEIGHT_STEPS = 100 / HEIGHT_SNAP - 1
private val SKETCH_WIDTH = 120.dp
private val SKETCH_PANEL = Color(0xFF102020)
private val SKETCH_ACCENT = Color(CassetteEnd.SMALLEST.color)
private const val SKETCH_ACCENT_DP = 3
// The Karoo's 480 x 800 screen, for the sketch's first frame before this window is measured.
private const val KAROO_RATIO = 800f / 480f

// What the preview shows: a 2x12 SRAM AXS road drivetrain on its smallest cog.
private val PREVIEW_READING =
    GearReading(
        front = 2,
        frontCount = 2,
        frontTeeth = 50,
        rear = 12,
        rearCount = 12,
        rearTeeth = 10,
    )

/**
 * The Shifting section: a live preview of the shift overlay, its settings and layout, the
 * permission it needs, and a button that shows it over this screen.
 */
@Composable
internal fun ColumnScope.ShiftOverlaySectionContent(
    config: ShiftOverlayConfig,
    overlayAllowed: Boolean,
    onUpdate: (ShiftOverlayConfig) -> Unit,
) {
    val context = LocalContext.current
    HelperText(
        "Shows the gears over the ride screen for a moment when a shift lands on the smallest or " +
            "largest cog, from any electronic groupset paired with the Karoo, such as SRAM AXS."
    )
    BoolToggleRow(
        label = "SHIFT OVERLAY",
        value = config.enabled,
        onChange = { onUpdate(config.copy(enabled = it)) },
    )
    if (!config.enabled) return

    if (!overlayAllowed) {
        HelperText(
            "Barberfish+ needs permission to display over other apps to draw on the ride screen. " +
                "Turn it on for Barberfish+ on the next screen, then come back."
        )
        ActionButton("ALLOW DISPLAY OVER OTHER APPS") {
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri(),
                )
            runCatching { context.startActivity(intent) }
                .onFailure { Timber.w(it, "No overlay permission screen") }
        }
    }

    ShiftOverlayPreview(config)

    ChoiceRow(
        label = "SHOW ON",
        options = ShiftTrigger.entries.map { it to it.label },
        selected = config.trigger,
        onSelect = { onUpdate(config.copy(trigger = it)) },
        help = "The ends of the cassette only, or every shift, front or rear.",
    )
    ChoiceRow(
        label = "SHOW FOR",
        options = SHIFT_OVERLAY_SECONDS,
        selected = config.seconds,
        onSelect = { onUpdate(config.copy(seconds = it)) },
    )
    ShiftOverlayLayoutControls(config, onUpdate)
    ShiftOverlayPlacementControls(config, onUpdate)

    if (overlayAllowed) {
        ActionButton("SHOW ON SCREEN") {
            ShiftOverlay(context).show(PREVIEW_READING, CassetteEnd.SMALLEST, config)
        }
    }
}

/** What the overlay shows and how big: size, header, gear bars, gears text, ratio, chainrings. */
@Composable
private fun ShiftOverlayLayoutControls(
    config: ShiftOverlayConfig,
    onUpdate: (ShiftOverlayConfig) -> Unit,
) {
    ChoiceRow(
        label = "SIZE",
        options = ShiftOverlaySize.entries.map { it to it.label },
        selected = config.size,
        onSelect = { onUpdate(config.copy(size = it)) },
    )
    BoolToggleRow(
        label = "HEADER",
        value = config.showHeader,
        onChange = { onUpdate(config.copy(showHeader = it)) },
        help = "With the header off, the gears are written in the end's color instead.",
    )
    BoolToggleRow(
        label = "GEAR BARS",
        value = config.showBars,
        onChange = { onUpdate(config.copy(showBars = it)) },
        help =
            "The chainrings and cassette drawn as bars. Off, the overlay is as wide as its text.",
    )
    // Without the bars the gears are all that is left to show, so they cannot be turned off.
    ChoiceRow(
        label = "GEARS AS",
        options =
            ShiftGearText.entries
                .filter { config.showBars || it != ShiftGearText.OFF }
                .map { it to it.label },
        selected = if (config.showBars) config.gearText else config.textGears,
        onSelect = { onUpdate(config.copy(gearText = it)) },
        help = "F50 - R10 in teeth, or F2 - R12 as gear numbers.",
    )
    BoolToggleRow(
        label = "RATIO",
        value = config.showRatio,
        onChange = { onUpdate(config.copy(showRatio = it)) },
    )
    BoolToggleRow(
        label = "CHAINRINGS",
        value = config.showChainrings,
        onChange = { onUpdate(config.copy(showChainrings = it)) },
        help = "Off leaves the front out of the bars and the gears, for the cassette alone.",
    )
}

/**
 * Where on the screen the overlay sits: how far down, and which side when it is narrower than the
 * screen, with a sketch of the Karoo's screen showing the panel at its real size and place.
 */
@Composable
private fun ShiftOverlayPlacementControls(
    config: ShiftOverlayConfig,
    onUpdate: (ShiftOverlayConfig) -> Unit,
) {
    LabeledHelper("PLACE ON SCREEN") {
        HelperText(
            "How far down the screen the overlay sits. Drag it in the sketch, or use the slider."
        )
    }
    ScreenPlacementSketch(config) { onUpdate(config.copy(heightPercent = it)) }
    Slider(
        value = config.height.toFloat(),
        onValueChange = { onUpdate(config.copy(heightPercent = snapHeight(it))) },
        valueRange = 0f..100f,
        steps = HEIGHT_STEPS,
        colors =
            SliderDefaults.colors(
                thumbColor = OceanBlue,
                activeTrackColor = OceanBlue,
                inactiveTrackColor = Grey200,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
    )
    ChoiceRow(
        label = "ALIGN",
        options = ShiftOverlayAlign.entries.map { it to it.label },
        selected = config.align,
        onSelect = { onUpdate(config.copy(align = it)) },
        help = "The side an overlay narrower than the screen keeps to.",
    )
}

/**
 * The Karoo's screen in outline with the overlay where it will show. The screen is this app's own
 * window, which on the Karoo fills the screen, and the overlay is placed in it by the overlay's own
 * view, as it places itself in its window on the ride screen. Dragging or tapping in the sketch
 * moves the overlay up or down.
 */
@Composable
private fun ScreenPlacementSketch(config: ShiftOverlayConfig, onHeight: (Int) -> Unit) {
    val context = LocalContext.current
    val root = LocalView.current.rootView
    var screen by remember { mutableStateOf(IntSize(root.width, root.height)) }
    val panel =
        remember(config, screen) {
            if (screen.width <= 0 || screen.height <= 0) {
                null
            } else {
                ShiftOverlayView(context, PREVIEW_READING, CassetteEnd.SMALLEST, config, true).run {
                    measure(
                        View.MeasureSpec.makeMeasureSpec(screen.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(screen.height, View.MeasureSpec.EXACTLY),
                    )
                    panelBounds
                }
            }
        }
    // A touch at the sketch's [touchY] puts the overlay's middle there.
    fun heightAt(touchY: Float, sketchPx: Float): Int {
        val bounds = panel ?: return config.height
        val panelPx = bounds.height() * sketchPx / screen.height
        val free = (sketchPx - panelPx).coerceAtLeast(1f)
        return snapHeight((touchY - panelPx / 2) / free * 100)
    }
    Column(
        modifier =
            Modifier.fillMaxWidth().onGloballyPositioned {
                if (root.width > 0) screen = IntSize(root.width, root.height)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ratio = if (screen.width > 0) screen.height.toFloat() / screen.width else KAROO_RATIO
        Canvas(
            modifier =
                Modifier.size(SKETCH_WIDTH, SKETCH_WIDTH * ratio)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PREVIEW_BACKDROP)
                    .pointerInput(panel) {
                        detectTapGestures { onHeight(heightAt(it.y, size.height.toFloat())) }
                    }
                    .pointerInput(panel) {
                        detectVerticalDragGestures { change, _ ->
                            onHeight(heightAt(change.position.y, size.height.toFloat()))
                        }
                    }
        ) {
            val bounds = panel ?: return@Canvas
            val sx = size.width / screen.width
            val sy = size.height / screen.height
            drawRoundRect(
                color = SKETCH_PANEL,
                topLeft = Offset(bounds.left * sx, bounds.top * sy),
                size = Size(bounds.width() * sx, bounds.height() * sy),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawRoundRect(
                color = SKETCH_ACCENT,
                topLeft = Offset(bounds.left * sx, bounds.top * sy),
                size = Size(bounds.width() * sx, SKETCH_ACCENT_DP.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
        Caption("${config.height}% down the screen")
    }
}

/** A height on the screen, rounded to the nearest 5% and kept between top and bottom. */
private fun snapHeight(percent: Float): Int =
    ((percent / HEIGHT_SNAP).roundToInt() * HEIGHT_SNAP).coerceIn(0, 100)

/**
 * The overlay as configured, drawn by its own view on a ride-screen grey, updating as it changes.
 */
@Composable
private fun ShiftOverlayPreview(config: ShiftOverlayConfig) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(PREVIEW_BACKDROP)
                .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        key(config) {
            AndroidView(
                factory = { ShiftOverlayView(it, PREVIEW_READING, CassetteEnd.SMALLEST, config) }
            )
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, OceanBlue, RoundedCornerShape(6.dp))
                .background(Color.White)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OceanBlue,
                ),
        )
    }
}
