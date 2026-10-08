package com.jpweytjens.barberfish.datatype

import com.jpweytjens.barberfish.datatype.shared.FieldState

/** A speed state with the raw speed in m/s it came from, for the trend against the average. */
internal data class SpeedReading(val state: FieldState, val ms: Double?)
