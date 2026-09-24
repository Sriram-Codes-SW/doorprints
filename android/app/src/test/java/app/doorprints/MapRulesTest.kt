package app.doorprints

import app.doorprints.ui.LEGEND_DOTS
import app.doorprints.ui.LEGEND_DOT_BOX_DP
import app.doorprints.ui.LEGEND_DOT_GAP_DP
import app.doorprints.ui.LEGEND_PADDING_DP
import app.doorprints.ui.LEGEND_ROUNDING_SLACK_DP
import app.doorprints.ui.LegendPlace
import app.doorprints.ui.LocationFix
import app.doorprints.ui.MAP_ATTRIBUTION_GAP_DP
import app.doorprints.ui.MAP_ATTRIBUTION_OVERLAY_GAP_DP
import app.doorprints.ui.MAP_ATTRIBUTION_SIZE_DP
import app.doorprints.ui.MAP_ATTRIBUTION_STACK_DP
import app.doorprints.ui.LEGEND_OUTLINE_ALPHA
import app.doorprints.ui.LEGEND_OUTLINE_DP
import app.doorprints.ui.MAP_BOTTOM_ROW_MIN_DP
import app.doorprints.ui.MAP_BOTTOM_STACK_MIN_DP
import app.doorprints.ui.MAP_CONTROL_COLUMN_INSET_DP
import app.doorprints.ui.MAP_LEGEND_BESIDE_FAB_DP
import app.doorprints.ui.MAP_NORTH_UP
import app.doorprints.ui.MAP_SNACKBAR_MIN_WIDTH_DP
import app.doorprints.ui.MARKER_HIT_RADIUS_DP
import app.doorprints.ui.MARKER_LABEL_MAX_WIDTH_EM
import app.doorprints.ui.MARKER_OPACITY_REJECTED
import app.doorprints.ui.MARKER_RADII
import app.doorprints.ui.MARKER_STROKE_DP
import app.doorprints.ui.MARKER_STROKE_SHORTLISTED_DP
import app.doorprints.ui.attributionBottomDp
import app.doorprints.ui.controlsClearanceDp
import app.doorprints.ui.hasIndicScript
import app.doorprints.ui.legendBesideFab
import app.doorprints.ui.legendFitsOneLine
import app.doorprints.ui.legendMinWidthDp
import app.doorprints.ui.legendOneLineWidthDp
import app.doorprints.ui.legendPlace
import app.doorprints.ui.mapControlsInRow
import app.doorprints.ui.markerLabelSizeSp
import app.doorprints.ui.refusedTapSnackbarText
import app.doorprints.ui.rowReachesAttribution
import app.doorprints.ui.snackbarActionOnNewLine
import app.doorprints.ui.snackbarBesideRow
import app.doorprints.ui.topBandBesideControls
import app.doorprints.ui.topBandEndInsetDp
import app.doorprints.ui.topBandMaxHeightDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The Map's top band (Hunt card and notes) never shares space with the bottom controls, at any text size and on a
 * short map (UX review, whole-app audit, rounds 3 and 4; WCAG 1.3.4, 1.4.4, 1.4.10, 2.5.8), and scrolls inside what
 * it gets. Round 5: the snackbar sits beside the row when there is room, and the markers tell status by size too.
 * Round 6: the legend that explains them, its place in the column layout, and the house labels' size and scripts.
 * Round 7: the legend's place from its measured width (Tamil included), MapLibre's attribution lifted above it, and
 * the one-sentence refusal snackbar. Round 8: a north-up map (no compass to hide), the legend's width with room for
 * pixel rounding and on one line or one item per line, the attribution 8 dp above an overlay, and the snackbar's
 * action placed by its measured width.
 */
class MapRulesTest {

    @Test
    fun theTopBandEndsAboveTheMeasuredBottomColumn() {
        // A 360 × 780 dp phone leaves about 620 dp for the map; the column measures 280 dp.
        assertFalse(mapControlsInRow(620f))
        assertEquals(340f, topBandMaxHeightDp(620f, 280f), 0.01f)
        // 200 % font makes the column taller: the band gives way.
        assertEquals(260f, topBandMaxHeightDp(620f, 360f), 0.01f)
        assertEquals(0f, topBandEndInsetDp(620f, 360f), 0.01f)
    }

    @Test
    fun beforeTheFirstMeasureTheFloorIsKept() {
        assertEquals(620f - MAP_BOTTOM_STACK_MIN_DP, topBandMaxHeightDp(620f, 0f), 0.01f)
        assertEquals(300f - MAP_BOTTOM_ROW_MIN_DP, topBandMaxHeightDp(300f, 0f), 0.01f)
    }

    @Test
    fun aShortMapPutsTheControlsInOneRow() {
        // Landscape phone (412 dp tall, less the status and navigation bars): about 290 dp of map.
        assertTrue(mapControlsInRow(290f))
        // 50/50 split-screen on a 360 × 800 phone: about 330 dp each.
        assertTrue(mapControlsInRow(330f))
        assertTrue(mapControlsInRow(479f))
        assertFalse(mapControlsInRow(480f))
    }

    @Test
    fun aShortMapNeverPutsTheBandUnderAButton() {
        // The round-3 case: 290 dp of map. The row is 88 dp, so the band gets the 202 dp above it, where the column
        // would have left it a 72 dp quarter with Zoom in on the Hunt switch.
        assertEquals(202f, topBandMaxHeightDp(290f, 88f), 0.01f)
        assertEquals(0f, topBandEndInsetDp(290f, 88f), 0.01f)
        // Every short map, with the row on one line (88 dp) or wrapped at 200 % font (about 144 dp), and larger:
        // the band ends at or above the top of the controls.
        for (available in 100..479 step 1) {
            for (stack in listOf(0f, 88f, 120f, 144f, 200f)) {
                val a = available.toFloat()
                val band = topBandMaxHeightDp(a, stack)
                val stackTop = a - maxOf(stack, MAP_BOTTOM_ROW_MIN_DP)
                assertTrue("band $band ends below the row top $stackTop on $a dp", band <= maxOf(stackTop, 0f) + 0.01f)
                assertFalse(topBandBesideControls(a, stack))
            }
        }
    }

    @Test
    fun whenTheColumnLeavesTooLittleTheBandSitsBesideIt() {
        // A column made very tall (a huge font on a tablet in portrait): the band keeps a quarter, beside the column.
        assertTrue(topBandBesideControls(500f, 420f))
        assertEquals(125f, topBandMaxHeightDp(500f, 420f), 0.01f)
        assertEquals(MAP_CONTROL_COLUMN_INSET_DP, topBandEndInsetDp(500f, 420f), 0.01f)
        // In the column layout, every case either ends the band above the column or insets it clear of the buttons.
        for (available in 480..1200 step 5) {
            for (stack in listOf(0f, 268f, 280f, 360f, 420f, 600f)) {
                val a = available.toFloat()
                val band = topBandMaxHeightDp(a, stack)
                val stackTop = a - maxOf(stack, MAP_BOTTOM_STACK_MIN_DP)
                val clear = band <= stackTop + 0.01f || topBandEndInsetDp(a, stack) == MAP_CONTROL_COLUMN_INSET_DP
                assertTrue("band $band overlaps the column (top $stackTop) on $a dp", clear)
            }
        }
    }

    @Test
    fun theBandNeverExceedsTheMapOrGoesNegative() {
        assertEquals(0f, topBandMaxHeightDp(0f, 280f), 0.01f)
        assertEquals(0f, topBandMaxHeightDp(-10f, 280f), 0.01f)
        assertEquals(0f, topBandMaxHeightDp(60f, 88f), 0.01f)
    }

    @Test
    fun theSnackbarSitsBesideTheRowOnlyWhenItFits() {
        // A 360 × 800 phone in landscape: about 800 dp wide, the row (four buttons and its margins) about 370 dp.
        assertTrue(snackbarBesideRow(800f, 370f))
        // A 50/50 split-screen in landscape (400 dp) or in portrait (360 dp): above the row, as before.
        assertFalse(snackbarBesideRow(400f, 370f))
        assertFalse(snackbarBesideRow(360f, 370f))
        // Exactly the minimum: 24 dp of margins and gap plus 288 dp of snackbar.
        assertTrue(snackbarBesideRow(370f + 24f + MAP_SNACKBAR_MIN_WIDTH_DP, 370f))
        assertFalse(snackbarBesideRow(370f + 24f + MAP_SNACKBAR_MIN_WIDTH_DP - 1f, 370f))
        // Before the row is measured, never beside (it would sit on the buttons).
        assertFalse(snackbarBesideRow(1200f, 0f))
    }

    @Test
    fun markersTellStatusBySizeNotOnlyColour() {
        // The web map's circle-radius stops (map-page.ts), zoom 8, 14 and 18, in ascending order.
        assertEquals(listOf(8f, 14f, 18f), MARKER_RADII.map { it.zoom })
        assertEquals(listOf(7f, 11f, 15f), MARKER_RADII.map { it.shortlisted })
        assertEquals(listOf(4f, 6f, 9f), MARKER_RADII.map { it.rejected })
        assertEquals(listOf(5f, 8f, 12f), MARKER_RADII.map { it.new })
        // At every zoom: shortlisted largest, rejected smallest, so the shortlisted / rejected pair (about 1.3:1 in
        // luminance) differs in size for users who cannot tell the colours apart (WCAG 1.4.1).
        MARKER_RADII.forEach { r ->
            assertTrue("zoom ${r.zoom}", r.shortlisted > r.new && r.new > r.rejected)
            // The 24 dp hit radius covers the largest dot and its ring.
            assertTrue("zoom ${r.zoom}", r.shortlisted + MARKER_STROKE_SHORTLISTED_DP <= MARKER_HIT_RADIUS_DP)
        }
        assertTrue(MARKER_STROKE_SHORTLISTED_DP > MARKER_STROKE_DP)
        assertEquals(0.75f, MARKER_OPACITY_REJECTED, 0.001f)
    }

    @Test
    fun theLegendDrawsTheMarkersAsTheWebDoes() {
        // The web's order and .dot sizes (map-page.css): new 12 px, shortlisted 16 px, rejected 9 px.
        assertEquals(listOf("NEW", "SHORTLISTED", "REJECTED"), LEGEND_DOTS.map { it.status })
        assertEquals(listOf(12f, 16f, 9f), LEGEND_DOTS.map { it.diameterDp })
        // The same ring and opacity as the markers on the map, so the key matches what it explains.
        assertEquals(
            listOf(MARKER_STROKE_DP, MARKER_STROKE_SHORTLISTED_DP, MARKER_STROKE_DP),
            LEGEND_DOTS.map { it.ringDp },
        )
        assertEquals(listOf(1f, 1f, MARKER_OPACITY_REJECTED), LEGEND_DOTS.map { it.alpha })
        val (new, shortlisted, rejected) = LEGEND_DOTS
        assertTrue(shortlisted.diameterDp > new.diameterDp && new.diameterDp > rejected.diameterDp)
        // Every dot has some colour inside its white ring.
        LEGEND_DOTS.forEach { assertTrue(it.status, it.diameterDp / 2f - it.ringDp > 0f) }
        // The web's box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.35).
        assertEquals(1f, LEGEND_OUTLINE_DP, 0.001f)
        assertEquals(0.35f, LEGEND_OUTLINE_ALPHA, 0.001f)
    }

    @Test
    fun theLegendNeedsItsWidestNameOnOneLine() {
        // The widest name (measured on the device, rounded up to a whole dp), the 18 dp dot box, the 4 dp gap, 12 dp
        // padding on each side and 2 dp for pixel rounding (round 8).
        assertEquals(18f, LEGEND_DOT_BOX_DP, 0.001f)
        assertEquals(2f, LEGEND_ROUNDING_SLACK_DP, 0.001f)
        assertEquals(168f, legendMinWidthDp(120f), 0.01f)
        // Tamil "நிராகரிக்கப்பட்டது" at labelMedium (Noto Sans Tamil, 12 sp): 119.8 dp, so the legend needs 168 dp,
        // not the 120 dp round 6 guessed.
        assertEquals(168f, legendMinWidthDp(119.8f), 0.01f)
        assertEquals(169f, legendMinWidthDp(120.01f), 0.01f)
        // English "Shortlisted" is about 64 dp.
        assertEquals(112f, legendMinWidthDp(64f), 0.01f)
        assertEquals(48f, legendMinWidthDp(-1f), 0.01f)
    }

    @Test
    fun theLegendAsLaidOutInPixelsNeverGetsLessThanItsNameNeeds() {
        // Compose rounds each part to whole pixels on its own (size(18.dp), spacedBy(4.dp), the two 12 dp paddings),
        // and the legend's place (its start and end padding) is rounded too, 1 px at most. At the exact threshold
        // legendPlace allows, the widest name must still get its full measured width at every common density: at
        // 2.625x, 24 + 18 + 4 dp is 120.75 px as a sum but 122 px as laid out (round 7 was 1-2 px short in Tamil).
        // mdpi (1x) to xxxhdpi (4x), with the common 2.625x and 2.75x.
        val densities = listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)
        for (density in densities) {
            for (labelPx in listOf(1, 99, 168, 314, 315, 316, 500, 629, 800)) {
                val labelDp = labelPx / density
                val laidOutPx = 2 * (LEGEND_PADDING_DP * density).roundToInt() +
                    (LEGEND_DOT_BOX_DP * density).roundToInt() + (LEGEND_DOT_GAP_DP * density).roundToInt() + labelPx
                // The width legendPlace gives the legend at its threshold, less 1 px for the rounding of its place.
                val givenPx = legendMinWidthDp(labelDp) * density - 1f
                assertTrue("$labelPx px name at ${density}x: laid out $laidOutPx px, given $givenPx px", laidOutPx <= givenPx)
            }
        }
    }

    @Test
    fun theLegendIsOneLineOrOneItemPerLineNeverAMix() {
        // English at 100 %: "New" 26, "Shortlisted" 64, "Rejected" 50 dp. One line: 24 dp padding, 3 × (18 + 4) dp of
        // dots and gaps, the names, 2 × 8 dp between items and 2 dp for rounding.
        val english = listOf(26f, 64f, 50f)
        assertEquals(24f + 66f + 140f + 16f + 2f, legendOneLineWidthDp(english), 0.01f)
        // A name is rounded up to a whole dp, as in legendMinWidthDp.
        assertEquals(legendOneLineWidthDp(listOf(26f, 64f, 50f)) + 3f, legendOneLineWidthDp(listOf(26.2f, 64.5f, 50.9f)), 0.01f)
        // Beside *Save house here* on a 412 dp phone (412 - 16 - about 170 - 32 = 194 dp): a FlowRow put "New" and
        // "Shortlisted" on one line and "Rejected" alone; now it is one item per line.
        assertFalse(legendFitsOneLine(194f, legendOneLineWidthDp(english)))
        // At the start of a landscape phone's row, or full width in the band: all three on one line.
        assertTrue(legendFitsOneLine(400f, legendOneLineWidthDp(english)))
        // Exactly the one-line width fits; a dp less does not.
        val one = legendOneLineWidthDp(english)
        assertTrue(legendFitsOneLine(one, one))
        assertFalse(legendFitsOneLine(one - 1f, one))
        // Not measured yet: never one line (the Column is the safe layout).
        assertEquals(0f, legendOneLineWidthDp(emptyList()), 0.001f)
        assertFalse(legendFitsOneLine(1000f, 0f))
    }

    @Test
    fun theColumnLegendSitsBesideSaveHouseHereOnlyWhenItFits() {
        val english = legendMinWidthDp(64f)
        val tamil = legendMinWidthDp(119.8f)
        // A 360 dp phone at 100 % font in English: *Save house here* is about 170 dp, which leaves the legend 142 dp.
        assertTrue(legendBesideFab(360f, 170f, english))
        // Tamil on the common 412 dp phone: "இங்கே வீட்டைச் சேமி" makes the button about 232 dp, which leaves 132 dp,
        // too narrow for "நிராகரிக்கப்பட்டது" (round 6 said "beside" and broke the word mid-syllable).
        assertFalse(legendBesideFab(412f, 232f, tamil))
        assertEquals(LegendPlace.ABOVE_FAB, legendPlace(false, 412f, 232f, 0f, tamil))
        // Exactly the minimum: the 16 dp margin, the button, the legend's 32 dp of margin and gap, and the legend
        // (a 120 dp name, 168 dp of legend, so the sums are exact in Float).
        val exact = legendMinWidthDp(120f)
        assertTrue(legendBesideFab(170f + 16f + MAP_LEGEND_BESIDE_FAB_DP + exact, 170f, exact))
        assertFalse(legendBesideFab(170f + 16f + MAP_LEGEND_BESIDE_FAB_DP + exact - 1f, 170f, exact))
        // Before the button is measured the legend is not drawn, and the rule never says "beside".
        assertFalse(legendBesideFab(1200f, 0f, english))
        assertNull(legendPlace(false, 1200f, 0f, 0f, english))
    }

    @Test
    fun theLegendGoesIntoTheBandWhenNoBottomPlaceFitsIt() {
        // 200 % font on a 360 dp phone in Tamil: the name is about 240 dp, the legend 288 dp; above the button
        // it would get 360 - 16 - 80 = 264 dp, so it is the top band's last item instead.
        val tamil200 = legendMinWidthDp(239.6f)
        assertEquals(LegendPlace.IN_BAND, legendPlace(false, 360f, 300f, 0f, tamil200))
        // The same on a wider phone fits above the button.
        assertEquals(LegendPlace.ABOVE_FAB, legendPlace(false, 412f, 300f, 0f, tamil200))
        // English at 100 %: beside the button.
        assertEquals(LegendPlace.BESIDE_FAB, legendPlace(false, 360f, 170f, 0f, legendMinWidthDp(64f)))
        // Not measured yet: nothing is drawn.
        assertNull(legendPlace(false, 360f, 170f, 0f, 0f))
    }

    @Test
    fun theRowLegendSitsAtTheStartAndLeavesTheSnackbarItsPlace() {
        val tamil = legendMinWidthDp(119.8f)
        // An 800 dp landscape phone: the four controls alone are about 370 dp with their margins, so the legend
        // sits at the bottom start and the snackbar again fits beside the row (round 5; round 6 had lost it).
        assertEquals(LegendPlace.BESIDE_ROW, legendPlace(true, 800f, 232f, 370f, tamil))
        assertTrue(snackbarBesideRow(800f, 370f))
        // A narrow split-screen whose row takes nearly the whole width: the legend goes into the band.
        assertEquals(LegendPlace.IN_BAND, legendPlace(true, 400f, 232f, 370f, tamil))
        // Exactly the minimum: the 16 dp gutter, the legend, then the row with its own 16 dp margin.
        val exact = legendMinWidthDp(120f)
        assertEquals(LegendPlace.BESIDE_ROW, legendPlace(true, 370f + 16f + exact, 232f, 370f, exact))
        assertEquals(LegendPlace.IN_BAND, legendPlace(true, 370f + 16f + exact - 1f, 232f, 370f, exact))
        // Before the row is measured: nothing is drawn.
        assertNull(legendPlace(true, 800f, 232f, 0f, tamil))
    }

    @Test
    fun mapLibresAttributionIsLiftedAboveWhatSitsAtTheBottomStart() {
        // Nothing there: MapLibre's own 4 dp from the bare map edge.
        assertEquals(4f, MAP_ATTRIBUTION_GAP_DP, 0.001f)
        assertEquals(MAP_ATTRIBUTION_GAP_DP, attributionBottomDp(0f), 0.001f)
        // A legend 16 dp from the bottom and 74 dp tall: the "i" sits 8 dp above its top (round 8: the overlays' 8 dp
        // rhythm; 4 dp nearly touched the legend's shadow).
        assertEquals(8f, MAP_ATTRIBUTION_OVERLAY_GAP_DP, 0.001f)
        assertEquals(98f, attributionBottomDp(90f), 0.001f)
        // A row of controls reaches the button only when it starts within 8 dp of the button's end (16 + 21 dp).
        assertFalse(rowReachesAttribution(800f, 370f))
        assertFalse(rowReachesAttribution(360f, 0f))
        assertTrue(rowReachesAttribution(360f, 340f))
        assertTrue(rowReachesAttribution(360f, 332f))
        assertFalse(rowReachesAttribution(360f, 331f))
    }

    @Test
    fun theBandAndTheSnackbarKeepClearOfTheAttributionAboveTheLegend() {
        // Round 9: the 8 dp gap, the 21 dp "i" and 8 dp more (round 8 kept 16 dp, and the "i" reaches 29 dp).
        assertEquals(21f, MAP_ATTRIBUTION_SIZE_DP, 0.001f)
        assertEquals(37f, MAP_ATTRIBUTION_STACK_DP, 0.001f)
        // Tamil at 130 % on a 700 dp landscape map: a legend one item per line whose top is 114 dp up, beside an
        // 88 dp row. The band and the snackbar end at least 151 dp up, clear of the "i" (122 to 143 dp).
        val clearance = controlsClearanceDp(88f, 114f)
        assertTrue(clearance >= 151f)
        assertTrue(clearance >= attributionBottomDp(114f) + MAP_ATTRIBUTION_SIZE_DP + 8f)
        // Taller controls than the legend's stack: their measured height.
        assertEquals(300f, controlsClearanceDp(300f, 114f), 0.001f)
        // No legend at the bottom start (in the band, or not measured yet): the controls alone.
        assertEquals(88f, controlsClearanceDp(88f, 0f), 0.001f)
    }

    @Test
    fun theRefusedTapSnackbarIsOneShortSentence() {
        val off = "Location is off for Doorprints."
        val approximate = "Doorprints has only your approximate location."
        assertEquals(off, refusedTapSnackbarText(LocationFix.ALLOW, off, approximate))
        assertEquals(off, refusedTapSnackbarText(LocationFix.OPEN_SETTINGS, off, approximate))
        assertEquals(approximate, refusedTapSnackbarText(LocationFix.TURN_ON_PRECISE, off, approximate))
        assertEquals(approximate, refusedTapSnackbarText(LocationFix.OPEN_SETTINGS_PRECISE, off, approximate))
    }

    @Test
    fun aSnackbarActionGoesOnItsOwnLineByItsMeasuredWidth() {
        // Widths as measured at labelLarge (14 sp, 100 % font); the snackbar on a 360 dp phone is 328 dp wide (its
        // 16 dp margins), so an action and its 24 dp of padding may take 98.4 dp inline.
        val phone = 360f - 32f
        // "Open settings" (about 92 dp) and Hindi "सेटिंग खोलें" (about 78 dp): both on their own line. Round 7 counted
        // UTF-16 units, and the Hindi label (exactly 12) stayed inline while the English one (13) went down.
        assertTrue(snackbarActionOnNewLine(92f, phone))
        assertTrue(snackbarActionOnNewLine(78f, phone))
        // Tamil "அமைப்புகளைத் திற" (about 118 dp) too.
        assertTrue(snackbarActionOnNewLine(118f, phone))
        // "Undo" (about 36 dp) and a short Telugu label full of viramas (about 40 dp, many more code units): inline.
        assertFalse(snackbarActionOnNewLine(36f, phone))
        assertFalse(snackbarActionOnNewLine(40f, phone))
        // Up to 30 % (98.4 dp with the padding) stays inline; more goes down.
        assertFalse(snackbarActionOnNewLine(74f, phone))
        assertTrue(snackbarActionOnNewLine(75f, phone))
        // A wide snackbar beside a landscape row (800 - 16 - 370 - 8 = 406 dp) keeps "Open settings" inline.
        assertFalse(snackbarActionOnNewLine(92f, 406f))
        // No action: nothing to place. Width not known yet: on its own line, the layout that never squeezes.
        assertFalse(snackbarActionOnNewLine(0f, phone))
        assertTrue(snackbarActionOnNewLine(36f, 0f))
    }

    @Test
    fun theMapIsNorthUpAsOnTheWeb() {
        // No rotation, so no compass to hide under a button (round 8, WCAG 2.5.1); the web map disables rotation too.
        assertTrue(MAP_NORTH_UP)
    }

    @Test
    fun houseLabelsFollowTheFontScaleUpToOneAndAHalf() {
        assertEquals(12f, markerLabelSizeSp(1f), 0.001f)
        assertEquals(15.6f, markerLabelSizeSp(1.3f), 0.001f)
        // 200 % font (and Android 14's non-linear 2.0): capped at 18, so a name does not cover its neighbours.
        assertEquals(18f, markerLabelSizeSp(2f), 0.001f)
        assertEquals(18f, markerLabelSizeSp(1.5f), 0.001f)
        // A smaller font makes them smaller, as every other text.
        assertEquals(10.2f, markerLabelSizeSp(0.85f), 0.001f)
        assertTrue(MARKER_LABEL_MAX_WIDTH_EM > 0f)
    }

    @Test
    fun indicNamesAreFoundForTheLabelFilter() {
        assertTrue(hasIndicScript("பச்சை வில்லா"))
        assertTrue(hasIndicScript("हरा विला"))
        assertTrue(hasIndicScript("పచ్చ విల్లా"))
        assertTrue(hasIndicScript("Green Villa ஏ"))
        assertFalse(hasIndicScript("Green Villa, 2nd floor"))
        assertFalse(hasIndicScript("Café Ünal"))
        assertFalse(hasIndicScript(""))
    }
}
