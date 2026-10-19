package com.jpweytjens.barberfish.datatype

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.datatype.shared.DoubleLayout
import com.jpweytjens.barberfish.datatype.shared.DoublePanelSpec
import com.jpweytjens.barberfish.datatype.shared.DoubleState
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.HUD_UPDATE_INTERVAL_MS
import com.jpweytjens.barberfish.datatype.shared.SlotState
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.datatype.shared.layout
import com.jpweytjens.barberfish.datatype.shared.renderDoublePanelBitmap
import com.jpweytjens.barberfish.datatype.shared.slots
import com.jpweytjens.barberfish.datatype.shared.toColorConfig
import com.jpweytjens.barberfish.datatype.shared.toDoubleSlotSizeConfig
import com.jpweytjens.barberfish.datatype.shared.toViewSizeConfig
import com.jpweytjens.barberfish.datatype.shared.visibleSlots
import com.jpweytjens.barberfish.datatype.shared.withDoubleLabel
import com.jpweytjens.barberfish.extension.DataFieldDesignConfig
import com.jpweytjens.barberfish.extension.DoubleFieldConfig
import com.jpweytjens.barberfish.extension.DoubleFieldKind
import com.jpweytjens.barberfish.extension.DoubleLabel
import com.jpweytjens.barberfish.extension.TimeConfig
import com.jpweytjens.barberfish.extension.ZoneConfig
import com.jpweytjens.barberfish.extension.streamDoubleFieldConfig
import com.jpweytjens.barberfish.extension.streamTimeConfig
import com.jpweytjens.barberfish.extension.streamUserProfile
import com.jpweytjens.barberfish.extension.streamZoneConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.sample

// Gap between the two rows of a vertical Double; matches barberfish_double_rows.xml.
internal const val DOUBLE_ROW_GAP_DP = 2f

/**
 * A Double field: two HUD slot fields in one Karoo cell, side by side or stacked, with the label
 * style its config picks. Slots come from [HUDSlotSource], so each keeps its HUD options, and a
 * slot whose sensor is not paired drops out as a HUD column does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DoubleField(
    private val karooSystem: KarooSystemService,
    private val kind: DoubleFieldKind,
) : BarberfishBase<DoubleState>("barberfish", kind.typeId) {

    private val slotSource = HUDSlotSource(karooSystem)

    @OptIn(FlowPreview::class)
    override fun liveFlow(context: Context, config: ViewConfig): Flow<DoubleState> =
        combine(
                context.streamDoubleFieldConfig(kind),
                context.streamZoneConfig(),
                karooSystem.streamUserProfile(),
            ) { cfg, zones, profile ->
                Triple(cfg, zones, profile)
            }
            .flatMapLatest { (cfg, zones, profile) ->
                combine(
                    slotSource.liveFlow(cfg.first, zones, profile, context),
                    slotSource.liveFlow(cfg.second, zones, profile, context),
                ) { first, second ->
                    DoubleState(
                        first = SlotState(first, cfg.first.colorMode),
                        second = SlotState(second, cfg.second.colorMode),
                        orientation = cfg.orientation,
                        label = cfg.label,
                    )
                }
            }
            // Two slots can update out of step; redraw at the HUD's pace, not on every value.
            .sample(HUD_UPDATE_INTERVAL_MS)

    override fun previewFlow(context: Context, config: ViewConfig): Flow<DoubleState> =
        combine(
                context.streamDoubleFieldConfig(kind),
                context.streamZoneConfig(),
                karooSystem.streamUserProfile(),
                context.streamTimeConfig(),
            ) { cfg, zones, profile, timeCfg ->
                previewStates(cfg, timeCfg, profile, zones)
            }
            .flatMapLatest { cyclePreview(it) }

    override fun renderState(
        state: DoubleState,
        design: DataFieldDesignConfig,
        config: ViewConfig,
        context: Context,
    ): RemoteViews = doubleRemoteViews(state, design, config, context)

    companion object {
        /** The preview ride's moments for [cfg], both slots at the same moment. */
        fun previewStates(
            cfg: DoubleFieldConfig,
            timeCfg: TimeConfig,
            profile: UserProfile,
            zones: ZoneConfig,
        ): List<DoubleState> {
            val first = HUDSlotSource.previewStates(cfg.first, timeCfg, profile, zones)
            val second = HUDSlotSource.previewStates(cfg.second, timeCfg, profile, zones)
            return (0 until minOf(first.size, second.size)).map { i ->
                DoubleState(
                    first = SlotState(first[i], cfg.first.colorMode),
                    second = SlotState(second[i], cfg.second.colorMode),
                    orientation = cfg.orientation,
                    label = cfg.label,
                )
            }
        }
    }
}

/**
 * The Double cell for [state] at [config]'s size. With labels side by side, each slot is the
 * standard field layout at half width, as in the HUD, and one slot left draws like a standalone
 * field. Stacked, each row is one bitmap with the label beside the value. Without labels, the
 * values are bitmaps filling their share of the cell. Shared by the live field, the picker preview
 * and the config screen.
 */
fun doubleRemoteViews(
    state: DoubleState,
    design: DataFieldDesignConfig,
    config: ViewConfig,
    context: Context,
): RemoteViews {
    val density = context.resources.displayMetrics.density
    val visible = visibleSlots(state.slots)
    val rv =
        when (state.layout()) {
            DoubleLayout.FIELD -> {
                val slot = state.slots[visible.single()]
                return barberfishFieldRemoteViews(
                    field = slot.field.withDoubleLabel(state.label),
                    alignment = config.alignment,
                    colorMode = slot.colorMode,
                    sizeConfig = config.toDoubleSlotSizeConfig(1, state.label, design, density),
                    preview = config.preview,
                    context = context,
                )
            }
            DoubleLayout.FIELDS ->
                RemoteViews(context.packageName, R.layout.barberfish_double).apply {
                    fillSlotContainers(
                        listOf(R.id.double_slot_first, R.id.double_slot_second),
                        state.slots.map { it.copy(field = it.field.withDoubleLabel(state.label)) },
                        config.toDoubleSlotSizeConfig(2, state.label, design, density),
                        config.alignment,
                        context,
                    )
                }
            DoubleLayout.VALUES ->
                panelRemoteViews(R.layout.barberfish_double_values, state, design, config, context)
            DoubleLayout.ROWS ->
                panelRemoteViews(R.layout.barberfish_double_rows, state, design, config, context)
        }
    // Preview corner radius, as on the other fields (API 31+; Karoo 3 is API 33).
    if (config.preview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rv.setViewOutlinePreferredRadius(R.id.double_root, 12f, TypedValue.COMPLEX_UNIT_DIP)
        rv.setBoolean(R.id.double_root, "setClipToOutline", true)
    }
    return rv
}

// How far a value may grow past the cell's own value size when no label takes room from it.
private const val NO_LABEL_VALUE_GROWTH = 1.4f

/**
 * The bitmap-panel layouts: [layoutRes] is the stacked rows or the label-free values side by side.
 * Each visible slot is drawn at its panel's pixel size; a slot that dropped out is hidden.
 */
private fun panelRemoteViews(
    layoutRes: Int,
    state: DoubleState,
    design: DataFieldDesignConfig,
    config: ViewConfig,
    context: Context,
): RemoteViews {
    val density = context.resources.displayMetrics.density
    val isNightMode =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val visible = visibleSlots(state.slots)
    val stacked = layoutRes == R.layout.barberfish_double_rows
    // The cell's own native sizes bound the label block and the value.
    val cell = config.toViewSizeConfig(design = design)
    val growth = if (state.label == DoubleLabel.NONE) NO_LABEL_VALUE_GROWTH else 1f
    val spec =
        DoublePanelSpec(
            widthPx = if (stacked) config.viewSize.first else config.viewSize.first / visible.size,
            heightPx =
                if (stacked) ((config.viewSize.second - DOUBLE_ROW_GAP_DP * density) / 2f).toInt()
                else config.viewSize.second,
            label = state.label,
            alignment = config.alignment,
            labelFontPx = cell.headerFontSize.value * density,
            iconPx = cell.headerIconSize.value * density,
            valueFontPx = cell.valueFontSizeBase * density * growth,
            paddingHPx = cell.paddingH.value * density,
            density = density,
        )
    val rv = RemoteViews(context.packageName, layoutRes)
    val ids = listOf(R.id.double_panel_first, R.id.double_panel_second)
    state.slots.forEachIndexed { index, slot ->
        if (index !in visible) {
            rv.setViewVisibility(ids[index], View.GONE)
            return@forEachIndexed
        }
        val field: FieldState = slot.field
        val colors = field.color.toColorConfig(slot.colorMode, isNightMode, field.liveIcon)
        rv.setImageViewBitmap(ids[index], renderDoublePanelBitmap(field, colors, spec, context))
    }
    return rv
}
