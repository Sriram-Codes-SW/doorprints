package app.doorprints.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UIKit.UIViewController

/**
 * The iOS app's root view controller (ADR-23 CMP-8b), what MainActivity's `setContent` is on Android: the common root
 * ([DoorprintsRoot]) with the iOS seams ([IosPlatformServices], the process's [IosAppContainer] services) and the iOS
 * feature set ([PlatformFeatures.Ios]: the features not on iPhone yet are hidden). The Swift app (ios/) calls it once,
 * as `MainViewControllerKt.MainViewController()`, and makes it the window's root.
 *
 * Also starts the process's start-up work ([IosAppContainer.start]) and, in a debug build launched with
 * `-DoorprintsSelfCheck`, the launch self-check ([startSelfCheckIfRequested]). Main thread.
 */
fun MainViewController(): UIViewController {
    IosAppContainer.start()
    startSelfCheckIfRequested()
    val platform = IosPlatformServices()
    val services = IosAppContainer.services
    // No notifications on iOS yet, so nothing opens the app at a house or a screen: no deep link ever arrives.
    val deepLinks = MutableStateFlow<DeepLink?>(null)
    return ComposeUIViewController {
        CompositionLocalProvider(
            LocalPlatformServices provides platform,
            LocalAppServices provides services,
            LocalPlatformFeatures provides PlatformFeatures.Ios,
        ) {
            DoorprintsRoot(
                deepLinks = deepLinks,
                onDeepLinkHandled = { deepLinks.value = null },
            )
        }
    }
}
