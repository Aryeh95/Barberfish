package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.ConvertType
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.datatype.shared.formatFixed
import com.jpweytjens.barberfish.datatype.shared.formatSigned
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.streamUserProfile
import com.jpweytjens.barberfish.extension.toErrorFieldState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The thin numeric fields and slots. Each streams one native datatype, converts the raw value, and
 * emits a FieldState. Route-gated kinds report "Not available" off-route.
 */
enum class ValueKind(
    val typeId: String,
    val sourceType: String, // DataType.Type.*
    val fieldId: String, // DataType.Field.*
    val convert: ConvertType,
    val decimals: Int,
    val label: String,
    val iconRes: Int,
    val requiresRoute: Boolean,
    val previewRaw: List<Double>,
) {
    DISTANCE(
        "distance",
        DataType.Type.DISTANCE,
        DataType.Field.DISTANCE,
        ConvertType.DISTANCE,
        1,
        "Distance",
        R.drawable.ic_route,
        false,
        listOf(12_300.0, 47_200.0, 103_800.0),
    ),
    DISTANCE_REMAINING(
        "distance-remaining",
        DataType.Type.DISTANCE_TO_DESTINATION,
        DataType.Field.DISTANCE_TO_DESTINATION,
        ConvertType.DISTANCE,
        1,
        "Dist\nRemaining",
        R.drawable.ic_finish_flag,
        true,
        listOf(42_100.0, 23_400.0, 4_800.0),
    ),
    ELEVATION_REMAINING(
        "elevation-remaining",
        DataType.Type.ELEVATION_REMAINING,
        DataType.Field.ASCENT_REMAINING,
        ConvertType.ELEVATION,
        0,
        "Ascent\nRemaining",
        R.drawable.ic_arrow_outward,
        true,
        listOf(1240.0, 540.0, 80.0),
    ),
    DESCENT_REMAINING(
        "descent-remaining",
        DataType.Type.DESCENT_REMAINING,
        DataType.Field.DESCENT_REMAINING,
        ConvertType.ELEVATION,
        0,
        "Descent\nRemaining",
        R.drawable.ic_arrow_outward_down,
        true,
        listOf(1310.0, 610.0, 95.0),
    ),
    // The ride's climbing totals and current elevation: HUD and Double slots only, as the Karoo
    // has its own fields for them.
    ASCENT(
        "ascent",
        DataType.Type.ELEVATION_GAIN,
        DataType.Field.ELEVATION_GAIN,
        ConvertType.ELEVATION,
        0,
        "Ascent",
        R.drawable.ic_arrow_outward,
        false,
        listOf(320.0, 860.0, 1450.0),
    ),
    DESCENT(
        "descent",
        DataType.Type.ELEVATION_LOSS,
        DataType.Field.ELEVATION_LOSS,
        ConvertType.ELEVATION,
        0,
        "Descent",
        R.drawable.ic_arrow_outward_down,
        false,
        listOf(290.0, 810.0, 1390.0),
    ),
    ELEVATION(
        "elevation",
        DataType.Type.PRESSURE_ELEVATION_CORRECTION,
        DataType.Field.PRESSURE_ELEVATION,
        ConvertType.ELEVATION,
        0,
        "Elevation",
        R.drawable.ic_landscape,
        false,
        listOf(112.0, 348.0, 615.0),
    );

    // Elevation goes below sea level; every other kind is a distance or climb, never negative.
    fun format(value: Double): String =
        if (this == ELEVATION) formatSigned(value, decimals) else formatFixed(value, decimals)

    // Ride totals: they start at 0 and only grow, so while the Karoo sends nothing (before the
    // ride starts, or paused) they read 0 or their last value rather than "No data".
    val isRideTotal: Boolean
        get() = this == DISTANCE || this == ASCENT || this == DESCENT
}

@OptIn(ExperimentalCoroutinesApi::class)
class ValueField(
    private val karooSystem: KarooSystemService,
    private val kind: ValueKind,
) : BarberfishDataType("barberfish", kind.typeId) {

    override fun liveFlow(context: Context): Flow<FieldState> =
        karooSystem.streamUserProfile().flatMapLatest { profile ->
            liveStates(karooSystem, kind, profile)
        }

    override fun previewFlow(context: Context): Flow<FieldState> =
        karooSystem.streamUserProfile().flatMapLatest { profile ->
            cyclePreview(previewStates(kind, profile))
        }

    companion object {
        /**
         * Live states for [kind], shared by the standalone field and its HUD and Double slots. A
         * ride total remembers its last value to show while its stream is idle.
         */
        fun liveStates(
            karooSystem: KarooSystemService,
            kind: ValueKind,
            profile: UserProfile,
        ): Flow<FieldState> {
            val states = karooSystem.streamDataFlow(kind.sourceType)
            if (!kind.isRideTotal) return states.map { toFieldState(it, kind, profile) }
            return flow {
                var lastRaw: Double? = null
                states.collect { state ->
                    (state as? StreamState.Streaming)?.dataPoint?.values?.get(kind.fieldId)?.let {
                        lastRaw = it
                    }
                    emit(toFieldState(state, kind, profile, lastRaw))
                }
            }
        }

        /**
         * [state] as a field. A ride total whose stream is idle shows [heldRaw], its last value, or
         * 0 before it has one, with the icon in the idle colour as the stream is not live.
         */
        fun toFieldState(
            state: StreamState,
            kind: ValueKind,
            profile: UserProfile,
            heldRaw: Double? = null,
        ): FieldState {
            if (kind.isRideTotal && state is StreamState.Idle) {
                return FieldState(
                    primary = kind.format(kind.convert.apply(heldRaw ?: 0.0, profile)),
                    label = kind.label,
                    color = FieldColor.Default,
                    iconRes = kind.iconRes,
                    liveIcon = false,
                )
            }
            val notAvailable =
                if (kind.requiresRoute) FieldState.noRoute(kind.label, kind.iconRes)
                else FieldState.notAvailable(kind.label, kind.iconRes)
            state.toErrorFieldState(kind.label, kind.iconRes, notAvailable)?.let {
                return it
            }
            val streaming = state as? StreamState.Streaming ?: return notAvailable
            if (kind.requiresRoute) {
                val onRoute = streaming.dataPoint.values[DataType.Field.ON_ROUTE]
                if (onRoute != null && onRoute == 0.0) {
                    return FieldState.offRoute(kind.label, kind.iconRes)
                }
            }
            val raw = streaming.dataPoint.values[kind.fieldId] ?: return notAvailable
            val converted = kind.convert.apply(raw, profile)
            return FieldState(
                primary = kind.format(converted),
                label = kind.label,
                color = FieldColor.Default,
                iconRes = kind.iconRes,
            )
        }

        fun previewStates(kind: ValueKind, profile: UserProfile): List<FieldState> =
            kind.previewRaw.map { raw ->
                FieldState(
                    primary = kind.format(kind.convert.apply(raw, profile)),
                    label = kind.label,
                    color = FieldColor.Default,
                    iconRes = kind.iconRes,
                )
            }
    }
}
