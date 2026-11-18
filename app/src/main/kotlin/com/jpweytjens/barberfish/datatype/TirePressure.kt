package com.jpweytjens.barberfish.datatype

import android.os.SystemClock
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.formatFixed
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.TirePressureUnit
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.toErrorFieldState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * The Outrider extension's id. It pairs each Outrider TL tire pressure sensor as a Karoo sensor
 * that publishes one stream, the tire's gauge pressure (barometric pressure taken off) in the unit
 * set in the Outrider app, under [DataType.Field.SINGLE]. Outrider keeps the sensor connection,
 * calibration and alerts; these slots show the value and Barberfish+'s target coloring.
 */
const val OUTRIDER_EXTENSION = "Outrider"

/** A tire with an Outrider sensor, and the stream its pressure comes on. */
enum class TirePosition(typeId: String, val label: String, val previewPsi: List<Double>) {
    FRONT("outrider-front", "Front Tire", listOf(68.4, 68.1, 67.9)),
    REAR("outrider-rear", "Rear Tire", listOf(72.6, 72.3, 72.0));

    val streamId: String = DataType.dataTypeId(OUTRIDER_EXTENSION, typeId)
}

object TirePressure {
    internal val ICON = R.drawable.ic_tire

    /** How long a sensor's last value is held, grey, from its last live reading. */
    internal const val HOLD_MS = 10 * 60_000L

    /**
     * Live states for [position]. While the sensor reconnects (Searching or Idle) its last value
     * stays on screen in grey until [HOLD_MS] has elapsed since the last live reading, so repeated
     * Searching/Idle flips never extend a stale reading past [HOLD_MS].
     */
    fun liveStates(
        karooSystem: KarooSystemService,
        position: TirePosition,
        slot: HUDSlotConfig,
    ): Flow<FieldState> =
        holdTireStates(
            karooSystem.streamDataFlow(position.streamId),
            position,
            slot,
            SystemClock::elapsedRealtime,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun holdTireStates(
        states: Flow<StreamState>,
        position: TirePosition,
        slot: HUDSlotConfig,
        clockMs: () -> Long,
    ): Flow<FieldState> {
        var lastRaw: Double? = null
        var lastSeenMs = 0L
        return states.transformLatest { state ->
            val raw =
                (state as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.SINGLE)
            if (raw != null) {
                lastRaw = raw
                lastSeenMs = clockMs()
            }
            val held = lastRaw
            if (held != null && (state is StreamState.Searching || state is StreamState.Idle)) {
                val remainingMs = HOLD_MS - (clockMs() - lastSeenMs)
                if (remainingMs > 0L) {
                    emit(heldState(held, position, slot))
                    delay(remainingMs)
                }
            }
            emit(toFieldState(state, position, slot))
        }
    }

    /** A sensor's last value while it reconnects: the value, in grey. */
    fun heldState(value: Double, position: TirePosition, slot: HUDSlotConfig): FieldState =
        valueState(value, position, slot).copy(color = FieldColor.Muted, liveIcon = false)

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

    internal fun valueState(value: Double, position: TirePosition, slot: HUDSlotConfig) =
        FieldState(
            formatFixed(value, slot.tireUnit.decimals),
            label = position.label,
            color = tireColor(value, slot.tireTarget, slot.tireBandPercent),
            iconRes = ICON,
            colorMode = slot.colorMode,
        )

    /**
     * Converts a configured target pressure when the rider switches [TirePressureUnit]s, preserving
     * an unset `0.0` target.
     */
    fun convertTarget(target: Double, from: TirePressureUnit, to: TirePressureUnit): Double {
        if (target <= 0.0 || from == to) return target
        val converted = target / from.fromPsi * to.fromPsi
        val factor = 10.0.pow(to.decimals)
        return ((converted * factor).roundToLong() / factor).coerceAtLeast(1.0 / factor)
    }

    /**
     * The band [value] falls in around [target]: each [bandPercent] of the target further away,
     * above or below, is one step from green through yellow and orange to red. No target, or no
     * step, leaves the value uncolored.
     */
    fun tireColor(value: Double, target: Double, bandPercent: Double): FieldColor =
        if (target <= 0.0 || bandPercent <= 0.0) FieldColor.Default
        else FieldColor.Deviation((abs(value - target) / target * 100.0 / bandPercent).toInt())
}
