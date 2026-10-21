package com.jpweytjens.barberfish.datatype

import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.formatFixed
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.toErrorFieldState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Outrider extension's id. It pairs each Outrider TL tire pressure sensor as a Karoo sensor
 * that publishes one stream, the tire's gauge pressure (barometric pressure taken off) in the unit
 * set in the Outrider app, under [DataType.Field.SINGLE]. Outrider keeps the sensor connection,
 * calibration and alerts; these slots only show the value.
 */
const val OUTRIDER_EXTENSION = "Outrider"

/** A tire with an Outrider sensor, and the stream its pressure comes on. */
enum class TirePosition(typeId: String, val label: String, val previewPsi: List<Double>) {
    FRONT("outrider-front", "Front Tire", listOf(68.4, 68.1, 67.9)),
    REAR("outrider-rear", "Rear Tire", listOf(72.6, 72.3, 72.0));

    val streamId: String = DataType.dataTypeId(OUTRIDER_EXTENSION, typeId)
}

object TirePressure {
    private val ICON = R.drawable.ic_tire

    fun liveStates(
        karooSystem: KarooSystemService,
        position: TirePosition,
        slot: HUDSlotConfig,
    ): Flow<FieldState> =
        karooSystem.streamDataFlow(position.streamId).map { toFieldState(it, position, slot) }

    /**
     * [state] as a field. A sensor that is not paired, or Outrider not installed, reads "No
     * sensor", so a Double or HUD drops the slot as it does a missing heart rate strap.
     */
    fun toFieldState(state: StreamState, position: TirePosition, slot: HUDSlotConfig): FieldState {
        val label = position.label
        val raw = (state as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.SINGLE)
        return when {
            state !is StreamState.Streaming ->
                state.toErrorFieldState(label, ICON, FieldState.noSensor(label, ICON))
                    ?: FieldState.notAvailable(label, ICON)
            raw == null -> FieldState.notAvailable(label, ICON)
            else -> valueState(raw, position, slot)
        }
    }

    fun previewStates(position: TirePosition, slot: HUDSlotConfig): List<FieldState> =
        position.previewPsi.map { psi -> valueState(psi * slot.tireUnit.fromPsi, position, slot) }

    private fun valueState(value: Double, position: TirePosition, slot: HUDSlotConfig) =
        FieldState(
            formatFixed(value, slot.tireUnit.decimals),
            label = position.label,
            color = tireColor(value, slot.tireTarget, slot.tireBandPercent),
            iconRes = ICON,
            colorMode = slot.colorMode,
        )

    /**
     * The band [value] falls in around [target]: each [bandPercent] of the target further away,
     * above or below, is one step from green through yellow and orange to red. No target, or no
     * step, leaves the value uncolored.
     */
    fun tireColor(value: Double, target: Double, bandPercent: Double): FieldColor =
        if (target <= 0.0 || bandPercent <= 0.0) FieldColor.Default
        else FieldColor.Deviation((abs(value - target) / target * 100.0 / bandPercent).toInt())
}
