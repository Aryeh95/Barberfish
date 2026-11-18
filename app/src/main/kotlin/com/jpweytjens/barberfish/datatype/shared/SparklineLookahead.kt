package com.jpweytjens.barberfish.datatype.shared

import com.jpweytjens.barberfish.extension.SparklineConfig
import io.hammerhead.karooext.models.UserProfile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal val DEFAULT_LOOKAHEAD_SCALES_M: List<Int> = listOf(5_000, 10_000, 20_000)

private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_KM = 1000.0
private const val SCALE_MATCH_TOLERANCE_M = 50
private const val MAX_CUSTOM_SCALES = 8
private const val MIN_LOOKAHEAD_UNITS = 0.2
private const val MAX_LOOKAHEAD_UNITS = 200.0
private val SCALE_SPLIT_REGEX = Regex("[,;\\s/]+")

internal fun metersPerDistanceUnit(profile: UserProfile): Double =
    if (profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL) {
        METERS_PER_MILE
    } else {
        METERS_PER_KM
    }

internal fun SparklineConfig.effectiveScalesM(): List<Int> = lookaheadScalesM.ifEmpty {
    DEFAULT_LOOKAHEAD_SCALES_M
}

internal fun SparklineConfig.effectiveLookaheadMInt(): Int =
    lookaheadMeters ?: (lookaheadKm * 1_000)

internal val SparklineConfig.effectiveLookaheadM: Float
    get() = effectiveLookaheadMInt().toFloat()

internal fun SparklineConfig.withLookaheadMeters(meters: Int): SparklineConfig =
    copy(lookaheadMeters = meters, lookaheadKm = (meters / 1_000).coerceAtLeast(1))

/** Advances to the next configured distance scale in [effectiveScalesM], wrapping to the start. */
internal fun SparklineConfig.withNextLookahead(): SparklineConfig {
    val scales = effectiveScalesM()
    val current = effectiveLookaheadMInt()
    val idx = scales.indexOfFirst { abs(it - current) <= SCALE_MATCH_TOLERANCE_M }
    val next =
        if (idx >= 0) scales[(idx + 1) % scales.size]
        else scales.firstOrNull { it > current } ?: scales.first()
    return withLookaheadMeters(next)
}

/**
 * Replaces the custom tap-cycle distance scales. Empty resets to [DEFAULT_LOOKAHEAD_SCALES_M];
 * snaps the active lookahead to the first stop if it is not among the new stops.
 */
internal fun SparklineConfig.withCustomScalesM(scalesM: List<Int>): SparklineConfig {
    val effective = scalesM.ifEmpty { DEFAULT_LOOKAHEAD_SCALES_M }
    val current = effectiveLookaheadMInt()
    val matched =
        effective.firstOrNull { abs(it - current) <= SCALE_MATCH_TOLERANCE_M } ?: effective.first()
    return copy(
        lookaheadScalesM = scalesM,
        lookaheadMeters = matched,
        lookaheadKm = (matched / 1_000).coerceAtLeast(1),
    )
}

/**
 * Parses a rider-entered list such as `"1, 3, 5, 10"` or `"1, 2, 5, 8"` in [profile]'s distance
 * unit (`km` or `mi`) into ordered meter stops, deduplicating near-identical entries.
 */
internal fun parseLookaheadScalesInput(raw: String, profile: UserProfile): List<Int> {
    val factor = metersPerDistanceUnit(profile)
    val result = mutableListOf<Int>()
    for (token in raw.trim().split(SCALE_SPLIT_REGEX)) {
        val cleaned = token.dropLastWhile { it.isLetter() }
        val value = cleaned.toDoubleOrNull()
        if (value != null && value in MIN_LOOKAHEAD_UNITS..MAX_LOOKAHEAD_UNITS) {
            val meters = (value * factor).roundToInt()
            if (result.none { abs(it - meters) <= SCALE_MATCH_TOLERANCE_M }) {
                result += meters
            }
        }
    }
    return result.take(MAX_CUSTOM_SCALES)
}

internal fun formatLookaheadNumber(meters: Int, profile: UserProfile, isCustom: Boolean): String {
    val display = meters / metersPerDistanceUnit(profile)
    return if (!isCustom) {
        display.toInt().toString()
    } else {
        val tenth = (display * 10.0).roundToLong() / 10.0
        if (tenth == tenth.toLong().toDouble()) tenth.toLong().toString() else tenth.toString()
    }
}

internal fun formatLookaheadScalesInput(scalesM: List<Int>, profile: UserProfile): String =
    scalesM.joinToString(", ") { formatLookaheadNumber(it, profile, isCustom = true) }

internal fun formatLookaheadPill(meters: Int, profile: UserProfile, isCustom: Boolean): String =
    "${formatLookaheadNumber(meters, profile, isCustom)} ${ConvertType.DISTANCE.unit(profile)}"

internal fun formatLookaheadTransition(
    meters: Int,
    profile: UserProfile,
    isCustom: Boolean,
): String =
    "${formatLookaheadNumber(meters, profile, isCustom)}${ConvertType.DISTANCE.unit(profile)}"
