package com.househunt.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.househunt.app.i18n.AppLocale
import com.househunt.app.ui.HouseHuntRoot
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Where a notification tap should take the user. */
sealed interface DeepLink {
    data class OpenHouse(val id: String) : DeepLink
    data class NewHouse(val lat: Double, val lon: Double, val visitId: String?) : DeepLink
}

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
        handle(intent)
        setContent {
            HouseHuntRoot(deepLinks = deepLinks, onDeepLinkHandled = { deepLinks.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * The launcher activity is exported, so any app can start it with extras (threat model F-25): accept only a
     * well-formed UUID and in-range coordinates; the screens then look the ids up in the local database.
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifications.EXTRA_OPEN_HOUSE)?.let {
            if (isUuid(it)) deepLinks.value = DeepLink.OpenHouse(it)
            return
        }
        if (intent.hasExtra(Notifications.EXTRA_NEW_LAT)) {
            val lat = intent.getDoubleExtra(Notifications.EXTRA_NEW_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(Notifications.EXTRA_NEW_LON, Double.NaN)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            val visitId = intent.getStringExtra(Notifications.EXTRA_VISIT_ID)?.takeIf(::isUuid)
            deepLinks.value = DeepLink.NewHouse(lat, lon, visitId)
        }
    }

    private fun isUuid(value: String): Boolean =
        value.length == 36 && runCatching { UUID.fromString(value) }.isSuccess
}
