package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.datatype.DoubleField
import com.jpweytjens.barberfish.datatype.ETAKind
import com.jpweytjens.barberfish.datatype.TimeKind
import com.jpweytjens.barberfish.datatype.shared.DoubleLayout
import com.jpweytjens.barberfish.datatype.shared.DoubleState
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.SlotState
import com.jpweytjens.barberfish.datatype.shared.VALUE_BITMAP_HEIGHT_RATIO
import com.jpweytjens.barberfish.datatype.shared.ViewSizeConfig
import com.jpweytjens.barberfish.datatype.shared.effectiveSlot
import com.jpweytjens.barberfish.datatype.shared.layout
import com.jpweytjens.barberfish.datatype.shared.slots
import com.jpweytjens.barberfish.datatype.shared.toDoubleSlotSizeConfig
import com.jpweytjens.barberfish.datatype.shared.visibleSlots
import com.jpweytjens.barberfish.datatype.shared.windSpeedApplies
import com.jpweytjens.barberfish.datatype.shared.withDoubleLabel
import com.jpweytjens.barberfish.extension.DataFieldDesignConfig
import com.jpweytjens.barberfish.extension.DoubleFieldConfig
import com.jpweytjens.barberfish.extension.DoubleFieldKind
import com.jpweytjens.barberfish.extension.DoubleLabel
import com.jpweytjens.barberfish.extension.DoubleOrientation
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import com.jpweytjens.barberfish.extension.PowerSmoothingStream
import com.jpweytjens.barberfish.extension.TimeConfig
import com.jpweytjens.barberfish.extension.WindLayout
import com.jpweytjens.barberfish.extension.ZoneColorMode
import com.jpweytjens.barberfish.extension.ZoneConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleFieldTest {

    // Same options as the DataStore's Json in Settings.kt.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val profile =
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

    private val live = SlotState(FieldState("142", "HR", FieldColor.Default), ZoneColorMode.TEXT)
    private val unpaired = SlotState(FieldState.noSensor("Power"), ZoneColorMode.TEXT)
    private val searching = SlotState(FieldState.searching("Power"), ZoneColorMode.TEXT)

    private fun double(
        first: SlotState,
        second: SlotState,
        orientation: DoubleOrientation = DoubleOrientation.HORIZONTAL,
        label: DoubleLabel = DoubleLabel.BOTH,
    ) = DoubleState(first, second, orientation, label)

    // Half-width cell on a five-row Karoo 3 page.
    private fun halfCell(textSize: Int = 46) =
        ViewConfig(
            gridSize = 30 to 12,
            viewSize = 238 to 126,
            textSize = textSize,
            alignment = ViewConfig.Alignment.RIGHT,
            boundariesEnabled = true,
            preview = false,
        )

    @Test
    fun `an unpaired slot drops out and the other takes the cell`() {
        assertEquals(listOf(1), visibleSlots(double(unpaired, live).slots))
        assertEquals(listOf(0), visibleSlots(double(live, unpaired).slots))
        assertEquals(DoubleLayout.FIELD, double(unpaired, live).layout())
        assertEquals(
            DoubleLayout.FIELD,
            double(live, unpaired, DoubleOrientation.VERTICAL).layout(),
        )
    }

    @Test
    fun `both unpaired keep both, searching keeps its slot`() {
        assertEquals(listOf(0, 1), visibleSlots(double(unpaired, unpaired).slots))
        assertEquals(DoubleLayout.FIELDS, double(unpaired, unpaired).layout())
        assertEquals(listOf(0, 1), visibleSlots(double(searching, live).slots))
    }

    @Test
    fun `orientation and label pick the layout`() {
        assertEquals(DoubleLayout.FIELDS, double(live, live).layout())
        assertEquals(DoubleLayout.ROWS, double(live, live, DoubleOrientation.VERTICAL).layout())
        assertEquals(
            DoubleLayout.ROWS,
            double(live, live, DoubleOrientation.VERTICAL, DoubleLabel.NONE).layout(),
        )
        assertEquals(
            DoubleLayout.VALUES,
            double(live, live, DoubleOrientation.HORIZONTAL, DoubleLabel.NONE).layout(),
        )
        // One slot left with no label: the value alone fills the cell.
        assertEquals(
            DoubleLayout.VALUES,
            double(live, unpaired, DoubleOrientation.VERTICAL, DoubleLabel.NONE).layout(),
        )
    }

    @Test
    fun `label modes name the value the way they say`() {
        val field = FieldState("250", "Power", FieldColor.Default, iconRes = 1)
        assertEquals("", field.withDoubleLabel(DoubleLabel.ICON).label)
        assertEquals(1, field.withDoubleLabel(DoubleLabel.ICON).iconRes)
        assertEquals(field, field.withDoubleLabel(DoubleLabel.BOTH))
        assertEquals(field, field.withDoubleLabel(DoubleLabel.HEADER))
        assertTrue(DoubleLabel.ICON.showsIcon && !DoubleLabel.ICON.showsText)
        assertTrue(!DoubleLabel.HEADER.showsIcon && DoubleLabel.HEADER.showsText)
        assertTrue(DoubleLabel.BOTH.showsIcon && DoubleLabel.BOTH.showsText)
        assertFalse(DoubleLabel.NONE.showsIcon || DoubleLabel.NONE.showsText)
    }

    @Test
    fun `side by side slots get half the width and the HUD's label size`() {
        val cfg =
            halfCell().toDoubleSlotSizeConfig(2, DoubleLabel.BOTH, DataFieldDesignConfig(), 1.875f)
        assertEquals(119f, cfg.cellWidthPxOverride!!, 0.01f)
        assertEquals(ViewSizeConfig.HUD_FOUR.headerFontSize, cfg.headerFontSize)
        assertTrue(cfg.showIcons)
        // Denser pages keep the HUD label size too, rather than a 2-col cell's.
        val sixRow =
            halfCell(38)
                .copy(gridSize = 30 to 10, viewSize = 238 to 104)
                .toDoubleSlotSizeConfig(2, DoubleLabel.HEADER, DataFieldDesignConfig(), 1.875f)
        assertEquals(ViewSizeConfig.HUD_FOUR.headerFontSize, sixRow.headerFontSize)
        assertFalse(sixRow.showIcons)
    }

    @Test
    fun `header and value fit the cell height`() {
        for ((rows, height, text) in
            listOf(Triple(15, 160, 50), Triple(12, 126, 46), Triple(10, 104, 38))) {
            for (density in listOf(1.5f, 1.875f)) {
                val config = halfCell(text).copy(gridSize = 30 to rows, viewSize = 238 to height)
                val cfg =
                    config.toDoubleSlotSizeConfig(
                        2,
                        DoubleLabel.BOTH,
                        DataFieldDesignConfig(),
                        density,
                    )
                val used =
                    cfg.headerMinHeightDp * density +
                        VALUE_BITMAP_HEIGHT_RATIO * cfg.valueFontSizeBase * density
                assertTrue("$rows rows at $density: $used > $height", used <= height)
                assertTrue(cfg.valueFontSizeBase <= text)
            }
        }
    }

    @Test
    fun `one slot left sizes as the whole cell`() {
        val cfg =
            halfCell().toDoubleSlotSizeConfig(1, DoubleLabel.BOTH, DataFieldDesignConfig(), 1.875f)
        assertEquals(238f, cfg.cellWidthPxOverride!!, 0.01f)
        assertEquals(30, cfg.colSpan)
    }

    @Test
    fun `six Doubles, each with its own key, type id and default slots`() {
        val kinds = DoubleFieldKind.entries
        assertEquals(6, kinds.size)
        assertEquals((1..6).map { "double-$it" }, kinds.map { it.typeId })
        assertEquals((1..6).map { "double_field_${it}_config" }, kinds.map { it.key.name })
        assertEquals((1..6).map { "Double $it" }, kinds.map { it.title })
        assertEquals(
            listOf(
                HUDSlotField.Power to HUDSlotField.HR,
                HUDSlotField.Speed to HUDSlotField.Cadence,
                HUDSlotField.AvgPower to HUDSlotField.AvgCadence,
                HUDSlotField.LapPower to HUDSlotField.LapAvgHR,
                HUDSlotField.NP to HUDSlotField.AvgHR,
                HUDSlotField.Grade to HUDSlotField.DistanceRemaining,
            ),
            kinds.map { it.default.first.field to it.default.second.field },
        )
        // No two Doubles start out showing the same pair.
        assertEquals(kinds.size, kinds.map { it.default }.toSet().size)
        assertTrue(
            kinds.all {
                it.default.orientation == DoubleOrientation.HORIZONTAL &&
                    it.default.label == DoubleLabel.BOTH
            }
        )
    }

    @Test
    fun `config round-trips through the stored JSON with its slot options`() {
        val config =
            DoubleFieldConfig(
                first =
                    HUDSlotConfig(
                        field = HUDSlotField.ETA(ETAKind.TIME_OF_ARRIVAL),
                        colorMode = ZoneColorMode.BACKGROUND,
                    ),
                second =
                    HUDSlotConfig(
                        field = HUDSlotField.Power,
                        powerSmoothing = PowerSmoothingStream.S10,
                    ),
                orientation = DoubleOrientation.VERTICAL,
                label = DoubleLabel.ICON,
            )
        val stored = json.encodeToString(DoubleFieldConfig.serializer(), config)
        assertEquals(config, json.decodeFromString(DoubleFieldConfig.serializer(), stored))
    }

    @Test
    fun `missing keys fall back to the defaults`() {
        val decoded = json.decodeFromString(DoubleFieldConfig.serializer(), """{"label":"NONE"}""")
        assertEquals(DoubleFieldConfig(label = DoubleLabel.NONE), decoded)
    }

    @Test
    fun `previews pair both slots moment by moment for every slot field`() {
        val fields =
            listOf(
                HUDSlotField.Power,
                HUDSlotField.LastLapAvgCadence,
                HUDSlotField.Grade,
                HUDSlotField.Wind,
                HUDSlotField.Time(TimeKind.TOTAL),
                HUDSlotField.ETA(ETAKind.TIME_OF_ARRIVAL),
                HUDSlotField.AvgSpeed(includePaused = true),
            )
        for (first in fields) {
            for (second in fields) {
                val states =
                    DoubleField.previewStates(
                        DoubleFieldConfig(
                            HUDSlotConfig(field = first),
                            HUDSlotConfig(field = second),
                        ),
                        TimeConfig(),
                        profile,
                        ZoneConfig(),
                    )
                assertTrue("$first + $second", states.isNotEmpty())
                assertTrue(states.all { it.layout() == DoubleLayout.FIELDS })
            }
        }
    }

    @Test
    fun `wind show speed applies only side by side with a label`() {
        for (orientation in DoubleOrientation.entries) {
            for (label in DoubleLabel.entries) {
                val config = DoubleFieldConfig(orientation = orientation, label = label)
                assertEquals(
                    "$orientation $label",
                    orientation == DoubleOrientation.HORIZONTAL && label != DoubleLabel.NONE,
                    config.windSpeedApplies,
                )
            }
        }
    }

    @Test
    fun `stacked or label-free Doubles draw wind without the speed, keeping the stored option`() {
        val wind =
            HUDSlotConfig(
                field = HUDSlotField.Wind,
                windShowSpeed = true,
                windLayout = WindLayout.HEADWIND,
            )
        val stacked =
            DoubleFieldConfig(first = wind, second = wind, orientation = DoubleOrientation.VERTICAL)
        assertFalse(stacked.effectiveSlot(wind).windShowSpeed)
        assertEquals(WindLayout.HEADWIND, stacked.effectiveSlot(wind).windLayout)
        assertTrue(stacked.first.windShowSpeed)
        val previews = DoubleField.previewStates(stacked, TimeConfig(), profile, ZoneConfig())
        assertTrue(
            previews.all { !it.first.field.headwindLayout && it.first.field.speedRow == null }
        )
        val sideBySide = stacked.copy(orientation = DoubleOrientation.HORIZONTAL)
        assertTrue(sideBySide.effectiveSlot(wind).windShowSpeed)
        val noLabel = sideBySide.copy(label = DoubleLabel.NONE)
        assertFalse(noLabel.effectiveSlot(wind).windShowSpeed)
    }
}
