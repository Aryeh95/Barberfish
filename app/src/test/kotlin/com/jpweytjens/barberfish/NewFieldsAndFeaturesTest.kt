package com.jpweytjens.barberfish

import androidx.compose.ui.graphics.Color
import com.jpweytjens.barberfish.datatype.GearsField
import com.jpweytjens.barberfish.datatype.HUDSlotSource
import com.jpweytjens.barberfish.datatype.SpeedField
import com.jpweytjens.barberfish.datatype.VamField
import com.jpweytjens.barberfish.datatype.WindField
import com.jpweytjens.barberfish.datatype.WkgField
import com.jpweytjens.barberfish.datatype.climbSwapReducer
import com.jpweytjens.barberfish.datatype.isOnRouteClimbSegment
import com.jpweytjens.barberfish.datatype.shared.ClimbCacheHolder
import com.jpweytjens.barberfish.datatype.shared.DrivetrainAlert
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.GradeReading
import com.jpweytjens.barberfish.datatype.shared.apcaContrast
import com.jpweytjens.barberfish.datatype.shared.bestTextOnBackground
import com.jpweytjens.barberfish.datatype.shared.effectiveLookaheadMInt
import com.jpweytjens.barberfish.datatype.shared.effectiveScalesM
import com.jpweytjens.barberfish.datatype.shared.formatLookaheadPill
import com.jpweytjens.barberfish.datatype.shared.formatLookaheadScalesInput
import com.jpweytjens.barberfish.datatype.shared.formatLookaheadTransition
import com.jpweytjens.barberfish.datatype.shared.parseLookaheadScalesInput
import com.jpweytjens.barberfish.datatype.shared.toBackgroundColor
import com.jpweytjens.barberfish.datatype.shared.toColor
import com.jpweytjens.barberfish.datatype.shared.withCustomScalesM
import com.jpweytjens.barberfish.datatype.shared.withNextLookahead
import com.jpweytjens.barberfish.datatype.toSpeedFieldConfig
import com.jpweytjens.barberfish.extension.GearDisplayFormat
import com.jpweytjens.barberfish.extension.GearsFieldConfig
import com.jpweytjens.barberfish.extension.GradePalette
import com.jpweytjens.barberfish.extension.HUDSlotConfig
import com.jpweytjens.barberfish.extension.HUDSlotField
import com.jpweytjens.barberfish.extension.SparklineConfig
import com.jpweytjens.barberfish.extension.SpeedThresholdSource
import com.jpweytjens.barberfish.extension.TimeConfig
import com.jpweytjens.barberfish.extension.VamFieldConfig
import com.jpweytjens.barberfish.extension.VamWindow
import com.jpweytjens.barberfish.extension.WindFieldConfig
import com.jpweytjens.barberfish.extension.WkgFieldConfig
import com.jpweytjens.barberfish.extension.WkgSource
import com.jpweytjens.barberfish.extension.ZoneColorMode
import com.jpweytjens.barberfish.extension.ZoneConfig
import com.jpweytjens.barberfish.extension.decodeSettingsBackup
import com.jpweytjens.barberfish.extension.encodeSettingsBackup
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState.Climb
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFieldsAndFeaturesTest {

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
            powerZones =
                listOf(137, 187, 225, 262, 300, 375, 2000).map {
                    UserProfile.Zone(min = 0, max = it)
                },
        )

    private val imperialProfile =
        metricProfile.copy(
            preferredUnit =
                UserProfile.PreferredUnit(
                    distance = UserProfile.PreferredUnit.UnitType.IMPERIAL,
                    elevation = UserProfile.PreferredUnit.UnitType.IMPERIAL,
                    temperature = UserProfile.PreferredUnit.UnitType.IMPERIAL,
                    weight = UserProfile.PreferredUnit.UnitType.IMPERIAL,
                )
        )

    private val zones = ZoneConfig()

    @Test
    fun `WkgField divides watts by rider weight and colors by power zone`() {
        val powerState =
            StreamState.Streaming(
                DataPoint(
                    DataType.Type.SMOOTHED_3S_AVERAGE_POWER,
                    mapOf(DataType.Field.SMOOTHED_3S_AVERAGE_POWER to 280.0),
                )
            )
        val state =
            WkgField.toFieldState(
                powerState,
                WkgFieldConfig(source = WkgSource.S3, colorMode = ZoneColorMode.TEXT),
                metricProfile,
                zones,
            )
        assertEquals("4.00", state.primary)
        assertEquals("3s W/kg", state.label)
        assertEquals(5, (state.color as FieldColor.Zone).zone)

        // NP Idle shows "Needs 30s"; Last Lap on lap 1 shows "No laps yet".
        val npIdle =
            WkgField.toFieldState(
                StreamState.Idle,
                WkgFieldConfig(source = WkgSource.NP),
                metricProfile,
                zones,
            )
        assertEquals("Needs 30s power data", npIdle.primary)

        val firstLap =
            WkgField.toFieldState(
                powerState,
                WkgFieldConfig(source = WkgSource.LAST_LAP),
                metricProfile,
                zones,
                lapNumber = 1,
            )
        assertEquals("No laps yet", firstLap.primary)
    }

    @Test
    fun `VamField formats vertical speed in m per h or ft per h with grade coloring`() {
        val vamStream =
            StreamState.Streaming(
                DataPoint(
                    VamWindow.S30.typeId,
                    mapOf(DataType.Field.VERTICAL_SPEED to 1000.0),
                )
            )
        val cfg = VamFieldConfig(window = VamWindow.S30, colorMode = ZoneColorMode.TEXT)
        val metric =
            VamField.toFieldState(
                vamStream,
                GradeReading.Fresh(8.5f),
                cfg,
                metricProfile,
                GradePalette.BARBERFISH,
            )
        assertEquals("1000", metric.primary)
        assertEquals("30s VAM", metric.label)
        assertTrue(metric.color is FieldColor.Grade)

        val imperial =
            VamField.toFieldState(
                vamStream,
                GradeReading.Fresh(8.5f),
                cfg,
                imperialProfile,
                GradePalette.BARBERFISH,
            )
        assertEquals("3281", imperial.primary)

        // Idle reads "0" before climbing begins.
        val idle =
            VamField.toFieldState(
                StreamState.Idle,
                GradeReading.Unavailable,
                cfg,
                metricProfile,
                GradePalette.BARBERFISH,
            )
        assertEquals("0", idle.primary)
    }

    @Test
    fun `GearsField formats teeth, numbers, and ratio with drivetrain alert coloring`() {
        val front =
            StreamState.Streaming(
                DataPoint(
                    DataType.Type.SHIFTING_FRONT_GEAR,
                    mapOf(
                        DataType.Field.SHIFTING_FRONT_GEAR to 2.0,
                        DataType.Field.SHIFTING_FRONT_GEAR_MAX to 2.0,
                        DataType.Field.SHIFTING_FRONT_GEAR_TEETH to 50.0,
                    ),
                )
            )
        val rearSmallest =
            StreamState.Streaming(
                DataPoint(
                    DataType.Type.SHIFTING_REAR_GEAR,
                    mapOf(
                        DataType.Field.SHIFTING_REAR_GEAR to 12.0,
                        DataType.Field.SHIFTING_REAR_GEAR_MAX to 12.0,
                        DataType.Field.SHIFTING_REAR_GEAR_TEETH to 10.0,
                    ),
                )
            )
        val teeth = GearsField.toFieldState(front, rearSmallest, GearsFieldConfig())
        assertEquals("50-10", teeth.primary)
        assertEquals(FieldColor.Drivetrain(DrivetrainAlert.SMALLEST_COG), teeth.color)

        val numbers =
            GearsField.toFieldState(
                front,
                rearSmallest,
                GearsFieldConfig(format = GearDisplayFormat.NUMBERS),
            )
        assertEquals("2-12", numbers.primary)

        val ratio =
            GearsField.toFieldState(
                front,
                rearSmallest,
                GearsFieldConfig(format = GearDisplayFormat.RATIO),
            )
        assertEquals("5.00", ratio.primary)

        val rearOnly =
            GearsField.toFieldState(front, rearSmallest, GearsFieldConfig(showChainrings = false))
        assertEquals("10", rearOnly.primary)

        // Unpaired groupset marks noSensor = true so HUD/Double drops the slot.
        val unpaired =
            GearsField.toFieldState(
                StreamState.NotAvailable,
                StreamState.NotAvailable,
                GearsFieldConfig(),
            )
        assertTrue(unpaired.noSensor)
    }

    @Test
    fun `Drivetrain alert colors meet APCA contrast in dark, light, and fill modes`() {
        val black = Color(0xFF000000)
        val white = Color(0xFFFFFFFF)
        for (alert in DrivetrainAlert.entries) {
            val fieldColor = FieldColor.Drivetrain(alert)
            val darkText = fieldColor.toColor(isNightMode = true)!!
            val lightText = fieldColor.toColor(isNightMode = false)!!
            val fill = fieldColor.toBackgroundColor()!!
            val onFill = bestTextOnBackground(fill)
            assertTrue(
                "$alert dark Lc",
                abs(apcaContrast(darkText, black)) >= 45.0,
            )
            assertTrue(
                "$alert light Lc",
                abs(apcaContrast(lightText, white)) >= 45.0,
            )
            assertTrue(
                "$alert fill Lc",
                abs(apcaContrast(onFill, fill)) >= 45.0,
            )
        }
    }

    @Test
    fun `ClimbSwap stays active across Karoo active-climb drop and obeys grade hysteresis`() {
        val cache = ClimbCacheHolder()
        val climb =
            Climb(startDistance = 2_000.0, length = 1_500.0, grade = 6.5, totalElevation = 98.0)
        fun nav(climbs: List<Climb>) =
            NavigationState.NavigatingRoute(
                routePolyline = "abcde",
                routeDistance = 10_000.0,
                rejoinPolyline = null,
                rejoinDistance = null,
                name = "Test Route",
                reversed = false,
                breadcrumb = false,
                pois = emptyList(),
                climbs = climbs,
            )

        // Approaching the climb at 1_500 m (8_500 m remaining): inventory cached, not yet on climb.
        assertFalse(
            isOnRouteClimbSegment(
                nav(listOf(climb)),
                8_500.0,
                onRouteConfirmed = true,
                climbCache = cache,
            )
        )

        // Entering the climb at 2_500 m (7_500 m remaining): Karoo OS drops the active climb from
        // route.climbs, yet ClimbCacheHolder preserves it so climb-swap remains active!
        assertTrue(
            isOnRouteClimbSegment(
                nav(emptyList()),
                7_500.0,
                onRouteConfirmed = true,
                climbCache = cache,
            )
        )

        // Off-route disables route-climb override.
        assertFalse(
            isOnRouteClimbSegment(
                nav(emptyList()),
                7_500.0,
                onRouteConfirmed = false,
                climbCache = cache,
            )
        )

        // Unrouted grade hysteresis: 4.0% turns on, stays on down to 2.5%, turns off below 2.5%.
        var climbing = false
        climbing = climbSwapReducer(climbing, onRouteClimb = false, GradeReading.Fresh(3.9f))
        assertFalse(climbing)
        climbing = climbSwapReducer(climbing, onRouteClimb = false, GradeReading.Fresh(4.0f))
        assertTrue(climbing)
        climbing = climbSwapReducer(climbing, onRouteClimb = false, GradeReading.Fresh(2.8f))
        assertTrue(climbing)
        climbing = climbSwapReducer(climbing, onRouteClimb = false, GradeReading.Fresh(2.4f))
        assertFalse(climbing)

        // Preview states swap Speed -> VAM on steep climb moments and keep Speed on flat moments.
        val swapSlot = HUDSlotConfig(field = HUDSlotField.Speed, climbSwapField = HUDSlotField.Vam)
        val previews = HUDSlotSource.previewStates(swapSlot, TimeConfig(), metricProfile, zones)
        assertEquals("Speed", previews[0].label) // flat moment (0.3% grade)
        assertEquals("30s VAM", previews[4].label) // steep climb moment (13.0% grade)
    }

    @Test
    fun `Speed slot threshold config and Imperial Wind previews match expectations`() {
        val speedSlot =
            HUDSlotConfig(
                field = HUDSlotField.Speed,
                colorMode = ZoneColorMode.TEXT,
                speedSource = SpeedThresholdSource.FIXED,
                speedThresholdKph = 30.0,
                speedRangeBelow = 5.0,
                speedRangeAbove = 5.0,
            )
        val speedPreviews = SpeedField.previewStates(speedSlot.toSpeedFieldConfig(), metricProfile)
        assertTrue(speedPreviews.any { it.color is FieldColor.Threshold })

        val metricWind = WindField.previewStates(WindFieldConfig(), metricProfile)
        val imperialWind = WindField.previewStates(WindFieldConfig(), imperialProfile)
        // Strongest headwind sample (index 4: 29 km/h) scales down in mph (~18 mph).
        assertNotEquals(metricWind[4].primary, imperialWind[4].primary)
    }

    @Test
    fun `SettingsBackup encodes and decodes preferences cleanly and rejects unsupported versions`() {
        val original =
            mapOf(
                "hud_config" to """{"columns":4}""",
                "tire_alert_config" to """{"enabled":true,"frontTarget":65.0}""",
            )
        val encoded = encodeSettingsBackup(original)
        assertEquals(original, decodeSettingsBackup(encoded))

        val futurePayload = """{"version":99,"preferences":{}}"""
        val failed = runCatching { decodeSettingsBackup(futurePayload) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun `custom HUD sparkline tap-cycle scales support imperial and metric lists and cycle cleanly`() {
        // Imperial: "1, 3, 5, 10 mi"
        val imperialScales = parseLookaheadScalesInput("1, 3, 5, 10 mi", imperialProfile)
        assertEquals(listOf(1609, 4828, 8047, 16093), imperialScales)
        assertEquals("1, 3, 5, 10", formatLookaheadScalesInput(imperialScales, imperialProfile))

        var cfg = SparklineConfig().withCustomScalesM(imperialScales)
        assertEquals(1609, cfg.effectiveLookaheadMInt())
        assertEquals(
            "1 mi",
            formatLookaheadPill(cfg.effectiveLookaheadMInt(), imperialProfile, true),
        )
        assertEquals(
            "1mi",
            formatLookaheadTransition(cfg.effectiveLookaheadMInt(), imperialProfile, true),
        )

        // Tap cycles 1 -> 3 -> 5 -> 10 -> 1 mi.
        val cycledTransitions = mutableListOf<String>()
        repeat(4) {
            cfg = cfg.withNextLookahead()
            cycledTransitions +=
                formatLookaheadTransition(cfg.effectiveLookaheadMInt(), imperialProfile, true)
        }
        assertEquals(listOf("3mi", "5mi", "10mi", "1mi"), cycledTransitions)

        // Metric: "1, 2, 5, 8"
        val metricScales = parseLookaheadScalesInput("1,2,5,8", metricProfile)
        assertEquals(listOf(1000, 2000, 5000, 8000), metricScales)
        var metricCfg = SparklineConfig().withCustomScalesM(metricScales)
        // Default SparklineConfig started at 5000 m, which is in [1000, 2000, 5000, 8000], so 5 km
        // stays selected!
        assertEquals(5000, metricCfg.effectiveLookaheadMInt())
        metricCfg = metricCfg.withNextLookahead()
        assertEquals(
            "8km",
            formatLookaheadTransition(metricCfg.effectiveLookaheadMInt(), metricProfile, true),
        )
        metricCfg = metricCfg.withNextLookahead()
        assertEquals(
            "1km",
            formatLookaheadTransition(metricCfg.effectiveLookaheadMInt(), metricProfile, true),
        )

        // Empty input resets to default [5000, 10000, 20000] m.
        val reset = metricCfg.withCustomScalesM(parseLookaheadScalesInput("", metricProfile))
        assertEquals(listOf(5_000, 10_000, 20_000), reset.effectiveScalesM())
        assertEquals(5_000, reset.effectiveLookaheadMInt())
    }
}
