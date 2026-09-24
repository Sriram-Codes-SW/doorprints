package app.doorprints.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.HouseEntity
import app.doorprints.location.HuntState
import app.doorprints.ui.res.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How long *Save house here* and *My location* wait for a GPS fix before giving up. */
private const val LOCATE_TIMEOUT_MS = 15_000L

/** A fix older than this makes Hunt mode's card say it is waiting for GPS. */
private const val STALE_FIX_MS = 120_000L

/** What to do once the location permission has been granted. */
private enum class AfterGrant { HUNT, SAVE_HERE, MY_LOCATION }

/**
 * The map. Common since CMP-7 (ADR-23; was `:app`'s `MapScreen`): this is the chrome (the Hunt card, the notes, the
 * controls, the legend, the snackbar and the camera's saved state); the map view itself is [PlatformMap] (Android:
 * MapLibre's `MapView`, with India's boundary through [applyIndiaView]), and Hunt mode, the notification check and the
 * system's motion and font settings come from [MapServices].
 *
 * [showAddTip] is set when the user came here from the empty house list's **Add a house on the map** (UX review,
 * round 15). The map then says how to add a house in a [Snackbar], which `SnackbarHost` announces as a polite live
 * region. [onAddTipShown] clears the one-shot flag as soon as the tip is queued. With touch exploration on, or without
 * the location permission, the tip names *Save house here* (`map_add_tip_a11y`); otherwise it gives both ways.
 *
 * **Permissions in context (whole-app audit).** Nothing is asked when the map opens. Location is asked for by the Hunt
 * switch, *Save house here* and *My location*; notifications only when Hunt mode is turned on, with one line of why
 * ([rememberNotificationAsk]). Both are checked again on every resume, so a permission granted in system settings
 * counts at once. Once location has been refused, the Hunt card says so ("Location is off for Doorprints…") with
 * *Allow location*, or *Open settings* when Android will not ask again (whether it will is [rememberLocationAsk],
 * shared with the house form and the Assistant and recomputed after each answer). With approximate location only
 * (round 3), it says "Doorprints has only your approximate location…" with *Turn on precise location*, which asks
 * again so Android shows "Change to precise location?" ([LocationPermissionNote], the same note the form and the
 * Assistant show, each with its own reason; here "Hunt mode and ‘Save house here’ need precise location."). While
 * Hunt mode is on and no alert could reach the user (notification permission, app notifications or the Alerts
 * channel off), the card says that too, with *Allow notifications*. Each note carries its own button ([WarnNote]).
 * Every other message is a snackbar (announced), never a toast.
 *
 * **A refusal is said once, by the note (round 5).** There is no refusal snackbar any more: one pointing at the Hunt
 * card sat on the card's own button on a short map. Each note is in a polite [LiveMessage], so TalkBack reads the full
 * sentence once when it appears; the note is hidden while Android's prompt is up (`asking`), so it appears, and is
 * read, after the answer, and the band scrolls to it ([BringIntoViewRequester]). A tap on the Hunt switch, *Save
 * house here* or *My location* when Android will not ask again ([locationStart] SHOW_NOTE) starts nothing: it brings
 * the note into view and, with TalkBack on, moves focus to it, so the reason and *Open settings* are read. Without
 * TalkBack (round 6) the note may already be in view, far from the thumb, so the tap also gives a "reject" haptic and
 * a snackbar next to the button just tapped: without it the tap looked like it did nothing. Round 7: the snackbar is
 * one short sentence ("Location is off for Doorprints." or "Doorprints has only your approximate location.";
 * [refusedTapSnackbarText]) with *Open settings*, shown for SnackbarDuration.Long as it carries an action; the note
 * keeps the reason (the whole note wrapped to 10-20 lines in a snackbar). An action whose measured label (labelLarge,
 * this locale and font scale) and button padding take more than 30 % of the snackbar's width goes on its own line
 * ([snackbarActionOnNewLine]; round 8: measured, not counted in characters, so en, hi, ta and te lay out alike). The
 * snackbar offers the note's own action, so covering the note's button costs nothing.
 *
 * **Large text (round 3, WCAG 1.4.4).** The top band (Hunt card, map notes) ends above the bottom controls (their
 * measured height, at least 270 dp as a column; [topBandMaxHeightDp]) and scrolls inside that, so no note sits under
 * a button at 130 % or 200 % font. The snackbar is not counted (round 4), so the band does not jump each time one
 * comes and goes. Beside the bottom row when the map is wide enough ([snackbarBesideRow], round 5: a landscape
 * phone), so "Finding your location…" covers neither the band nor the row; otherwise just above the controls, where
 * it may cover the bottom of the band for its few seconds. While Hunt mode is on the switch says "on" and the
 * subtitle is dropped.
 *
 * **Short map (round 4; WCAG 1.3.4, 1.4.10, 2.5.8).** Below 480 dp of map height (a phone in landscape, split-screen,
 * a half-open foldable; [mapControlsInRow]) the controls are one row at the bottom end, [Zoom out][Zoom in][My
 * location][Save house here], 8 dp apart (*Save house here* wraps to a second line on a narrow map), so the band gets
 * everything above about 88 dp. The column's four buttons used to leave the band a 72 dp quarter with Zoom in drawn
 * on the Hunt switch. If a column ever leaves the band less than a quarter, the band keeps the quarter beside it,
 * inset 80 dp from the end ([topBandEndInsetDp]), so a button never covers the card.
 *
 * **Waiting for a fix.** *Save house here* and *My location* show a spinner in place of their icon (the labels stay,
 * so the buttons do not change width, and TalkBack hears the name with the state "Finding your location…"), announce
 * it in a snackbar, ignore further taps while it runs, and give up after 15 s with a tip, so a slow cold fix can no
 * longer open two new-house forms.
 *
 * **Where the map is.** The camera (centre, zoom, bearing) is saved state: coming back from a house, another tab or a
 * rotation shows the same place. Only the first time does the map frame itself: all houses, or the user's location
 * at zoom 16 when there are none. Until that framing (or a restored camera, or the user moving the map: a gesture,
 * the zoom buttons or *My location*) `framed` is false and camera-idle saves nothing, so the whole-of-India start is
 * never kept as the user's place.
 *
 * **Loading and errors.** "Loading the map…" until the style arrives; if the map cannot load (offline, or the tile
 * service fails), an error card with *Try again* and *Open Houses*: every house is in the Houses tab.
 *
 * **Legend (round 6; UX-002, A11Y-003, WCAG 1.4.1).** The markers tell status by size, ring and opacity as well as
 * colour, and [MapLegend] says what they mean, as the web map does: ● New, ★ Shortlisted, ✕ Rejected as dots drawn
 * like the markers, each with its status name. Where it goes is [legendPlace] (round 7), from the widest status name
 * measured on the device (`rememberTextMeasurer`, the legend's style, this locale and font scale; [legendMinWidthDp]),
 * so no name ever breaks mid-word: in the column layout at the bottom start, beside *Save house here*
 * ([legendBesideFab]) or above it; in the row layout at the bottom start on its own, with the four controls at the
 * bottom end (the web's legend-start / actions-end row), faded out (150 ms, scaled by the system animator duration
 * scale, so none under *Remove animations*) while a snackbar sits beside the row in its place (round 8); and
 * where neither leaves room (a 360 dp phone at 200 % font in Tamil, a narrow split-screen), as the last item of the
 * top band, under the Hunt card. Its three items are on one line when that fits the width it is given, otherwise one
 * per line, never a mix ([legendFitsOneLine], round 8). The band and the snackbar keep clear of a legend taller than
 * the controls. Hidden while the map has failed or its style has not loaded. The house names on the map follow the font scale up to 1.5×
 * ([markerLabelSizeSp]) and wrap after 8 ems.
 *
 * **MapLibre's own controls (rounds 7 and 8).** Its attribution button ("i": the OpenStreetMap, OpenMapTiles and
 * OpenFreeMap credits that ODbL and OpenFreeMap require) is on the 16 dp gutter and lifted 8 dp above whatever the Map
 * draws at the bottom start ([attributionBottomDp]: the legend while it is drawn, or a row of controls that reaches
 * it), 4 dp from the bare map edge otherwise, as the web lifts its corner controls with `--map-stack-h`, so no Compose
 * surface hides it or takes its taps. While a snackbar sits beside the landscape row, in the legend's place, the "i"
 * is hidden along with the legend and comes back in the same place when it goes (round 10), never half covered. Its
 * logo is off (the BSD licence does not ask for it; the web shows none). The
 * map is north-up ([MAP_NORTH_UP], round 8): rotation, tilt and the compass are off, as on the web, so no rotated map
 * is ever left without a visible way back to north (WCAG 2.5.1), and a camera saved before is restored at bearing 0.
 *
 * **Gutters (round 6).** The Hunt card's edge, the bottom controls, the legend and the snackbar are all 16 dp from the
 * screen edge, as on every other screen (Rows.kt).
 *
 * **Motor access.** A tap hits any marker within a 48 dp square, and Zoom in / Zoom out buttons (48 dp) stand in for
 * pinch and double-tap (docs/05, 2.5.1); under *Remove animations* they move without animating (read again on every
 * resume, so turning it on in settings counts when the user comes back).
 */
@Composable
fun MapScreen(
    onOpenHouse: (String) -> Unit,
    onNewHouse: (Double, Double) -> Unit,
    onOpenHouses: () -> Unit = {},
    showAddTip: Boolean = false,
    onAddTipShown: () -> Unit = {},
    deletedHouse: String? = null,
    onDeletedShown: () -> Unit = {},
) {
    val platform = LocalPlatformServices.current
    val services = LocalAppServices.current
    val mapServices = services.mapScreen
    val repo = services.repository
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val addTip = stringResource(Res.string.map_add_tip)
    val addTipA11y = stringResource(Res.string.map_add_tip_a11y)
    val findingText = stringResource(Res.string.common_finding_location)
    val waitingGps = stringResource(Res.string.map_waiting_gps)
    val longPressTip = stringResource(Res.string.map_long_press_tip)
    val needsLocation = stringResource(Res.string.map_save_needs_location)
    val locationNeeded = stringResource(Res.string.map_location_needed)
    val myLocationLabel = stringResource(Res.string.map_my_location)
    LaunchedEffect(showAddTip) {
        if (showAddTip) {
            onAddTipShown()
            val tip = if (platform.isScreenReaderOn() || !hasPreciseLocation(platform)) addTipA11y else addTip
            // In the screen's scope, not this effect's: clearing the flag restarts this effect, which must not
            // cancel the snackbar it has just shown.
            scope.launch { snackbar.showSnackbar(tip, withDismissAction = true, duration = SnackbarDuration.Long) }
        }
    }
    // A house deleted from its form: "Deleted Green Villa" with Undo.
    LaunchedEffect(deletedHouse) {
        val id = deletedHouse ?: return@LaunchedEffect
        onDeletedShown()
        scope.launch { offerDeletedHouseUndo(repo, snackbar, id) }
    }
    // null until Room answers, so the first framing knows "no houses" from "not loaded yet".
    val loadedHouses: List<HouseEntity>? by repo.houses.collectAsStateWithLifecycle(initialValue = null)
    val houses = loadedHouses.orEmpty()
    val hunt by mapServices.hunt.collectAsStateWithLifecycle()
    var map by remember { mutableStateOf<MapControl?>(null) }
    // How many times the base style has loaded (0: not yet): the effects that follow a style load key on it, as they
    // keyed on MapLibre's Style object before CMP-7.
    var styleLoads by remember { mutableIntStateOf(0) }
    var mapFailed by remember { mutableStateOf(false) }
    var camera by rememberSaveable(stateSaver = CameraSpotSaver) { mutableStateOf<CameraSpot?>(null) }
    // False until the map has been placed once: by the first framing, a restored camera, or the user moving it. Until
    // then the camera-idle listener saves nothing, so the "whole of India" start is never kept as the user's place
    // (UX review, whole-app audit, round 2: MapLibre delivers the idle of the initial setCameraPosition after the
    // listener is registered, and the framing then saw a saved camera and never ran).
    var framed by rememberSaveable { mutableStateOf(false) }

    // Permissions, checked again on every resume (a grant in system settings counts as soon as the user is back).
    // Whether location was asked, and whether Android will still ask, is shared with the house form and the Assistant
    // (LocationPermission.kt) and recomputed after every answer, not during composition.
    val locationAsk = rememberLocationAsk()
    var permissionGranted by remember { mutableStateOf(hasPreciseLocation(platform)) }
    var notificationsReach by remember { mutableStateOf(mapServices.notificationsReachUser()) }
    // Under *Remove animations* the camera jumps; read again on resume, so the setting counts when the user is back.
    var noAnimations by remember { mutableStateOf(mapServices.animationsOff()) }
    LifecycleResumeEffect(Unit) {
        permissionGranted = hasPreciseLocation(platform)
        notificationsReach = mapServices.notificationsReachUser()
        noAnimations = mapServices.animationsOff()
        onPauseOrDispose { }
    }
    // A refusal is said once, by the Hunt card's note with its next step (round 5): no snackbar. The note is hidden
    // while Android's prompt is up, so it appears after the answer and its LiveMessage reads it once; the band then
    // scrolls to it. Approximate only is not "no permission" (round 3).
    var asking by rememberSaveable { mutableStateOf(false) }
    val noteView = remember { BringIntoViewRequester() }
    val noteFocus = remember { FocusRequester() }
    // Bumped to bring the note into view one frame later, once it is composed and laid out.
    var revealNote by remember { mutableIntStateOf(0) }
    var focusNoteOnReveal by remember { mutableStateOf(false) }
    fun revealLocationNote(focus: Boolean) {
        focusNoteOnReveal = focus
        revealNote++
    }
    LaunchedEffect(revealNote) {
        if (revealNote == 0) return@LaunchedEffect
        withFrameNanos { }
        noteView.bringIntoView()
        // Only when nothing new appeared (Android will not ask, so the note was already there and already read):
        // with TalkBack on, focus moves to the note, so the tap is answered by its reason and *Open settings*.
        if (focusNoteOnReveal && platform.isScreenReaderOn()) runCatching { noteFocus.requestFocus() }
    }
    // Without TalkBack, a tap that starts nothing is answered next to the thumb (round 6): a "reject" haptic and a
    // snackbar of one short sentence with the note's next step, *Open settings* (round 7: the note carries the reason;
    // the whole note wrapped to 10-20 lines in a snackbar). With TalkBack, focus on the note says it once.
    val haptic = LocalHapticFeedback.current
    val locationOffShort = stringResource(Res.string.location_off_short)
    val approximateOnlyText = stringResource(Res.string.location_approximate_only)
    val openSettingsLabel = stringResource(Res.string.perm_open_settings)
    var refusedTapSnackbar by remember { mutableStateOf<Job?>(null) }
    fun answerRefusedTap() {
        // Android will not ask again, so the note offers *Open settings* (with or without approximate location).
        val fix = locationAsk.fix ?: return
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
        val message = refusedTapSnackbarText(fix, locationOffShort, approximateOnlyText)
        refusedTapSnackbar?.cancel()
        refusedTapSnackbar = scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = openSettingsLabel,
                withDismissAction = true,
                // Long, as it carries an action (M3): time to read it and reach *Open settings*.
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) platform.openAppSettings()
        }
    }
    var afterGrant by remember { mutableStateOf<AfterGrant?>(null) }
    var grantedFor by remember { mutableStateOf<AfterGrant?>(null) }
    val requestLocation = rememberLocationPermissionRequest {
        permissionGranted = hasPreciseLocation(platform)
        locationAsk.refresh()
        asking = false
        val next = afterGrant
        afterGrant = null
        if (permissionGranted) {
            grantedFor = next
        } else {
            // The Hunt card's note appears now (asked is set), with *Allow location*, *Turn on precise location* or,
            // after a second refusal, *Open settings*; its LiveMessage reads it, and the band scrolls to it.
            revealLocationNote(focus = false)
        }
    }
    /**
     * False when [next] can run now (precise location). Otherwise asks for location while Android will still ask, or
     * brings the Hunt card's note (with *Open settings*) into view when it will not ([locationStart]), and returns true.
     * In that last case, without TalkBack, [answerRefusedTap] also answers the tap where it happened (round 6).
     */
    fun needsLocation(next: AfterGrant): Boolean {
        when (locationStart(precise = permissionGranted, canAsk = locationAsk.canAsk)) {
            LocationStart.RUN -> return false
            LocationStart.ASK -> {
                afterGrant = next
                asking = true
                locationAsk.markAsked()
                requestLocation()
            }
            LocationStart.SHOW_NOTE -> {
                revealLocationNote(focus = true)
                if (!platform.isScreenReaderOn()) answerRefusedTap()
            }
        }
        return true
    }

    // *Allow notifications*: the system's prompt while it will still show, otherwise the notification settings.
    val allowNotifications = mapServices.rememberAllowNotifications {
        notificationsReach = mapServices.notificationsReachUser()
    }
    // Notifications are asked for in context, when Hunt mode is turned on; Hunt mode starts whatever the answer.
    val notifyAsk = rememberNotificationAsk(rationale = Res.string.map_hunt_notify_rationale)

    fun startHunt() {
        if (needsLocation(AfterGrant.HUNT)) return
        notifyAsk {
            // start() returns false when the permission was revoked since this screen last checked.
            if (!mapServices.startHunt()) {
                permissionGranted = hasPreciseLocation(platform)
                scope.launch { snackbar.showSnackbar(locationNeeded, withDismissAction = true) }
            }
            notificationsReach = mapServices.notificationsReachUser()
        }
    }

    // One location lookup at a time, whichever button started it (see "Waiting for a fix"). Plain remember: a
    // rotation cancels the lookup, and a restored true would block both buttons for good.
    var locating by remember { mutableStateOf(false) }
    val currentOnNewHouse by rememberUpdatedState(onNewHouse)
    fun locate(then: (Pair<Double, Double>) -> Unit) {
        if (locating) return
        locating = true
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val finding = launch { snackbar.showSnackbar(findingText, duration = SnackbarDuration.Indefinite) }
            val here = try {
                withTimeoutOrNull(LOCATE_TIMEOUT_MS) { services.location.current() }
            } finally {
                finding.cancel()
                locating = false
            }
            if (here != null) {
                then(here)
            } else {
                // Long-press is no way out for a TalkBack user (A11Y-B02): say what the button needs instead.
                val tip = if (platform.isScreenReaderOn()) needsLocation else longPressTip
                snackbar.showSnackbar("$waitingGps $tip", withDismissAction = true, duration = SnackbarDuration.Long)
            }
        }
    }
    fun saveHere() {
        if (needsLocation(AfterGrant.SAVE_HERE)) return
        locate { (lat, lon) -> currentOnNewHouse(lat, lon) }
    }
    fun goToMe() {
        if (needsLocation(AfterGrant.MY_LOCATION)) return
        locate { (lat, lon) ->
            map?.let {
                // The user chose where to look: the first framing must not move the map away afterwards.
                framed = true
                it.moveTo(lat, lon, 17.0, animate = !noAnimations)
            }
        }
    }
    // Once the permission the user was asked for is granted, carry on with what they tapped.
    LaunchedEffect(grantedFor) {
        val next = grantedFor ?: return@LaunchedEffect
        grantedFor = null
        when (next) {
            AfterGrant.HUNT -> startHunt()
            AfterGrant.SAVE_HERE -> saveHere()
            AfterGrant.MY_LOCATION -> goToMe()
        }
    }

    val density = LocalDensity.current
    val framePaddingPx = with(density) { 64.dp.roundToPx() }
    // The house labels follow the font scale (round 6; WCAG 1.4.4): read at style load and again on every resume, so
    // a change in system settings counts when the user comes back.
    var labelFontScale by remember { mutableFloatStateOf(density.fontScale) }
    LifecycleResumeEffect(Unit) {
        labelFontScale = mapServices.fontScale()
        onPauseOrDispose { }
    }

    // The first framing only (see "Where the map is"): once the style and the houses are both loaded. Keyed on framed,
    // so a pan while the user's location is being looked up cancels it.
    LaunchedEffect(styleLoads, loadedHouses != null, permissionGranted, framed) {
        if (framed) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val list = loadedHouses ?: return@LaunchedEffect
        if (styleLoads == 0) return@LaunchedEffect
        when {
            list.size == 1 -> m.moveTo(list[0].lat, list[0].lon, 15.0, animate = false)
            // Houses on one spot give an empty box, which zooms in as far as the map goes: at most 16.
            list.size > 1 -> m.frame(list.map { it.lat to it.lon }, framePaddingPx, maxZoom = 16.0)
            permissionGranted -> services.location.current()?.let { (lat, lon) ->
                m.moveTo(lat, lon, 16.0, animate = false)
            } ?: return@LaunchedEffect
            else -> return@LaunchedEffect
        }
        framed = true
        m.camera()?.let { camera = it }
    }

    val mapDescription = stringResource(Res.string.map_region_desc)
    // The bottom controls' height as last measured, one per layout (0 until then; a larger font makes them taller),
    // so a rotation never sizes the band from the other layout's height. The snackbar is not in it (round 4). The
    // row's width too (round 5), to decide whether the snackbar fits beside it.
    var columnControlsPx by remember { mutableIntStateOf(0) }
    var rowControlsPx by remember { mutableIntStateOf(0) }
    var rowControlsWidthPx by remember { mutableIntStateOf(0) }
    // *Save house here*'s own size (round 6), so the column layout's legend keeps clear of it.
    var fabWidthPx by remember { mutableIntStateOf(0) }
    var fabHeightPx by remember { mutableIntStateOf(0) }
    // The legend's own height at the bottom start (round 7), to lift MapLibre's attribution above it.
    var legendHeightPx by remember { mutableIntStateOf(0) }
    // The status names measured at the legend's style, one line, for this locale and font scale (as ActionBar measures
    // its labels): the narrowest legend that breaks no name (round 7: the widest name, its dot box, gap and padding),
    // and its width with all three on one line (round 8: one line or one item per line, never a mix).
    val legendStyle = legendTextStyle()
    val legendLabels = LEGEND_DOTS.map { stringResource(statusLabel(it.status)) }
    val textMeasurer = rememberTextMeasurer()
    val legendLabelWidthsDp = remember(textMeasurer, legendLabels, legendStyle, density) {
        legendLabels.map { label ->
            val px = textMeasurer.measure(label, legendStyle, softWrap = false, maxLines = 1).size.width
            with(density) { px.toDp() }.value
        }
    }
    val legendMinWidth = legendMinWidthDp(legendLabelWidthsDp.maxOrNull() ?: 0f)
    val legendOneLineWidth = legendOneLineWidthDp(legendLabelWidthsDp)
    // The snackbar action's style (M3's labelLarge), to measure its label (round 8; snackbarActionOnNewLine).
    val snackbarActionStyle = MaterialTheme.typography.labelLarge
    // The web's mapUsable(): the legend explains markers that are on screen, so not while the map failed or loads.
    val mapUsable = !mapFailed && styleLoads > 0
    // With TalkBack on, the location note can take focus (see "A refusal is said once"); otherwise it is no tab stop.
    val noteModifier = Modifier.bringIntoViewRequester(noteView).focusRequester(noteFocus)
        .then(if (platform.isScreenReaderOn()) Modifier.focusable() else Modifier)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A short map (landscape, split-screen, a half-open foldable) lays the controls out in one row (MapRules).
        val controlsInRow = mapControlsInRow(maxHeight.value)
        val fabWidthDp = with(density) { fabWidthPx.toDp() }
        val fabHeightDp = with(density) { fabHeightPx.toDp() }
        val rowWidthDp = with(density) { rowControlsWidthPx.toDp() }
        val rowHeightDp = with(density) { rowControlsPx.toDp() }
        // Where the legend goes (round 7; MapRules.legendPlace): beside or above *Save house here*, at the start of the
        // bottom row, or in the top band; null (not drawn) before what it depends on is measured.
        val legendAt = if (mapUsable) {
            legendPlace(controlsInRow, maxWidth.value, fabWidthDp.value, rowWidthDp.value, legendMinWidth)
        } else {
            null
        }
        val legendBottom = when (legendAt) {
            LegendPlace.ABOVE_FAB -> 16.dp + fabHeightDp + 12.dp
            LegendPlace.BESIDE_FAB, LegendPlace.BESIDE_ROW -> 16.dp
            LegendPlace.IN_BAND, null -> 0.dp
        }
        // A snackbar beside the row sits where the row layout's legend is, so the legend fades out while it shows
        // (round 7; round 8 fades instead of cutting). The attribution stays above the legend's place (round 9), and
        // is hidden while the snackbar is there (round 10).
        val snackbarAtStart = controlsInRow && snackbarBesideRow(maxWidth.value, rowWidthDp.value) &&
            snackbar.currentSnackbarData != null
        // The top of the legend's place at the bottom start, or 0 when it has none (or is not measured yet). The band
        // and the snackbar keep clear of it even while the legend is faded out, so the band does not jump each time a
        // snackbar comes and goes (round 4).
        val legendPlaceTop = if (legendBottom > 0.dp && legendHeightPx > 0) {
            legendBottom + with(density) { legendHeightPx.toDp() }
        } else {
            0.dp
        }
        // Whether the legend is drawn at the bottom start now (round 8): not in the band, and not faded out for a
        // snackbar beside the row. Only the legend's fade uses it (round 9).
        val legendShown = legendAt != null && legendAt != LegendPlace.IN_BAND &&
            !(legendAt == LegendPlace.BESIDE_ROW && snackbarAtStart)
        // The bottom controls' measured height, and the legend's top plus the attribution's stack (37 dp; round 9,
        // MapRules.controlsClearanceDp) when that is higher (a tall legend beside the row), so the band and the
        // snackbar keep clear of the controls, the legend's place and the "i" above it.
        val measuredControlsDp = if (controlsInRow) rowHeightDp else with(density) { columnControlsPx.toDp() }
        val controlsDp = controlsClearanceDp(measuredControlsDp.value, legendPlaceTop.value).dp
        // MapLibre's attribution button ("i", the OpenStreetMap / OpenFreeMap credits) sits on the 16 dp gutter, 8 dp
        // above whatever the Map places at the bottom start (rounds 7 to 9), as the web lifts it with --map-stack-h:
        // the legend's place, or a row of controls that reaches it (a narrow split-screen); 4 dp from the bare edge
        // otherwise. The legend's place, not whether it is shown (round 9): the margin is set without animation, so
        // following the fade made the "i" jump down and up with every snackbar. The logo is off: MapLibre's BSD
        // licence does not ask for it, and the web shows none.
        val attributionOverlayTop = when {
            legendAt != null && legendAt != LegendPlace.IN_BAND && legendPlaceTop > 0.dp -> legendPlaceTop.value
            controlsInRow && rowReachesAttribution(maxWidth.value, rowWidthDp.value) -> (rowHeightDp - 16.dp).value
            else -> 0f
        }
        val attributionBottomPx = with(density) { attributionBottomDp(attributionOverlayTop).dp.roundToPx() }
        val gutterPx = with(density) { MAP_GUTTER_DP.dp.roundToPx() }
        // Round 10: while a snackbar sits beside the landscape row, in the legend's place, the "i" is hidden with the
        // legend instead of being left half covered (a one-line snackbar 48 dp tall from 16 dp up cuts through a
        // 21 dp "i" about 54-75 dp up, which looks like a rendering bug; "Finding your location…" can stay 15 s). It
        // is not moved, so it comes back in the same place, whole and tappable, as soon as the snackbar goes; the
        // credits are one tap away again then (MapLibre's setAttributionEnabled only toggles the view's visibility).
        // The snackbar's width (its host less the margins), for where its action goes (round 8).
        val snackbarWidthDp = if (controlsInRow && snackbarBesideRow(maxWidth.value, rowWidthDp.value)) {
            maxWidth - 16.dp - rowWidthDp - 8.dp
        } else {
            maxWidth - 32.dp
        }
        val topBandMax = topBandMaxHeightDp(maxHeight.value, controlsDp.value).dp
        val topBandEndInset = topBandEndInsetDp(maxHeight.value, controlsDp.value).dp
        PlatformMap(
            houses = houses,
            labelSizeSp = markerLabelSizeSp(labelFontScale),
            showLocation = permissionGranted,
            attribution = MapAttribution(gutterPx, attributionBottomPx, shown = !snackbarAtStart),
            events = object : MapEvents {
                override fun onReady(control: MapControl) {
                    map = control
                    // Where the user left it (that counts as framed); the whole of India only before the first
                    // framing, and not saved as a place. A camera saved while rotation was allowed comes back
                    // north-up.
                    val restored = camera
                    framed = restored != null
                    if (restored != null) {
                        val bearing = if (MAP_NORTH_UP) 0.0 else restored.bearing
                        control.setCamera(restored.lat, restored.lon, restored.zoom, bearing)
                    } else {
                        control.setCamera(INDIA_START_LAT, INDIA_START_LON, INDIA_START_ZOOM, bearing = null)
                    }
                }

                override fun onStyleLoaded() {
                    styleLoads++
                }

                override fun onFailed() {
                    mapFailed = true
                }

                // A pan, pinch or double-tap by the user (even before the style arrives) keeps their place: the first
                // framing then never runs.
                override fun onUserGesture() {
                    framed = true
                }

                override fun onCameraIdle(spot: CameraSpot) {
                    if (framed) camera = spot
                }

                override fun onHouseTap(id: String) = onOpenHouse(id)

                override fun onLongPress(lat: Double, lon: Double) = currentOnNewHouse(lat, lon)
            },
            // The canvas itself is not navigable with TalkBack; every house is also in the Houses tab (A11Y-B02).
            modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
        )

        // The top band ends above the bottom controls and scrolls within that (see "Large text" and "Short map"). The
        // outer 12 dp is outside the scroll, so a drag in the margin still pans the map; the inner 4 dp keeps the
        // card's shadow, so the card's edge is on the 16 dp gutter of the controls and the snackbar (round 6). Beside
        // a column it could not end above, it stops short of the buttons (topBandEndInset). No spacedBy (round 6): the
        // map-status message pads its own content, so while idle it adds 1 dp, not an invisible 9 dp strip that
        // scrolled the band instead of panning the map.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(max = topBandMax)
                .padding(end = topBandEndInset).padding(12.dp)
                .verticalScroll(rememberScrollState()).padding(4.dp),
        ) {
            HuntCard(
                hunt = hunt,
                onToggle = { on -> if (!on) mapServices.stopHunt() else startHunt() },
                onOpenHouse = onOpenHouse,
                // Refused, or approximate only (which is a grant, so it shows whether or not this app asked); not
                // while Android's prompt is up, so it appears, and is read, after the answer.
                showLocationNote = !permissionGranted && !asking && (locationAsk.asked || locationAsk.approximateOnly),
                locationAsk = locationAsk,
                noteModifier = noteModifier,
                // The note records the ask and picks request or settings itself (LocationPermissionNote).
                onRequestLocation = {
                    afterGrant = null
                    requestLocation()
                },
                notificationsOff = hunt.active && !notificationsReach,
                onAllowNotifications = allowNotifications,
                onCloseStopReason = { mapServices.clearHuntStopReason() },
            )
            // Always composed, so the error or the loading line is announced when it appears.
            LiveMessage(assertive = mapFailed) {
                when {
                    mapFailed -> ResultCard(
                        tone = ResultTone.ERROR,
                        text = stringResource(Res.string.map_failed),
                        modifier = Modifier.padding(top = 8.dp),
                        actions = {
                            ResultActionsRow {
                                TextButton(
                                    onClick = {
                                        mapFailed = false
                                        map?.reloadStyle()
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { ButtonLabel(stringResource(Res.string.common_try_again)) }
                                TextButton(onClick = onOpenHouses, modifier = Modifier.heightIn(min = 48.dp)) {
                                    ButtonLabel(stringResource(Res.string.map_open_houses))
                                }
                            }
                        },
                    )
                    styleLoads == 0 -> Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(Res.string.map_loading), style = MaterialTheme.typography.bodyMedium)
                            ProgressBar()
                        }
                    }
                }
            }
            // No room for the legend at the bottom (round 7): the band's last item, full width under the Hunt card.
            if (legendAt == LegendPlace.IN_BAND) {
                MapLegend(legendOneLineWidth, Modifier.padding(top = 8.dp).fillMaxWidth())
            }
        }

        // The four controls, laid out as a column or a row below.
        val zoomInButton: @Composable () -> Unit = {
            // Zoom without pinch or double-tap (docs/05, 2.5.1).
            SmallFloatingActionButton(
                onClick = {
                    map?.let {
                        framed = true
                        it.zoomIn(animate = !noAnimations)
                    }
                },
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.map_zoom_in)) }
        }
        val zoomOutButton: @Composable () -> Unit = {
            SmallFloatingActionButton(
                onClick = {
                    map?.let {
                        framed = true
                        it.zoomOut(animate = !noAnimations)
                    }
                },
                modifier = Modifier.size(48.dp),
            ) { Icon(MinusIcon, contentDescription = stringResource(Res.string.map_zoom_out)) }
        }
        val myLocationButton: @Composable () -> Unit = {
            // The name is on the button, not on the icon, so it stays while the spinner shows; the state says why.
            SmallFloatingActionButton(
                onClick = { goToMe() },
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = myLocationLabel
                    if (locating) stateDescription = findingText
                },
            ) {
                if (locating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                }
            }
        }
        val saveHereButton: @Composable () -> Unit = {
            // Only the icon changes while locating: the label stays, so the button keeps its width (round 3).
            ExtendedFloatingActionButton(
                onClick = { saveHere() },
                icon = {
                    if (locating) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                },
                text = { Text(stringResource(Res.string.map_save_here)) },
                modifier = Modifier
                    .onSizeChanged {
                        fabWidthPx = it.width
                        fabHeightPx = it.height
                    }
                    .semantics { if (locating) stateDescription = findingText },
            )
        }
        // Neither layout has pointer input of its own, so the map still gets the touches between and beside the
        // buttons. The measured size includes the 16 dp margin.
        if (controlsInRow) {
            // The legend at the bottom start and the controls at the bottom end (round 7), as the web's phone row puts
            // the legend first and the actions last. Faded out (150 ms; round 8, instead of a cut that read as a
            // glitch) while a snackbar sits beside the row, in its place, so "Finding your location…" goes back
            // beside the row (round 5) instead of over the Hunt card. Compose scales the fade by the system animator
            // duration scale, so under *Remove animations* it is instant, as ActionBar's transitions are.
            if (legendAt == LegendPlace.BESIDE_ROW) {
                AnimatedVisibility(
                    visible = legendShown,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 16.dp, end = rowWidthDp),
                    enter = fadeIn(tween(ANIMATION_MS)),
                    exit = fadeOut(tween(ANIMATION_MS)),
                ) {
                    MapLegend(legendOneLineWidth, Modifier.onSizeChanged { legendHeightPx = it.height })
                }
            }
            // One row at the bottom end, 8 dp apart; *Save house here* wraps to a second line when the map is too
            // narrow for all four (a 50/50 split-screen at a large font).
            FlowRow(
                Modifier.align(Alignment.BottomEnd)
                    .onSizeChanged {
                        rowControlsPx = it.height
                        rowControlsWidthPx = it.width
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                zoomOutButton()
                zoomInButton()
                myLocationButton()
                saveHereButton()
            }
        } else {
            // The legend at the bottom start (round 6): beside *Save house here* (16 dp from it; one item per line on
            // a phone), or above it, clear of the 48 dp buttons, when the button leaves it too little room; in the top
            // band when even that is too narrow for the widest status name (round 7; MapRules.legendPlace).
            when (legendAt) {
                LegendPlace.BESIDE_FAB -> MapLegend(
                    legendOneLineWidth,
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = legendBottom, end = fabWidthDp + MAP_LEGEND_BESIDE_FAB_DP.dp)
                        .onSizeChanged { legendHeightPx = it.height },
                )
                LegendPlace.ABOVE_FAB -> MapLegend(
                    legendOneLineWidth,
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = legendBottom, end = MAP_CONTROL_COLUMN_INSET_DP.dp)
                        .onSizeChanged { legendHeightPx = it.height },
                )
                else -> Unit
            }
            Column(
                Modifier.align(Alignment.BottomEnd).onSizeChanged { columnControlsPx = it.height }.padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                zoomInButton()
                zoomOutButton()
                myLocationButton()
                saveHereButton()
            }
        }
        // The snackbar is not part of the controls' measured height (round 4): it is transient, so the band does not
        // grow and shrink each time one comes and goes. Beside the row when the map is wide enough (round 5; a phone
        // in landscape), so it covers neither the band nor a button; otherwise just above the controls, where it may
        // cover the lower part of the band for its few seconds. The column layout keeps it above.
        // One host whichever the place, so a snackbar on screen stays through a resize. An action whose measured label
        // and padding take more than 30 % of the snackbar's width ("Open settings" on a phone, in every language) goes
        // on its own line (rounds 7 and 8), so it does not squeeze the message into a narrow column.
        SnackbarHost(
            snackbar,
            if (controlsInRow && snackbarBesideRow(maxWidth.value, rowWidthDp.value)) {
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(start = 16.dp, bottom = 16.dp, end = rowWidthDp + 8.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = controlsDp)
                    .padding(horizontal = 16.dp)
            },
        ) { data ->
            val actionWidthDp = data.visuals.actionLabel?.let { label ->
                val px = textMeasurer.measure(label, snackbarActionStyle, softWrap = false, maxLines = 1).size.width
                with(density) { px.toDp() }.value
            } ?: 0f
            Snackbar(data, actionOnNewLine = snackbarActionOnNewLine(actionWidthDp, snackbarWidthDp.value))
        }
    }
}

/** Material's "remove" glyph (a minus), built from its path: the core icon set has none. */
private val MinusIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Minus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes("M19,13H5v-2h14v2z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/**
 * Hunt mode's card: the switch, and while it is on the street, the nearest saved house and the GPS state.
 *
 * **Quiet feedback (UX-006; whole-app audit).** The visible details are not a live region: the nearest house's
 * distance changes on every fix, and TalkBack read it out every few seconds. One always-composed 1 dp node announces
 * only events: a new street, a different nearest house, and the GPS state (waiting or weak), never the distance. The
 * staleness check runs on a 15 s clock, so "Waiting for a GPS signal…" appears when fixes stop coming.
 *
 * Under the switch, when they apply: why Hunt mode stopped by itself ([HuntState.State.stopReason], a NEUTRAL card
 * with a close button), location off or approximate only ([LocationPermissionNote], with [noteModifier]: the Map's
 * bring-into-view and focus hooks), and notifications off, each note with its own button, each in a polite
 * [LiveMessage] (round 5). A weak or missing GPS fix is muted text, not error red: it is a condition, not a failure.
 * While Hunt mode is on the switch already says so, so the subtitle shows only while it is off (round 3: a more
 * compact card at large text sizes).
 *
 * **Even padding (round 6).** The announcer is drawn over the card's bottom edge rather than in the column, and each
 * empty note [LiveMessage] (1 dp, so it is in the tree) is taken off the bottom padding, so the card's last visible
 * line is 12 dp from its edge whichever notes show, the same as the top.
 */
@Composable
private fun HuntCard(
    hunt: HuntState.State,
    onToggle: (Boolean) -> Unit,
    onOpenHouse: (String) -> Unit,
    showLocationNote: Boolean,
    locationAsk: LocationAsk,
    noteModifier: Modifier,
    onRequestLocation: () -> Unit,
    notificationsOff: Boolean,
    onAllowNotifications: () -> Unit,
    onCloseStopReason: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by produceState(nowMillis()) {
        while (true) {
            delay(15_000)
            value = nowMillis()
        }
    }
    // Locals: HuntState is in :shared since CMP-7, so its properties no longer smart-cast here.
    val lastFixAt = hunt.lastFixAt
    val accuracyM = hunt.accuracyM
    val stale = lastFixAt == null || now - lastFixAt > STALE_FIX_MS
    val weak = !stale && accuracyM != null && accuracyM > HuntState.MAX_ACCURACY_M
    val streetText = hunt.street?.let { street ->
        if (hunt.streetHouses + hunt.streetVisits > 0) {
            stringResource(Res.string.map_street_seen, street, hunt.streetHouses, hunt.streetVisits)
        } else {
            stringResource(Res.string.map_street_new, street)
        }
    }
    val nearestName = hunt.nearestHouse?.let { stringResource(Res.string.map_nearest_name, it.label) }
    val gpsText = when {
        !hunt.active -> null
        stale -> stringResource(Res.string.map_waiting_gps)
        weak -> stringResource(Res.string.map_weak_gps_short)
        else -> null
    }
    // What TalkBack hears: built from the events only (street, nearest house id, GPS state), never the distance, so it
    // changes, and is read, only when one of those does.
    val announcement = if (hunt.active) listOfNotNull(streetText, nearestName, gpsText).joinToString(". ") else ""
    // The empty note live regions, each 1 dp tall, given back from the bottom padding (see "Even padding").
    val emptyNotes = (if (showLocationNote) 0 else 1) + (if (notificationsOff) 0 else 1)
    ElevatedCard(modifier.fillMaxWidth()) {
        Box {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = (12 - emptyNotes).dp)) {
                // The whole row is one switch for TalkBack: "Hunt mode, <state hint>, switch, on/off".
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = hunt.active, role = Role.Switch, onValueChange = onToggle),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.map_hunt_mode), fontWeight = FontWeight.SemiBold)
                        if (!hunt.active) {
                            Text(stringResource(Res.string.map_hunt_off), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Switch(checked = hunt.active, onCheckedChange = null)
                }
                if (hunt.active) {
                    Column {
                        streetText?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        }
                        hunt.nearestHouse?.let { h ->
                            TextButton(
                                onClick = { onOpenHouse(h.id) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(Res.string.map_nearest, h.label, hunt.nearestDistanceM?.toInt() ?: 0))
                            }
                        }
                        // A weak or missing fix is a condition, not a failure: muted, never error red (round 5).
                        when {
                            stale -> Text(
                                stringResource(Res.string.map_waiting_gps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            weak && accuracyM != null -> Text(
                                stringResource(Res.string.map_weak_gps, accuracyM.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                hunt.stopReason?.let { reason ->
                    ResultCard(
                        tone = ResultTone.NEUTRAL,
                        text = stringResource(
                            when (reason) {
                                HuntState.StopReason.LOW_BATTERY -> Res.string.map_hunt_stopped_battery
                                HuntState.StopReason.NO_PERMISSION -> Res.string.map_hunt_stopped_permission
                                HuntState.StopReason.NOT_ALLOWED -> Res.string.map_hunt_stopped_not_allowed
                            },
                        ),
                        onDismiss = onCloseStopReason,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // Each note in a polite live region, always composed, so TalkBack reads it once when it appears (round 5).
                LiveMessage {
                    if (showLocationNote) {
                        LocationPermissionNote(
                            ask = locationAsk,
                            deniedText = stringResource(Res.string.map_location_off),
                            approximateText = approximateLocationText(Res.string.map_needs_precise),
                            launchRequest = onRequestLocation,
                            modifier = Modifier.padding(top = 8.dp).then(noteModifier),
                        )
                    }
                }
                LiveMessage {
                    if (notificationsOff) {
                        // bodyMedium, as the location note above it (round 7): both are blocking messages.
                        WarnNote(
                            stringResource(Res.string.map_notifications_off),
                            Modifier.padding(top = 8.dp),
                            action = stringResource(Res.string.notify_allow),
                            onAction = onAllowNotifications,
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            // The announcer: always composed, 1 dp, over the card's bottom edge so it takes no room in the column.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).clearAndSetSemantics {
                    contentDescription = announcement
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
    }
}

/**
 * The map's legend (UX review, whole-app audit, round 6; UX-002, A11Y-003, WCAG 1.4.1), as on the web map
 * (map-page.html's "Legend" group): a dot drawn like each marker ([LEGEND_DOTS]: new 12 dp with a 2 dp white ring,
 * shortlisted 16 dp with a 3 dp ring, rejected 9 dp at 75 % opacity, each with a 1 dp dark hairline) and its status
 * name, so a single marker on its own can be told apart without seeing its colour. The dots use [MarkerColors], as
 * the markers do (the tiles stay light in both themes); the box is `surface` at 92 % with a 2 dp shadow (round 7: the
 * tonal elevation did nothing on a translucent colour), like the Hunt card and the buttons it floats with. Each dot
 * is centred in the same 18 dp box ([LEGEND_DOT_BOX_DP], round 7), so stacked names start on one line. The names are
 * labelMedium SemiBold ([legendTextStyle]), as the web's 600, and the caller measures them in that style to place the
 * legend where no name breaks ([legendPlace]). Round 8: the three items are on one line when [oneLineWidthDp] (the
 * caller's measure of that line, padding included) fits the width the legend is given, otherwise one item per line,
 * never a mix ([legendFitsOneLine]): a FlowRow packed two on one line and the third alone, with dots out of line. A
 * name never wraps (`softWrap = false`), so a pixel lost to rounding clips instead of breaking a word. TalkBack hears
 * "Legend" (a heading, the group's name) and then each status name; the dots are not read.
 */
@Composable
private fun MapLegend(oneLineWidthDp: Float, modifier: Modifier = Modifier) {
    val legend = stringResource(Res.string.map_legend)
    val style = legendTextStyle()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
        modifier = modifier.semantics {
            contentDescription = legend
            heading()
            isTraversalGroup = true
        },
    ) {
        BoxWithConstraints {
            val items: @Composable () -> Unit = {
                LEGEND_DOTS.forEach { dot ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LEGEND_DOT_GAP_DP.dp),
                    ) {
                        Box(Modifier.size(LEGEND_DOT_BOX_DP.dp), contentAlignment = Alignment.Center) {
                            LegendDotMark(dot)
                        }
                        // labelMedium: 12/16 sp Latin, 12/20 sp for Indic scripts (IndicTypography), SemiBold.
                        Text(stringResource(statusLabel(dot.status)), style = style, softWrap = false, maxLines = 1)
                    }
                }
            }
            val padding = Modifier.padding(horizontal = LEGEND_PADDING_DP.dp, vertical = 6.dp)
            if (legendFitsOneLine(maxWidth.value, oneLineWidthDp)) {
                Row(
                    padding,
                    horizontalArrangement = Arrangement.spacedBy(LEGEND_ITEM_GAP_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { items() }
            } else {
                Column(padding, verticalArrangement = Arrangement.spacedBy(4.dp)) { items() }
            }
        }
    }
}

/** The legend's text style: labelMedium in SemiBold, the web legend's weight 600 (round 7). */
@Composable
private fun legendTextStyle(): TextStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)

/** The status name for a [LegendDot.status]. */
private fun statusLabel(status: String): StringResource = when (status) {
    "SHORTLISTED" -> Res.string.status_SHORTLISTED
    "REJECTED" -> Res.string.status_REJECTED
    else -> Res.string.status_NEW
}

/** One legend dot: the web's `.dot` (map-page.css), a fill inside a white ring, a dark hairline outside it. */
@Composable
private fun LegendDotMark(dot: LegendDot) {
    val fill = Color(
        when (dot.status) {
            "SHORTLISTED" -> MarkerColors.SHORTLISTED
            "REJECTED" -> MarkerColors.REJECTED
            else -> MarkerColors.NEW
        },
    )
    // No semantics: the status name beside it says what it is.
    Canvas(Modifier.size((dot.diameterDp + 2 * LEGEND_OUTLINE_DP).dp)) {
        val outline = LEGEND_OUTLINE_DP.dp.toPx()
        val ring = dot.ringDp.dp.toPx()
        val radius = dot.diameterDp.dp.toPx() / 2f
        // Three circles that do not overlap, so the rejected dot's opacity applies evenly, as the web's does.
        drawCircle(
            color = Color.Black,
            radius = radius + outline / 2f,
            alpha = LEGEND_OUTLINE_ALPHA * dot.alpha,
            style = Stroke(width = outline),
        )
        drawCircle(color = Color.White, radius = radius - ring / 2f, alpha = dot.alpha, style = Stroke(width = ring))
        drawCircle(color = fill, radius = radius - ring, alpha = dot.alpha)
    }
}

/** Precise location is allowed: what Hunt mode, *Save house here* and *My location* need. */
private fun hasPreciseLocation(platform: PlatformServices): Boolean = platform.locationAccess() == LocationAccess.PRECISE
