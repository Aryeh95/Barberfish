package com.jpweytjens.barberfish.datatype

import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.WindUnit
import com.jpweytjens.barberfish.datatype.shared.windUnitFor
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.ZoneColorMode
import io.hammerhead.karooext.models.UserProfile
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

/*
 * The Wind slot's Headwind layout, after karoo-headwind's Tailwind & ride speed field: a big arrow,
 * the ride speed, and the summary line `+9▼14`. The summary is the tail (+) or head (-) wind
 * component, whether the speed is above (▲), below (▼) or at (≈) the ride average, and the total
 * wind speed. The whole value takes Headwind's colours: green into a tailwind, red into a headwind.
 */

// Headwind's ramp: neutral in calm air, full green at a 10 km/h tailwind, full red at 15 headwind.
private const val FULL_TAILWIND_KMH = 10.0
private const val FULL_HEADWIND_KMH = 15.0
private const val MPH_TO_KMH = 1.609344

// Headwind's band for "at the average" (≈): 0.1 km/h either side, in m/s.
private const val AVERAGE_BAND_MS = 0.1 / 3.6

/** Red-green factor for [FieldColor.Threshold]: -1 full headwind, +1 full tailwind. */
internal fun headwindStyleFactor(headwindKmh: Double): Float =
    if (headwindKmh >= 0) -(headwindKmh / FULL_HEADWIND_KMH).coerceAtMost(1.0).toFloat()
    else (-headwindKmh / FULL_TAILWIND_KMH).coerceAtMost(1.0).toFloat()

/** ▲ above the average, ▼ below, ≈ at it, a space when either is unknown. */
internal fun headwindTrend(speedMs: Double?, averageMs: Double?): String =
    when {
        speedMs == null || averageMs == null -> " "
        speedMs > averageMs + AVERAGE_BAND_MS -> "▲"
        speedMs < averageMs - AVERAGE_BAND_MS -> "▼"
        else -> "≈"
    }

/** Headwind's summary: `+9▼14`, plus for a tailwind and minus for a headwind. */
internal fun headwindSummary(headwind: Int, trend: String, total: String): String {
    val sign =
        when {
            headwind < 0 -> "+"
            headwind > 0 -> "-"
            else -> ""
        }
    return "$sign${headwind.absoluteValue}$trend$total"
}

/**
 * One slot state in the Headwind layout. Both live: speed, summary and arrow, coloured by the
 * head/tailwind. Speed live without wind: the same layout with the speed alone, so the slot keeps
 * its look and header choice while Headwind is silent. Wind live without speed: the plain wind
 * state. Neither: the speed's text state.
 */
@Suppress("LongParameterList")
internal fun headwindStyleState(
    speed: FieldState,
    speedMs: Double?,
    averageMs: Double?,
    wind: FieldState,
    colorMode: ZoneColorMode,
    showHeader: Boolean,
    profile: UserProfile,
): FieldState {
    val speedLive = speed.color != FieldColor.StreamState
    val windLive = wind.color != FieldColor.StreamState
    return when {
        speedLive && windLive ->
            bothLiveState(speed, speedMs, averageMs, wind, colorMode, showHeader, profile)
        speedLive -> speedOnlyState(speed, wind, showHeader)
        windLive -> wind.copy(hideHeader = !showHeader)
        else -> speed
    }
}

@Suppress("LongParameterList")
private fun bothLiveState(
    speed: FieldState,
    speedMs: Double?,
    averageMs: Double?,
    wind: FieldState,
    colorMode: ZoneColorMode,
    showHeader: Boolean,
    profile: UserProfile,
): FieldState {
    val headwind = wind.primary.toIntOrNull() ?: 0
    val kmh =
        if (windUnitFor(profile) == WindUnit.MPH) headwind * MPH_TO_KMH else headwind.toDouble()
    return FieldState(
        primary = speed.primary,
        label = speed.label,
        color =
            if (colorMode == ZoneColorMode.NONE) FieldColor.Default
            else FieldColor.Threshold(headwindStyleFactor(kmh)),
        iconRes = speed.iconRes,
        secondaryIconRes = wind.iconRes,
        secondary =
            headwindSummary(
                headwind,
                headwindTrend(speedMs, averageMs),
                wind.windSpeedRow.orEmpty(),
            ),
        colorMode = colorMode,
        windArrowDeg = wind.windArrowDeg,
        headwindLayout = true,
        hideHeader = !showHeader,
    )
}

/** The Headwind layout with the speed alone and no wind summary, uncoloured. */
private fun speedOnlyState(speed: FieldState, wind: FieldState, showHeader: Boolean): FieldState =
    speed.copy(
        secondaryIconRes = wind.iconRes,
        secondary = "",
        headwindLayout = true,
        hideHeader = !showHeader,
    )

/** [speed] and [average] combined with these wind states in the Headwind layout. */
internal fun Flow<FieldState>.withHeadwindStates(
    speed: Flow<SpeedReading>,
    average: Flow<Double?>,
    slot: HUDSlotConfig,
    profile: UserProfile,
): Flow<FieldState> =
    combine(speed, average.onStart { emit(null) }, onStart { emit(FieldState.searching()) }) {
        s,
        avg,
        wind ->
        headwindStyleState(s.state, s.ms, avg, wind, slot.colorMode, slot.windShowHeader, profile)
    }

/** Preview frames in the Headwind layout; the trend compares each speed with the frames' mean. */
internal fun headwindPreview(
    speeds: List<FieldState>,
    winds: List<FieldState>,
    slot: HUDSlotConfig,
    profile: UserProfile,
): List<FieldState> {
    val values = speeds.map { it.primary.replace(',', '.').toDoubleOrNull() }
    val mean = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()
    return speeds.indices.zip(winds) { i, wind ->
        headwindStyleState(
            speeds[i],
            values[i],
            mean,
            wind,
            slot.colorMode,
            slot.windShowHeader,
            profile,
        )
    }
}
