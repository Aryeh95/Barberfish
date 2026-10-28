package com.jpweytjens.barberfish.screens

import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.jpweytjens.barberfish.datatype.shared.OceanBlue
import com.jpweytjens.barberfish.extension.CassetteEnd
import com.jpweytjens.barberfish.extension.GearReading
import com.jpweytjens.barberfish.extension.SHIFT_OVERLAY_EDGE_MARGIN_DP
import com.jpweytjens.barberfish.extension.ShiftGearText
import com.jpweytjens.barberfish.extension.ShiftOverlay
import com.jpweytjens.barberfish.extension.ShiftOverlayAlign
import com.jpweytjens.barberfish.extension.ShiftOverlayConfig
import com.jpweytjens.barberfish.extension.ShiftOverlaySize
import com.jpweytjens.barberfish.extension.ShiftOverlayView
import com.jpweytjens.barberfish.extension.ShiftTrigger
import com.jpweytjens.barberfish.extension.overlayOrigin
import kotlin.math.abs
import timber.log.Timber

private val SHIFT_OVERLAY_SECONDS = listOf(2 to "2 s", 3 to "3 s", 5 to "5 s")

// Behind the settings preview, standing in for a ride page.
private val PREVIEW_BACKDROP = Color(0xFF5C6B73)

private val HEIGHT_STEPS = (0..100 step 5).toList()
private val SKETCH_WIDTH = 120.dp
private val SKETCH_PANEL = Color(0xFF102020)
private val SKETCH_ACCENT = Color(CassetteEnd.SMALLEST.color)
private const val SKETCH_ACCENT_DP = 3

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
    ChoiceRow(
        label = "GEARS AS",
        options = ShiftGearText.entries.map { it to it.label },
        selected = config.gearText,
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
        HelperText("How far down the screen the overlay sits, from the top to the bottom.")
    }
    ScreenPlacementSketch(config)
    SmoothingSlider(
        options = HEIGHT_STEPS,
        selected = HEIGHT_STEPS.minBy { abs(it - config.height) },
        label = { "$it%" },
        onSelected = { onUpdate(config.copy(heightPercent = it)) },
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
 * The Karoo's screen in outline with the overlay where it will show, sized by measuring the
 * overlay's own view at this device's screen width and placed by the same [overlayOrigin] the
 * overlay uses.
 */
@Composable
private fun ScreenPlacementSketch(config: ShiftOverlayConfig) {
    val context = LocalContext.current
    val metrics = LocalResources.current.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = metrics.heightPixels
    val panel =
        remember(config) {
            ShiftOverlayView(context, PREVIEW_READING, CassetteEnd.SMALLEST, config).run {
                measure(
                    View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                measuredWidth to measuredHeight
            }
        }
    val margin = (SHIFT_OVERLAY_EDGE_MARGIN_DP * metrics.density).toInt()
    val (x, y) = overlayOrigin(screenW, screenH, panel.first, panel.second, margin, config)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            modifier =
                Modifier.width(SKETCH_WIDTH)
                    .aspectRatio(screenW.toFloat() / screenH.coerceAtLeast(1))
                    .clip(RoundedCornerShape(6.dp))
                    .background(PREVIEW_BACKDROP)
        ) {
            val sx = size.width / screenW
            val sy = size.height / screenH
            drawRoundRect(
                color = SKETCH_PANEL,
                topLeft = Offset(x * sx, y * sy),
                size = Size(panel.first * sx, panel.second * sy),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawRoundRect(
                color = SKETCH_ACCENT,
                topLeft = Offset(x * sx, y * sy),
                size = Size(panel.first * sx, SKETCH_ACCENT_DP.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

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
