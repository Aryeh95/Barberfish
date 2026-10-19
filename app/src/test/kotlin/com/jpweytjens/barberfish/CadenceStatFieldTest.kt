package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.datatype.CadenceStatField
import com.jpweytjens.barberfish.datatype.CadenceStatKind
import com.jpweytjens.barberfish.datatype.cadenceStatKind
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.extension.CadenceStatFieldConfig
import com.jpweytjens.barberfish.extension.CadenceThresholdConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cadence aggregates to their SDK streams, mirroring the power and HR ones: the lap types
 * report under the average field, last lap waits for a finished lap, and max ignores the 0 it reads
 * before the first pedal stroke.
 */
class CadenceStatFieldTest {

    private val off = CadenceThresholdConfig()

    private fun streaming(kind: CadenceStatKind, rpm: Double) =
        StreamState.Streaming(DataPoint(kind.sourceType, mapOf(kind.fieldId to rpm)))

    @Test
    fun `each kind streams the SDK type and field the SDK documents`() {
        assertEquals(DataType.Type.AVERAGE_CADENCE, CadenceStatKind.AVG.sourceType)
        assertEquals(DataType.Field.AVERAGE_CADENCE, CadenceStatKind.AVG.fieldId)
        assertEquals(DataType.Type.CADENCE_LAP, CadenceStatKind.LAP_AVG.sourceType)
        assertEquals(DataType.Field.AVERAGE_CADENCE, CadenceStatKind.LAP_AVG.fieldId)
        assertEquals(
            DataType.Type.AVERAGE_CADENCE_LAST_LAP,
            CadenceStatKind.LAST_LAP_AVG.sourceType,
        )
        assertEquals(DataType.Field.AVERAGE_CADENCE, CadenceStatKind.LAST_LAP_AVG.fieldId)
        assertEquals(DataType.Type.MAX_CADENCE, CadenceStatKind.MAX.sourceType)
        assertEquals(DataType.Field.MAX_CADENCE, CadenceStatKind.MAX.fieldId)
    }

    @Test
    fun `type ids and config keys are distinct`() {
        val kinds = CadenceStatKind.entries
        assertEquals(kinds.size, kinds.map { it.typeId }.toSet().size)
        assertEquals(kinds.size, kinds.map { it.configKey }.toSet().size)
        assertTrue(kinds.none { it.configKey == "cadence_field_config" })
    }

    @Test
    fun `HUD slot fields map to their kind, and only the four aggregates do`() {
        assertEquals(CadenceStatKind.AVG, HUDSlotField.AvgCadence.cadenceStatKind)
        assertEquals(CadenceStatKind.LAP_AVG, HUDSlotField.LapAvgCadence.cadenceStatKind)
        assertEquals(CadenceStatKind.LAST_LAP_AVG, HUDSlotField.LastLapAvgCadence.cadenceStatKind)
        assertEquals(CadenceStatKind.MAX, HUDSlotField.MaxCadence.cadenceStatKind)
        assertNull(HUDSlotField.Cadence.cadenceStatKind)
        assertNull(HUDSlotField.AvgPower.cadenceStatKind)
    }

    @Test
    fun `streaming value renders as whole rpm with the kind's label and icons`() {
        val s =
            CadenceStatField.toFieldState(
                streaming(CadenceStatKind.LAP_AVG, 87.6),
                CadenceStatKind.LAP_AVG,
                off,
            )
        assertEquals("87", s.primary)
        assertEquals("Lap Avg Cadence", s.label)
        assertEquals(R.drawable.ic_lap, s.iconRes)
        assertEquals(R.drawable.ic_avg_cadence, s.secondaryIconRes)
        assertEquals(FieldColor.Default, s.color)
    }

    @Test
    fun `last lap shows no laps yet until a lap is finished`() {
        val kind = CadenceStatKind.LAST_LAP_AVG
        val first = CadenceStatField.toFieldState(streaming(kind, 90.0), kind, off, lapNumber = 1)
        assertEquals("No laps yet", first.primary)
        val second = CadenceStatField.toFieldState(streaming(kind, 90.0), kind, off, lapNumber = 2)
        assertEquals("90", second.primary)
    }

    @Test
    fun `max cadence of zero reads not available`() {
        val kind = CadenceStatKind.MAX
        assertEquals(
            "Not available",
            CadenceStatField.toFieldState(streaming(kind, 0.0), kind, off).primary,
        )
    }

    @Test
    fun `missing sensor is flagged so the HUD and Doubles can drop the slot`() {
        val s = CadenceStatField.toFieldState(StreamState.NotAvailable, CadenceStatKind.AVG, off)
        assertTrue(s.noSensor)
    }

    @Test
    fun `threshold colors the aggregate like the Cadence field`() {
        val target = CadenceThresholdConfig(thresholdRpm = 90.0)
        val low =
            CadenceStatField.toFieldState(
                streaming(CadenceStatKind.AVG, 80.0),
                CadenceStatKind.AVG,
                target,
            )
        val color = low.color as FieldColor.Threshold
        assertTrue(color.factor < 0f)
        val previews =
            CadenceStatField.previewStates(CadenceStatKind.MAX, CadenceStatFieldConfig(target))
        assertTrue(previews.isNotEmpty())
        assertTrue(previews.all { it.color is FieldColor.Threshold })
    }
}
