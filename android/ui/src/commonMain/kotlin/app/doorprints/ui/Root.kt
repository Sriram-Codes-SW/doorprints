/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import app.doorprints.shared.listing.ListingText
import app.doorprints.data.ConnectLink
import app.doorprints.data.HouseEntity
import app.doorprints.data.TourEnd
import kotlinx.coroutines.launch
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.ViewingKind
import app.doorprints.shared.model.Scoring
import app.doorprints.shared.model.LengthUnit
import app.doorprints.ui.res.*
import app.doorprints.shared.export.ExportLanguages
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Where a notification tap should take the user (CMP-5: common, was `app.doorprints.DeepLink` in `:app`). The platform
 * reads its own intent or URL, checks it (`:app`'s MainActivity accepts only a well-formed UUID, in-range coordinates
 * and the screens in [Routes.NOTIFICATION_SCREENS]; threat model F-25) and hands [DoorprintsRoot] one of these.
 */
sealed interface DeepLink {
    /** A house's form; [questions]: scrolled to its questions (a viewing reminder's *Questions*, S4b-BL-93b). */
    data class OpenHouse(val id: String, val questions: Boolean = false) : DeepLink
    /**
     * A new house at [lat], [lon], from a Hunt mode arrival alert: [visitId] is the visit, which the first save links
     * to the house and whose alert it removes.
     */
    data class NewHouse(val lat: Double, val lon: Double, val visitId: String?) : DeepLink

    /** Export, Import or Settings, from an export/import/backup notification. Always one of the notification screens. */
    data class OpenScreen(val route: String) : DeepLink

    /**
     * A backup or update file another app opened in Doorprints (docs/11 5.28: "open with" or "share to" from WhatsApp,
     * email, the Files app): the Import screen with that file picked. [file] is the platform's reference (Android: a
     * `content://` URI); the Import screen validates it as any picked file.
     */
    data class ImportFile(val file: String) : DeepLink

    /**
     * A listing shared as text into Doorprints (docs/11 5.29): the Map asks where the house is, then the new-house
     * form opens with the text parsed into it. [text] is capped by the platform (`ListingText.MAX_CHARS`).
     */
    data class NewHouseFromListing(val text: String) : DeepLink

    /** A connect link from the server's owner page (its QR code), already checked ([ConnectLink.parse]). */
    data class Connect(val link: ConnectLink) : DeepLink

    /** A viewing's form, from a tapped Hunt mode reminder (docs/11 5.16, slice 3c); [id] is checked by the platform. */
    data class OpenViewing(val id: String) : DeepLink

    /**
     * *Start Hunt mode* from a reminder while location is not granted (5.16, 5.18): the Map, which asks for location
     * as its own Hunt switch does (the person tapped to start it) and then starts Hunt mode.
     */
    data object StartHunt : DeepLink

    /**
     * A tapped Hunt mode reminder or area wake-up on iPhone (S4b-BL-94c), where a notification has no *Start Hunt mode*
     * action (an iPhone app cannot start location tracking from one): the Map, which offers Hunt mode in a snackbar
     * with *Start Hunt mode*; the action takes the Hunt switch's path, as [StartHunt] does.
     */
    data object OfferHunt : DeepLink
}

/** One tab of the bottom bar. */
private data class NavTab(val route: String, val label: StringResource, val icon: ImageVector)

private val baseTabs = listOf(
    NavTab("map", Res.string.nav_map, Icons.Default.Place),
    NavTab("houses", Res.string.nav_houses, Icons.Default.Home),
    NavTab("compare", Res.string.nav_compare, Icons.AutoMirrored.Filled.List),
)
private val assistantTab = NavTab("assistant", Res.string.nav_assistant, Icons.Default.Search)
private val settingsTab = NavTab("settings", Res.string.nav_settings, Icons.Default.Settings)

/**
 * The navigation routes: patterns for the graph and builders that fill in the ids. The notification screens are the
 * only routes a platform may open from outside.
 */
object Routes {
    /** The routes a notification may open; `:app`'s `Notifications.SCREEN_*` are these. */
    const val SETTINGS = "settings"
    /** The Map tab, from the repeated-path alert's notification (docs/11 5.27.5). */
    const val MAP = "map"
    const val EXPORT = "export"
    const val IMPORT = "import"
    /** *Share updates with…* (docs/11 5.28). */
    const val SHARE = "share-updates"

    /** The brokers list and one broker's page (docs/11 5.25); a broker's page with the id [NEW_BROKER] adds one. */
    const val BROKERS = "brokers"
    const val BROKER = "broker/{id}"
    const val NEW_BROKER = "new"

    /** Settings > Criteria (docs/11 5.4, slice 2). */
    const val CRITERIA = "criteria"

    /** Settings > Questions (docs/11 5.5, slice 3a). */
    const val QUESTIONS = "questions"

    /** Settings > Viewings (docs/11 5.8, slice 3b-1), optionally one house's; and the viewing form ([NEW_VIEWING] plans one). */
    const val VIEWINGS = "viewings?houseId={houseId}"
    const val VIEWING = "viewing/{id}?houseId={houseId}&kind={kind}"
    const val NEW_VIEWING = "new"

    /** Settings > My areas and My places (docs/11 slice 4a), and the form of one; the id [NEW_RECORD] adds one. */
    const val AREAS = "areas"
    const val AREA = "area/{id}"
    const val PLACES = "places"
    const val PLACE = "place/{id}"
    const val NEW_RECORD = "new"
    /** The rationale before *Wake me in my hunting areas* is turned on (docs/11 slice 4b, 5.18). */
    const val AREA_WAKEUP = "area-wakeup"

    fun area(id: String) = "area/$id"
    fun place(id: String) = "place/$id"

    fun viewings(houseId: String? = null) = "viewings" + (houseId?.let { "?houseId=$it" } ?: "")
    fun viewing(id: String?, houseId: String? = null, kind: String? = null) =
        "viewing/${id ?: NEW_VIEWING}?" + listOfNotNull(houseId?.let { "houseId=$it" }, kind?.let { "kind=$it" }).joinToString("&")

    /** The screens a notification may open ([DeepLink.OpenScreen]); `:app`'s `Notifications.SCREENS`. */
    val NOTIFICATION_SCREENS = setOf(EXPORT, IMPORT, SETTINGS, MAP)

    /** The destination patterns, for popUpTo and for recognising the entry on top. */
    const val HOUSE = "house/{id}"
    const val NEW_HOUSE = "new?lat={lat}&lon={lon}&visitId={visitId}"

    fun house(id: String) = "house/$id"
    fun broker(id: String) = "broker/$id"
    fun newHouse(lat: Double, lon: Double, visitId: String? = null) =
        "new?lat=$lat&lon=$lon" + (visitId?.let { "&visitId=$it" } ?: "")
}

/** Set on a house entry by the new-house form's first save, so the house form says "Saved" once. */
private const val JUST_SAVED_KEY = "justSaved"

/**
 * A tab, the bottom bar's way (and every other route to a tab, such as a notification's Settings or the map's
 * *Open Houses*): the tab's own saved stack comes back and the current one is saved, never pushed on top of it.
 * [home] is the graph's start destination ([homeRoute]), the entry every tab switch pops back to.
 */
private fun NavController.openTab(route: String, home: String) {
    navigate(route) {
        popUpTo(home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** How the guided tour ended before, once read: null inside means it has not (the first-run offer is then made). */
private data class StoredTour(val end: TourEnd?)

/**
 * The start destination: the Map, or the Houses tab where the platform has no map yet (iOS, CMP-8b;
 * [PlatformFeatures.map]). The Map tab stays in the bar either way, with its note.
 */
private fun homeRoute(features: PlatformFeatures) = if (features.map) "map" else "houses"

/** Whether [entry] is the resumed destination: a tap or a finished write during a transition is dropped. */
private fun resumed(entry: NavBackStackEntry) = entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

/**
 * The app's root (common since CMP-5): the theme, the bottom bar and the navigation graph, with JetBrains
 * navigation-compose (on Android the same androidx navigation as before). [deepLinks] is the platform's latest checked
 * notification tap; [onDeepLinkHandled] clears it once acted on. Every screen is common since CMP-7 (the Map was the last one
 * drawn by `:app`, through a `RootScreens` slot). Needs [LocalAppServices] and [LocalPlatformServices].
 */
@Composable
fun DoorprintsRoot(deepLinks: StateFlow<DeepLink?>, onDeepLinkHandled: () -> Unit) {
    DoorprintsTheme {
        val services = LocalAppServices.current
        val repo = services.repository
        val nav = rememberNavController()
        val features = LocalPlatformFeatures.current
        val home = homeRoute(features)
        fun NavController.openTab(route: String) = openTab(route, home)
        val deepLink by deepLinks.collectAsStateWithLifecycle()
        // AI features appear only when the server says they are on (and it is reachable). Asked once per process, in
        // DoorprintsApp (whole-app audit): asking on every recreation hid the tab on a rotation while offline.
        val aiEnabled by repo.aiEnabled.collectAsStateWithLifecycle()
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route
        // The Assistant tab stays while the user is on it, even if the status turns off meanwhile: otherwise the bar,
        // which is drawn only on a tab, vanished and left the screen with no navigation (whole-app audit).
        val tabs = baseTabs + (if (aiEnabled || current == "assistant") listOf(assistantTab) else emptyList()) + settingsTab

        // The guided tour (S4b-FR-39): its steps, the boxes of what it points at, and whether it was offered before.
        val tourSession = remember { TourSession() }
        val tourRegistry = remember { TourRegistry() }
        val tourStored by remember(repo) { repo.settings.tourEnd().map { StoredTour(it) } }
            .collectAsStateWithLifecycle(initialValue = null)
        var tourEndedHere by rememberSaveable { mutableStateOf(false) }
        val tourMemory = TourMemory.of(tourStored != null, tourStored?.end, tourEndedHere)
        val tourContext = TourContext(features, assistant = aiEnabled, offlineMaps = services.offlineMaps.supported)
        // However it ends, it is not offered again; the write is the application's, so leaving the screen cannot cancel it.
        fun endTour(end: TourEnd) {
            tourEndedHere = true
            services.appScope.launch { runCatching { repo.settings.setTourEnd(end) } }
        }
        // Each step moves the app to its tab, fresh (a tab's saved sub-screen would hide the step's target).
        LaunchedEffect(tourSession.step?.id) {
            val route = tourSession.step?.route ?: return@LaunchedEffect
            if (nav.currentBackStackEntry?.destination?.route != route) {
                nav.navigate(route) {
                    popUpTo(home)
                    launchSingleTop = true
                }
            }
        }

        // The viewing reminders are set again on every resume (docs/11 5.16): an "Alarms & reminders" grant, a clock
        // change or an edit made while the app was away is picked up.
        LifecycleResumeEffect(services) {
            services.rescheduleReminders()
            // The area wake-up (slice 4b): a location permission lost in the system settings switches it off (My
            // areas says once why); otherwise its geofences are registered again.
            services.areaWakeup.resumed()
            onPauseOrDispose {}
        }

        // "Language changed to தமிழ்", once, after the recreate that the language switch causes (AppLocale.set).
        val rootSnackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            services.consumeLanguageChange()?.let { change ->
                val name = change.language?.let { ExportLanguages.nativeName(it) }
                    ?: getString(Res.string.settings_language_system)
                rootSnackbar.showSnackbar(getString(Res.string.settings_language_changed, name))
            }
        }

        // The question bank is seeded once per install, in the app's language (docs/11 5.5, slice 3a); the screens that
        // read it follow the records as they arrive. A failure leaves it for the next start.
        LaunchedEffect(repo) {
            runCatching { repo.seedQuestionsOnce(appLanguage()) }
        }

        // The copy import whose houses the list shows behind a "Just imported" chip, set by a finished copy
        // import's "See your houses" (UX review, round 16). Only the run id: the ids are read from its undo record,
        // so nothing large goes into the saved state, and the chip goes when the record does (a day, or an undo that
        // removed every copy). The Import screen is popped on the way, so the list offers the same undo in a row
        // under the chip (UX review, round 18) rather than Back leading to the result.
        // Cleared whenever the Import screen is opened (UX review, round 19): after a second copy import left with
        // Back, the list must not keep pointing at the first one, whose undo would then remove the wrong copies. The
        // list itself also prefers a newer undoable import over this run.
        var importedRun by rememberSaveable { mutableStateOf<String?>(null) }
        // A backup file another app opened in Doorprints (docs/11 5.28), until the Import screen has picked it.
        var pendingImportFile by remember { mutableStateOf<String?>(null) }
        // A shared listing (docs/11 5.29) waiting for its place on the map, then for the new-house form to take it.
        var pendingListing by remember { mutableStateOf<String?>(null) }
        // One-shot: set by the empty house list's "Add a house on the map" (and by a shared listing), cleared by the
        // map once it has shown how to add a house.
        var mapAddTip by rememberSaveable { mutableStateOf(false) }
        // A shared listing whose link is already a saved house: "You saved this on …" with Open or Add anyway.
        var listingDuplicate by remember { mutableStateOf<Pair<HouseEntity, String>?>(null) }
        // Counts the "See your houses" taps, so the list turns its "Just imported" filter on once per tap and not on
        // every return to the Houses tab, including a second tap for the same run after an undo that kept houses.
        var importedOpen by rememberSaveable { mutableIntStateOf(0) }
        // A connect link waiting for *Connect* or *Not now* (docs/03 §12.1). Plain remember on purpose: its invite is a
        // one-time secret and stays out of the saved-state Bundle, so a rotation closes the question (scan again).
        var pendingConnect by remember { mutableStateOf<ConnectLink?>(null) }
        // *Start Hunt mode* from a reminder, waiting for the Map to ask for location and start it (slice 3c).
        var huntRequested by remember { mutableStateOf(false) }
        // An iPhone reminder or area tap, waiting for the Map to offer Hunt mode (S4b-BL-94c).
        var huntOffered by remember { mutableStateOf(false) }
        // A reminder's *Questions*: the house whose form scrolls to its questions once it is open (S4b-BL-93b).
        var questionsFor by remember { mutableStateOf<String?>(null) }
        // A deep link to the Map shows the Map itself (S4b-BL-94a): whatever was open over it is closed, where
        // openTab would bring back the sub-screen the Map's stack had.
        fun NavController.openMapFresh() = navigate("map") {
            popUpTo(home)
            launchSingleTop = true
        }
        LaunchedEffect(deepLink) {
            if (deepLink == null) return@LaunchedEffect
            // On a cold start from a notification this runs before the NavHost (inside the Scaffold's subcomposition)
            // has set its graph, and navigate() would throw; wait for the graph's first entry (found by the emulator
            // smoke test, docs/06 TC-I-35).
            nav.currentBackStackEntryFlow.first()
            val top = nav.currentBackStackEntry
            fun onTop(route: String, arg: String, value: String?) =
                top != null && top.destination.route == route && top.arguments?.read { getStringOrNull(arg) } == value
            fun openHouse(id: String) {
                // The same alert tapped twice must not stack two copies of the form.
                if (!onTop(Routes.HOUSE, "id", id)) nav.navigate(Routes.house(id))
            }
            when (val d = deepLink) {
                is DeepLink.OpenHouse -> {
                    if (d.questions) questionsFor = d.id
                    openHouse(d.id)
                }
                is DeepLink.NewHouse -> {
                    // A "stay here?" alert whose visit was already saved as a house opens that house, not a second
                    // new-house form that would save a duplicate (whole-app audit).
                    val savedAs = d.visitId?.let { repo.getVisit(it)?.houseId }
                    when {
                        savedAs != null -> openHouse(savedAs)
                        d.visitId != null && onTop(Routes.NEW_HOUSE, "visitId", d.visitId) -> Unit
                        else -> nav.navigate(Routes.newHouse(d.lat, d.lon, d.visitId))
                    }
                }
                is DeepLink.Connect -> {
                    // Asked on Settings, where the server's address and status are.
                    nav.openTab(Routes.SETTINGS)
                    pendingConnect = d.link
                }
                is DeepLink.ImportFile -> {
                    importedRun = null
                    pendingImportFile = d.file
                    nav.navigate(Routes.IMPORT) { launchSingleTop = true }
                }
                is DeepLink.NewHouseFromListing -> {
                    // The same link already saved (its tracking parameters aside): offer that house first.
                    val url = ListingText.urlIn(d.text)?.let(ListingText::cleanUrl)
                    val saved = url?.let { u -> repo.houseSnapshot().firstOrNull { h -> h.listingUrl?.let(ListingText::cleanUrl) == u } }
                    if (saved != null) {
                        listingDuplicate = saved to d.text
                    } else {
                        pendingListing = d.text
                        if (features.map) mapAddTip = true
                        nav.openMapFresh()
                    }
                }
                is DeepLink.OpenViewing -> {
                    if (!onTop(Routes.VIEWING, "id", d.id)) nav.navigate(Routes.viewing(d.id))
                }
                DeepLink.StartHunt -> {
                    huntRequested = true
                    nav.openMapFresh()
                }
                DeepLink.OfferHunt -> {
                    huntOffered = true
                    nav.openMapFresh()
                }
                is DeepLink.OpenScreen -> when (d.route) {
                    // Settings and the Map are tabs: their own stacks, never pushed over a form with unsaved edits.
                    Routes.SETTINGS, Routes.MAP -> nav.openTab(d.route)
                    else -> {
                        // A new import makes the list's "Just imported" run stale (see importedRun above).
                        if (d.route == Routes.IMPORT) importedRun = null
                        // Export and Import are single-top: a notification tapped while the screen is open must not
                        // stack a second copy of it.
                        nav.navigate(d.route) { launchSingleTop = true }
                    }
                }
                null -> return@LaunchedEffect
            }
            onDeepLinkHandled()
        }
        listingDuplicate?.let { (house, text) ->
            AlertDialog(
                onDismissRequest = { listingDuplicate = null },
                title = { Text(stringResource(Res.string.house_dup_title, house.createdAt.dateText())) },
                text = { Text(stringResource(Res.string.house_dup_text, house.label)) },
                confirmButton = {
                    TextButton(onClick = {
                        listingDuplicate = null
                        nav.navigate(Routes.house(house.id))
                    }) { Text(stringResource(Res.string.house_dup_open)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        listingDuplicate = null
                        pendingListing = text
                        if (features.map) mapAddTip = true
                        nav.openMapFresh()
                    }) { Text(stringResource(Res.string.house_dup_add)) }
                },
            )
        }
        // One-shot: set by the empty house list's "Add a house on the map", cleared by the map once it has shown how
        // to add a house (UX review, round 15). Saveable, so a rotation during the switch does not lose it.
        // Without a map (iOS) nothing offers "Add a house on the map", and the tip is never set: the Map tab's note
        // has no snackbar to show it.
        val openMapWithTip = {
            if (features.map) mapAddTip = true
            nav.navigate("map") {
                popUpTo(home)
                launchSingleTop = true
            }
        }
        pendingConnect?.let { link ->
            val platform = LocalPlatformServices.current
            ConnectLinkDialog(link, repo, remember { platform.deviceName() }, onDone = { pendingConnect = null })
        }
        CompositionLocalProvider(LocalTourRegistry provides tourRegistry) {
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    snackbarHost = { SnackbarHost(rootSnackbar) },
                    bottomBar = {
                        if (tabs.any { it.route == current }) {
                            NavigationBar(Modifier.tourTarget(TourTargets.NAV_BAR)) {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = current == tab.route,
                                        onClick = { nav.openTab(tab.route) },
                                        modifier = if (tab.route == assistantTab.route) Modifier.tourTarget(TourTargets.NAV_ASSISTANT) else Modifier,
                                        // The visible label names the item for TalkBack; the icon is decorative. One line,
                                        // ellipsised: at 200 % in Tamil and Telugu a label broke mid-word or ran into the
                                        // icon; TalkBack still reads it whole (README section 8, device check 21 (f)).
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        // The pill is secondaryContainer = --primary-soft (Theme.kt, round 19); M3's active
                                        // label is `secondary`, which here is the amber --star. Teal, as the web's phone bar.
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    // consumeWindowInsets: the padding already covers the system bars, so a screen's own Scaffold, TopAppBar or
                    // bottom bar (Export's action area) must not add them a second time.
                    NavHost(nav, startDestination = home, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                        composable("map") { entry ->
                            // A shared listing waiting for its place: the tip says so, and the form takes the text.
                            val listingPending = pendingListing != null
                            val deleted by entry.savedStateHandle.getStateFlow<String?>(DELETED_HOUSE_KEY, null)
                                .collectAsStateWithLifecycle()
                            MapScreen(
                                // Only from the resumed map, like every other exit (round 21): a second tap during the
                                // transition, or a slow GPS fix landing after the user left, is dropped.
                                onOpenHouse = { if (resumed(entry)) nav.navigate(Routes.house(it)) },
                                onNewHouse = { lat, lon -> if (resumed(entry)) nav.navigate(Routes.newHouse(lat, lon)) },
                                onOpenHouses = { if (resumed(entry)) nav.openTab("houses") },
                                showAddTip = mapAddTip,
                                onAddTipShown = { mapAddTip = false },
                                addTipForListing = listingPending,
                                listingPlace = remember(pendingListing) { pendingListing?.let { ListingText.parse(it).locality } },
                                huntRequest = huntRequested,
                                onStartHuntHandled = { huntRequested = false },
                                huntOffer = huntOffered,
                                onHuntOfferHandled = { huntOffered = false },
                                deletedHouse = deleted,
                                onDeletedShown = { entry.savedStateHandle[DELETED_HOUSE_KEY] = null },
                            )
                        }
                        composable("houses") { entry ->
                            val deleted by entry.savedStateHandle.getStateFlow<String?>(DELETED_HOUSE_KEY, null)
                                .collectAsStateWithLifecycle()
                            HouseListScreen(
                                onOpenHouse = { if (resumed(entry)) nav.navigate(Routes.house(it)) },
                                onOpenSettings = { if (resumed(entry)) nav.openTab("settings") },
                                deletedHouse = deleted,
                                onDeletedShown = { entry.savedStateHandle[DELETED_HOUSE_KEY] = null },
                                onOpenImport = {
                                    importedRun = null
                                    nav.navigate("import")
                                },
                                importedRun = importedRun,
                                importedOpen = importedOpen,
                                // The first-run hero's "Add a house on the map": the same route as Export's empty state,
                                // plus the one-shot flag that makes the map say how to add a house.
                                onOpenMap = openMapWithTip,
                            )
                        }
                        composable("compare") { entry ->
                            // null until the database answers, so the empty state does not flash on the way in (CMP-4 P4c's
                            // CompareScreen in :app's CompareTab.kt, collected here since CMP-5).
                            val loaded: List<HouseEntity>? by repo.houses.collectAsStateWithLifecycle(initialValue = null)
                            val counts by repo.visitCounts.collectAsStateWithLifecycle(emptyList())
                            val brokers: Map<String, Broker> by remember(repo) { repo.observeBrokers().map { it.toMap() } }
                                .collectAsStateWithLifecycle(emptyMap())
                            val lengthUnit by remember(repo) { repo.settings.lengthUnit }.collectAsStateWithLifecycle(LengthUnit.FT)
                            val scoring by remember(repo) { repo.observeScoring() }.collectAsStateWithLifecycle(Scoring.DEFAULT)
                            val places by remember(repo) { repo.observePlaces() }.collectAsStateWithLifecycle(emptyList())
                            CompareScreen(
                                loaded = loaded,
                                counts = counts,
                                brokers = brokers,
                                lengthUnit = lengthUnit,
                                scoring = scoring,
                                places = places,
                                onOpenHouse = { if (resumed(entry)) nav.navigate(Routes.house(it)) },
                                onOpenMap = openMapWithTip,
                            )
                        }
                        composable("assistant") { entry ->
                            AssistantScreen(
                                onOpenHouse = { if (resumed(entry)) nav.navigate(Routes.house(it)) },
                                onOpenMap = { nav.openTab("map") },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onOpenExport = { nav.navigate("export") },
                                onOpenImport = {
                                    importedRun = null
                                    nav.navigate("import")
                                },
                                onOpenShare = { nav.navigate(Routes.SHARE) },
                                onOpenBrokers = { nav.navigate(Routes.BROKERS) },
                                onOpenCriteria = { nav.navigate(Routes.CRITERIA) },
                                onOpenQuestions = { nav.navigate(Routes.QUESTIONS) },
                                onOpenViewings = { nav.navigate(Routes.viewings()) },
                                onOpenAreas = { nav.navigate(Routes.AREAS) },
                                onOpenPlaces = { nav.navigate(Routes.PLACES) },
                                onTakeTour = { tourSession.start(TourSteps.forPhone(tourContext)) },
                            )
                        }
                        // My areas and My places (docs/11 slice 4a): sub-screens of Settings with a back arrow, like Brokers.
                        composable(Routes.AREAS) { entry ->
                            AreasScreen(
                                onBack = dropUnlessResumed { nav.popBackStack() },
                                onOpenArea = { if (resumed(entry)) nav.navigate(Routes.area(it)) },
                                onTurnOnWakeup = { if (resumed(entry)) nav.navigate(Routes.AREA_WAKEUP) },
                            )
                        }
                        composable(Routes.AREA_WAKEUP) { entry ->
                            // Not dropUnlessResumed: the permission answer can arrive while the entry is only started.
                            AreaWakeupRationaleScreen(onDone = { if (nav.currentBackStackEntry == entry) nav.popBackStack() })
                        }
                        composable(Routes.AREA, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                            AreaFormScreen(
                                areaId = entry.arguments?.read { getStringOrNull("id") }?.takeIf { it != Routes.NEW_RECORD },
                                onDone = dropUnlessResumed { nav.popBackStack() },
                            )
                        }
                        composable(Routes.PLACES) { entry ->
                            PlacesScreen(
                                onBack = dropUnlessResumed { nav.popBackStack() },
                                onOpenPlace = { if (resumed(entry)) nav.navigate(Routes.place(it)) },
                            )
                        }
                        composable(Routes.PLACE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                            PlaceFormScreen(
                                placeId = entry.arguments?.read { getStringOrNull("id") }?.takeIf { it != Routes.NEW_RECORD },
                                onDone = dropUnlessResumed { nav.popBackStack() },
                            )
                        }
                        // Viewings (docs/11 5.8, slice 3b-1): the history (all, or one house's) and the form, sub-screens with a back arrow.
                        composable(
                            Routes.VIEWINGS,
                            arguments = listOf(navArgument("houseId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                        ) { entry ->
                            ViewingsScreen(
                                houseId = entry.arguments?.read { getStringOrNull("houseId") },
                                onBack = dropUnlessResumed { nav.popBackStack() },
                                onOpenViewing = { if (resumed(entry)) nav.navigate(Routes.viewing(it)) },
                                onPlan = { house, kind -> if (resumed(entry)) nav.navigate(Routes.viewing(null, house, kind.name)) },
                            )
                        }
                        composable(
                            Routes.VIEWING,
                            arguments = listOf(
                                navArgument("id") { type = NavType.StringType },
                                navArgument("houseId") { type = NavType.StringType; nullable = true; defaultValue = null },
                                navArgument("kind") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { entry ->
                            val args = entry.arguments
                            ViewingFormScreen(
                                viewingId = args?.read { getStringOrNull("id") }?.takeIf { it != Routes.NEW_VIEWING },
                                houseId = args?.read { getStringOrNull("houseId") },
                                kind = ViewingKind.fromWire(args?.read { getStringOrNull("kind") }),
                                onDone = dropUnlessResumed { nav.popBackStack() },
                            )
                        }
                        // Criteria (docs/11 5.4, slice 2): a sub-screen of Settings with its own back arrow, like Brokers.
                        // Questions (docs/11 5.5, slice 3a): the bank of viewing questions, a sub-screen of Settings like Criteria.
                        composable(Routes.QUESTIONS) {
                            QuestionsScreen(onBack = dropUnlessResumed { nav.popBackStack() })
                        }
                        composable(Routes.CRITERIA) {
                            CriteriaScreen(onBack = dropUnlessResumed { nav.popBackStack() })
                        }
                        // Brokers (docs/11 5.25, slice 1b): a sub-screen of Settings with its own back arrow, like Share updates.
                        composable(Routes.BROKERS) { entry ->
                            BrokersScreen(
                                onBack = dropUnlessResumed { nav.popBackStack() },
                                onOpenBroker = { if (resumed(entry)) nav.navigate(Routes.broker(it)) },
                            )
                        }
                        composable(
                            Routes.BROKER,
                            arguments = listOf(navArgument("id") { type = NavType.StringType }),
                        ) { entry ->
                            val id = entry.arguments?.read { getStringOrNull("id") }?.takeIf { it != Routes.NEW_BROKER }
                            BrokerScreen(
                                brokerId = id,
                                onBack = dropUnlessResumed { nav.popBackStack() },
                                onOpenHouse = { if (resumed(entry)) nav.navigate(Routes.house(it)) },
                            )
                        }
                        composable(Routes.SHARE) {
                            ShareUpdatesScreen(onBack = dropUnlessResumed { nav.popBackStack() })
                        }
                        // Offline copy (Sprint 4a). Both are full screens with their own back arrow rather than tabs:
                        // they are a task the user finishes and leaves, not a place to come back to.
                        // The empty export screen's "Map" and a finished import's "See your houses" go to a tab. The
                        // Settings stack they came from is popped, not saved: a later tap on the Settings tab should open
                        // Settings, not restore this sub-screen.
                        // Every "leave this screen" below is dropUnlessResumed (UX review, round 21): a second tap, or a save
                        // that finishes during the exit transition, finds the entry no longer RESUMED and is ignored, so the
                        // back stack is never popped twice (from the Map, that emptied the NavHost: a blank screen, no bar).
                        composable("export") {
                            ExportScreen(
                                onBack = dropUnlessResumed { nav.popBackStack() },
                                // The same "Add a house on the map" button, so the same tip on arrival.
                                onOpenMap = openMapWithTip,
                            )
                        }
                        composable("import") {
                            ImportScreen(
                                onBack = dropUnlessResumed { nav.popBackStack() },
                                // A file another app opened in Doorprints (DeepLink.ImportFile), picked once.
                                initialFile = pendingImportFile,
                                onInitialFileConsumed = { pendingImportFile = null },
                                onOpenHouses = { run ->
                                    importedRun = run
                                    importedOpen++
                                    nav.navigate("houses") {
                                        popUpTo(home)
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable(
                            Routes.HOUSE,
                            arguments = listOf(navArgument("id") { type = NavType.StringType }),
                        ) { entry ->
                            val justSaved by entry.savedStateHandle.getStateFlow(JUST_SAVED_KEY, false)
                                .collectAsStateWithLifecycle()
                            val houseId = entry.arguments?.read { getStringOrNull("id") }
                            HouseEditScreen(
                                houseId = houseId,
                                newLat = null, newLon = null, visitId = null,
                                showQuestions = houseId != null && questionsFor == houseId,
                                onQuestionsShown = { questionsFor = null },
                                onDone = dropUnlessResumed { nav.popBackStack() },
                                // The house's Viewings card (slice 3b-1): plan one here, or see this house's history.
                                onPlanViewing = { house, kind -> if (resumed(entry)) nav.navigate(Routes.viewing(null, house, kind.name)) },
                                onOpenViewings = { house -> if (resumed(entry)) nav.navigate(Routes.viewings(house)) },
                                // *Save a copy* after *Close this hunt* (slice 5).
                                onSaveCopy = { if (resumed(entry)) nav.navigate("export") },
                                // A saved walk's *Show on map* (docs/11 5.27.6): the Map, which outlines it.
                                onShowOnMap = { if (resumed(entry)) nav.openMapFresh() },
                                // "Save as a new house" after this one was removed elsewhere: continue on the copy.
                                onCreated = { id ->
                                    if (resumed(entry)) {
                                        nav.navigate(Routes.house(id)) { popUpTo(Routes.HOUSE) { inclusive = true } }
                                        nav.currentBackStackEntry?.savedStateHandle?.set(JUST_SAVED_KEY, true)
                                    }
                                },
                                // Back to the Map or the list, which offers "Deleted Green Villa" with Undo.
                                onDeleted = { id ->
                                    if (resumed(entry)) {
                                        nav.previousBackStackEntry?.savedStateHandle?.set(DELETED_HOUSE_KEY, id)
                                        nav.popBackStack()
                                    }
                                },
                                showSaved = justSaved,
                                onSavedShown = { entry.savedStateHandle[JUST_SAVED_KEY] = false },
                                // "This house is no longer on this phone" (round 21): to the Houses tab, whichever tab the
                                // stale link was opened from, as Import's "See your houses" does.
                                onOpenHouses = dropUnlessResumed {
                                    nav.navigate("houses") {
                                        popUpTo(home)
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable(
                            Routes.NEW_HOUSE,
                            arguments = listOf(
                                navArgument("lat") { type = NavType.StringType },
                                navArgument("lon") { type = NavType.StringType },
                                navArgument("visitId") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { entry ->
                            val args = entry.arguments
                            val onDone = dropUnlessResumed { nav.popBackStack() }
                            HouseEditScreen(
                                houseId = null,
                                newLat = args?.read { getStringOrNull("lat") }?.toDoubleOrNull(),
                                newLon = args?.read { getStringOrNull("lon") }?.toDoubleOrNull(),
                                visitId = args?.read { getStringOrNull("visitId") },
                                // A shared listing (docs/11 5.29), parsed into the fresh form once.
                                listingText = pendingListing,
                                onListingConsumed = { pendingListing = null },
                                onDone = onDone,
                                // A new house has no stale link and cannot be deleted from its form: both just close it, as
                                // HouseEditScreen's defaults do.
                                onOpenHouses = onDone,
                                // The first save continues on the house as an existing one, so photos can be added at once
                                // (whole-app audit; the web's New house → Create → house page). The form is replaced, so Back
                                // goes to where it was opened from.
                                onCreated = { id ->
                                    if (resumed(entry)) {
                                        nav.navigate(Routes.house(id)) { popUpTo(Routes.NEW_HOUSE) { inclusive = true } }
                                        nav.currentBackStackEntry?.savedStateHandle?.set(JUST_SAVED_KEY, true)
                                    }
                                },
                                onDeleted = { onDone() },
                                showSaved = false,
                                onSavedShown = {},
                            )
                        }
                    }
                }
                // The first-run offer and the tour, over everything (S4b-FR-39). Not modal: only its card takes touches.
                TourOverlay(
                    session = tourSession,
                    registry = tourRegistry,
                    offer = TourOffer.show(tourMemory, tourSession.active, current, home),
                    currentRoute = current,
                    onStart = { tourSession.start(TourSteps.forPhone(tourContext)) },
                    onDecline = { endTour(tourSession.skip()) },
                    onNext = { tourSession.next()?.let(::endTour) },
                    onBack = tourSession::back,
                    onSkip = { endTour(tourSession.skip()) },
                )
            }
        }
    }
}
