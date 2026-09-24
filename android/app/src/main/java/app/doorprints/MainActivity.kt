package app.doorprints

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val deepLinks = MutableStateFlow<DeepLink?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Re-create the channels with this (localised) context so their names follow the app language.
        Notifications.createChannels(this)
        // Only on a real start (UX review, whole-app audit, blocker). After a rotation, the in-app language switch
        // (AppLocale.set recreates the activity), a dark-mode change or process death, getIntent() still holds the
        // notification's extras while NavController has already restored its back stack, so handling them again
        // pushed the house (or one more new-house form) on top of it on every recreation.
        if (savedInstanceState == null) handle(intent)
        setContent {
            // The common UI's seams (ADR-23 CMP-3, CMP-5): the platform's (screen reader, permissions) and the app's
            // (data, backup, language). The root and its graph are common code; the intent is read here and handed
            // over as a DeepLink. Every screen is common since CMP-7.
            ProvideAppServices {
                DoorprintsRoot(
                    deepLinks = deepLinks,
                    onDeepLinkHandled = { deepLinks.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The new intent becomes getIntent(), so its extras are the ones handle() removes, and a later recreation
        // does not see the launcher intent's (or an older notification's) extras instead.
        setIntent(intent)
        handle(intent)
    }

    /**
     * The launcher activity is exported, so any app can start it with extras (threat model F-25): accept only a
     * well-formed UUID and in-range coordinates; the screens then look the ids up in the local database.
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        // Reopened from Recents (after process death the system replays the task's last intent, which may be an old
        // notification's): that notification was already acted on, so its extras must not run a second time.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            parse(intent)?.let { deepLinks.value = it }
        }
        // Consumed: a later getIntent() (a recreation of this same activity) carries nothing to act on.
        DEEP_LINK_EXTRAS.forEach(intent::removeExtra)
    }

    private fun parse(intent: Intent): DeepLink? {
        intent.getStringExtra(Notifications.EXTRA_OPEN_SCREEN)?.let {
            // A fixed allow-list, never a route taken from the extra as-is.
            return if (it in Notifications.SCREENS) DeepLink.OpenScreen(it) else null
        }
        intent.getStringExtra(Notifications.EXTRA_OPEN_HOUSE)?.let {
            return if (isUuid(it)) DeepLink.OpenHouse(it) else null
        }
        if (intent.hasExtra(Notifications.EXTRA_NEW_LAT)) {
            val lat = intent.getDoubleExtra(Notifications.EXTRA_NEW_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(Notifications.EXTRA_NEW_LON, Double.NaN)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val visitId = intent.getStringExtra(Notifications.EXTRA_VISIT_ID)?.takeIf(::isUuid)
            return DeepLink.NewHouse(lat, lon, visitId)
        }
        return null
    }

    private fun isUuid(value: String): Boolean =
        value.length == 36 && runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        /** Every extra a notification can carry into this activity; all are removed once handled. */
        val DEEP_LINK_EXTRAS = listOf(
            Notifications.EXTRA_OPEN_SCREEN,
            Notifications.EXTRA_OPEN_HOUSE,
            Notifications.EXTRA_NEW_LAT,
            Notifications.EXTRA_NEW_LON,
            Notifications.EXTRA_VISIT_ID,
        )
    }
}
