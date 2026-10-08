package com.jpweytjens.barberfish.screens

import androidx.compose.runtime.Composable
import com.jpweytjens.barberfish.R
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.SpeedSmoothingStream
import com.jpweytjens.barberfish.extension.WindLayout

/** The Wind slot's own controls: Show speed, and the speed smoothing when it is on. */
@Composable
internal fun HUDWindCard(slot: HUDSlotConfig, onUpdate: (HUDSlotConfig) -> Unit) {
    BoolToggleRow(
        label = "SHOW SPEED",
        value = slot.windShowSpeed,
        onChange = { onUpdate(slot.copy(windShowSpeed = it)) },
        help = "Show the ride speed with the wind.",
    )
    if (slot.windShowSpeed) {
        ChoiceRow(
            label = "LAYOUT",
            options = WindLayout.entries.map { it to it.label },
            selected = slot.windLayout,
            onSelect = { onUpdate(slot.copy(windLayout = it)) },
            help =
                "Barberfish stacks speed over the wind. Headwind draws a big arrow, the speed " +
                    "and a summary line in wind colors.",
        )
        if (slot.windLayout == WindLayout.HEADWIND) {
            BoolToggleRow(
                label = "HEADER",
                value = slot.windShowHeader,
                onChange = { onUpdate(slot.copy(windShowHeader = it)) },
                help = "Off gives the digits more room and keeps the wind icon in the corner.",
            )
        }
        ControlLabel("SMOOTHING")
        SmoothingSlider(
            options = SpeedSmoothingStream.entries,
            selected = slot.speedSmoothing,
            label = { it.label },
            onSelected = { onUpdate(slot.copy(speedSmoothing = it)) },
            thumbIcon = R.drawable.ic_col_speed,
        )
    }
}
