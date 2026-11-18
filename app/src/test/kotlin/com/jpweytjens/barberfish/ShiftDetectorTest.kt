package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.extension.CassetteEnd
import com.jpweytjens.barberfish.extension.DrivetrainBattery
import com.jpweytjens.barberfish.extension.GearReading
import com.jpweytjens.barberfish.extension.ShiftDetector
import com.jpweytjens.barberfish.extension.ShiftGearText
import com.jpweytjens.barberfish.extension.ShiftOverlayAlign
import com.jpweytjens.barberfish.extension.ShiftOverlayConfig
import com.jpweytjens.barberfish.extension.ShiftOverlayPosition
import com.jpweytjens.barberfish.extension.ShiftTrigger
import com.jpweytjens.barberfish.extension.overlayOrigin
import io.hammerhead.karooext.models.BatteryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftDetectorTest {

    // A 2x12 SRAM AXS road drivetrain: 37/50, 10-33. Gear 1 is the 33, gear 12 the 10.
    private val teeth = listOf(33, 28, 24, 21, 19, 17, 15, 14, 13, 12, 11, 10)

    private fun axs(rear: Int, front: Int = 2) =
        GearReading(
            front = front,
            frontCount = 2,
            frontTeeth = if (front == 1) 37 else 50,
            rear = rear,
            rearCount = 12,
            rearTeeth = teeth[rear - 1],
        )

    @Test
    fun `the first reading after connecting is not a shift`() {
        val detector = ShiftDetector(everyShift = false)
        assertNull(detector.onReading(axs(12)))
    }

    @Test
    fun `shifting onto the smallest and the largest cog alerts`() {
        val detector = ShiftDetector(everyShift = false)
        detector.onReading(axs(11))
        assertEquals(CassetteEnd.SMALLEST, detector.onReading(axs(12))?.end)
        detector.onReading(axs(2))
        assertEquals(CassetteEnd.LARGEST, detector.onReading(axs(1))?.end)
    }

    @Test
    fun `staying on the end cog or shifting the front does not repeat the alert`() {
        val detector = ShiftDetector(everyShift = false)
        detector.onReading(axs(11))
        detector.onReading(axs(12))
        assertNull(detector.onReading(axs(12)))
        assertNull(detector.onReading(axs(12, front = 1)))
    }

    @Test
    fun `cogs between the ends do not alert unless every shift is on`() {
        val endsOnly = ShiftDetector(everyShift = false)
        endsOnly.onReading(axs(5))
        assertNull(endsOnly.onReading(axs(6)))

        val every = ShiftDetector(everyShift = true)
        every.onReading(axs(5))
        val shift = every.onReading(axs(6))
        assertEquals(ShiftDetector.Shift(null), shift)
        assertEquals(ShiftDetector.Shift(null), every.onReading(axs(6, front = 1)))
    }

    @Test
    fun `teeth decide the end when a groupset numbers from the smallest cog`() {
        val detector = ShiftDetector(everyShift = false)
        detector.onReading(GearReading(rear = 2, rearCount = 12, rearTeeth = 11))
        val end = detector.onReading(GearReading(rear = 1, rearCount = 12, rearTeeth = 10))?.end
        assertEquals(CassetteEnd.SMALLEST, end)
    }

    @Test
    fun `without a gear count only the largest cog is recognised`() {
        val detector = ShiftDetector(everyShift = false)
        detector.onReading(GearReading(rear = 2))
        assertEquals(CassetteEnd.LARGEST, detector.onReading(GearReading(rear = 1))?.end)
        detector.onReading(GearReading(rear = 11))
        assertNull(detector.onReading(GearReading(rear = 12)))
    }

    @Test
    fun `a dropped groupset starts over`() {
        val detector = ShiftDetector(everyShift = false)
        detector.onReading(axs(11))
        detector.onReading(GearReading())
        assertNull(detector.onReading(axs(12)))
    }

    @Test
    fun `the gears are written in teeth or as gear numbers`() {
        val reading = axs(12)
        assertEquals("F50 - R10", reading.label(ShiftGearText.TEETH))
        assertEquals("F2 - R12", reading.label(ShiftGearText.NUMBERS))
        assertEquals("R10", reading.label(ShiftGearText.TEETH, withFront = false))
        assertNull(reading.label(ShiftGearText.OFF))
        assertEquals(5.0, reading.ratio!!, 1e-9)
    }

    @Test
    fun `teeth fall back to gear numbers when the groupset does not report sizes`() {
        assertEquals("F2 - R12", GearReading(front = 2, rear = 12).label(ShiftGearText.TEETH))
        assertNull(GearReading().label(ShiftGearText.TEETH))
    }

    @Test
    fun `front derailleur connecting or dropping out mid-ride is not a shift`() {
        val every = ShiftDetector(ShiftTrigger.EVERY_SHIFT)
        // Rear connects first while front is still asleep (null).
        assertNull(
            every.onReading(GearReading(front = null, rear = 6, rearCount = 12, rearTeeth = 17))
        )
        // Front wakes up on ring 2 without any rear shift -> must NOT fire a phantom shift.
        assertNull(every.onReading(axs(6, front = 2)))
        // Front temporarily drops out -> must NOT fire a phantom shift.
        assertNull(
            every.onReading(GearReading(front = null, rear = 6, rearCount = 12, rearTeeth = 17))
        )
        // Front reconnects on ring 2 -> still not a shift.
        assertNull(every.onReading(axs(6, front = 2)))
        // Actual front shift 2 -> 1 DOES fire in EVERY_SHIFT mode.
        assertEquals(ShiftDetector.Shift(null), every.onReading(axs(6, front = 1)))
    }

    @Test
    fun `cross-chain detection flags big-big and small-small on multi-ring setups`() {
        assertTrue(axs(rear = 2, front = 2).isCrossChained()) // big ring + 2nd largest cog
        assertTrue(axs(rear = 11, front = 1).isCrossChained()) // small ring + 2nd smallest cog
        assertFalse(axs(rear = 6, front = 2).isCrossChained()) // middle of cassette
        // 1x drivetrain never flags cross-chaining.
        assertFalse(
            GearReading(front = 1, frontCount = 1, rear = 2, rearCount = 12, rearTeeth = 28)
                .isCrossChained()
        )

        val detector = ShiftDetector(ShiftTrigger.ENDS_AND_CROSS)
        detector.onReading(axs(rear = 4, front = 2))
        assertNull(detector.onReading(axs(rear = 3, front = 2)))
        assertEquals(CassetteEnd.CROSS_CHAIN, detector.onReading(axs(rear = 2, front = 2))?.end)
        // Shifting further onto cog 1 prioritizes LARGEST_COG over CROSS_CHAIN.
        assertEquals(CassetteEnd.LARGEST, detector.onReading(axs(rear = 1, front = 2))?.end)
    }

    @Test
    fun `low drivetrain battery exposes badge and triggers one-time shift popup`() {
        val lowRear = DrivetrainBattery(front = BatteryStatus.OK, rear = BatteryStatus.LOW)
        assertTrue(lowRear.isLowOrCritical)
        assertFalse(lowRear.isCritical)
        assertEquals("R BATT LOW", lowRear.badgeText)

        val critFront = DrivetrainBattery(front = BatteryStatus.CRITICAL, rear = BatteryStatus.LOW)
        assertTrue(critFront.isCritical)
        assertEquals("F BATT CRIT", critFront.badgeText)

        val detector = ShiftDetector(ShiftTrigger.END_COGS, warnLowBattery = true)
        detector.onReading(axs(5), lowRear)
        // First non-end shift while battery is low triggers a one-time popup.
        assertEquals(ShiftDetector.Shift(null), detector.onReading(axs(6), lowRear))
        // Subsequent non-end shifts do not repeat the popup.
        assertNull(detector.onReading(axs(7), lowRear))
        // Once battery is replaced (OK), the one-time warning resets for future low-battery states.
        assertNull(detector.onReading(axs(8), DrivetrainBattery(rear = BatteryStatus.NEW)))
        assertEquals(ShiftDetector.Shift(null), detector.onReading(axs(9), lowRear))
    }
}

class ShiftOverlayPlacementTest {

    private fun origin(config: ShiftOverlayConfig) =
        overlayOrigin(
            screen = 480 to 800,
            panel = 200 to 100,
            margin = 6,
            config = config,
        )

    @Test
    fun `the height runs from the top edge to the bottom edge`() {
        assertEquals(140 to 0, origin(ShiftOverlayConfig(heightPercent = 0)))
        assertEquals(140 to 350, origin(ShiftOverlayConfig(heightPercent = 50)))
        assertEquals(140 to 700, origin(ShiftOverlayConfig(heightPercent = 100)))
    }

    @Test
    fun `a narrow overlay keeps to the side it is aligned to`() {
        val top = ShiftOverlayConfig(heightPercent = 0)
        assertEquals(6 to 0, origin(top.copy(align = ShiftOverlayAlign.LEFT)))
        assertEquals(274 to 0, origin(top.copy(align = ShiftOverlayAlign.RIGHT)))
    }

    @Test
    fun `settings from 4_0_2 keep their place`() {
        assertEquals(5, ShiftOverlayConfig(position = ShiftOverlayPosition.TOP).height)
        assertEquals(50, ShiftOverlayConfig(position = ShiftOverlayPosition.MIDDLE).height)
        assertEquals(95, ShiftOverlayConfig(position = ShiftOverlayPosition.BOTTOM).height)
        val moved = ShiftOverlayConfig(position = ShiftOverlayPosition.BOTTOM, heightPercent = 20)
        assertEquals(20, moved.height)
    }
}
