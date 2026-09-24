package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import app.doorprints.DoorprintsApp

/**
 * The common root's slots for the screens still in `:app` ([RootScreens], CMP-5): each forwards to the screen with the
 * arguments the root gives it. A slot goes when its screen moves to `:ui` (the house form's went with CMP-6 P6a;
 * Export's and Import's go with P6b, the Map's with CMP-7).
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

    @Composable
    override fun Export(onBack: () -> Unit, onOpenMap: () -> Unit) = ExportScreen(onBack = onBack, onOpenMap = onOpenMap)

    @Composable
    override fun Import(onBack: () -> Unit, onOpenHouses: (importedRunId: String?) -> Unit) =
        ImportScreen(onBack = onBack, onOpenHouses = onOpenHouses)
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
