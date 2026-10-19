package com.jpweytjens.barberfish.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.ConvertType
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.HUDState
import com.jpweytjens.barberfish.datatype.shared.HUD_UPDATE_INTERVAL_MS
import com.jpweytjens.barberfish.datatype.shared.SlotState
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.datatype.shared.sparklineBitmapFlow
import com.jpweytjens.barberfish.extension.HUDConfig
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.SparklineTapReceiver
import com.jpweytjens.barberfish.extension.TimeConfig
import com.jpweytjens.barberfish.extension.ZoneConfig
import com.jpweytjens.barberfish.extension.streamDataFieldDesignConfig
import com.jpweytjens.barberfish.extension.streamHUDConfig
import com.jpweytjens.barberfish.extension.streamHudSparklineConfig
import com.jpweytjens.barberfish.extension.streamTimeConfig
import com.jpweytjens.barberfish.extension.streamUserProfile
import com.jpweytjens.barberfish.extension.streamZoneConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

// Fixed-height overlay strip inside a HUD slot. Independent of cell size — the HUD
// design budgets 34dp of vertical space for the sparkline regardless of slot height.
private const val HUD_SPARKLINE_HEIGHT_DP = 34f

@OptIn(ExperimentalCoroutinesApi::class)
class HUDField(private val karooSystem: KarooSystemService) :
    HUDDataType("barberfish", "three-column") {

    private val slots = HUDSlotSource(karooSystem)

    @OptIn(FlowPreview::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        if (config.gridSize.second < 18) {
            super.startView(context, config, emitter)
            return
        }
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(Dispatchers.IO + Job())
        emitter.setCancellable {
            scope.cancel()
            SparklineTapReceiver.tapSignal.value = 0L to 10
        }
        scope.launch {
            val hudStateFlow = if (config.preview) previewFlow(context) else liveFlow(context)
            val dm = context.resources.displayMetrics
            val sparklineHeightPx = (HUD_SPARKLINE_HEIGHT_DP * dm.density).toInt()
            val sparklineFlow =
                sparklineBitmapFlow(
                    karooSystem,
                    context,
                    configFlow = context.streamHudSparklineConfig(),
                    widthPx = dm.widthPixels,
                    heightPx = sparklineHeightPx,
                    isPreview = config.preview,
                )
            val transitionFlow: Flow<Int?> =
                SparklineTapReceiver.tapSignal.flatMapLatest { (ts, km) ->
                    if (ts == 0L) flowOf(null)
                    else
                        flow {
                            emit(km)
                            delay(2000L)
                            emit(null)
                        }
                }
            transitionFlow
                .flatMapLatest { transitionKm ->
                    combine(
                        hudStateFlow.sample(HUD_UPDATE_INTERVAL_MS),
                        sparklineFlow,
                        context.streamDataFieldDesignConfig(),
                    ) { hudState, frame, design ->
                        val isNightMode =
                            (context.resources.configuration.uiMode and
                                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        val showSparklineArea =
                            frame.hudEnabled &&
                                (frame.bitmap != null ||
                                    transitionKm != null ||
                                    frame.counterText != null)
                        val rv =
                            buildHudRemoteViews(
                                hudState,
                                design,
                                config,
                                context,
                                sparklineHeightPx = if (showSparklineArea) sparklineHeightPx else 0,
                            )
                        when {
                            transitionKm != null && frame.hudEnabled -> {
                                rv.setViewVisibility(R.id.hud_sparkline_container, View.VISIBLE)
                                rv.setViewVisibility(R.id.hud_elevation_sparkline, View.GONE)
                                rv.setViewVisibility(R.id.hud_sparkline_transition, View.VISIBLE)
                                val displayDist =
                                    ConvertType.DISTANCE.toDisplay(
                                            transitionKm.toDouble(),
                                            hudState.profile,
                                        )
                                        .toInt()
                                val distUnit = ConvertType.DISTANCE.unit(hudState.profile)
                                rv.setTextViewText(
                                    R.id.hud_transition_text,
                                    "$displayDist$distUnit",
                                )
                                val transitionColor = if (isNightMode) Color.WHITE else Color.BLACK
                                rv.setTextColor(R.id.hud_transition_text, transitionColor)
                                rv.setInt(
                                    R.id.hud_transition_icon,
                                    "setColorFilter",
                                    transitionColor,
                                )
                            }
                            frame.counterText != null -> {
                                rv.setViewVisibility(R.id.hud_sparkline_container, View.VISIBLE)
                                rv.setViewVisibility(R.id.hud_elevation_sparkline, View.GONE)
                                rv.setViewVisibility(R.id.hud_sparkline_transition, View.VISIBLE)
                                rv.setViewVisibility(R.id.hud_transition_icon, View.GONE)
                                rv.setTextViewText(R.id.hud_transition_text, frame.counterText)
                                rv.setTextColor(
                                    R.id.hud_transition_text,
                                    if (isNightMode) Color.WHITE else Color.BLACK,
                                )
                            }
                            frame.bitmap != null -> {
                                rv.setViewVisibility(R.id.hud_sparkline_container, View.VISIBLE)
                                rv.setImageViewBitmap(R.id.hud_elevation_sparkline, frame.bitmap)
                                rv.setViewVisibility(R.id.hud_elevation_sparkline, View.VISIBLE)
                                rv.setViewVisibility(R.id.hud_sparkline_transition, View.GONE)
                            }
                            else -> rv.setViewVisibility(R.id.hud_sparkline_container, View.GONE)
                        }
                        if (!config.preview && frame.hudEnabled) {
                            val layoutRes =
                                if (hudState.columns == 4) R.layout.barberfish_hud_four
                                else R.layout.barberfish_hud
                            val intent =
                                Intent(context, SparklineTapReceiver::class.java).apply {
                                    action = SparklineTapReceiver.ACTION
                                    putExtra(
                                        SparklineTapReceiver.EXTRA_SURFACE,
                                        SparklineTapReceiver.SURFACE_HUD,
                                    )
                                }
                            val pi =
                                PendingIntent.getBroadcast(
                                    context,
                                    layoutRes,
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                        PendingIntent.FLAG_IMMUTABLE,
                                )
                            rv.setOnClickPendingIntent(R.id.hud_sparkline_container, pi)
                        }
                        rv
                    }
                }
                .collect { rv -> emitter.updateView(rv) }
        }
    }

    override fun liveFlow(context: Context): Flow<HUDState> =
        combine(
                context.streamHUDConfig(),
                context.streamZoneConfig(),
                karooSystem.streamUserProfile(),
            ) { cfg, zones, profile ->
                Triple(cfg, zones, profile)
            }
            .flatMapLatest { (cfg, zones, profile) ->
                combine(
                    slots.liveFlow(cfg.leftSlot, zones, profile, context),
                    slots.liveFlow(cfg.middleSlot, zones, profile, context),
                    slots.liveFlow(cfg.rightSlot, zones, profile, context),
                    slots.liveFlow(cfg.fourthSlot, zones, profile, context),
                ) { left, middle, right, fourth ->
                    HUDState(
                        columns = cfg.columns,
                        left = SlotState(left, cfg.leftSlot.colorMode),
                        middle = SlotState(middle, cfg.middleSlot.colorMode),
                        right = SlotState(right, cfg.rightSlot.colorMode),
                        fourth = SlotState(fourth, cfg.fourthSlot.colorMode),
                        profile = profile,
                    )
                }
            }

    override fun previewFlow(context: Context): Flow<HUDState> =
        combine(
                context.streamHUDConfig(),
                context.streamZoneConfig(),
                karooSystem.streamUserProfile(),
            ) { cfg, zones, profile ->
                Triple(cfg, zones, profile)
            }
            .flatMapLatest { (cfg, zones, profile) ->
                context.streamTimeConfig().flatMapLatest { timeCfg ->
                    cyclePreview(previewStates(cfg, timeCfg, profile, zones))
                }
            }

    companion object {
        fun previewStates(
            hudConfig: HUDConfig,
            timeCfg: TimeConfig,
            profile: UserProfile,
            zones: ZoneConfig,
        ): List<HUDState> {
            fun slot(slotCfg: HUDSlotConfig): List<FieldState> =
                HUDSlotSource.previewStates(slotCfg, timeCfg, profile, zones)
            val l = slot(hudConfig.leftSlot)
            val m = slot(hudConfig.middleSlot)
            val r = slot(hudConfig.rightSlot)
            val f = slot(hudConfig.fourthSlot)
            val n =
                if (hudConfig.columns == 4) minOf(l.size, m.size, r.size, f.size)
                else minOf(l.size, m.size, r.size)
            return (0 until n).map { i ->
                HUDState(
                    columns = hudConfig.columns,
                    left = SlotState(l[i], hudConfig.leftSlot.colorMode),
                    middle = SlotState(m[i], hudConfig.middleSlot.colorMode),
                    right = SlotState(r[i], hudConfig.rightSlot.colorMode),
                    fourth = SlotState(f[i], hudConfig.fourthSlot.colorMode),
                    profile = profile,
                )
            }
        }
    }
}
