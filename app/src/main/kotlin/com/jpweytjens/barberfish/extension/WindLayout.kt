package com.jpweytjens.barberfish.extension

import kotlinx.serialization.Serializable

/** How the Wind HUD slot draws speed and wind when Show speed is on. */
@Serializable
enum class WindLayout(val label: String) {
    /** Speed over the arrow and headwind number, colour on the headwind only. */
    BARBERFISH("Barberfish"),

    /** karoo-headwind's Tailwind & ride speed layout: big arrow, speed, summary line. */
    HEADWIND("Headwind"),
}
