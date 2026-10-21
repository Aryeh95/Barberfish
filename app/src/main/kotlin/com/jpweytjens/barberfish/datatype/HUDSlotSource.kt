package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.extension.AvgPowerFieldConfig
import com.jpweytjens.barberfish.extension.CadenceFieldConfig
import com.jpweytjens.barberfish.extension.CadenceStatFieldConfig
import com.jpweytjens.barberfish.extension.GradeFieldConfig
import com.jpweytjens.barberfish.extension.HRFieldConfig
import com.jpweytjens.barberfish.extension.HRMaxPercentFieldConfig
import com.jpweytjens.barberfish.extension.HRZoneFieldConfig
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import com.jpweytjens.barberfish.extension.LapPowerFieldConfig
import com.jpweytjens.barberfish.extension.MaxHRFieldConfig
import com.jpweytjens.barberfish.extension.MaxPowerFieldConfig
import com.jpweytjens.barberfish.extension.NPFieldConfig
import com.jpweytjens.barberfish.extension.PowerFieldConfig
import com.jpweytjens.barberfish.extension.PowerZoneFieldConfig
import com.jpweytjens.barberfish.extension.SpeedFieldConfig
import com.jpweytjens.barberfish.extension.TimeConfig
import com.jpweytjens.barberfish.extension.WindFieldConfig
import com.jpweytjens.barberfish.extension.ZoneConfig
import com.jpweytjens.barberfish.extension.lapNumberFrom
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.streamETAConfig
import com.jpweytjens.barberfish.extension.streamTimeConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Field states for one HUD slot config, live from the Karoo streams or cycled from the preview
 * ride. Every multi-slot field (the HUD and the Double fields) builds its slots here, so a slot
 * field shows the same value, label and coloring wherever it is placed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HUDSlotSource(private val karooSystem: KarooSystemService) {

    fun liveFlow(
        slot: HUDSlotConfig,
        zones: ZoneConfig,
        profile: UserProfile,
        context: Context,
    ): Flow<FieldState> =
        when (slot.field) {
            HUDSlotField.Speed ->
                karooSystem.streamDataFlow(slot.speedSmoothing.typeId).map {
                    SpeedField.toFieldState(it, profile, slot.speedSmoothing)
                }
            HUDSlotField.HR ->
                karooSystem.streamDataFlow(DataType.Type.HEART_RATE).map {
                    HRField.toFieldState(it, profile, zones, slot.colorMode)
                }
            HUDSlotField.Power ->
                karooSystem.streamDataFlow(slot.powerSmoothing.typeId).map {
                    PowerField.toFieldState(it, slot.powerSmoothing, profile, zones, slot.colorMode)
                }
            HUDSlotField.Cadence ->
                karooSystem.streamDataFlow(slot.cadenceSmoothing.typeId).map {
                    CadenceField.toFieldState(it, slot.cadenceSmoothing, slot.cadenceThreshold)
                }
            HUDSlotField.AvgCadence,
            HUDSlotField.LapAvgCadence,
            HUDSlotField.LastLapAvgCadence,
            HUDSlotField.MaxCadence ->
                CadenceStatField.liveStates(
                    karooSystem,
                    checkNotNull(slot.field.cadenceStatKind),
                    slot.cadenceThreshold,
                    slot.colorMode,
                )
            HUDSlotField.AvgPower ->
                karooSystem.streamDataFlow(DataType.Type.AVERAGE_POWER).map {
                    AvgPowerField.toFieldState(it, profile, zones, slot.colorMode)
                }
            HUDSlotField.NP ->
                karooSystem.streamDataFlow(DataType.Type.NORMALIZED_POWER).map {
                    NPField.toFieldState(it, profile, zones, slot.colorMode)
                }
            HUDSlotField.LapPower ->
                karooSystem.streamDataFlow(DataType.Type.POWER_LAP).map {
                    LapPowerField.toFieldState(
                        it,
                        profile,
                        zones,
                        slot.colorMode,
                        isLastLap = false,
                    )
                }
            HUDSlotField.LastLapPower ->
                combine(
                    karooSystem.streamDataFlow(DataType.Type.AVERAGE_POWER_LAST_LAP),
                    karooSystem.streamDataFlow(DataType.Type.LAP_NUMBER),
                ) { state, lapState ->
                    LapPowerField.toFieldState(
                        state,
                        profile,
                        zones,
                        slot.colorMode,
                        isLastLap = true,
                        lapNumber = lapNumberFrom(lapState),
                    )
                }
            HUDSlotField.PowerZone ->
                karooSystem.streamDataFlow(DataType.Type.POWER_ZONE).map {
                    PowerZoneField.toFieldState(
                        it,
                        profile,
                        zones,
                        slot.colorMode,
                        slot.zoneDisplayMode,
                    )
                }
            HUDSlotField.MaxPower ->
                karooSystem.streamDataFlow(DataType.Type.MAX_POWER).map {
                    MaxPowerField.toFieldState(it, profile, zones, slot.colorMode)
                }
            HUDSlotField.AvgHR ->
                karooSystem.streamDataFlow(DataType.Type.AVERAGE_HR).map {
                    AvgHRField.toFieldState(
                        it,
                        profile,
                        zones,
                        slot.colorMode,
                        "Avg HR",
                        R.drawable.ic_avg_hr,
                    )
                }
            HUDSlotField.LapAvgHR ->
                karooSystem.streamDataFlow(DataType.Type.AVERAGE_LAP_HR).map {
                    AvgHRField.toFieldState(
                        it,
                        profile,
                        zones,
                        slot.colorMode,
                        "Lap Avg HR",
                        R.drawable.ic_lap,
                        R.drawable.ic_avg_hr,
                    )
                }
            HUDSlotField.LastLapAvgHR ->
                combine(
                    karooSystem.streamDataFlow(DataType.Type.AVERAGE_HR_LAST_LAP),
                    karooSystem.streamDataFlow(DataType.Type.LAP_NUMBER),
                ) { state, lapState ->
                    AvgHRField.toFieldState(
                        state,
                        profile,
                        zones,
                        slot.colorMode,
                        "LL Avg HR",
                        R.drawable.ic_last_lap,
                        R.drawable.ic_avg_hr,
                        isLastLap = true,
                        lapNumber = lapNumberFrom(lapState),
                    )
                }
            HUDSlotField.HRMaxPercent ->
                combine(
                    karooSystem.streamDataFlow(DataType.Type.PERCENT_MAX_HR),
                    karooSystem.streamDataFlow(DataType.Type.HEART_RATE),
                ) { percentState, hrState ->
                    HRMaxPercentField.toFieldState(
                        percentState,
                        hrState,
                        profile,
                        zones,
                        slot.colorMode,
                    )
                }
            HUDSlotField.MaxHR ->
                karooSystem.streamDataFlow(DataType.Type.MAX_HR).map {
                    MaxHRField.toFieldState(it, profile, zones, slot.colorMode)
                }
            HUDSlotField.HRZone ->
                karooSystem.streamDataFlow(DataType.Type.HR_ZONE).map {
                    HRZoneField.toFieldState(
                        it,
                        profile,
                        zones,
                        slot.colorMode,
                        slot.zoneDisplayMode,
                    )
                }
            HUDSlotField.Grade ->
                GradeField.gradeFlow(context, karooSystem).map {
                    GradeField.toGradeFieldState(
                        it,
                        GradeFieldConfig(
                            slot.colorMode,
                            slot.gradePrecision,
                            slot.gradeShowPercentSign,
                        ),
                        zones.gradePalette,
                    )
                }
            HUDSlotField.Wind ->
                WindField.liveStates(karooSystem, profile, WindFieldConfig(slot.colorMode))
                    .withSpeed(slot, karooSystem, profile)
            HUDSlotField.Distance -> ValueField.liveStates(karooSystem, ValueKind.DISTANCE, profile)
            HUDSlotField.DistanceRemaining ->
                ValueField.liveStates(karooSystem, ValueKind.DISTANCE_REMAINING, profile)
            HUDSlotField.ElevationRemaining ->
                ValueField.liveStates(karooSystem, ValueKind.ELEVATION_REMAINING, profile)
            HUDSlotField.DescentRemaining ->
                ValueField.liveStates(karooSystem, ValueKind.DESCENT_REMAINING, profile)
            HUDSlotField.Ascent -> ValueField.liveStates(karooSystem, ValueKind.ASCENT, profile)
            HUDSlotField.Descent -> ValueField.liveStates(karooSystem, ValueKind.DESCENT, profile)
            HUDSlotField.Elevation ->
                ValueField.liveStates(karooSystem, ValueKind.ELEVATION, profile)
            HUDSlotField.FrontTire -> TirePressure.liveStates(karooSystem, TirePosition.FRONT, slot)
            HUDSlotField.RearTire -> TirePressure.liveStates(karooSystem, TirePosition.REAR, slot)
            is HUDSlotField.AvgSpeed ->
                AvgSpeedField.streamFlow(
                    karooSystem,
                    slot.avgSpeedConfig,
                    profile,
                    slot.field.includePaused,
                )
            is HUDSlotField.Time ->
                if (slot.field.kind == TimeKind.LAST_LAP) {
                    combine(
                        TimeField.secondsFlow(karooSystem, slot.field.kind),
                        karooSystem.streamDataFlow(DataType.Type.LAP_NUMBER).map {
                            lapNumberFrom(it)
                        },
                        context.streamTimeConfig(),
                        TimeField.liveIconFlow(karooSystem, slot.field.kind),
                    ) { seconds, lapNumber, cfg, liveIcon ->
                        if (lapNumber <= 1)
                            FieldState.noLapsYet(slot.field.kind.label, slot.field.kind.iconRes)
                        else TimeField.toFieldState(seconds, slot.field.kind, cfg.format, liveIcon)
                    }
                } else {
                    combine(
                        TimeField.secondsFlow(karooSystem, slot.field.kind),
                        context.streamTimeConfig(),
                        TimeField.liveIconFlow(karooSystem, slot.field.kind),
                    ) { seconds, cfg, liveIcon ->
                        TimeField.toFieldState(seconds, slot.field.kind, cfg.format, liveIcon)
                    }
                }
            is HUDSlotField.ETA ->
                combine(context.streamETAConfig(), context.streamTimeConfig()) { etaCfg, timeCfg ->
                        etaCfg to timeCfg
                    }
                    .flatMapLatest { (etaCfg, timeCfg) ->
                        ETAField.streamFlow(karooSystem, slot.field.kind, etaCfg, timeCfg.format)
                    }
        }.catch { e ->
            // Isolate a slot failure: degrade it to Not available rather than let the
            // exception cancel the whole HUD combine and freeze every slot for the ride.
            Timber.e(e, "HUD slot ${slot.field} threw; showing Not available")
            emit(FieldState.notAvailable())
        }

    companion object {
        /** The preview ride's states for [slotCfg], one per moment. */
        fun previewStates(
            slotCfg: HUDSlotConfig,
            timeCfg: TimeConfig,
            profile: UserProfile,
            zones: ZoneConfig,
        ): List<FieldState> =
            when (val field = slotCfg.field) {
                HUDSlotField.Power ->
                    PowerField.previewStates(
                        PowerFieldConfig(slotCfg.powerSmoothing, slotCfg.colorMode),
                        profile,
                        zones,
                    )
                HUDSlotField.HR ->
                    HRField.previewStates(HRFieldConfig(slotCfg.colorMode), profile, zones)
                HUDSlotField.Speed ->
                    SpeedField.previewStates(SpeedFieldConfig(slotCfg.speedSmoothing), profile)
                HUDSlotField.Cadence ->
                    CadenceField.previewStates(
                        CadenceFieldConfig(slotCfg.cadenceSmoothing, slotCfg.cadenceThreshold)
                    )
                HUDSlotField.AvgCadence,
                HUDSlotField.LapAvgCadence,
                HUDSlotField.LastLapAvgCadence,
                HUDSlotField.MaxCadence ->
                    CadenceStatField.previewStates(
                        checkNotNull(field.cadenceStatKind),
                        CadenceStatFieldConfig(slotCfg.cadenceThreshold, slotCfg.colorMode),
                    )
                is HUDSlotField.AvgSpeed ->
                    AvgSpeedField.previewStates(
                        slotCfg.avgSpeedConfig,
                        profile,
                        field.includePaused,
                    )
                HUDSlotField.AvgPower ->
                    AvgPowerField.previewStates(
                        AvgPowerFieldConfig(slotCfg.colorMode),
                        profile,
                        zones,
                    )
                HUDSlotField.NP ->
                    NPField.previewStates(NPFieldConfig(slotCfg.colorMode), profile, zones)
                HUDSlotField.LapPower ->
                    LapPowerField.previewStates(
                        LapPowerFieldConfig(slotCfg.colorMode),
                        profile,
                        zones,
                        isLastLap = false,
                    )
                HUDSlotField.LastLapPower ->
                    LapPowerField.previewStates(
                        LapPowerFieldConfig(slotCfg.colorMode),
                        profile,
                        zones,
                        isLastLap = true,
                    )
                HUDSlotField.PowerZone ->
                    PowerZoneField.previewStates(
                        PowerZoneFieldConfig(slotCfg.colorMode, slotCfg.zoneDisplayMode),
                        profile,
                        zones,
                    )
                HUDSlotField.MaxPower ->
                    MaxPowerField.previewStates(
                        MaxPowerFieldConfig(slotCfg.colorMode),
                        profile,
                        zones,
                    )
                HUDSlotField.AvgHR ->
                    AvgHRField.previewStates(HRFieldConfig(slotCfg.colorMode), profile, zones)
                HUDSlotField.LapAvgHR ->
                    LapAvgHRField.previewStates(
                        HRFieldConfig(slotCfg.colorMode),
                        profile,
                        zones,
                    )
                HUDSlotField.LastLapAvgHR ->
                    LastLapAvgHRField.previewStates(
                        HRFieldConfig(slotCfg.colorMode),
                        profile,
                        zones,
                    )
                HUDSlotField.HRMaxPercent ->
                    HRMaxPercentField.previewStates(
                        HRMaxPercentFieldConfig(slotCfg.colorMode),
                        profile,
                        zones,
                    )
                HUDSlotField.MaxHR ->
                    MaxHRField.previewStates(
                        MaxHRFieldConfig(slotCfg.colorMode),
                        profile,
                        zones,
                    )
                HUDSlotField.HRZone ->
                    HRZoneField.previewStates(
                        HRZoneFieldConfig(slotCfg.colorMode, slotCfg.zoneDisplayMode),
                        profile,
                        zones,
                    )
                HUDSlotField.Grade ->
                    GradeField.previewStates(
                        GradeFieldConfig(
                            slotCfg.colorMode,
                            slotCfg.gradePrecision,
                            slotCfg.gradeShowPercentSign,
                        ),
                        zones,
                    )
                HUDSlotField.Wind ->
                    WindField.previewStates(WindFieldConfig(slotCfg.colorMode))
                        .withSpeedPreview(slotCfg, profile)
                HUDSlotField.Distance -> ValueField.previewStates(ValueKind.DISTANCE, profile)
                HUDSlotField.DistanceRemaining ->
                    ValueField.previewStates(ValueKind.DISTANCE_REMAINING, profile)
                HUDSlotField.ElevationRemaining ->
                    ValueField.previewStates(ValueKind.ELEVATION_REMAINING, profile)
                HUDSlotField.DescentRemaining ->
                    ValueField.previewStates(ValueKind.DESCENT_REMAINING, profile)
                HUDSlotField.Ascent -> ValueField.previewStates(ValueKind.ASCENT, profile)
                HUDSlotField.Descent -> ValueField.previewStates(ValueKind.DESCENT, profile)
                HUDSlotField.Elevation -> ValueField.previewStates(ValueKind.ELEVATION, profile)
                HUDSlotField.FrontTire -> TirePressure.previewStates(TirePosition.FRONT, slotCfg)
                HUDSlotField.RearTire -> TirePressure.previewStates(TirePosition.REAR, slotCfg)
                is HUDSlotField.Time -> TimeField.previewStates(timeCfg, field.kind)
                is HUDSlotField.ETA -> ETAField.previewStates(field.kind, timeCfg.format)
            }
    }
}
