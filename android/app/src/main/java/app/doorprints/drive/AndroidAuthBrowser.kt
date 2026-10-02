package app.doorprints.drive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.doorprints.drive.connect.AuthBrowser
import app.doorprints.drive.connect.BrowserResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens Google's consent page in the system browser (`ACTION_VIEW`, no library: docs/15 §5.5) and waits for the
 * redirect that `MainActivity` receives. Only an `https://accounts.google.com/` address is ever opened.
 */
class AndroidAuthBrowser(private val context: Context) : AuthBrowser {
    override suspend fun authorize(url: String, redirectUri: String): BrowserResult {
        if (!url.startsWith("https://accounts.google.com/")) return BrowserResult.Unavailable
        val waiting = DriveRedirects.begin()
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val activity = ForegroundActivity.current
            try {
                if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                return BrowserResult.Unavailable
            }
            val answer = withTimeoutOrNull(DriveRedirects.WAIT_MS) { waiting.await() }
            return if (answer == null) BrowserResult.Cancelled else BrowserResult.Redirected(answer)
        } finally {
            DriveRedirects.end(waiting)
        }
    }
}
