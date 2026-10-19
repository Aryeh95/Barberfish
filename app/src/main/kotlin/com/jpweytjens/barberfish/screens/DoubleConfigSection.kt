package com.jpweytjens.barberfish.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jpweytjens.barberfish.datatype.DoubleField
import com.jpweytjens.barberfish.datatype.doubleRemoteViews
import com.jpweytjens.barberfish.datatype.shared.BarberfishYellow
import com.jpweytjens.barberfish.datatype.shared.DoubleState
import com.jpweytjens.barberfish.datatype.shared.PREVIEW_DELAY_MS
import com.jpweytjens.barberfish.datatype.shared.remoteViewsToBitmap
import com.jpweytjens.barberfish.extension.DoubleFieldConfig
import com.jpweytjens.barberfish.extension.DoubleFieldKind
import com.jpweytjens.barberfish.extension.DoubleLabel
import com.jpweytjens.barberfish.extension.DoubleOrientation
import com.jpweytjens.barberfish.extension.TimeConfig
import com.jpweytjens.barberfish.extension.ZoneConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.delay

// The preview is a native half-width cell on a four-row page: 240 by 160 px on the Karoo 3
// (density 1.875), with the value size the Karoo gives that cell.
private val DOUBLE_PREVIEW_WIDTH = 128.dp
private val DOUBLE_PREVIEW_HEIGHT = 85.dp
private const val DOUBLE_PREVIEW_TEXT_SP = 50

// Preview moment the screenshot tool freezes on: the steep climb of the preview ride, as in the
// HUD preview.
private const val SCREENSHOT_MOMENT_INDEX = 4

/**
 * The Double fields section: one card per Double, each with its orientation and label settings and
 * a live preview. Tapping a value in the preview opens that slot's field and options, the same card
 * a HUD column uses.
 */
@Composable
internal fun DoubleFieldsSection(
    configs: Map<DoubleFieldKind, DoubleFieldConfig>,
    zoneConfig: ZoneConfig,
    timeCfg: TimeConfig,
    profile: UserProfile,
    onUpdate: (DoubleFieldKind, DoubleFieldConfig) -> Unit,
    initialSelected: DoubleFieldKind? = null,
    initialSelectedSlot: Int? = null,
) {
    var selected by remember { mutableStateOf(initialSelected) }
    HelperText("Place Double 1, 2 or 3 on a data page, then set what each shows here.")
    DoubleFieldKind.entries.forEach { kind ->
        DoubleFieldCard(
            kind = kind,
            config = configs[kind] ?: kind.default,
            zoneConfig = zoneConfig,
            timeCfg = timeCfg,
            profile = profile,
            selected = selected == kind,
            onSelect = { selected = if (selected == kind) null else kind },
            onUpdate = { onUpdate(kind, it) },
            initialSelectedSlot = if (kind == initialSelected) initialSelectedSlot else null,
        )
    }
}

@Composable
private fun DoubleFieldCard(
    kind: DoubleFieldKind,
    config: DoubleFieldConfig,
    zoneConfig: ZoneConfig,
    timeCfg: TimeConfig,
    profile: UserProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (DoubleFieldConfig) -> Unit,
    initialSelectedSlot: Int?,
) {
    var selectedSlot by remember { mutableStateOf(initialSelectedSlot) }
    ExpandableCard(
        title = kind.title.uppercase(),
        selected = selected,
        onSelect = onSelect,
        testTag = "bf:double:${kind.number}",
        headerExtra = { HelperText(config.summary()) },
    ) {
        ChoiceRow(
            label = "ORIENTATION",
            options = DoubleOrientation.entries.map { it to it.label },
            selected = config.orientation,
            onSelect = { onUpdate(config.copy(orientation = it)) },
            help = "Side by side, or one above the other.",
        )
        ChoiceRow(
            label = "LABEL",
            options = DoubleLabel.entries.map { it to it.label },
            selected = config.label,
            onSelect = { onUpdate(config.copy(label = it)) },
            help = "Name each value by its icon, its header, both, or not at all.",
        )
        HelperText("Tap a value in the preview to choose its field and options.")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DoublePreview(
                config = config,
                zoneConfig = zoneConfig,
                timeCfg = timeCfg,
                profile = profile,
                selectedSlot = selectedSlot,
                onSlotSelected = { idx -> selectedSlot = if (selectedSlot == idx) null else idx },
                modifier = Modifier.testTag("bf:double:${kind.number}:preview"),
            )
        }
        when (selectedSlot) {
            0 ->
                HUDSlotFieldCard(
                    slot = config.first,
                    profile = profile,
                    onUpdate = { onUpdate(config.copy(first = it)) },
                )
            1 ->
                HUDSlotFieldCard(
                    slot = config.second,
                    profile = profile,
                    onUpdate = { onUpdate(config.copy(second = it)) },
                )
        }
    }
}

/** "Power and Heart rate, side by side": the card's one-line summary while it is closed. */
private fun DoubleFieldConfig.summary(): String {
    val arrangement =
        when (orientation) {
            DoubleOrientation.HORIZONTAL -> "side by side"
            DoubleOrientation.VERTICAL -> "stacked"
        }
    return "${first.field.displayName()} and ${second.field.displayName()}, $arrangement."
}

/**
 * The Double rendered through the same RemoteViews as the live cell, cycling the preview ride. Two
 * tap targets over it, left and right or top and bottom as the orientation lays the values out,
 * select a slot.
 */
@Composable
private fun DoublePreview(
    config: DoubleFieldConfig,
    zoneConfig: ZoneConfig,
    timeCfg: TimeConfig,
    profile: UserProfile,
    selectedSlot: Int?,
    onSlotSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNight = isSystemInDarkTheme()
    val states =
        remember(config, zoneConfig, timeCfg, profile) {
            DoubleField.previewStates(config, timeCfg, profile, zoneConfig)
        }
    val screenshotMode = LocalScreenshotMode.current
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(states, screenshotMode) {
        index = 0
        if (screenshotMode) {
            index = SCREENSHOT_MOMENT_INDEX.coerceIn(states.indices)
            return@LaunchedEffect
        }
        while (true) {
            delay(PREVIEW_DELAY_MS)
            index = (index + 1) % states.size
        }
    }
    val bitmap = rememberDoubleBitmap(states[index.coerceIn(states.indices)])
    Box(
        modifier =
            modifier
                .width(DOUBLE_PREVIEW_WIDTH)
                .height(DOUBLE_PREVIEW_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isNight) Color.Black else Color.White)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        DoubleSlotTargets(config.orientation, selectedSlot, onSlotSelected)
    }
}

/** [state] rendered as a native half-width cell, through the live cell's RemoteViews. */
@Composable
private fun rememberDoubleBitmap(state: DoubleState): Bitmap {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val design = LocalDataFieldDesign.current
    val isNight = isSystemInDarkTheme()
    val widthPx = (DOUBLE_PREVIEW_WIDTH.value * density).toInt()
    val heightPx = (DOUBLE_PREVIEW_HEIGHT.value * density).toInt()
    return remember(state, design, widthPx, heightPx, isNight) {
        val viewConfig =
            ViewConfig(
                gridSize = 30 to 15,
                viewSize = widthPx to heightPx,
                textSize = DOUBLE_PREVIEW_TEXT_SP,
                alignment = ViewConfig.Alignment.RIGHT,
                boundariesEnabled = false,
                preview = true,
            )
        remoteViewsToBitmap(
            doubleRemoteViews(state, design, viewConfig, context),
            widthPx,
            heightPx,
            context,
        )
    }
}

/** The two tap targets over the preview, laid out as [orientation] lays out the values. */
@Composable
private fun DoubleSlotTargets(
    orientation: DoubleOrientation,
    selectedSlot: Int?,
    onSlotSelected: (Int) -> Unit,
) {
    @Composable
    fun Target(idx: Int, modifier: Modifier) {
        Box(
            modifier
                .pointerInput(onSlotSelected) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onSlotSelected(idx)
                    }
                }
                .then(
                    if (selectedSlot == idx)
                        Modifier.border(2.dp, BarberfishYellow, RoundedCornerShape(6.dp))
                    else Modifier
                )
        )
    }
    if (orientation == DoubleOrientation.HORIZONTAL) {
        Row(Modifier.fillMaxSize()) {
            Target(0, Modifier.weight(1f).fillMaxSize())
            Target(1, Modifier.weight(1f).fillMaxSize())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Target(0, Modifier.weight(1f).fillMaxWidth())
            Target(1, Modifier.weight(1f).fillMaxWidth())
        }
    }
}
