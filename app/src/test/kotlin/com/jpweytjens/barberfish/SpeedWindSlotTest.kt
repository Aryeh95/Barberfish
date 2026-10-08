package com.jpweytjens.barberfish

import com.jpweytjens.barberfish.datatype.SpeedField
import com.jpweytjens.barberfish.datatype.SpeedReading
import com.jpweytjens.barberfish.datatype.WindField
import com.jpweytjens.barberfish.datatype.headwindStyleFactor
import com.jpweytjens.barberfish.datatype.headwindStyleState
import com.jpweytjens.barberfish.datatype.headwindSummary
import com.jpweytjens.barberfish.datatype.headwindTrend
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.HEADWIND_SPEED_PROBE
import com.jpweytjens.barberfish.datatype.shared.HEADWIND_SUMMARY_PROBE
import com.jpweytjens.barberfish.datatype.shared.HUDState
import com.jpweytjens.barberfish.datatype.shared.SPEED_WIND_ROW_GAP_PX
import com.jpweytjens.barberfish.datatype.shared.SlotState
import com.jpweytjens.barberfish.datatype.shared.WindArrowGeometry
import com.jpweytjens.barberfish.datatype.shared.fitText
import com.jpweytjens.barberfish.datatype.shared.headwindStyleGeometry
import com.jpweytjens.barberfish.datatype.shared.speedWindGeometry
import com.jpweytjens.barberfish.datatype.shared.visibleColumns
import com.jpweytjens.barberfish.datatype.shared.windSpeedFontPx
import com.jpweytjens.barberfish.datatype.speedWindState
import com.jpweytjens.barberfish.datatype.withHeadwindStates
import com.jpweytjens.barberfish.datatype.withSpeedPreview
import com.jpweytjens.barberfish.datatype.withSpeedStates
import com.jpweytjens.barberfish.extension.HUDConfig
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import com.jpweytjens.barberfish.extension.SpeedSmoothingStream
import com.jpweytjens.barberfish.extension.WindFieldConfig
import com.jpweytjens.barberfish.extension.WindLayout
import com.jpweytjens.barberfish.extension.ZoneColorMode
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedWindSlotTest {

    private val metric =
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
    private val cfg = WindFieldConfig(colorMode = ZoneColorMode.TEXT)

    private fun single(v: Double) =
        StreamState.Streaming(DataPoint("x", mapOf(DataType.Field.SINGLE to v)))

    private fun speed(kph: Double, smoothing: SpeedSmoothingStream = SpeedSmoothingStream.S0) =
        SpeedField.toFieldState(
            StreamState.Streaming(
                DataPoint(smoothing.typeId, mapOf(smoothing.fieldId to kph / 3.6))
            ),
            metric,
            smoothing,
        )

    private fun wind(angle: Double, headwind: Double, windSpeed: Double) =
        WindField.toFieldState(single(angle), single(headwind), single(windSpeed), metric, cfg)

    @Test
    fun both_live_stack_speed_over_the_wind() {
        val w = wind(225.0, 12.4, 15.0)
        val s = speedWindState(speed(28.4), w)
        assertEquals("12", s.primary)
        assertEquals("28.4", s.speedRow)
        assertEquals("Speed", s.label)
        assertEquals(R.drawable.ic_col_speed, s.iconRes)
        assertEquals(w.iconRes, s.secondaryIconRes)
        assertEquals(225f, s.windArrowDeg!!, 1e-6f)
        assertEquals("15", s.windSpeedRow)
        assertEquals(w.color, s.color)
        assertTrue((s.color as FieldColor.Threshold).factor < 0f)
        assertFalse(s.noSensor)
    }

    @Test
    fun tailwind_colours_green() {
        val s = speedWindState(speed(30.0), wind(0.0, -6.0, 6.0))
        assertEquals("-6", s.primary)
        assertTrue((s.color as FieldColor.Threshold).factor > 0f)
    }

    @Test
    fun calm_keeps_the_stack_without_an_arrow() {
        val s = speedWindState(speed(25.0), wind(90.0, 0.0, 1.0))
        assertNull(s.windArrowDeg)
        assertNotNull(s.speedRow)
        assertEquals("1", s.windSpeedRow)
    }

    @Test
    fun smoothed_speed_label_passes_through() {
        val s = speedWindState(speed(25.0, SpeedSmoothingStream.S3), wind(90.0, 3.0, 9.0))
        assertEquals("3s Speed", s.label)
    }

    @Test
    fun no_wind_data_falls_back_to_plain_speed_and_keeps_the_column() {
        val sp = speed(25.0)
        val s = speedWindState(sp, WindField.noWindData())
        assertEquals(sp, s)
        assertNull(s.windSpeedRow)
        assertFalse(s.noSensor)
        val live = SlotState(FieldState("142", "HR", FieldColor.Default), ZoneColorMode.TEXT)
        val hud = HUDState(3, live, SlotState(s, ZoneColorMode.TEXT), live, live, metric)
        assertEquals(listOf(0, 1, 2), hud.visibleColumns())
    }

    @Test
    fun wind_searching_falls_back_to_plain_speed() {
        val sp = speed(25.0)
        assertEquals(sp, speedWindState(sp, FieldState.searching("Wind")))
    }

    @Test
    fun speed_missing_shows_the_plain_wind() {
        val w = wind(225.0, 12.4, 15.0)
        assertEquals(w, speedWindState(FieldState.searching("Speed"), w))
    }

    @Test
    fun both_missing_show_the_speed_text_and_never_collapse() {
        val s = speedWindState(FieldState.searching("Speed"), WindField.noWindData())
        assertEquals("Searching…", s.primary)
        assertEquals("Speed", s.label)
        assertFalse(s.noSensor)
    }

    @Test
    fun speed_shows_at_once_while_headwind_is_silent() = runBlocking {
        val sp = speed(25.0)
        val first = emptyFlow<FieldState>().withSpeedStates(flowOf(sp)).first()
        assertEquals(sp, first)
    }

    @Test
    fun stack_follows_both_flows() = runBlocking {
        val w = wind(180.0, 10.0, 10.0)
        val states = flowOf(w).withSpeedStates(flowOf(speed(20.0))).toList()
        assertEquals("20.0", states.last().speedRow)
        assertEquals("10", states.last().primary)
    }

    @Test
    fun preview_unchanged_when_off() {
        val frames = WindField.previewStates(cfg)
        assertEquals(
            frames,
            frames.withSpeedPreview(HUDSlotConfig(field = HUDSlotField.Wind), metric),
        )
    }

    @Test
    fun preview_stacks_every_wind_frame_when_on() {
        val frames = WindField.previewStates(cfg)
        val slot = HUDSlotConfig(field = HUDSlotField.Wind, windShowSpeed = true)
        val stacked = frames.withSpeedPreview(slot, metric)
        assertEquals(frames.size, stacked.size)
        assertTrue(stacked.all { it.speedRow != null })
        assertEquals(listOf("1", "6", "14", "15", "29"), stacked.map { it.windSpeedRow })
        assertNull(stacked.first().windArrowDeg) // the calm frame
    }

    @Test
    fun geometry_on_a_karoo_3_hud_slot() {
        val g = speedWindGeometry(bitmapHeightPx = 60, density = 1.875f)
        assertEquals(28f, g.bandPx, 1e-6f)
        assertEquals(28, g.boxPx)
        assertEquals(7, g.gapPx)
        assertEquals(35, g.textLeftPx)
        assertEquals(4f, SPEED_WIND_ROW_GAP_PX, 0f)
        assertTrue(WindArrowGeometry.sweepRadiusPx(g.boxPx.toFloat()) <= g.bandPx / 2f)
    }

    @Test
    fun arrow_box_never_exceeds_a_band() {
        for (h in listOf(40, 60, 67, 90, 130)) {
            val g = speedWindGeometry(h, 1.875f)
            assertTrue(g.boxPx <= g.bandPx)
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun settings_without_the_option_read_as_off() {
        val stored = json.encodeToString(HUDConfig()).replace(",\"windShowSpeed\":false", "")
        assertFalse(stored.contains("windShowSpeed"))
        val cfg = json.decodeFromString<HUDConfig>(stored)
        assertFalse(cfg.leftSlot.windShowSpeed)
    }

    @Test
    fun the_option_round_trips() {
        val on =
            HUDConfig(leftSlot = HUDSlotConfig(field = HUDSlotField.Wind, windShowSpeed = true))
        assertTrue(json.decodeFromString<HUDConfig>(json.encodeToString(on)).leftSlot.windShowSpeed)
    }

    @Test
    fun wind_speed_rounds_like_the_headwind_number() {
        assertEquals("15", wind(225.0, 12.4, 14.6).windSpeedRow)
        assertEquals("0", wind(90.0, 0.0, 0.3).windSpeedRow)
    }

    @Test
    fun wind_speed_font_shrinks_only_to_fit_the_box() {
        assertEquals(33f, windSpeedFontPx(33f, probeWidthPx = 20f, boxPx = 28), 1e-6f)
        assertEquals(33f * 28f / 40f, windSpeedFontPx(33f, probeWidthPx = 40f, boxPx = 28), 1e-4f)
        assertTrue(windSpeedFontPx(33f, probeWidthPx = 100f, boxPx = 28) < 33f)
    }

    private val headwindSlot =
        HUDSlotConfig(
            field = HUDSlotField.Wind,
            windShowSpeed = true,
            windLayout = WindLayout.HEADWIND,
        )

    @Test
    fun headwind_summary_matches_headwind() {
        assertEquals("+9▼9", headwindSummary(-9, "▼", "9"))
        assertEquals("-6▲12", headwindSummary(6, "▲", "12"))
        assertEquals("0≈5", headwindSummary(0, "≈", "5"))
    }

    @Test
    fun headwind_trend_against_the_average() {
        assertEquals("▲", headwindTrend(9.0, 8.0))
        assertEquals("▼", headwindTrend(7.0, 8.0))
        assertEquals("≈", headwindTrend(8.0, 8.01))
        assertEquals(" ", headwindTrend(8.0, null))
    }

    @Test
    fun headwind_colour_ramp() {
        assertEquals(0f, headwindStyleFactor(0.0), 1e-6f)
        assertEquals(-1f, headwindStyleFactor(15.0), 1e-6f)
        assertEquals(1f, headwindStyleFactor(-10.0), 1e-6f)
        assertEquals(0.5f, headwindStyleFactor(-5.0), 1e-6f)
    }

    @Test
    fun headwind_layout_state_when_both_live() {
        val w = wind(180.0, 10.0, 14.0)
        val s =
            headwindStyleState(
                speed(14.0),
                14.0 / 3.6,
                16.0 / 3.6,
                w,
                ZoneColorMode.TEXT,
                true,
                metric,
            )
        assertEquals("14.0", s.primary)
        assertEquals("-10▼14", s.secondary)
        assertTrue(s.headwindLayout)
        assertFalse(s.hideHeader)
        assertEquals(180f, s.windArrowDeg!!, 1e-6f)
        assertTrue((s.color as FieldColor.Threshold).factor < 0f)
        assertNull(s.speedRow)
    }

    @Test
    fun headwind_layout_header_off_and_no_color() {
        val s =
            headwindStyleState(
                speed(20.0),
                null,
                null,
                wind(0.0, -6.0, 6.0),
                ZoneColorMode.NONE,
                false,
                metric,
            )
        assertTrue(s.hideHeader)
        assertEquals(FieldColor.Default, s.color)
        assertEquals("+6 6", s.secondary)
    }

    @Test
    fun headwind_layout_without_wind_keeps_its_look_and_header_choice() {
        val s =
            headwindStyleState(
                speed(25.0),
                7.0,
                7.0,
                WindField.noWindData(),
                ZoneColorMode.TEXT,
                false,
                metric,
            )
        assertEquals("25.0", s.primary)
        assertEquals("", s.secondary)
        assertTrue(s.headwindLayout)
        assertTrue(s.hideHeader)
        assertNull(s.windArrowDeg)
        assertEquals(FieldColor.Default, s.color)
        assertFalse(s.noSensor)
    }

    @Test
    fun headwind_layout_without_speed_shows_the_wind_with_the_header_choice() {
        val w = wind(225.0, 12.4, 15.0)
        val s =
            headwindStyleState(
                FieldState.searching("Speed"),
                null,
                null,
                w,
                ZoneColorMode.TEXT,
                false,
                metric,
            )
        assertEquals(w.copy(hideHeader = true), s)
    }

    @Test
    fun headwind_layout_with_neither_shows_the_speed_text() {
        val s =
            headwindStyleState(
                FieldState.searching("Speed"),
                null,
                null,
                WindField.noWindData(),
                ZoneColorMode.TEXT,
                false,
                metric,
            )
        assertEquals("Searching…", s.primary)
        assertFalse(s.hideHeader)
    }

    @Test
    fun fit_text_uses_the_wider_of_text_and_probe() {
        assertEquals(HEADWIND_SPEED_PROBE, fitText("9.8", HEADWIND_SPEED_PROBE))
        assertEquals("100.5", fitText("100.5", HEADWIND_SPEED_PROBE))
        assertEquals(HEADWIND_SUMMARY_PROBE, fitText("+9▼9", HEADWIND_SUMMARY_PROBE))
    }

    @Test
    fun headwind_layout_preview_frames() {
        val frames = WindField.previewStates(cfg).withSpeedPreview(headwindSlot, metric)
        assertEquals(5, frames.size)
        assertTrue(frames.all { it.headwindLayout && it.secondary != null })
    }

    @Test
    fun headwind_live_flow_shows_speed_before_headwind() = runBlocking {
        val sp = speed(25.0)
        val first =
            emptyFlow<FieldState>()
                .withHeadwindStates(
                    flowOf(SpeedReading(sp, 7.0)),
                    emptyFlow(),
                    headwindSlot,
                    metric,
                )
                .first()
        assertEquals("25.0", first.primary)
        assertTrue(first.headwindLayout)
        assertEquals("", first.secondary)
    }

    @Test
    fun headwind_geometry_with_and_without_the_icon() {
        val plain = headwindStyleGeometry(108, 0f)
        assertEquals(108f, plain.arrowPx, 1e-4f)
        assertEquals(0f, plain.arrowTopPx, 1e-6f)
        val reserved = headwindStyleGeometry(108, 30f)
        assertEquals(30f, reserved.arrowTopPx, 1e-6f)
        assertEquals(108f, reserved.arrowTopPx + reserved.arrowPx, 1e-4f)
        assertEquals(108f, reserved.speedBandPx + reserved.summaryBandPx, 1e-4f)
        // The reserve never eats more than half the height.
        assertEquals(54f, headwindStyleGeometry(108, 90f).arrowTopPx, 1e-6f)
    }

    @Test
    fun layout_settings_round_trip_and_default() {
        val stored =
            json.encodeToString(HUDConfig(leftSlot = headwindSlot.copy(windShowHeader = false)))
        val back = json.decodeFromString<HUDConfig>(stored).leftSlot
        assertEquals(WindLayout.HEADWIND, back.windLayout)
        assertFalse(back.windShowHeader)
        val old =
            json.decodeFromString<HUDConfig>(
                json.encodeToString(HUDConfig()).replace(",\"windLayout\":\"BARBERFISH\"", "")
            )
        assertEquals(WindLayout.BARBERFISH, old.leftSlot.windLayout)
    }
}
