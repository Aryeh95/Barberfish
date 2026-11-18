package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.datatype.TirePosition
import com.jpweytjens.barberfish.datatype.TirePressure
import com.jpweytjens.barberfish.datatype.TiresField
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import com.jpweytjens.barberfish.extension.TireAlertConfig
import com.jpweytjens.barberfish.extension.TireAlertKind
import com.jpweytjens.barberfish.extension.TireLeakDetector
import com.jpweytjens.barberfish.extension.TirePressureUnit
import com.jpweytjens.barberfish.extension.ZoneColorMode
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `a held value keeps its number in grey, the icon not live`() {
        val held =
            TirePressure.heldState(
                68.44,
                TirePosition.FRONT,
                slot(TirePressureUnit.PSI, target = 70.0),
            )
        assertEquals("68.4", held.primary)
        assertEquals(FieldColor.Muted, held.color)
        assertEquals(false, held.liveIcon)
        assertEquals(false, held.noSensor)
    }

    @Test
    fun `searching or idle past HOLD_MS since last live reading drops held state immediately`() =
        runBlocking {
            var now = 1_000L
            // Emit a live reading at t=1_000, then advance fake clock past HOLD_MS before
            // Searching/Idle.
            val stream = flow {
                emit(streaming(TirePosition.FRONT, 68.4))
                now += TirePressure.HOLD_MS + 500L
                emit(StreamState.Searching)
                now += 5_000L
                emit(StreamState.Idle)
            }
            val emitted =
                TirePressure.holdTireStates(
                        stream,
                        TirePosition.FRONT,
                        slot(TirePressureUnit.PSI, target = 70.0),
                        clockMs = { now },
                    )
                    .toList()
            // Live "68.4", then immediately "Searching…" and "No data" without holding stale 68.4.
            assertEquals(listOf("68.4", "Searching…", "No data"), emitted.map { it.primary })
        }

    @Test
    fun `switching units converts configured target pressures and leaves zero unset`() {
        assertEquals(
            0.0,
            TirePressure.convertTarget(0.0, TirePressureUnit.PSI, TirePressureUnit.BAR),
            1e-6,
        )
        assertEquals(
            4.83,
            TirePressure.convertTarget(70.0, TirePressureUnit.PSI, TirePressureUnit.BAR),
            1e-6,
        )
        assertEquals(
            483.0,
            TirePressure.convertTarget(70.0, TirePressureUnit.PSI, TirePressureUnit.KPA),
            1e-6,
        )
        assertEquals(
            70.0,
            TirePressure.convertTarget(4.83, TirePressureUnit.BAR, TirePressureUnit.PSI),
            0.1,
        )
    }

    @Test
    fun `combined tires field colors each tire independently and falls back cleanly`() {
        val frontGreen =
            FieldState(
                "68.4",
                "Front Tire",
                FieldColor.Deviation(0),
                colorMode = ZoneColorMode.TEXT,
            )
        val rearRed =
            FieldState("52.0", "Rear Tire", FieldColor.Deviation(3), colorMode = ZoneColorMode.TEXT)
        val combined =
            TiresField.combineTireStates(
                frontGreen,
                rearRed,
                beside = true,
                colorMode = ZoneColorMode.TEXT,
            )
        val dual = checkNotNull(combined.dualValues)
        assertEquals("68.4", dual.first)
        assertEquals(FieldColor.Deviation(0), dual.firstColor)
        assertEquals("52.0", dual.second)
        assertEquals(FieldColor.Deviation(3), dual.secondColor)
        // Worst band wins in fill mode.
        assertEquals(FieldColor.Deviation(3), combined.color)

        // Single-sensor fallback when rear is unpaired.
        val unpairedRear = FieldState.noSensor("Rear Tire")
        val singleFallback =
            TiresField.combineTireStates(
                frontGreen,
                unpairedRear,
                beside = true,
                colorMode = ZoneColorMode.TEXT,
            )
        assertNull(singleFallback.dualValues)
        assertEquals("68.4", singleFallback.primary)
        assertFalse(singleFallback.noSensor)

        // Both unpaired marks noSensor = true so HUD / Double drops the slot.
        val bothUnpaired =
            TiresField.combineTireStates(
                FieldState.noSensor("Front Tire"),
                unpairedRear,
                beside = true,
                colorMode = ZoneColorMode.TEXT,
            )
        assertTrue(bothUnpaired.noSensor)
    }

    @Test
    fun `tire leak detector alerts on rapid drop and critical low band with cooldown`() {
        val cfg =
            TireAlertConfig(
                enabled = true,
                unit = TirePressureUnit.PSI,
                dropRatePsiPerMin = 2.0,
                frontTarget = 65.0,
                rearTarget = 68.0,
                bandPercent = 8.0,
            )
        val detector = TireLeakDetector(TirePosition.REAR, cfg)
        assertNull(detector.onSample(68.0, nowMs = 0L))
        // Drop of 2.5 PSI over 30s = 5.0 PSI/min >= 2.0 PSI/min -> RAPID_DROP.
        val rapid = detector.onSample(65.5, nowMs = 30_000L)
        assertNotNull(rapid)
        assertEquals(TireAlertKind.RAPID_DROP, rapid!!.kind)
        assertEquals("-2.5 PSI", rapid.detailText)

        // Cooldown suppresses another alert 30s later even at critical low pressure.
        assertNull(detector.onSample(45.0, nowMs = 60_000L))

        // After the 3-minute cooldown expires, critical red-band low pressure alerts.
        val low = detector.onSample(45.0, nowMs = 220_000L)
        assertNotNull(low)
        assertEquals(TireAlertKind.LOW_PRESSURE, low!!.kind)
    }
}
