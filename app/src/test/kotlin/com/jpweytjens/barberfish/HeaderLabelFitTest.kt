package com.jpweytjens.barberfish

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import com.jpweytjens.barberfish.datatype.applySparklineHeaderChrome
import com.jpweytjens.barberfish.datatype.barberfishFieldRemoteViews
import com.jpweytjens.barberfish.datatype.headerLabelWidthPx
import com.jpweytjens.barberfish.datatype.shared.FieldColor
import com.jpweytjens.barberfish.datatype.shared.FieldState
import com.jpweytjens.barberfish.datatype.shared.ViewSizeConfig
import com.jpweytjens.barberfish.datatype.shared.toDoubleSlotSizeConfig
import com.jpweytjens.barberfish.datatype.shared.toViewSizeConfig
import com.jpweytjens.barberfish.datatype.xmlDimensionPx
import com.jpweytjens.barberfish.extension.DataFieldDesignConfig
import com.jpweytjens.barberfish.extension.DoubleLabel
import com.jpweytjens.barberfish.extension.ZoneColorMode
import io.hammerhead.karooext.models.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The header label is a bitmap in an ImageView with scaleType=center, so a bitmap wider than the
 * view's content is cropped at both ends: at density 1.5 that cut the last letter of right-aligned
 * labels ("3S POWEF"). Inflates the real field layouts at the Karoo 2, Karoo 3 and xhdpi densities
 * and checks the label bitmap never outgrows its view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HeaderLabelFitTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val density: Float
        get() = context.resources.displayMetrics.density

    private fun field(icons: Int) =
        FieldState(
            "340",
            "LL Avg Power",
            FieldColor.Default,
            iconRes = if (icons > 0) R.drawable.ic_last_lap else null,
            secondaryIconRes = if (icons > 1) R.drawable.ic_avg_power else null,
        )

    private fun cell(cols: Int, rows: Int, width: Int, height: Int, textSize: Int) =
        ViewConfig(
            gridSize = cols to rows,
            viewSize = width to height,
            textSize = textSize,
            alignment = ViewConfig.Alignment.RIGHT,
            boundariesEnabled = true,
            preview = false,
        )

    /** Lays [rv] out at [width] by [height] and returns the label view. */
    private fun label(rv: RemoteViews, width: Int, height: Int): ImageView {
        val view = rv.apply(context, FrameLayout(context))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view.findViewById(R.id.field_label)
    }

    private fun assertFits(name: String, label: ImageView) {
        val content = label.width - label.paddingLeft - label.paddingRight
        val bitmap = label.drawable.intrinsicWidth
        assertTrue(
            "$name at $density: label bitmap $bitmap px > view $content px",
            bitmap <= content,
        )
    }

    private fun checkAllLayouts() {
        // (name, size config, slot width) as the HUD, the Doubles and the standalone fields
        // size them; standalone fields use the cell's own width (BarberfishDataType).
        val half = cell(30, 12, 238, 126, 46)
        val full = cell(60, 15, 478, 160, 69)
        val hudPad = (4 * density).toInt()
        val hudFour = (478 - 2 * hudPad) / 4f
        val hudThree = (478 - 2 * hudPad) / 3f
        val design = DataFieldDesignConfig()
        val cases =
            listOf(
                Triple(
                    "HUD 4-col",
                    ViewSizeConfig.HUD_FOUR.copy(cellWidthPxOverride = hudFour),
                    hudFour.toInt(),
                ),
                Triple(
                    "HUD 3-col",
                    ViewSizeConfig.HUD_THREE.copy(cellWidthPxOverride = hudThree),
                    hudThree.toInt(),
                ),
                Triple(
                    "Double half",
                    half.toDoubleSlotSizeConfig(2, DoubleLabel.BOTH, design, density),
                    119,
                ),
                Triple("half cell", half.toViewSizeConfig().copy(cellWidthPxOverride = 238f), 238),
                Triple("full cell", full.toViewSizeConfig().copy(cellWidthPxOverride = 478f), 478),
            )
        for ((name, size, width) in cases) {
            for (alignment in ViewConfig.Alignment.entries) {
                for (icons in 0..2) {
                    val rv =
                        barberfishFieldRemoteViews(
                            field(icons),
                            alignment,
                            ZoneColorMode.TEXT,
                            size,
                            preview = false,
                            context = context,
                        )
                    assertFits("$name $alignment $icons icons", label(rv, width, 126))
                }
            }
        }
        // The graphical fields' header (Profile, Overview) pads the header in code.
        val rv = RemoteViews(context.packageName, R.layout.barberfish_sparkline)
        applySparklineHeaderChrome(rv, "Profile", R.drawable.ic_grade, half, context)
        assertFits("sparkline header", label(rv, 238, 126))
    }

    @Test
    @Config(qualifiers = "w320dp-h533dp-hdpi")
    fun `labels fit at Karoo 2 density`() = checkAllLayouts()

    @Test
    @Config(qualifiers = "w256dp-h427dp-300dpi")
    fun `labels fit at Karoo 3 density`() = checkAllLayouts()

    @Test
    @Config(qualifiers = "w240dp-h400dp-xhdpi")
    fun `labels fit at density 2`() = checkAllLayouts()

    @Test
    fun `XML dp dimensions round to the nearest pixel, at least one`() {
        assertEquals(2, xmlDimensionPx(1f, 1.5f))
        assertEquals(2, xmlDimensionPx(1f, 1.875f))
        assertEquals(2, xmlDimensionPx(1f, 2f))
        assertEquals(1, xmlDimensionPx(1f, 1f))
        assertEquals(1, xmlDimensionPx(1f, 0.75f))
    }

    @Test
    fun `label width counts whole-pixel icons and gap and the rounded chrome`() {
        // HUD 4-col slot on the Karoo 2: 110 px header, one 11 dp icon (16 px), 2 dp gap (3 px),
        // and 2 + 2 px header padding plus the 2 px label margin.
        assertEquals(85, headerLabelWidthPx(110f, 1, 11f, 2f, 1.5f))
        assertEquals(104, headerLabelWidthPx(110f, 0, 11f, 2f, 1.5f))
    }
}
