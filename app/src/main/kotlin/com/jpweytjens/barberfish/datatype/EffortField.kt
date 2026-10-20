package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.ASCENT_MARKER
import com.jpweytjens.barberfish.datatype.shared.ConvertType
import com.jpweytjens.barberfish.datatype.shared.DESCENT_MARKER
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.datatype.shared.formatFixed
import com.jpweytjens.barberfish.extension.EffortFieldConfig
import com.jpweytjens.barberfish.extension.FieldOrientation
import com.jpweytjens.barberfish.extension.streamDataFlow
import com.jpweytjens.barberfish.extension.streamEffortFieldConfig
import com.jpweytjens.barberfish.extension.streamUserProfile
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

private const val LABEL = "Ride\nRemaining"
private val ICON = R.drawable.ic_road

@OptIn(ExperimentalCoroutinesApi::class)
class EffortField(private val karooSystem: KarooSystemService) :
    BarberfishDataType("barberfish", "remaining-effort") {

    override fun liveFlow(context: Context): Flow<FieldState> =
        karooSystem.streamUserProfile().flatMapLatest { profile ->
            context.streamEffortFieldConfig().flatMapLatest { config ->
                // Descent remaining is only streamed while the field shows it.
                val descentFlow: Flow<StreamState?> =
                    if (config.showDescent)
                        karooSystem.streamDataFlow(DataType.Type.DESCENT_REMAINING)
                    else flowOf(null)
                combine(
                    karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION),
                    karooSystem.streamDataFlow(DataType.Type.ELEVATION_REMAINING),
                    descentFlow,
                ) { distState, ascentState, descentState ->
                    toFieldState(distState, ascentState, profile, config, descentState)
                }
            }
        }

    override fun previewFlow(context: Context): Flow<FieldState> =
        karooSystem.streamUserProfile().flatMapLatest { profile ->
            context.streamEffortFieldConfig().flatMapLatest { config ->
                cyclePreview(previewStates(profile, config))
            }
        }

    companion object {
        private fun onRouteValue(state: StreamState, fieldId: String): Double? {
            val streaming = state as? StreamState.Streaming ?: return null
            val onRoute = streaming.dataPoint.values[DataType.Field.ON_ROUTE]
            if (onRoute != null && onRoute == 0.0) return null
            return streaming.dataPoint.values[fieldId]
        }

        private fun effortFieldState(
            distText: String,
            ascentText: String,
            descentText: String?,
            config: EffortFieldConfig,
        ): FieldState {
            val climb = ASCENT_MARKER + ascentText
            val descent = descentText?.let { DESCENT_MARKER + it }
            // The descent always follows the climb; the order setting moves the distance.
            val values =
                if (config.climbFirst) listOfNotNull(climb, descent, distText)
                else listOfNotNull(distText, climb, descent)
            return FieldState(
                primary = values[0],
                secondary = values[1],
                tertiary = values.getOrNull(2),
                secondaryBeside = config.orientation == FieldOrientation.HORIZONTAL,
                label = LABEL,
                color = FieldColor.Default,
                iconRes = ICON,
                hideHeader = !config.showHeader,
            )
        }

        fun toFieldState(
            distState: StreamState,
            ascentState: StreamState,
            profile: UserProfile,
            config: EffortFieldConfig,
            descentState: StreamState? = null,
        ): FieldState {
            val distM = onRouteValue(distState, DataType.Field.DISTANCE_TO_DESTINATION)
            val ascentM = onRouteValue(ascentState, DataType.Field.ASCENT_REMAINING)
            val descentM = descentState?.let { onRouteValue(it, DataType.Field.DESCENT_REMAINING) }
            val descentMissing = config.showDescent && descentM == null
            if (distM == null || ascentM == null || descentMissing) {
                return FieldState.noRoute(LABEL, ICON)
            }
            return effortFieldState(
                distText = formatFixed(ConvertType.DISTANCE.apply(distM, profile), 1),
                ascentText = formatFixed(ConvertType.ELEVATION.apply(ascentM, profile), 0),
                descentText =
                    descentM
                        ?.takeIf { config.showDescent }
                        ?.let { formatFixed(ConvertType.ELEVATION.apply(it, profile), 0) },
                config = config,
            )
        }

        fun previewStates(profile: UserProfile, config: EffortFieldConfig): List<FieldState> {
            val samples =
                listOf(
                    Triple(42_100.0, 1240.0, 1310.0),
                    Triple(23_400.0, 540.0, 610.0),
                    Triple(4_800.0, 80.0, 95.0),
                )
            return samples.map { (distM, ascentM, descentM) ->
                effortFieldState(
                    distText = formatFixed(ConvertType.DISTANCE.apply(distM, profile), 1),
                    ascentText = formatFixed(ConvertType.ELEVATION.apply(ascentM, profile), 0),
                    descentText =
                        if (config.showDescent)
                            formatFixed(ConvertType.ELEVATION.apply(descentM, profile), 0)
                        else null,
                    config = config,
                )
            }
        }
    }
}
