package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.PreviewRide
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.datatype.shared.powerZone
import com.jpweytjens.barberfish.datatype.shared.zoneFieldColor
import com.jpweytjens.barberfish.extension.WkgFieldConfig
import com.jpweytjens.barberfish.extension.WkgSource
import com.jpweytjens.barberfish.extension.ZoneConfig
import com.jpweytjens.barberfish.extension.lapNumberFrom
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.streamUserProfile
import com.jpweytjens.barberfish.extension.streamWkgFieldConfig
import com.jpweytjens.barberfish.extension.streamZoneConfig
import com.jpweytjens.barberfish.extension.toErrorFieldState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Power-to-weight ratio in W/kg across instantaneous, smoothed, average, NP, lap, or last-lap
 * power, colored by the rider's Karoo power zones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WkgField(private val karooSystem: KarooSystemService) :
    BarberfishDataType("barberfish", "wkg") {

    override fun liveFlow(context: Context): Flow<FieldState> =
        combine(
                context.streamWkgFieldConfig(),
                karooSystem.streamUserProfile(),
                context.streamZoneConfig(),
            ) { cfg, profile, zones ->
                Triple(cfg, profile, zones)
            }
            .flatMapLatest { (cfg, profile, zones) -> liveStates(karooSystem, cfg, profile, zones) }

    override fun previewFlow(context: Context): Flow<FieldState> =
        combine(
                context.streamWkgFieldConfig(),
                karooSystem.streamUserProfile(),
                context.streamZoneConfig(),
            ) { cfg, profile, zones ->
                Triple(cfg, profile, zones)
            }
            .flatMapLatest { (cfg, profile, zones) ->
                cyclePreview(previewStates(cfg, profile, zones))
            }

    companion object {
        private val ICON = R.drawable.ic_col_power

        fun liveStates(
            karooSystem: KarooSystemService,
            cfg: WkgFieldConfig,
            profile: UserProfile,
            zones: ZoneConfig,
        ): Flow<FieldState> =
            if (cfg.source.isLastLap) {
                combine(
                    karooSystem.streamDataFlow(cfg.source.typeId),
                    karooSystem.streamDataFlow(DataType.Type.LAP_NUMBER),
                ) { state, lapState ->
                    toFieldState(state, cfg, profile, zones, lapNumberFrom(lapState))
                }
            } else {
                karooSystem.streamDataFlow(cfg.source.typeId).map { state ->
                    toFieldState(state, cfg, profile, zones)
                }
            }

        fun toFieldState(
            state: StreamState,
            cfg: WkgFieldConfig,
            profile: UserProfile,
            zones: ZoneConfig,
            lapNumber: Int = 2,
        ): FieldState {
            val label = cfg.source.headerLabel
            val icon = sourceIcon(cfg.source)
            val err = state.toErrorFieldState(label, icon, FieldState.noSensor(label, icon))
            val watts =
                (state as? StreamState.Streaming)?.dataPoint?.values?.get(cfg.source.fieldId)
            return when {
                cfg.source.isLastLap && lapNumber <= 1 -> FieldState.noLapsYet(label, icon)
                cfg.source == WkgSource.NP && state is StreamState.Idle ->
                    FieldState.needs30sPower(label, icon)
                err != null -> err
                watts == null || profile.weight <= 0f -> FieldState.notAvailable(label, icon)
                else -> wattsToFieldState(watts, cfg, profile, zones)
            }
        }

        private fun wattsToFieldState(
            watts: Double,
            cfg: WkgFieldConfig,
            profile: UserProfile,
            zones: ZoneConfig,
        ): FieldState {
            val wkg = watts / profile.weight.toDouble().coerceAtLeast(1.0)
            val zone = powerZone(watts, profile.powerZones)
            val color = zoneFieldColor(zone, cfg.colorMode, profile, zones, isHr = false)
            return FieldState(
                primary = "%.2f".format(Locale.ROOT, wkg),
                label = cfg.source.headerLabel,
                color = color,
                iconRes = sourceIcon(cfg.source),
                colorMode = cfg.colorMode,
            )
        }

        private fun sourceIcon(source: WkgSource): Int =
            when (source) {
                WkgSource.AVG -> R.drawable.ic_avg_power
                WkgSource.LAP -> R.drawable.ic_lap_power
                WkgSource.LAST_LAP -> R.drawable.ic_last_lap_power
                else -> ICON
            }

        fun previewStates(
            cfg: WkgFieldConfig,
            profile: UserProfile,
            zones: ZoneConfig,
        ): List<FieldState> {
            val samples =
                when (cfg.source) {
                    WkgSource.AVG -> PreviewRide.avgPowerW
                    WkgSource.NP -> PreviewRide.npW
                    WkgSource.LAP -> PreviewRide.lapPowerW
                    WkgSource.LAST_LAP -> PreviewRide.lastLapPowerW
                    else -> PreviewRide.powerW
                }
            val safeProfile = if (profile.weight > 0f) profile else profile.copy(weight = 70f)
            return samples.map { watts ->
                wattsToFieldState(watts.toDouble(), cfg, safeProfile, zones)
            }
        }
    }
}
