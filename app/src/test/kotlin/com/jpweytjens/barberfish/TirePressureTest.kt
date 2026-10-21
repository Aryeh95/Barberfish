package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.datatype.TirePosition
import com.jpweytjens.barberfish.datatype.TirePressure
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import com.jpweytjens.barberfish.extension.TirePressureUnit
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tire slots read the Outrider extension's streams, in the unit set in its app. */
class TirePressureTest {

    private fun slot(unit: TirePressureUnit, target: Double = 0.0, step: Double = 10.0) =
        HUDSlotConfig(
            field = HUDSlotField.FrontTire,
            tireUnit = unit,
            tireTarget = target,
            tireBandPercent = step,
        )

    private fun streaming(position: TirePosition, value: Double) =
        StreamState.Streaming(DataPoint(position.streamId, mapOf(DataType.Field.SINGLE to value)))

    @Test
    fun `streams are the Outrider extension's front and rear types`() {
        assertEquals("TYPE_EXT::Outrider::outrider-front", TirePosition.FRONT.streamId)
        assertEquals("TYPE_EXT::Outrider::outrider-rear", TirePosition.REAR.streamId)
    }

    @Test
    fun `the value shows with the decimals Outrider uses for its unit`() {
        val psi =
            TirePressure.toFieldState(
                streaming(TirePosition.FRONT, 68.44),
                TirePosition.FRONT,
                slot(TirePressureUnit.PSI),
            )
        assertEquals("68.4", psi.primary)
        assertEquals("Front Tire", psi.label)
        val bar =
            TirePressure.toFieldState(
                streaming(TirePosition.REAR, 4.996),
                TirePosition.REAR,
                slot(TirePressureUnit.BAR),
            )
        assertEquals("5.00", bar.primary)
        val kpa =
            TirePressure.toFieldState(
                streaming(TirePosition.REAR, 499.6),
                TirePosition.REAR,
                slot(TirePressureUnit.KPA),
            )
        assertEquals("500", kpa.primary)
    }

    @Test
    fun `no sensor paired reads No sensor, so a Double drops the half`() {
        val s =
            TirePressure.toFieldState(
                StreamState.NotAvailable,
                TirePosition.FRONT,
                slot(TirePressureUnit.PSI),
            )
        assertEquals("No sensor", s.primary)
        assertTrue(s.noSensor)
        assertEquals(
            "Searching…",
            TirePressure.toFieldState(
                    StreamState.Searching,
                    TirePosition.REAR,
                    slot(TirePressureUnit.PSI),
                )
                .primary,
        )
    }

    @Test
    fun `previews convert to the chosen unit`() {
        assertEquals(
            "68.4",
            TirePressure.previewStates(TirePosition.FRONT, slot(TirePressureUnit.PSI))[0].primary,
        )
        assertEquals(
            "4.72",
            TirePressure.previewStates(TirePosition.FRONT, slot(TirePressureUnit.BAR))[0].primary,
        )
        assertEquals(
            "472",
            TirePressure.previewStates(TirePosition.FRONT, slot(TirePressureUnit.KPA))[0].primary,
        )
    }

    @Test
    fun `slots stored before the unit setting existed read PSI`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            TirePressureUnit.PSI,
            json.decodeFromString(HUDSlotConfig.serializer(), "{}").tireUnit,
        )
    }

    @Test
    fun `each step from the target, above or below, is the next color band`() {
        fun band(psi: Double) =
            (TirePressure.tireColor(psi, target = 70.0, bandPercent = 10.0) as FieldColor.Deviation)
                .band
        assertEquals(0, band(70.0))
        assertEquals(0, band(64.0)) // 8.6% under
        assertEquals(0, band(76.9))
        assertEquals(1, band(77.0)) // 10% over: yellow
        assertEquals(1, band(62.0)) // 11.4% under
        assertEquals(2, band(84.5)) // 20.7% over: orange
        assertEquals(3, band(49.0)) // 30% under: red
        assertEquals(5, band(30.0))
    }

    @Test
    fun `no target leaves the value uncolored`() {
        assertEquals(FieldColor.Default, TirePressure.tireColor(70.0, 0.0, 10.0))
        assertEquals(FieldColor.Default, TirePressure.tireColor(70.0, 70.0, 0.0))
        val s =
            TirePressure.toFieldState(
                streaming(TirePosition.FRONT, 80.0),
                TirePosition.FRONT,
                slot(TirePressureUnit.PSI),
            )
        assertEquals(FieldColor.Default, s.color)
    }

    @Test
    fun `the live value takes its band in the slot's unit`() {
        val s =
            TirePressure.toFieldState(
                streaming(TirePosition.REAR, 4.0),
                TirePosition.REAR,
                slot(TirePressureUnit.BAR, target = 5.0),
            )
        assertEquals(FieldColor.Deviation(2), s.color)
    }
}
