package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.datatype.EffortField
import com.jpweytjens.barberfish.datatype.shared.ASCENT_MARKER
import com.jpweytjens.barberfish.extension.EffortFieldConfig
import com.jpweytjens.barberfish.extension.FieldOrientation
import io.hammerhead.karooext.models.UserProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortFieldOrderTest {

    // Metric profile so distance renders in km and elevation in m (copied from SpeedThresholdTest).
    private val metricProfile =
        UserProfile(
            weight = 70f,
            preferredUnit =
                UserProfile.PreferredUnit(
                    distance = UserProfile.PreferredUnit.UnitType.METRIC,
                    elevation = UserProfile.PreferredUnit.UnitType.METRIC,
                    temperature = UserProfile.PreferredUnit.UnitType.METRIC,
                    weight = UserProfile.PreferredUnit.UnitType.METRIC,
                ),
            maxHr = 190,
            restingHr = 60,
            heartRateZones = emptyList(),
            ftp = 250,
            powerZones = emptyList(),
        )

    @Test
    fun `distance first by default, arrow on the climb row`() {
        val s =
            EffortField.previewStates(metricProfile, EffortFieldConfig(climbFirst = false)).first()
        assertFalse("distance should not carry the arrow", s.primary.startsWith("↗"))
        assertTrue("climb row carries the arrow", s.secondary!!.startsWith("↗"))
    }

    @Test
    fun `climb first swaps the rows and keeps the arrow on the climb`() {
        val s =
            EffortField.previewStates(metricProfile, EffortFieldConfig(climbFirst = true)).first()
        assertTrue("climb now on top, still carries the arrow", s.primary.startsWith("↗"))
        assertFalse("distance now on the bottom row", s.secondary!!.startsWith("↗"))
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `stacked is the default, so stored configs keep their layout`() {
        assertEquals(FieldOrientation.VERTICAL, EffortFieldConfig().orientation)
        // A config stored before the setting existed.
        val stored =
            json.decodeFromString(EffortFieldConfig.serializer(), """{"climbFirst":true}""")
        assertEquals(EffortFieldConfig(climbFirst = true), stored)
        assertFalse(EffortField.previewStates(metricProfile, stored).first().secondaryBeside)
    }

    @Test
    fun `orientation persists through the stored JSON`() {
        val config = EffortFieldConfig(climbFirst = true, orientation = FieldOrientation.HORIZONTAL)
        val stored = json.encodeToString(EffortFieldConfig.serializer(), config)
        assertEquals(config, json.decodeFromString(EffortFieldConfig.serializer(), stored))
    }

    @Test
    fun `horizontal puts the second value beside the first, in the chosen order`() {
        val side =
            EffortField.previewStates(
                    metricProfile,
                    EffortFieldConfig(climbFirst = true, orientation = FieldOrientation.HORIZONTAL),
                )
                .first()
        assertTrue(side.secondaryBeside)
        assertTrue(side.primary.startsWith(ASCENT_MARKER))
        assertNotNull(side.secondary)
    }
}
