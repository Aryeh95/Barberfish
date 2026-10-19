package com.jpweytjens.barberfish.datatype.shared

import com.jpweytjens.barberfish.extension.DoubleLabel
import com.jpweytjens.barberfish.extension.DoubleOrientation

data class DoubleState(
    val first: SlotState,
    val second: SlotState,
    val orientation: DoubleOrientation,
    val label: DoubleLabel,
)

/** The two slots in order: left then right, or top then bottom. */
val DoubleState.slots: List<SlotState>
    get() = listOf(first, second)

/** How a Double lays out its visible slots. */
enum class DoubleLayout {
    // One slot left: it takes the whole cell, drawn like a standalone field.
    FIELD,
    // Two fields side by side, each with its header above the value, as in the HUD.
    FIELDS,
    // No labels: each visible value drawn as a panel filling its share of the cell, side by side.
    VALUES,
    // Two rows, each with its label beside the value, so both fit a single-height cell.
    ROWS,
}

/**
 * The layout for this state. A slot whose sensor is not paired drops out and the other takes the
 * whole cell, as a HUD column does; with both unpaired both stay (see [visibleSlots]). Labels sit
 * above the values side by side and beside them when stacked; with no label the values are drawn
 * alone, side by side or (one left) filling the cell.
 */
fun DoubleState.layout(): DoubleLayout {
    val visible = visibleSlots(slots).size
    return when {
        label == DoubleLabel.NONE &&
            (visible == 1 || orientation == DoubleOrientation.HORIZONTAL) -> DoubleLayout.VALUES
        visible == 1 -> DoubleLayout.FIELD
        orientation == DoubleOrientation.HORIZONTAL -> DoubleLayout.FIELDS
        else -> DoubleLayout.ROWS
    }
}

/**
 * [field] as a Double with [label] names it in a field layout: Icon drops the header text. Header
 * only is a size-config matter (the icons are turned off there), and None never reaches a field
 * layout (see [layout]), so the state is otherwise unchanged.
 */
fun FieldState.withDoubleLabel(label: DoubleLabel): FieldState =
    if (label == DoubleLabel.ICON) copy(label = "") else this
