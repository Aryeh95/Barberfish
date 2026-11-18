package com.jpweytjens.barberfish.datatype

import android.content.Context
import com.jpweytjens.barberfish.datatype.shared.DualValues
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.cyclePreview
import com.jpweytjens.barberfish.extension.FieldOrientation
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import com.jpweytjens.barberfish.extension.TiresFieldConfig
import com.jpweytjens.barberfish.extension.ZoneColorMode
import com.jpweytjens.barberfish.extension.streamTiresFieldConfig
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Combined Front & Rear Outrider tire pressures in a single cell or HUD/Double slot, side by side
 * or stacked, with independent target-deviation coloring on each tire. If only one tire sensor is
 * paired, that tire fills the field; if neither is paired, `noSensor = true` drops the slot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TiresField(private val karooSystem: KarooSystemService) :
    BarberfishDataType("barberfish", "tires") {

    override fun liveFlow(context: Context): Flow<FieldState> =
        context.streamTiresFieldConfig().flatMapLatest { cfg -> liveStates(karooSystem, cfg) }

    override fun previewFlow(context: Context): Flow<FieldState> =
        context.streamTiresFieldConfig().flatMapLatest { cfg -> cyclePreview(previewStates(cfg)) }

    companion object {
        private const val LABEL = "Tires"

        fun slotToConfig(slot: HUDSlotConfig): TiresFieldConfig =
            TiresFieldConfig(
                unit = slot.tireUnit,
                frontTarget = slot.tireTarget,
                rearTarget = slot.tireRearTarget,
                bandPercent = slot.tireBandPercent,
                orientation = slot.tireOrientation,
                colorMode = slot.colorMode,
            )

        fun liveStates(
            karooSystem: KarooSystemService,
            cfg: TiresFieldConfig,
        ): Flow<FieldState> {
            val frontSlot = perTireSlot(HUDSlotField.FrontTire, cfg, cfg.frontTarget)
            val effectiveRear = if (cfg.rearTarget > 0.0) cfg.rearTarget else cfg.frontTarget
            val rearSlot = perTireSlot(HUDSlotField.RearTire, cfg, effectiveRear)
            return combine(
                TirePressure.liveStates(karooSystem, TirePosition.FRONT, frontSlot),
                TirePressure.liveStates(karooSystem, TirePosition.REAR, rearSlot),
            ) { front, rear ->
                combineTireStates(
                    front = front,
                    rear = rear,
                    beside = cfg.orientation == FieldOrientation.HORIZONTAL,
                    colorMode = cfg.colorMode,
                )
            }
        }

        fun combineTireStates(
            front: FieldState,
            rear: FieldState,
            beside: Boolean,
            colorMode: ZoneColorMode,
        ): FieldState {
            val frontLive = front.color !is FieldColor.StreamState
            val rearLive = rear.color !is FieldColor.StreamState
            return when {
                frontLive && rearLive -> bothLiveState(front, rear, beside, colorMode)
                frontLive -> front.copy(colorMode = colorMode)
                rearLive -> rear.copy(colorMode = colorMode)
                front.noSensor && rear.noSensor -> FieldState.noSensor(LABEL, TirePressure.ICON)
                front.primary == "Searching…" || rear.primary == "Searching…" ->
                    FieldState.searching(LABEL, TirePressure.ICON)
                else -> front.copy(label = LABEL, noSensor = false)
            }
        }

        private fun bothLiveState(
            front: FieldState,
            rear: FieldState,
            beside: Boolean,
            colorMode: ZoneColorMode,
        ): FieldState =
            FieldState(
                primary = "${front.primary} ${rear.primary}",
                label = LABEL,
                color = worstTireColor(front.color, rear.color, colorMode),
                iconRes = TirePressure.ICON,
                colorMode = colorMode,
                liveIcon = front.liveIcon || rear.liveIcon,
                dualValues =
                    DualValues(
                        first = front.primary,
                        firstColor = effectiveDualColor(front.color, colorMode),
                        second = rear.primary,
                        secondColor = effectiveDualColor(rear.color, colorMode),
                        beside = beside,
                    ),
            )

        private fun effectiveDualColor(color: FieldColor, colorMode: ZoneColorMode): FieldColor =
            if (colorMode == ZoneColorMode.NONE && color !is FieldColor.Muted) FieldColor.Default
            else color

        private fun worstTireColor(
            first: FieldColor,
            second: FieldColor,
            colorMode: ZoneColorMode,
        ): FieldColor {
            val fDev = (first as? FieldColor.Deviation)?.band
            val sDev = (second as? FieldColor.Deviation)?.band
            return when {
                first is FieldColor.Muted && second is FieldColor.Muted -> FieldColor.Muted
                colorMode == ZoneColorMode.NONE -> FieldColor.Default
                fDev != null && sDev != null -> FieldColor.Deviation(maxOf(fDev, sDev))
                fDev != null -> first
                sDev != null -> second
                else -> FieldColor.Default
            }
        }

        fun previewStates(cfg: TiresFieldConfig): List<FieldState> {
            val frontSlot = perTireSlot(HUDSlotField.FrontTire, cfg, cfg.frontTarget)
            val effectiveRear = if (cfg.rearTarget > 0.0) cfg.rearTarget else cfg.frontTarget
            val rearSlot = perTireSlot(HUDSlotField.RearTire, cfg, effectiveRear)
            val fronts = TirePressure.previewStates(TirePosition.FRONT, frontSlot)
            val rears = TirePressure.previewStates(TirePosition.REAR, rearSlot)
            val beside = cfg.orientation == FieldOrientation.HORIZONTAL
            return fronts.zip(rears) { f, r -> combineTireStates(f, r, beside, cfg.colorMode) }
        }

        private fun perTireSlot(
            field: HUDSlotField,
            cfg: TiresFieldConfig,
            target: Double,
        ): HUDSlotConfig =
            HUDSlotConfig(
                field = field,
                tireUnit = cfg.unit,
                tireTarget = target,
                tireBandPercent = cfg.bandPercent,
                colorMode = cfg.colorMode,
            )
    }
}
