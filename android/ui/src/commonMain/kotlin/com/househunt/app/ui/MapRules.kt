package com.househunt.app.ui

import kotlin.math.ceil

/*
 * The Map's layout decisions that need no Android, as pure functions so they are unit tested (MapRulesTest; UX
 * review, whole-app audit, rounds 3 and 4).
 *
 * Two layouts of the bottom controls (Zoom in, Zoom out, *My location*, *Save house here*):
 *  - a **column** at the bottom end, on a map at least [MAP_CONTROLS_ROW_BELOW_DP] tall (a phone in portrait);
 *  - a **row** at the bottom end, [Zoom out][Zoom in][My location][Save house here], 8 dp apart, on a shorter map
 *    (a phone in landscape, split-screen, a half-open foldable; [mapControlsInRow]). The row wraps *Save house here*
 *    onto a second line when the map is too narrow for all four (a 50/50 split-screen at 200 % font).
 * The snackbar is not part of either: it is transient, so the band's height does not jump each time a snackbar comes
 * and goes. Beside the row when the map is wide enough ([snackbarBesideRow]; round 5), otherwise just above the
 * controls. No refusal is a snackbar any more (round 5): the note in the band says it, so a snackbar can never cover
 * the button of the note it points to.
 *
 * The house markers' size, stroke and opacity by status ([MARKER_RADII] and the constants after it; round 5, UX-002,
 * WCAG 1.4.1) are here too, so a test holds them to the web map's values, and so is the legend that explains them
 * ([LEGEND_DOTS], [legendBesideFab]; round 6) and the labels' size ([markerLabelSizeSp]; round 6, WCAG 1.4.4).
 * Round 7: the legend's place from its measured width ([legendMinWidthDp], [legendPlace]), MapLibre's attribution
 * lifted above it ([attributionBottomDp]), and the one-sentence refusal snackbar ([refusedTapSnackbarText],
 * [snackbarActionOnNewLine]).
 * Round 8: the map is north-up ([MAP_NORTH_UP]), the legend's width keeps a margin for pixel rounding
 * ([LEGEND_ROUNDING_SLACK_DP]) and is one line or one item per line ([legendOneLineWidthDp], [legendFitsOneLine]),
 * the attribution keeps the 8 dp rhythm above an overlay ([MAP_ATTRIBUTION_OVERLAY_GAP_DP]), and a snackbar's action
 * goes on its own line by its measured width, not its character count ([snackbarActionOnNewLine]).
 * Round 9: the band and the snackbar keep clear of the attribution above the legend ([MAP_ATTRIBUTION_STACK_DP],
 * [controlsClearanceDp]).
 */

/**
 * The map is north-up (UX review, whole-app audit, round 8; WCAG 2.5.1): no two-finger rotation and no tilt, so no
 * compass is needed to undo one, as on the web map. Round 7 kept rotation with MapLibre's compass at the top end,
 * where a tall top band (Hunt mode on, a note, a landscape phone, 200 % font) put it under *Zoom in* or *Save house
 * here*: a rotated map with no visible way back to north. MapScreen turns rotation, tilt and the compass off with
 * this, and restores a saved camera at bearing 0.
 */
const val MAP_NORTH_UP = true

/** Below this map height (dp) the bottom controls are one row, not a column ([mapControlsInRow]). */
const val MAP_CONTROLS_ROW_BELOW_DP = 480f

/**
 * The least room kept free for the Map's bottom column (Zoom in, Zoom out, *My location* and *Save house here*, with
 * their gaps and the 16 dp margin), in dp: 3 × 48 dp small buttons, the 56 dp extended button, 3 × 12 dp gaps and
 * 2 × 16 dp margin come to 268 dp. The column's measured height is used once it is known (a larger font makes it
 * taller); this is the floor before the first measure.
 */
const val MAP_BOTTOM_STACK_MIN_DP = 270f

/** The same floor for the bottom row: the 56 dp extended button and 2 × 16 dp margin. */
const val MAP_BOTTOM_ROW_MIN_DP = 88f

/** In the column layout the top band may always take at least this share of the map's height. */
const val MAP_TOP_BAND_MIN_SHARE = 0.25f

/**
 * The end inset (dp) the top band takes while it shares height with the bottom column ([topBandEndInsetDp]): the
 * 48 dp buttons, their 16 dp margin and a 16 dp gap, so the band and the buttons never share horizontal space.
 */
const val MAP_CONTROL_COLUMN_INSET_DP = 80f

/**
 * True when the bottom controls are one row ([MAP_CONTROLS_ROW_BELOW_DP]; UX review, whole-app audit, round 4; WCAG
 * 1.3.4, 1.4.10, 2.5.8). A column of four buttons (about 270 dp) on a 290 dp landscape map left the Hunt card a 72 dp
 * band, with Zoom in drawn on top of the Hunt switch.
 */
fun mapControlsInRow(availableDp: Float): Boolean = availableDp < MAP_CONTROLS_ROW_BELOW_DP

/** The height kept for the bottom controls: their measured height, never less than the layout's floor. */
private fun bottomControlsDp(availableDp: Float, bottomStackDp: Float): Float =
    maxOf(bottomStackDp, if (mapControlsInRow(availableDp)) MAP_BOTTOM_ROW_MIN_DP else MAP_BOTTOM_STACK_MIN_DP)

/**
 * True when the top band takes the 25 % floor beside the bottom column instead of ending above it: only in the
 * column layout, when what is left above the column is less than a quarter of the map. [topBandEndInsetDp] then
 * keeps the band clear of the buttons.
 */
fun topBandBesideControls(availableDp: Float, bottomStackDp: Float): Boolean {
    if (mapControlsInRow(availableDp)) return false
    val height = maxOf(availableDp, 0f)
    return height - bottomControlsDp(availableDp, bottomStackDp) < height * MAP_TOP_BAND_MIN_SHARE
}

/**
 * The top band's maximum height, in dp, on a map area [availableDp] tall whose bottom controls are [bottomStackDp]
 * tall (UX review, whole-app audit, rounds 3 and 4; WCAG 1.4.4, 1.4.10): it ends above the controls, so no text sits
 * under a button, and scrolls inside that. In the row layout it always ends above the row (the row spans the width,
 * so nothing else would keep them apart). In the column layout, on a map too short for that, it still gets a
 * quarter of the height, beside the column ([topBandBesideControls], [topBandEndInsetDp]). Never more than the map.
 */
fun topBandMaxHeightDp(availableDp: Float, bottomStackDp: Float): Float {
    val height = maxOf(availableDp, 0f)
    val free = height - bottomControlsDp(availableDp, bottomStackDp)
    val band = if (topBandBesideControls(availableDp, bottomStackDp)) height * MAP_TOP_BAND_MIN_SHARE else free
    return band.coerceIn(0f, height)
}

/**
 * The top band's end inset, in dp: [MAP_CONTROL_COLUMN_INSET_DP] while it sits beside the bottom column
 * ([topBandBesideControls]), otherwise 0, so a tap on the Hunt switch can never land on Zoom in.
 */
fun topBandEndInsetDp(availableDp: Float, bottomStackDp: Float): Float =
    if (topBandBesideControls(availableDp, bottomStackDp)) MAP_CONTROL_COLUMN_INSET_DP else 0f

/** The narrowest snackbar worth placing beside the row (dp): M3's snackbar is readable from about 288 dp. */
const val MAP_SNACKBAR_MIN_WIDTH_DP = 288f

/** The snackbar's 16 dp start margin and its 8 dp gap before the row's own 16 dp margin, in dp. */
const val MAP_SNACKBAR_BESIDE_ROW_DP = 24f

/**
 * True when the snackbar sits beside the bottom row, at the bottom start, instead of above it (UX review, whole-app
 * audit, round 5; WCAG 2.4.11): a map [mapWidthDp] wide whose row measures [rowWidthDp] (with its 16 dp margins)
 * leaves at least [MAP_SNACKBAR_MIN_WIDTH_DP] for it. "Finding your location…" then covers neither the top band nor
 * the row. False before the row has been measured (0), and in the column layout, which keeps its place above.
 */
fun snackbarBesideRow(mapWidthDp: Float, rowWidthDp: Float): Boolean =
    rowWidthDp > 0f && mapWidthDp - rowWidthDp - MAP_SNACKBAR_BESIDE_ROW_DP >= MAP_SNACKBAR_MIN_WIDTH_DP

/**
 * A house marker's radius (dp) at one zoom level, by status: the web map's `circle-radius` stops (map-page.ts), so
 * status is not told by colour alone (UX review, whole-app audit, round 5; docs/05 UX-002, A11Y-003, WCAG 1.4.1).
 * Shortlisted is the largest, rejected the smallest, at every zoom.
 */
data class MarkerRadii(val zoom: Float, val shortlisted: Float, val rejected: Float, val new: Float)

/** Interpolated linearly by zoom between these stops (below the first and above the last, the end values). */
val MARKER_RADII = listOf(
    MarkerRadii(zoom = 8f, shortlisted = 7f, rejected = 4f, new = 5f),
    MarkerRadii(zoom = 14f, shortlisted = 11f, rejected = 6f, new = 8f),
    MarkerRadii(zoom = 18f, shortlisted = 15f, rejected = 9f, new = 12f),
)

/** The white ring round a marker (dp): thicker on a shortlisted house, as on the web. */
const val MARKER_STROKE_DP = 2f
const val MARKER_STROKE_SHORTLISTED_DP = 3f

/** A rejected house's marker is drawn at this opacity (the web's 0.75), every other at full opacity. */
const val MARKER_OPACITY_REJECTED = 0.75f

/** Half of the 48 dp square a tap on the map searches for a marker (MapScreen's hit test). */
const val MARKER_HIT_RADIUS_DP = 24f

/**
 * One dot of the map's legend (UX review, whole-app audit, round 6; UX-002, A11Y-003, WCAG 1.4.1), drawn like the
 * web's `.dot` (map-page.css): [diameterDp] across including the white ring of [ringDp], a [LEGEND_OUTLINE_DP] ring
 * of black at [LEGEND_OUTLINE_ALPHA] outside it, the whole dot at [alpha]. [status] is the `HouseStatus` name, which
 * picks the colour (`MarkerColors`) and the label (`status_NEW`, `status_SHORTLISTED`, `status_REJECTED`).
 */
data class LegendDot(val status: String, val diameterDp: Float, val ringDp: Float, val alpha: Float)

/** The legend's three dots, in the web's order, with the web's sizes (12, 16 and 9 px): shortlisted largest. */
val LEGEND_DOTS = listOf(
    LegendDot("NEW", diameterDp = 12f, ringDp = MARKER_STROKE_DP, alpha = 1f),
    LegendDot("SHORTLISTED", diameterDp = 16f, ringDp = MARKER_STROKE_SHORTLISTED_DP, alpha = 1f),
    LegendDot("REJECTED", diameterDp = 9f, ringDp = MARKER_STROKE_DP, alpha = MARKER_OPACITY_REJECTED),
)

/** The dark hairline round each legend dot (the web's `box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.35)`), in dp. */
const val LEGEND_OUTLINE_DP = 1f
const val LEGEND_OUTLINE_ALPHA = 0.35f

/** The box every legend dot is centred in (dp): the largest dot (16 dp) and its 1 dp hairline on both sides. */
const val LEGEND_DOT_BOX_DP = 18f

/** The gap between a legend dot's box and its status name, in dp. */
const val LEGEND_DOT_GAP_DP = 4f

/** The legend's start and end padding inside its box, in dp. */
const val LEGEND_PADDING_DP = 12f

/** The gap between two legend items on one line, in dp. */
const val LEGEND_ITEM_GAP_DP = 8f

/**
 * Room kept for pixel rounding (UX review, whole-app audit, round 8), in dp. The legend's parts are laid out in whole
 * pixels, each rounded on its own (`size(18.dp)`, `spacedBy(4.dp)`, the two 12 dp paddings): at 2.625× 24 + 18 + 4 dp
 * is 120.75 px as a sum but 122 px as laid out, so at the exact threshold the widest name got 1-2 px too little and
 * broke mid-word. 2 dp is at least 2 px at every density, more than the rounding of the parts and of the padding
 * that places the legend can take away.
 */
const val LEGEND_ROUNDING_SLACK_DP = 2f

/**
 * The narrowest the legend can be without breaking a status name (UX review, whole-app audit, round 7), in dp: one
 * item per line, so the widest of the three names as measured on the device ([widestLabelDp]: `rememberTextMeasurer`
 * at the legend's style, one line, for the current locale and font scale), its dot box, the gap and the padding.
 * Round 6 guessed a fixed 120 dp; Tamil "நிராகரிக்கப்பட்டது" alone is about 120 dp at 12 sp, so the legend needs
 * about 168 dp, and on a 412 dp phone the guess put it beside the Tamil button with 132 dp and broke the word.
 * Round 8: the name is rounded up to a whole dp and [LEGEND_ROUNDING_SLACK_DP] is added, so the legend as laid out in
 * pixels never gets less than the name needs.
 */
fun legendMinWidthDp(widestLabelDp: Float): Float =
    2 * LEGEND_PADDING_DP + LEGEND_DOT_BOX_DP + LEGEND_DOT_GAP_DP + ceil(maxOf(widestLabelDp, 0f)) +
        LEGEND_ROUNDING_SLACK_DP

/**
 * The legend's width with all three items on one line (UX review, whole-app audit, round 8), in dp: each name as
 * measured ([labelWidthsDp], rounded up), its dot box and gap, the 8 dp between items, the padding and
 * [LEGEND_ROUNDING_SLACK_DP]. 0 for no names.
 */
fun legendOneLineWidthDp(labelWidthsDp: List<Float>): Float {
    if (labelWidthsDp.isEmpty()) return 0f
    val items = labelWidthsDp.sumOf { (LEGEND_DOT_BOX_DP + LEGEND_DOT_GAP_DP + ceil(maxOf(it, 0f))).toDouble() }
    return 2 * LEGEND_PADDING_DP + items.toFloat() + LEGEND_ITEM_GAP_DP * (labelWidthsDp.size - 1) +
        LEGEND_ROUNDING_SLACK_DP
}

/**
 * True when the legend puts its three items on one line in [availableDp] (the width it is given, padding included);
 * otherwise it puts one item per line, never a mix (UX review, whole-app audit, round 8): a FlowRow packed "● New
 * ● Shortlisted" on one line and "● Rejected" alone on the next beside *Save house here* on a 412 dp phone, a ragged
 * key whose dots did not line up. False before the names are measured (0).
 */
fun legendFitsOneLine(availableDp: Float, oneLineWidthDp: Float): Boolean =
    oneLineWidthDp > 0f && availableDp >= oneLineWidthDp

/** In the column layout, the legend's 16 dp start margin and its 16 dp gap before *Save house here*, in dp. */
const val MAP_LEGEND_BESIDE_FAB_DP = 32f

/** The screen-edge gutter of every map overlay (the Hunt card, the controls, the legend, the snackbar), in dp. */
const val MAP_GUTTER_DP = 16f

/**
 * True when, in the column layout, the legend sits at the bottom start beside *Save house here* ([fabWidthDp], its
 * measured width without margins) on a map [mapWidthDp] wide: what is left after the button, its 16 dp margin and
 * the legend's own 32 dp of margin and gap is at least the legend's narrowest width (the `legendMinWidthDp` function
 * of the measured widest name). False before the button has been measured (0).
 */
fun legendBesideFab(mapWidthDp: Float, fabWidthDp: Float, legendMinWidthDp: Float): Boolean =
    fabWidthDp > 0f && mapWidthDp - fabWidthDp - MAP_GUTTER_DP - MAP_LEGEND_BESIDE_FAB_DP >= legendMinWidthDp

/** Where the Map draws its legend (UX review, whole-app audit, rounds 6 and 7; [legendPlace]). */
enum class LegendPlace {
    /** Column layout: bottom start, beside *Save house here*. */
    BESIDE_FAB,

    /** Column layout: bottom start, above *Save house here*, inset past the 48 dp buttons. */
    ABOVE_FAB,

    /** Row layout: bottom start, on its own, with the controls' row at the bottom end (the web's phone row). */
    BESIDE_ROW,

    /** The last item of the scrolling top band, full width under the Hunt card: no room at the bottom. */
    IN_BAND,
}

/**
 * Where the legend goes (UX review, whole-app audit, round 7), or null before what it depends on has been measured
 * (it is then not drawn, so it never flashes on a button):
 *  - column layout: [LegendPlace.BESIDE_FAB] when [legendBesideFab]; else [LegendPlace.ABOVE_FAB] when the map
 *    leaves it [legendMinWidthDp] between the 16 dp gutter and the 80 dp inset past the buttons
 *    ([MAP_CONTROL_COLUMN_INSET_DP]); else [LegendPlace.IN_BAND] (a 360 dp phone at 200 % font in Tamil).
 *  - row layout ([rowWidthDp]: the controls' row with its 16 dp margins, the legend no longer in it):
 *    [LegendPlace.BESIDE_ROW] when the legend fits between the 16 dp gutter and the row's own margin, as the web puts
 *    the legend at the start and the actions at the end of one row; else [LegendPlace.IN_BAND] (a narrow
 *    split-screen, where the row may wrap).
 */
fun legendPlace(
    controlsInRow: Boolean,
    mapWidthDp: Float,
    fabWidthDp: Float,
    rowWidthDp: Float,
    legendMinWidthDp: Float,
): LegendPlace? {
    if (legendMinWidthDp <= 0f) return null
    return if (controlsInRow) {
        when {
            rowWidthDp <= 0f -> null
            mapWidthDp - rowWidthDp - MAP_GUTTER_DP >= legendMinWidthDp -> LegendPlace.BESIDE_ROW
            else -> LegendPlace.IN_BAND
        }
    } else {
        when {
            fabWidthDp <= 0f -> null
            legendBesideFab(mapWidthDp, fabWidthDp, legendMinWidthDp) -> LegendPlace.BESIDE_FAB
            mapWidthDp - MAP_GUTTER_DP - MAP_CONTROL_COLUMN_INSET_DP >= legendMinWidthDp -> LegendPlace.ABOVE_FAB
            else -> LegendPlace.IN_BAND
        }
    }
}

/**
 * MapLibre's attribution button (its "i", which opens the OpenStreetMap / OpenMapTiles / OpenFreeMap credits that
 * ODbL and OpenFreeMap require) is 21 dp square (maplibre_info_icon_default.xml, 13.6.1). The Map turns MapLibre's
 * logo off (its BSD licence does not ask for it, and the web map shows none) and puts the button on the 16 dp gutter.
 */
const val MAP_ATTRIBUTION_SIZE_DP = 21f

/** MapLibre's own distance of the attribution button from the bare map's bottom edge, in dp. */
const val MAP_ATTRIBUTION_GAP_DP = 4f

/**
 * The gap kept between the attribution button and an overlay under it, in dp (round 8): the 8 dp rhythm of the map's
 * overlays (8 dp in the row, 12 dp in the column). Round 7's 4 dp left the 21 dp "i" nearly touching the legend's
 * 2 dp shadow.
 */
const val MAP_ATTRIBUTION_OVERLAY_GAP_DP = 8f

/**
 * How far the band and the snackbar keep clear of a legend at the bottom start, in dp (UX review, whole-app audit,
 * round 9): the attribution's 8 dp gap above the legend, its 21 dp button and 8 dp more (37 dp). Round 8 kept 16 dp,
 * but the "i" reaches 29 dp above the legend, so a snackbar above a tall legend (Tamil at 130 %, a 700 dp landscape
 * map) covered its bottom and the band's scroll ended 1 dp from it.
 */
const val MAP_ATTRIBUTION_STACK_DP = MAP_ATTRIBUTION_OVERLAY_GAP_DP + MAP_ATTRIBUTION_SIZE_DP + 8f

/**
 * The height the band and the snackbar keep clear of at the bottom, in dp (round 9): the bottom controls' measured
 * height ([measuredControlsDp]), or, when the legend has a place at the bottom start ([legendPlaceTopDp] > 0, whether
 * or not it is faded out for a snackbar), its top plus [MAP_ATTRIBUTION_STACK_DP] when that is higher, so the
 * attribution "i" above the legend stays uncovered.
 */
fun controlsClearanceDp(measuredControlsDp: Float, legendPlaceTopDp: Float): Float =
    if (legendPlaceTopDp > 0f) maxOf(measuredControlsDp, legendPlaceTopDp + MAP_ATTRIBUTION_STACK_DP) else measuredControlsDp

/**
 * True when the bottom row of controls reaches the attribution button at the bottom start (a narrow split-screen at
 * a large font): the row's buttons start less than 8 dp after the button's end. [rowWidthDp] includes the row's
 * 16 dp margins, and its lines are end-aligned, so its widest line starts at `mapWidthDp - rowWidthDp + 16`.
 */
fun rowReachesAttribution(mapWidthDp: Float, rowWidthDp: Float): Boolean =
    rowWidthDp > 0f && mapWidthDp - rowWidthDp + MAP_GUTTER_DP < MAP_GUTTER_DP + MAP_ATTRIBUTION_SIZE_DP + 8f

/**
 * The attribution button's bottom margin, in dp (UX review, whole-app audit, rounds 7 and 8):
 * [MAP_ATTRIBUTION_OVERLAY_GAP_DP] (8 dp) above [overlayTopDp], the top of whatever the Map places at the bottom start
 * (the legend's place, even while the legend is faded out for a snackbar, so the "i" never moves when a snackbar
 * comes or goes (round 9); or a row of controls that reaches it), as the web lifts MapLibre's corner controls
 * above its bottom stack (`--map-stack-h`); MapLibre's own 4 dp from the bare map edge when nothing is there (0). A
 * Compose surface over the button would hide the credits and take its taps.
 */
fun attributionBottomDp(overlayTopDp: Float): Float =
    if (overlayTopDp > 0f) overlayTopDp + MAP_ATTRIBUTION_OVERLAY_GAP_DP else MAP_ATTRIBUTION_GAP_DP

/** The 12 dp padding on each side of a snackbar's action button (M3's TextButton), in dp. */
const val SNACKBAR_ACTION_PADDING_DP = 24f

/** The largest share of the snackbar's width an action may take and still sit on the message's line. */
const val SNACKBAR_ACTION_INLINE_MAX_SHARE = 0.3f

/**
 * True when a snackbar's action goes on its own line (M3's `actionOnNewLine`; UX review, whole-app audit, rounds 7
 * and 8), so it does not squeeze the message into a narrow column: when the action's label as measured on the device
 * ([actionWidthDp]: labelLarge, one line, this locale and font scale; 0 for no action) and its button padding take
 * more than 30 % of the snackbar's width ([snackbarWidthDp], the host's width less its margins). Round 7 counted
 * characters (> 12), which is not width across scripts: Hindi "सेटिंग खोलें" is exactly 12 UTF-16 units and stayed
 * inline while English "Open settings" (13) went down, and Telugu and Tamil labels full of viramas and ZWNJ count
 * far more characters than they take up. Before the snackbar's width is known (0), an action goes on its own line.
 */
fun snackbarActionOnNewLine(actionWidthDp: Float, snackbarWidthDp: Float): Boolean {
    if (actionWidthDp <= 0f) return false
    if (snackbarWidthDp <= 0f) return true
    return actionWidthDp + SNACKBAR_ACTION_PADDING_DP > snackbarWidthDp * SNACKBAR_ACTION_INLINE_MAX_SHARE
}

/**
 * The snackbar for a Map control tapped when Android will not ask for location again (UX review, whole-app audit,
 * round 7): one short sentence, [offShort] ("Location is off for Doorprints.") for no location, [approximateOnly]
 * ("Doorprints has only your approximate location.") for approximate only. The Hunt card's note carries the reason
 * and the settings sentence; round 6 put the whole note in the snackbar, which wrapped to 10-20 lines on a phone.
 */
fun refusedTapSnackbarText(fix: LocationFix, offShort: String, approximateOnly: String): String = when (fix) {
    LocationFix.ALLOW, LocationFix.OPEN_SETTINGS -> offShort
    LocationFix.TURN_ON_PRECISE, LocationFix.OPEN_SETTINGS_PRECISE -> approximateOnly
}

/** The house labels' size on the map at 100 % font, in sp (MapLibre's `text-size` is in scaled pixels too). */
const val MARKER_LABEL_SIZE_SP = 12f

/** The largest font scale the house labels follow: beyond it a name would cover its neighbours' dots. */
const val MARKER_LABEL_MAX_SCALE = 1.5f

/**
 * The house labels' `text-size` for the system [fontScale] (UX review, whole-app audit, round 6; WCAG 1.4.4): MapLibre
 * does not apply the font scale itself, so a fixed 12 stayed 12 at 200 %. It follows the scale up to
 * [MARKER_LABEL_MAX_SCALE] (18 at most); a scale below 1 makes them smaller, as it does every other text.
 */
fun markerLabelSizeSp(fontScale: Float): Float =
    MARKER_LABEL_SIZE_SP * fontScale.coerceIn(0.5f, MARKER_LABEL_MAX_SCALE)

/** Beyond this width (ems) a house label wraps to a second line, so a long name does not run across the map. */
const val MARKER_LABEL_MAX_WIDTH_EM = 8f

/**
 * True when [label] has a Devanagari, Tamil or Telugu letter, the scripts whose conjuncts MapLibre's symbol layer may
 * not shape (README section 8, device check 21 (d), a release gate). `housesGeoJson` puts it in each feature as
 * `indic`, so the label layer can leave those names out ([MAP_LABELS_SHOW_INDIC]) rather than draw broken glyphs;
 * the house is still its dot, and its name is in the Houses tab.
 */
fun hasIndicScript(label: String): Boolean = label.any { c ->
    c in '\u0900'..'\u097F' || c in '\u0B80'..'\u0BFF' || c in '\u0C00'..'\u0C7F' || c in '\uA8E0'..'\uA8FF'
}

/**
 * Whether the map labels houses whose names are in an Indic script ([hasIndicScript]). True until device check 21 (d)
 * shows broken conjuncts on a release candidate; then set it to false (one line, no other change): the label layer
 * filters on `indic` with this value, so only names in other scripts are drawn.
 */
const val MAP_LABELS_SHOW_INDIC = true
