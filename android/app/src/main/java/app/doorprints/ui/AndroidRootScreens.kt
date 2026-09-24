package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import app.doorprints.DoorprintsApp

/**
 * The common root's slot for the screen still in `:app` ([RootScreens], CMP-5): the Map, with the arguments the root
 * gives it, until it moves to `:ui` (CMP-7). The house form's, Export's and Import's slots went with CMP-6.
 */
object AndroidRootScreens : RootScreens {
    @Composable
    override fun Map(
        onOpenHouse: (String) -> Unit,
        onNewHouse: (Double, Double) -> Unit,
        onOpenHouses: () -> Unit,
        showAddTip: Boolean,
        onAddTipShown: () -> Unit,
        deletedHouse: String?,
        onDeletedShown: () -> Unit,
    ) = MapScreen(
        onOpenHouse = onOpenHouse,
        onNewHouse = onNewHouse,
        onOpenHouses = onOpenHouses,
        showAddTip = showAddTip,
        onAddTipShown = onAddTipShown,
        deletedHouse = deletedHouse,
        onDeletedShown = onDeletedShown,
    )
}

/**
 * Provides the common UI's seams for [content]: [LocalPlatformServices] (`ProvidePlatformServices`) and
 * [LocalAppServices] (the process's [app.doorprints.AndroidAppServices]). MainActivity's content, and each screenshot
 * test's.
 */
@Composable
fun ProvideAppServices(content: @Composable () -> Unit) {
    val services = (LocalContext.current.applicationContext as DoorprintsApp).container.services
    ProvidePlatformServices {
        CompositionLocalProvider(LocalAppServices provides services, content = content)
    }
}
