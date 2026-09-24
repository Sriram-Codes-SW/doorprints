package app.doorprints.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * [PlatformServices] on Android. [context] is the composition's context (MainActivity's, or the screenshot test's
 * activity): the location rationale needs the activity ([canAskLocation]), and the dialler and the browser are started
 * from it ([dial], [openUrl]); everything else reads the application context. Created per composition by
 * [ProvidePlatformServices]; never held by a view model or a service.
 */
class AndroidPlatformServices(private val context: Context) : PlatformServices {
    private val app = context.applicationContext

    /** TalkBack (or another touch-exploration service) is on: what `isTouchExploring(context)` read before CMP-3. */
    override fun isScreenReaderOn(): Boolean =
        app.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    override fun locationAccess(): LocationAccess = currentLocationAccess(app)

    override fun locationAsked(): Boolean = locationAsked(app)

    override fun markLocationAsked() = markLocationAsked(app)

    override fun canAskLocation(): Boolean = canAskLocation(context)

    override fun openAppSettings() = openAppSettings(context)

    override fun canPostNotifications(): Boolean = canPostNotifications(app)

    /** From the activity, as the house form did before CMP-6. */
    override fun dial(number: String) {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    override fun openUrl(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Provides [LocalPlatformServices] for [content]: MainActivity's content, and each screenshot test's. */
@Composable
fun ProvidePlatformServices(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val services = remember(context) { AndroidPlatformServices(context) }
    CompositionLocalProvider(LocalPlatformServices provides services, content = content)
}
