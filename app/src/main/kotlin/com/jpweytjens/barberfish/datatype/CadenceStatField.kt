package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.PreviewRide
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.extension.CadenceStatFieldConfig
import com.jpweytjens.barberfish.extension.CadenceThresholdConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import com.jpweytjens.barberfish.extension.ZoneColorMode
import com.jpweytjens.barberfish.extension.lapNumberFrom
import com.jpweytjens.barberfish.extension.streamCadenceStatFieldConfig
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.toErrorFieldState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * The ride and lap cadence aggregates, the cadence counterparts of Avg Power, Lap Power, Last Lap
 * Power and Max Power. Lap and last-lap averages stream their own datatypes but report the value
 * under [DataType.Field.AVERAGE_CADENCE], like the power and HR lap types do under their average
 * field. Cadence has no Karoo zones, so each takes the Cadence field's threshold coloring.
 */
enum class CadenceStatKind(
    val typeId: String,
    val sourceType: String, // DataType.Type.*
    val fieldId: String, // DataType.Field.*
    val label: String,
    val iconRes: Int,
    val secondaryIconRes: Int?,
) {
    AVG(
        "avg-cadence",
        DataType.Type.AVERAGE_CADENCE,
        DataType.Field.AVERAGE_CADENCE,
        "Avg Cadence",
        R.drawable.ic_avg_cadence,
        null,
    ),
    LAP_AVG(
        "lap-avg-cadence",
        DataType.Type.CADENCE_LAP,
        DataType.Field.AVERAGE_CADENCE,
        "Lap Avg Cadence",
        R.drawable.ic_lap,
        R.drawable.ic_avg_cadence,
    ),
    LAST_LAP_AVG(
        "last-lap-avg-cadence",
        DataType.Type.AVERAGE_CADENCE_LAST_LAP,
        DataType.Field.AVERAGE_CADENCE,
        "LL Avg Cadence",
        R.drawable.ic_last_lap,
        R.drawable.ic_avg_cadence,
    ),
    MAX(
        "max-cadence",
        DataType.Type.MAX_CADENCE,
        DataType.Field.MAX_CADENCE,
        "Max Cadence",
        R.drawable.ic_cadence,
        null,
    );

    // DataStore key of the standalone field's config.
    val configKey: String
        get() = typeId.replace('-', '_') + "_field_config"

    // Last lap waits for a finished lap, like Last Lap Power.
    val isLastLap: Boolean
        get() = this == LAST_LAP_AVG

    val previewRpm: List<Int>
        get() =
            when (this) {
                AVG -> PreviewRide.avgCadenceRpm
                LAP_AVG -> PreviewRide.lapAvgCadenceRpm
                LAST_LAP_AVG -> PreviewRide.lastLapAvgCadenceRpm
                MAX -> PreviewRide.maxCadenceRpm
            }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CadenceStatField(
    private val karooSystem: KarooSystemService,
    private val kind: CadenceStatKind,
) : BarberfishDataType("barberfish", kind.typeId) {

    override fun liveFlow(context: Context): Flow<FieldState> =
        context.streamCadenceStatFieldConfig(kind).flatMapLatest { cfg ->
            liveStates(karooSystem, kind, cfg.threshold, cfg.colorMode)
        }

    override fun previewFlow(context: Context): Flow<FieldState> =
        context.streamCadenceStatFieldConfig(kind).flatMapLatest { cfg ->
            cyclePreview(previewStates(kind, cfg))
        }

    companion object {
        /** Live states for [kind], shared by the standalone field and its HUD slot. */
        fun liveStates(
            karooSystem: KarooSystemService,
            kind: CadenceStatKind,
            threshold: CadenceThresholdConfig,
            colorMode: ZoneColorMode,
        ): Flow<FieldState> =
            if (kind.isLastLap) {
                combine(
                    karooSystem.streamDataFlow(kind.sourceType),
                    karooSystem.streamDataFlow(DataType.Type.LAP_NUMBER),
                ) { state, lapState ->
                    toFieldState(state, kind, threshold, colorMode, lapNumberFrom(lapState))
                }
            } else {
                karooSystem.streamDataFlow(kind.sourceType).map {
                    toFieldState(it, kind, threshold, colorMode)
                }
            }

        fun toFieldState(
            state: StreamState,
            kind: CadenceStatKind,
            threshold: CadenceThresholdConfig,
            colorMode: ZoneColorMode = ZoneColorMode.TEXT,
            lapNumber: Int = 0,
        ): FieldState {
            val label = kind.label
            val iconRes = kind.iconRes
            val raw = (state as? StreamState.Streaming)?.dataPoint?.values?.get(kind.fieldId)
            return when {
                kind.isLastLap && lapNumber <= 1 -> FieldState.noLapsYet(label, iconRes)
                state !is StreamState.Streaming ->
                    state.toErrorFieldState(label, iconRes, FieldState.noSensor(label, iconRes))
                        ?: FieldState.notAvailable(label, iconRes)
                // Max reads 0 until the first pedal stroke, like Max Power.
                raw == null || (kind == CadenceStatKind.MAX && raw <= 0.0) ->
                    FieldState.notAvailable(label, iconRes)
                else ->
                    FieldState(
                        raw.toInt().toString(),
                        label = label,
                        color = cadenceFieldColor(raw, threshold),
                        iconRes = iconRes,
                        secondaryIconRes = kind.secondaryIconRes,
                        colorMode = colorMode,
                    )
            }
        }

        fun previewStates(kind: CadenceStatKind, cfg: CadenceStatFieldConfig): List<FieldState> =
            kind.previewRpm.map { rpm ->
                FieldState(
                    rpm.toString(),
                    label = kind.label,
                    color = cadenceFieldColor(rpm.toDouble(), cfg.threshold),
                    iconRes = kind.iconRes,
                    secondaryIconRes = kind.secondaryIconRes,
                    colorMode = cfg.colorMode,
                )
            }
    }
}

/** The cadence aggregate a HUD slot field shows, or null for every other slot field. */
val HUDSlotField.cadenceStatKind: CadenceStatKind?
    get() =
        when (this) {
            HUDSlotField.AvgCadence -> CadenceStatKind.AVG
            HUDSlotField.LapAvgCadence -> CadenceStatKind.LAP_AVG
            HUDSlotField.LastLapAvgCadence -> CadenceStatKind.LAST_LAP_AVG
            HUDSlotField.MaxCadence -> CadenceStatKind.MAX
            else -> null
        }
