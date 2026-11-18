package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.ConvertType
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.GradeReading
import com.jpweytjens.barberfish.datatype.shared.PreviewRide
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.datatype.shared.formatFixed
import com.jpweytjens.barberfish.extension.GradePalette
import com.jpweytjens.barberfish.extension.VamFieldConfig
import com.jpweytjens.barberfish.extension.ZoneColorMode
import com.jpweytjens.barberfish.extension.ZoneConfig
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.streamUserProfile
import com.jpweytjens.barberfish.extension.streamVamFieldConfig
import com.jpweytjens.barberfish.extension.streamZoneConfig
import com.jpweytjens.barberfish.extension.toErrorFieldState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Vertical ascent speed (VAM) in m/h (Metric) or ft/h (Imperial), across 30s smoothed, instant, lap
 * or ride average windows, colored by the road gradient palette when enabled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VamField(private val karooSystem: KarooSystemService) :
    BarberfishDataType("barberfish", "vam") {

    override fun liveFlow(context: Context): Flow<FieldState> =
        combine(
                context.streamVamFieldConfig(),
                karooSystem.streamUserProfile(),
                context.streamZoneConfig(),
            ) { cfg, profile, zones ->
                Triple(cfg, profile, zones)
            }
            .flatMapLatest { (cfg, profile, zones) ->
                liveStates(context, karooSystem, cfg, profile, zones)
            }

    override fun previewFlow(context: Context): Flow<FieldState> =
        combine(
                context.streamVamFieldConfig(),
                karooSystem.streamUserProfile(),
                context.streamZoneConfig(),
            ) { cfg, profile, zones ->
                Triple(cfg, profile, zones)
            }
            .flatMapLatest { (cfg, profile, zones) ->
                cyclePreview(previewStates(cfg, profile, zones))
            }

    companion object {
        private val ICON = R.drawable.ic_arrow_outward
        private val PREVIEW_VAM_MH = listOf(180.0, 420.0, 760.0, 1080.0, 1460.0, 120.0, 0.0)

        fun liveStates(
            context: Context,
            karooSystem: KarooSystemService,
            cfg: VamFieldConfig,
            profile: UserProfile,
            zones: ZoneConfig,
        ): Flow<FieldState> {
            val gradeFlow: Flow<GradeReading> =
                if (cfg.colorMode == ZoneColorMode.NONE) flowOf(GradeReading.Unavailable)
                else GradeField.gradeFlow(context, karooSystem)
            return combine(karooSystem.streamDataFlow(cfg.window.typeId), gradeFlow) { state, grade
                ->
                toFieldState(state, grade, cfg, profile, zones.gradePalette)
            }
        }

        fun toFieldState(
            state: StreamState,
            grade: GradeReading,
            cfg: VamFieldConfig,
            profile: UserProfile,
            palette: GradePalette,
        ): FieldState {
            val label = cfg.window.headerLabel
            val err = if (state is StreamState.Idle) null else state.toErrorFieldState(label, ICON)
            val rawMh =
                (state as? StreamState.Streaming)
                    ?.dataPoint
                    ?.values
                    ?.get(DataType.Field.VERTICAL_SPEED)
            return when {
                state is StreamState.Idle ->
                    FieldState(
                        primary = "0",
                        label = label,
                        color = FieldColor.Default,
                        iconRes = ICON,
                        colorMode = cfg.colorMode,
                        liveIcon = false,
                    )
                err != null -> err
                rawMh == null -> FieldState.notAvailable(label, ICON)
                else -> {
                    val converted = ConvertType.ELEVATION.apply(rawMh.coerceAtLeast(0.0), profile)
                    val gradePct = (grade as? GradeReading.Fresh)?.percent?.toDouble()
                    val color =
                        if (cfg.colorMode == ZoneColorMode.NONE || gradePct == null)
                            FieldColor.Default
                        else FieldColor.Grade(gradePct, palette)
                    FieldState(
                        primary = formatFixed(converted, 0),
                        label = label,
                        color = color,
                        iconRes = ICON,
                        colorMode = cfg.colorMode,
                    )
                }
            }
        }

        fun previewStates(
            cfg: VamFieldConfig,
            profile: UserProfile,
            zones: ZoneConfig,
        ): List<FieldState> =
            PREVIEW_VAM_MH.indices.map { i ->
                val converted = ConvertType.ELEVATION.apply(PREVIEW_VAM_MH[i], profile)
                val gradePct = PreviewRide.gradePercent[i]
                val color =
                    if (cfg.colorMode == ZoneColorMode.NONE) FieldColor.Default
                    else FieldColor.Grade(gradePct, zones.gradePalette)
                FieldState(
                    primary = formatFixed(converted, 0),
                    label = cfg.window.headerLabel,
                    color = color,
                    iconRes = ICON,
                    colorMode = cfg.colorMode,
                )
            }
    }
}
