package com.househunt.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
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
import com.househunt.app.DeepLink
import com.househunt.app.HouseHuntApp
import com.househunt.app.Notifications
import com.househunt.app.R
import com.househunt.app.data.Repository
import com.househunt.app.i18n.AppLocale
import com.househunt.shared.export.ExportLanguages
import kotlinx.coroutines.flow.StateFlow

@Composable
fun repository(): Repository = (LocalContext.current.applicationContext as HouseHuntApp).container.repository

private data class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val baseTabs = listOf(
    Tab("map", R.string.nav_map, Icons.Default.Place),
    Tab("houses", R.string.nav_houses, Icons.Default.Home),
    Tab("compare", R.string.nav_compare, Icons.AutoMirrored.Filled.List),
)
private val assistantTab = Tab("assistant", R.string.nav_assistant, Icons.Default.Search)
private val settingsTab = Tab("settings", R.string.nav_settings, Icons.Default.Settings)

object Routes {
    /** The destination patterns, for popUpTo and for recognising the entry on top. */
    const val HOUSE = "house/{id}"
    const val NEW_HOUSE = "new?lat={lat}&lon={lon}&visitId={visitId}"

    fun house(id: String) = "house/$id"
    fun newHouse(lat: Double, lon: Double, visitId: String? = null) =
        "new?lat=$lat&lon=$lon" + (visitId?.let { "&visitId=$it" } ?: "")
}

/** Set on a house entry by the new-house form's first save, so the house form says "Saved" once. */
private const val JUST_SAVED_KEY = "justSaved"

/**
 * A tab, the bottom bar's way (and every other route to a tab, such as a notification's Settings or the map's
 * *Open Houses*): the tab's own saved stack comes back and the current one is saved, never pushed on top of it.
 */
private fun NavController.openTab(route: String) {
    navigate(route) {
        popUpTo("map") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Whether [entry] is the resumed destination: a tap or a finished write during a transition is dropped. */
private fun resumed(entry: NavBackStackEntry) = entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

@Composable
fun HouseHuntRoot(deepLinks: StateFlow<DeepLink?>, onDeepLinkHandled: () -> Unit) {
    HouseHuntTheme {
        val repo = repository()
        val nav = rememberNavController()
        val deepLink by deepLinks.collectAsStateWithLifecycle()
        val context = LocalContext.current
        // AI features appear only when the server says they are on (and it is reachable). Asked once per process, in
        // HouseHuntApp (whole-app audit): asking on every recreation hid the tab on a rotation while offline.
        val aiEnabled by repo.aiEnabled.collectAsStateWithLifecycle()
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route
        // The Assistant tab stays while the user is on it, even if the status turns off meanwhile: otherwise the bar,
        // which is drawn only on a tab, vanished and left the screen with no navigation (whole-app audit).
        val tabs = baseTabs + (if (aiEnabled || current == "assistant") listOf(assistantTab) else emptyList()) + settingsTab

        // "Language changed to தமிழ்", once, after the recreate that the language switch causes (AppLocale.set).
        val rootSnackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            AppLocale.consumeChange(context)?.let { change ->
                val name = change.language?.let { ExportLanguages.nativeName(it) }
                    ?: context.getString(R.string.settings_language_system)
                rootSnackbar.showSnackbar(context.getString(R.string.settings_language_changed, name))
            }
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
        // Counts the "See your houses" taps, so the list turns its "Just imported" filter on once per tap and not on
        // every return to the Houses tab, including a second tap for the same run after an undo that kept houses.
        var importedOpen by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(deepLink) {
            val top = nav.currentBackStackEntry
            fun onTop(route: String, arg: String, value: String?) =
                top != null && top.destination.route == route && top.arguments?.getString(arg) == value
            fun openHouse(id: String) {
                // The same alert tapped twice must not stack two copies of the form.
                if (!onTop(Routes.HOUSE, "id", id)) nav.navigate(Routes.house(id))
            }
            when (val d = deepLink) {
                is DeepLink.OpenHouse -> openHouse(d.id)
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
                is DeepLink.OpenScreen -> when (d.route) {
                    // Settings is a tab: its own stack, never pushed over a form with unsaved edits.
                    Notifications.SCREEN_SETTINGS -> nav.openTab(d.route)
                    else -> {
                        // A new import makes the list's "Just imported" run stale (see importedRun above).
                        if (d.route == "import") importedRun = null
                        // Export and Import are single-top: a notification tapped while the screen is open must not
                        // stack a second copy of it.
                        nav.navigate(d.route) { launchSingleTop = true }
                    }
                }
                null -> return@LaunchedEffect
            }
            onDeepLinkHandled()
        }
        // One-shot: set by the empty house list's "Add a house on the map", cleared by the map once it has shown how
        // to add a house (UX review, round 15). Saveable, so a rotation during the switch does not lose it.
        var mapAddTip by rememberSaveable { mutableStateOf(false) }
        val openMapWithTip = {
            mapAddTip = true
            nav.navigate("map") {
                popUpTo("map")
                launchSingleTop = true
            }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(rootSnackbar) },
            bottomBar = {
                if (tabs.any { it.route == current }) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = current == tab.route,
                                onClick = { nav.openTab(tab.route) },
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
            NavHost(nav, startDestination = "map", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                composable("map") { entry ->
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
                    CompareScreen(
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
                    )
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
                        onOpenHouses = { run ->
                            importedRun = run
                            importedOpen++
                            nav.navigate("houses") {
                                popUpTo("map")
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
                    HouseEditScreen(
                        houseId = entry.arguments?.getString("id"),
                        newLat = null, newLon = null, visitId = null,
                        onDone = dropUnlessResumed { nav.popBackStack() },
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
                                popUpTo("map")
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
                    HouseEditScreen(
                        houseId = null,
                        newLat = entry.arguments?.getString("lat")?.toDoubleOrNull(),
                        newLon = entry.arguments?.getString("lon")?.toDoubleOrNull(),
                        visitId = entry.arguments?.getString("visitId"),
                        onDone = dropUnlessResumed { nav.popBackStack() },
                        // The first save continues on the house as an existing one, so photos can be added at once
                        // (whole-app audit; the web's New house → Create → house page). The form is replaced, so Back
                        // goes to where it was opened from.
                        onCreated = { id ->
                            if (resumed(entry)) {
                                nav.navigate(Routes.house(id)) { popUpTo(Routes.NEW_HOUSE) { inclusive = true } }
                                nav.currentBackStackEntry?.savedStateHandle?.set(JUST_SAVED_KEY, true)
                            }
                        },
                    )
                }
            }
        }
    }
}
