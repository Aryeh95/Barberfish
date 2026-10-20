package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.datatype.EffortField
import com.jpweytjens.barberfish.datatype.shared.ASCENT_MARKER
import com.jpweytjens.barberfish.datatype.shared.DESCENT_MARKER
import com.jpweytjens.barberfish.extension.EffortFieldConfig
import com.jpweytjens.barberfish.extension.FieldOrientation
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `descent and the header option default off and on, so stored configs are unchanged`() {
        val stored =
            json.decodeFromString(EffortFieldConfig.serializer(), """{"climbFirst":true}""")
        assertFalse(stored.showDescent)
        assertTrue(stored.showHeader)
        val s = EffortField.previewStates(metricProfile, stored).first()
        assertNull(s.tertiary)
        assertFalse(s.hideHeader)
    }

    @Test
    fun `descent follows the climb, with the distance first or last`() {
        val distFirst =
            EffortField.previewStates(metricProfile, EffortFieldConfig(showDescent = true)).first()
        assertEquals("42.1", distFirst.primary)
        assertEquals(ASCENT_MARKER + "1240", distFirst.secondary)
        assertEquals(DESCENT_MARKER + "1310", distFirst.tertiary)

        val climbFirst =
            EffortField.previewStates(
                    metricProfile,
                    EffortFieldConfig(climbFirst = true, showDescent = true),
                )
                .first()
        assertEquals(ASCENT_MARKER + "1240", climbFirst.primary)
        assertEquals(DESCENT_MARKER + "1310", climbFirst.secondary)
        assertEquals("42.1", climbFirst.tertiary)
    }

    @Test
    fun `header off hides the header`() {
        val s =
            EffortField.previewStates(metricProfile, EffortFieldConfig(showHeader = false)).first()
        assertTrue(s.hideHeader)
    }

    private fun streaming(type: String, field: String, value: Double) =
        StreamState.Streaming(DataPoint(type, mapOf(field to value)))

    private val dist =
        streaming(
            DataType.Type.DISTANCE_TO_DESTINATION,
            DataType.Field.DISTANCE_TO_DESTINATION,
            23_400.0,
        )
    private val ascent =
        streaming(DataType.Type.ELEVATION_REMAINING, DataType.Field.ASCENT_REMAINING, 540.0)

    @Test
    fun `live descent comes from the descent remaining stream`() {
        val descent =
            streaming(DataType.Type.DESCENT_REMAINING, DataType.Field.DESCENT_REMAINING, 610.4)
        val s =
            EffortField.toFieldState(
                dist,
                ascent,
                metricProfile,
                EffortFieldConfig(showDescent = true),
                descent,
            )
        assertEquals(DESCENT_MARKER + "610", s.tertiary)
    }

    @Test
    fun `live descent missing reads as no route, like a missing ascent`() {
        val s =
            EffortField.toFieldState(
                dist,
                ascent,
                metricProfile,
                EffortFieldConfig(showDescent = true),
                StreamState.NotAvailable,
            )
        assertEquals("No route", s.primary)
    }
}
