package com.househunt.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.househunt.app.DeepLink
import com.househunt.app.HouseHuntApp
import com.househunt.app.R
import com.househunt.app.data.Repository
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
    fun house(id: String) = "house/$id"
    fun newHouse(lat: Double, lon: Double, visitId: String? = null) =
        "new?lat=$lat&lon=$lon" + (visitId?.let { "&visitId=$it" } ?: "")
}

@Composable
fun HouseHuntRoot(deepLinks: StateFlow<DeepLink?>, onDeepLinkHandled: () -> Unit) {
    HouseHuntTheme {
        val repo = repository()
        val nav = rememberNavController()
        val deepLink by deepLinks.collectAsStateWithLifecycle()
        // AI features appear only when the server says they are on (and it is reachable).
        val aiEnabled by repo.aiEnabled.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { repo.refreshAiStatus() }
        val tabs = remember(aiEnabled) { baseTabs + (if (aiEnabled) listOf(assistantTab) else emptyList()) + settingsTab }

        LaunchedEffect(deepLink) {
            when (val d = deepLink) {
                is DeepLink.OpenHouse -> nav.navigate(Routes.house(d.id))
                is DeepLink.NewHouse -> nav.navigate(Routes.newHouse(d.lat, d.lon, d.visitId))
                null -> return@LaunchedEffect
            }
            onDeepLinkHandled()
        }
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route
        Scaffold(
            bottomBar = {
                if (tabs.any { it.route == current }) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = current == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo("map") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                // The visible label names the item for TalkBack; the icon is decorative.
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "map", modifier = Modifier.padding(padding)) {
                composable("map") {
                    MapScreen(
                        onOpenHouse = { nav.navigate(Routes.house(it)) },
                        onNewHouse = { lat, lon -> nav.navigate(Routes.newHouse(lat, lon)) },
                    )
                }
                composable("houses") {
                    HouseListScreen(onOpenHouse = { nav.navigate(Routes.house(it)) })
                }
                composable("compare") { CompareScreen(onOpenHouse = { nav.navigate(Routes.house(it)) }) }
                composable("assistant") { AssistantScreen(onOpenHouse = { nav.navigate(Routes.house(it)) }) }
                composable("settings") { SettingsScreen() }
                composable(
                    "house/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    HouseEditScreen(
                        houseId = entry.arguments?.getString("id"),
                        newLat = null, newLon = null, visitId = null,
                        onDone = { nav.popBackStack() },
                    )
                }
                composable(
                    "new?lat={lat}&lon={lon}&visitId={visitId}",
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
                        onDone = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
