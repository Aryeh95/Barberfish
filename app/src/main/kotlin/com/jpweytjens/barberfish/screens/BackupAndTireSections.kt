package com.jpweytjens.barberfish.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.jpweytjens.barberfish.datatype.TirePosition
import com.jpweytjens.barberfish.datatype.TirePressure
import com.jpweytjens.barberfish.datatype.shared.formatFixed
import com.jpweytjens.barberfish.extension.FieldOrientation
import com.jpweytjens.barberfish.extension.GearDisplayFormat
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.TireAlert
import com.jpweytjens.barberfish.extension.TireAlertConfig
import com.jpweytjens.barberfish.extension.TireAlertKind
import com.jpweytjens.barberfish.extension.TireAlertOverlay
import com.jpweytjens.barberfish.extension.TirePressureUnit
import com.jpweytjens.barberfish.extension.TiresFieldConfig
import com.jpweytjens.barberfish.extension.VamWindow
import com.jpweytjens.barberfish.extension.WkgSource
import com.jpweytjens.barberfish.extension.exportSettingsJson
import com.jpweytjens.barberfish.extension.importSettingsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val DEFAULT_BACKUP_FILENAME = "barberfish-plus-settings.json"

/** Controls for the combined Front & Rear Tires standalone field. */
@Composable
internal fun DualTireFieldControls(
    config: TiresFieldConfig,
    outriderPaired: Boolean,
    onUpdate: (TiresFieldConfig) -> Unit,
) {
    if (!outriderPaired) {
        HelperText("Pair Outrider TL sensors in the Outrider extension to stream tire pressure.")
    }
    ChoiceRow(
        label = "ORIENTATION",
        options = FieldOrientation.entries.map { it to it.label },
        selected = config.orientation,
        onSelect = { onUpdate(config.copy(orientation = it)) },
        help = "Front and rear side by side, or front above rear.",
    )
    ChoiceRow(
        label = "UNIT",
        options = TirePressureUnit.entries.map { it to it.label },
        selected = config.unit,
        onSelect = { nextUnit ->
            onUpdate(
                config.copy(
                    unit = nextUnit,
                    frontTarget =
                        TirePressure.convertTarget(config.frontTarget, config.unit, nextUnit),
                    rearTarget =
                        TirePressure.convertTarget(config.rearTarget, config.unit, nextUnit),
                )
            )
        },
        help = "The unit set in the Outrider app.",
    )
    ControlLabel("FRONT TARGET (${config.unit.label})")
    NumberInput(
        value = config.frontTarget,
        unit = PRESSURE_INPUT_UNIT,
        placeholder = "Front target (${config.unit.label}), empty for no color",
        onValueChange = { onUpdate(config.copy(frontTarget = it)) },
    )
    ControlLabel("REAR TARGET (${config.unit.label})")
    NumberInput(
        value = config.rearTarget,
        unit = PRESSURE_INPUT_UNIT,
        placeholder = "Rear target (${config.unit.label}), empty uses front target",
        onValueChange = { onUpdate(config.copy(rearTarget = it)) },
    )
    ControlLabel("STEP (%)")
    RangeInput(
        value = config.bandPercent,
        onValueChange = { onUpdate(config.copy(bandPercent = it)) },
    )
}

/** Controls for the combined Front & Rear Tires HUD / Double slot. */
@Composable
internal fun DualTireSlotControls(
    slot: HUDSlotConfig,
    outriderPaired: Boolean,
    onUpdate: (HUDSlotConfig) -> Unit,
) {
    if (!outriderPaired) {
        HelperText("Pair Outrider TL sensors in the Outrider extension to stream tire pressure.")
    }
    ChoiceRow(
        label = "ORIENTATION",
        options = FieldOrientation.entries.map { it to it.label },
        selected = slot.tireOrientation,
        onSelect = { onUpdate(slot.copy(tireOrientation = it)) },
    )
    ChoiceRow(
        label = "UNIT",
        options = TirePressureUnit.entries.map { it to it.label },
        selected = slot.tireUnit,
        onSelect = { nextUnit ->
            onUpdate(
                slot.copy(
                    tireUnit = nextUnit,
                    tireTarget =
                        TirePressure.convertTarget(slot.tireTarget, slot.tireUnit, nextUnit),
                    tireRearTarget =
                        TirePressure.convertTarget(slot.tireRearTarget, slot.tireUnit, nextUnit),
                )
            )
        },
        help = "The unit set in the Outrider app.",
    )
    ControlLabel("FRONT TARGET (${slot.tireUnit.label})")
    NumberInput(
        value = slot.tireTarget,
        unit = PRESSURE_INPUT_UNIT,
        placeholder = "Front target (${slot.tireUnit.label}), empty for no color",
        onValueChange = { onUpdate(slot.copy(tireTarget = it)) },
    )
    ControlLabel("REAR TARGET (${slot.tireUnit.label})")
    NumberInput(
        value = slot.tireRearTarget,
        unit = PRESSURE_INPUT_UNIT,
        placeholder = "Rear target (${slot.tireUnit.label}), empty uses front target",
        onValueChange = { onUpdate(slot.copy(tireRearTarget = it)) },
    )
    ControlLabel("STEP (%)")
    RangeInput(
        value = slot.tireBandPercent,
        onValueChange = { onUpdate(slot.copy(tireBandPercent = it)) },
    )
}

@Composable
internal fun WkgSourceControls(selected: WkgSource, onSelected: (WkgSource) -> Unit) {
    ControlLabel("SOURCE")
    SmoothingSlider(
        options = WkgSource.entries,
        selected = selected,
        label = { it.label },
        thumbIcon = com.jpweytjens.barberfish.R.drawable.ic_col_power,
        onSelected = onSelected,
    )
}

@Composable
internal fun VamWindowControls(selected: VamWindow, onSelected: (VamWindow) -> Unit) {
    ChoiceRow(
        label = "WINDOW",
        options = VamWindow.entries.map { it to it.label },
        selected = selected,
        onSelect = onSelected,
        help = "30s smooths barometer noise on climbs.",
    )
}

@Composable
internal fun GearsFormatControls(
    format: GearDisplayFormat,
    showChainrings: Boolean,
    onFormatChange: (GearDisplayFormat) -> Unit,
    onChainringsChange: (Boolean) -> Unit,
) {
    ChoiceRow(
        label = "DISPLAY",
        options = GearDisplayFormat.entries.map { it to it.label },
        selected = format,
        onSelect = onFormatChange,
        help = "50-10 in teeth, 2-12 as gear numbers, or 5.00 gear ratio.",
    )
    if (format != GearDisplayFormat.RATIO) {
        BoolToggleRow(
            label = "CHAINRINGS",
            value = showChainrings,
            onChange = onChainringsChange,
            help = "Off shows only the rear cog.",
        )
    }
}

/**
 * Tire Pressure Alert Overlay section: watches Outrider streams in the background and pops an alert
 * banner over the ride or map page on a rapid leak or red-band pressure drop.
 */
@Composable
internal fun ColumnScope.TireAlertSectionContent(
    config: TireAlertConfig,
    overlayAllowed: Boolean,
    outriderPaired: Boolean,
    onUpdate: (TireAlertConfig) -> Unit,
) {
    val context = LocalContext.current
    val testOverlay = remember(context) { TireAlertOverlay(context) }
    DisposableEffect(testOverlay) { onDispose { testOverlay.dismiss() } }

    HelperText(
        "Pops an alert over any ride or map page if an Outrider tire loses pressure rapidly or " +
            "drops into the red target band."
    )
    if (!outriderPaired) {
        HelperText("No Outrider sensors detected yet — pair them in the Outrider extension.")
    }
    BoolToggleRow(
        label = "TIRE ALERT OVERLAY",
        value = config.enabled,
        onChange = { onUpdate(config.copy(enabled = it)) },
    )
    if (!config.enabled) return

    if (!overlayAllowed) {
        HelperText(
            "Barberfish+ needs permission to display over other apps to show tire alerts over " +
                "the ride screen."
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
    TireAlertThresholdControls(config = config, onUpdate = onUpdate)
    if (overlayAllowed) {
        ActionButton("TEST TIRE ALERT") {
            val sampleVal = 52.0 * config.unit.fromPsi
            val dropVal = 3.2 * config.unit.fromPsi
            testOverlay.show(
                TireAlert(
                    position = TirePosition.REAR,
                    kind = TireAlertKind.RAPID_DROP,
                    pressureText = formatFixed(sampleVal, config.unit.decimals),
                    unitLabel = config.unit.label,
                    detailText =
                        "-${formatFixed(dropVal, config.unit.decimals)} ${config.unit.label}",
                )
            )
        }
    }
}

@Composable
private fun TireAlertThresholdControls(
    config: TireAlertConfig,
    onUpdate: (TireAlertConfig) -> Unit,
) {
    ChoiceRow(
        label = "UNIT",
        options = TirePressureUnit.entries.map { it to it.label },
        selected = config.unit,
        onSelect = { nextUnit ->
            onUpdate(
                config.copy(
                    unit = nextUnit,
                    frontTarget =
                        TirePressure.convertTarget(config.frontTarget, config.unit, nextUnit),
                    rearTarget =
                        TirePressure.convertTarget(config.rearTarget, config.unit, nextUnit),
                )
            )
        },
        help = "Match the unit set in the Outrider app.",
    )
    ControlLabel("LEAK RATE (PSI / MIN)")
    NumberInput(
        value = config.dropRatePsiPerMin,
        unit = PRESSURE_INPUT_UNIT,
        placeholder = "Drop rate (PSI/min), 0 disables leak rate check",
        onValueChange = { onUpdate(config.copy(dropRatePsiPerMin = it)) },
    )
    ControlLabel("FRONT TARGET (${config.unit.label})")
    NumberInput(
        value = config.frontTarget,
        unit = PRESSURE_INPUT_UNIT,
        placeholder = "Front target (${config.unit.label})",
        onValueChange = { onUpdate(config.copy(frontTarget = it)) },
    )
    ControlLabel("REAR TARGET (${config.unit.label})")
    NumberInput(
        value = config.rearTarget,
        unit = PRESSURE_INPUT_UNIT,
        placeholder = "Rear target (${config.unit.label}), empty uses front target",
        onValueChange = { onUpdate(config.copy(rearTarget = it)) },
    )
    ControlLabel("STEP (%)")
    RangeInput(
        value = config.bandPercent,
        onValueChange = { onUpdate(config.copy(bandPercent = it)) },
    )
}

/**
 * Backup & Restore section: exports or imports every Barberfish+ DataStore setting as a portable
 * JSON file via Android's document picker (no storage permission required).
 */
@Composable
internal fun ColumnScope.BackupSectionContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val message =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val (jsonText, count) = context.exportSettingsJson()
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(jsonText.toByteArray(Charsets.UTF_8))
                            } ?: error("Could not open destination file")
                            "Exported $count settings."
                        }
                            .getOrElse { e ->
                                Timber.e(e, "Failed to export settings")
                                "Export failed: ${e.message ?: "unknown error"}"
                            }
                    }
                status = message
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val message =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val raw =
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.bufferedReader(Charsets.UTF_8).readText()
                                } ?: error("Could not read selected file")
                            val count = context.importSettingsJson(raw)
                            "Restored $count settings."
                        }
                            .getOrElse { e ->
                                Timber.e(e, "Failed to import settings")
                                "Import failed: ${e.message ?: "invalid backup file"}"
                            }
                    }
                status = message
            }
        }

    HelperText(
        "Save all HUD slots, Doubles, palettes, tire targets, and shift overlay settings to a " +
            "portable JSON file, or restore a saved configuration."
    )
    ActionButton("EXPORT SETTINGS JSON") { exportLauncher.launch(DEFAULT_BACKUP_FILENAME) }
    ActionButton("IMPORT SETTINGS JSON") {
        importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
    }
    status?.let { Caption(it) }
}
