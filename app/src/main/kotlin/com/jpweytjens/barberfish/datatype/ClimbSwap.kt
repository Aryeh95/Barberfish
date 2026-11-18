package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.datatype.shared.ClimbCacheHolder
import com.jpweytjens.barberfish.datatype.shared.GradeReading
import com.jpweytjens.barberfish.datatype.shared.gradeMapRouteKey
import com.jpweytjens.barberfish.datatype.shared.sharedClimbCache
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.streamNavigationState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan

/** Sustained gradient (%) at which an unrouted ride enters climb-swap mode. */
internal const val CLIMB_SWAP_ON_PCT = 4.0f

/** Gradient (%) below which an unrouted climb-swap exits, preventing false-flat flicker. */
internal const val CLIMB_SWAP_OFF_PCT = 2.5f

/**
 * True when the rider is on a detected Karoo Climber climb segment of a loaded route. Reads through
 * [climbCache] because Karoo OS drops the active climb from `route.climbs` once the rider enters
 * it.
 */
internal fun isOnRouteClimbSegment(
    navState: OnNavigationState.NavigationState,
    distanceToDestinationM: Double?,
    onRouteConfirmed: Boolean,
    climbCache: ClimbCacheHolder = sharedClimbCache,
    clearCacheWhenIdle: Boolean = true,
): Boolean {
    val route = navState as? OnNavigationState.NavigationState.NavigatingRoute
    if (route == null) {
        if (clearCacheWhenIdle) climbCache.resolve(null, null, false)
        return false
    }
    val climbs =
        climbCache.resolve(
            gradeMapRouteKey(route.routePolyline, route.reversed),
            route.climbs,
            onRouteConfirmed,
        )
    val progressM = distanceToDestinationM?.let { (route.routeDistance - it).coerceAtLeast(0.0) }
    return onRouteConfirmed &&
        progressM != null &&
        climbs.any { climb ->
            progressM >= climb.startDistance && progressM <= climb.startDistance + climb.length
        }
}

/**
 * Hysteresis reducer deciding whether a HUD/Double slot's climb override is active: active while
 * inside a route climb segment, or when smoothed road gradient reaches [CLIMB_SWAP_ON_PCT] until it
 * drops below [CLIMB_SWAP_OFF_PCT].
 */
internal fun climbSwapReducer(
    wasClimbing: Boolean,
    onRouteClimb: Boolean,
    grade: GradeReading,
): Boolean {
    val pct = (grade as? GradeReading.Fresh)?.percent
    return when {
        onRouteClimb -> true
        pct == null -> false
        wasClimbing -> pct >= CLIMB_SWAP_OFF_PCT
        else -> pct >= CLIMB_SWAP_ON_PCT
    }
}

/**
 * Emits whether the rider is currently climbing, combining Karoo Climber route segments with the
 * road-fitted grade smoother's hysteresis band.
 */
internal fun climbActiveFlow(
    context: Context,
    karooSystem: KarooSystemService,
): Flow<Boolean> {
    val navFlow =
        karooSystem
            .streamNavigationState()
            .map { it.state to true }
            .onStart { emit(OnNavigationState.NavigationState.Idle to false) }
    val distFlow =
        karooSystem
            .streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            .map { state ->
                val streaming = state as? StreamState.Streaming
                val dist = streaming?.dataPoint?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION)
                val onRouteConfirmed =
                    streaming?.dataPoint?.values?.get(DataType.Field.ON_ROUTE)?.let { it >= 0.5 }
                        ?: false
                dist to onRouteConfirmed
            }
            .onStart { emit(null to false) }
    return combine(navFlow, distFlow, GradeField.gradeFlow(context, karooSystem)) {
            (nav, realNav),
            (distM, onRouteConfirmed),
            grade ->
            isOnRouteClimbSegment(nav, distM, onRouteConfirmed, clearCacheWhenIdle = realNav) to
                grade
        }
        .scan(false) { climbing, (onRouteClimb, grade) ->
            climbSwapReducer(climbing, onRouteClimb, grade)
        }
        .distinctUntilChanged()
}
