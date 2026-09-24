package app.doorprints.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** [PlatformServices] on Android. [context] is kept as the application context. */
class AndroidPlatformServices(context: Context) : PlatformServices {
    private val app = context.applicationContext

    /** TalkBack (or another touch-exploration service) is on: what `isTouchExploring(context)` read before CMP-3. */
    override fun isScreenReaderOn(): Boolean =
        app.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
}

/** Provides [LocalPlatformServices] for [content]: MainActivity's content, and each screenshot test's. */
@Composable
fun ProvidePlatformServices(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val services = remember(context) { AndroidPlatformServices(context) }
    CompositionLocalProvider(LocalPlatformServices provides services, content = content)
}
