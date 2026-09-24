package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import app.doorprints.DoorprintsApp

/**
 * Provides the common UI's seams for [content]: [LocalPlatformServices] (`ProvidePlatformServices`) and
 * [LocalAppServices] (the process's [app.doorprints.AndroidAppServices]). MainActivity's content, and each screenshot
 * test's. Every screen is common since CMP-7, so `:app` draws none of its own (the Map's `RootScreens` slot is gone).
 */
@Composable
fun ProvideAppServices(content: @Composable () -> Unit) {
    val services = (LocalContext.current.applicationContext as DoorprintsApp).container.services
    ProvidePlatformServices {
        CompositionLocalProvider(LocalAppServices provides services, content = content)
    }
}
