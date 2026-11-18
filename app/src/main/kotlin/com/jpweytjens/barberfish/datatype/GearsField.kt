package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.extension.GearReading
import com.jpweytjens.barberfish.extension.GearsFieldConfig
import com.jpweytjens.barberfish.extension.ZoneColorMode
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.streamGearsFieldConfig
import com.jpweytjens.barberfish.extension.toErrorFieldState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart

/**
 * Electronic drivetrain gears as a standalone field and HUD/Double slot. Shows teeth ("50-10"),
 * gear numbers ("2-12") or gear ratio ("5.00"), colored at the smallest cog (red), largest cog
 * (blue) and extreme cross-chain combinations (amber). Drops out (`noSensor = true`) when no
 * electronic groupset is paired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GearsField(private val karooSystem: KarooSystemService) :
    BarberfishDataType("barberfish", "gears") {

    override fun liveFlow(context: Context): Flow<FieldState> =
        context.streamGearsFieldConfig().flatMapLatest { cfg -> liveStates(karooSystem, cfg) }

    override fun previewFlow(context: Context): Flow<FieldState> =
        context.streamGearsFieldConfig().flatMapLatest { cfg -> cyclePreview(previewStates(cfg)) }

    companion object {
        private const val LABEL = "Gears"
        private val ICON = R.drawable.ic_gears

        fun liveStates(
            karooSystem: KarooSystemService,
            cfg: GearsFieldConfig,
        ): Flow<FieldState> =
            combine(
                karooSystem.streamDataFlow(DataType.Type.SHIFTING_FRONT_GEAR).onStart {
                    emit(StreamState.Idle)
                },
                karooSystem.streamDataFlow(DataType.Type.SHIFTING_REAR_GEAR),
            ) { frontState, rearState ->
                toFieldState(frontState, rearState, cfg)
            }

        fun toFieldState(
            frontState: StreamState,
            rearState: StreamState,
            cfg: GearsFieldConfig,
        ): FieldState {
            if (rearState !is StreamState.Streaming) {
                return rearState.toErrorFieldState(
                    LABEL,
                    ICON,
                    FieldState.noSensor(LABEL, ICON),
                ) ?: FieldState.notAvailable(LABEL, ICON)
            }
            val frontValues = (frontState as? StreamState.Streaming)?.dataPoint?.values
            val rearValues = rearState.dataPoint.values
            val reading =
                GearReading(
                    front = frontValues?.get(DataType.Field.SHIFTING_FRONT_GEAR)?.toInt(),
                    frontCount = frontValues?.get(DataType.Field.SHIFTING_FRONT_GEAR_MAX)?.toInt(),
                    frontTeeth =
                        frontValues?.get(DataType.Field.SHIFTING_FRONT_GEAR_TEETH)?.toInt(),
                    rear = rearValues[DataType.Field.SHIFTING_REAR_GEAR]?.toInt(),
                    rearCount = rearValues[DataType.Field.SHIFTING_REAR_GEAR_MAX]?.toInt(),
                    rearTeeth = rearValues[DataType.Field.SHIFTING_REAR_GEAR_TEETH]?.toInt(),
                )
            return readingToFieldState(reading, cfg)
        }

        fun readingToFieldState(reading: GearReading, cfg: GearsFieldConfig): FieldState {
            val text =
                reading.compactLabel(cfg.format, withFront = cfg.showChainrings)
                    ?: return FieldState.notAvailable(LABEL, ICON)
            val color =
                if (cfg.colorMode == ZoneColorMode.NONE) FieldColor.Default
                else
                    reading.drivetrainAlert()?.let { FieldColor.Drivetrain(it) }
                        ?: FieldColor.Default
            return FieldState(
                primary = text,
                label = LABEL,
                color = color,
                iconRes = ICON,
                colorMode = cfg.colorMode,
            )
        }

        fun previewStates(cfg: GearsFieldConfig): List<FieldState> {
            val samples =
                listOf(
                    GearReading(2, 2, 50, 7, 12, 15),
                    GearReading(2, 2, 50, 9, 12, 13),
                    GearReading(2, 2, 50, 12, 12, 10), // smallest cog
                    GearReading(2, 2, 50, 2, 12, 28), // cross-chain
                    GearReading(1, 2, 37, 1, 12, 33), // largest cog
                    GearReading(2, 2, 50, 11, 12, 11),
                    GearReading(1, 2, 37, 5, 12, 19),
                )
            return samples.map { readingToFieldState(it, cfg) }
        }
    }
}
