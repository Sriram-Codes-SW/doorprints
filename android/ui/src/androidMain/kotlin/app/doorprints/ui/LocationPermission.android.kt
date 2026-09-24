package app.doorprints.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/*
 * The Android side of the location and notification permissions (moved from :app's LocationPermission.kt and
 * NotifyAsk.kt in CMP-5): the grants, the shared "asked" flag and the rationale behind [AndroidPlatformServices], and
 * the functions :app's Map, house form and Hunt code still call with a Context.
 */

/** What each screen asks for: precise and approximate together, as Android recommends. */
val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Remembers whether the app has asked before, so "never ask again" can be told from "not asked yet". */
private const val PERMISSION_PREFS = "map_permissions" // The Map's old file name, so an update keeps the flag.
private const val KEY_LOCATION_ASKED = "locationAsked"

/** Precise location: what Hunt mode, *Save house here*, *Use my current location* and *Plan visits* need. */
fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun hasCoarseLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** [LocationAccess] for this app now. */
internal fun currentLocationAccess(context: Context): LocationAccess =
    locationAccess(hasLocationPermission(context), hasCoarseLocationPermission(context))

private fun prefs(context: Context) = context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)

/** True once any screen has shown Android's location prompt. */
internal fun locationAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCATION_ASKED, false)

/** Call right before launching the location request, from whichever screen. */
internal fun markLocationAsked(context: Context) {
    prefs(context).edit().putBoolean(KEY_LOCATION_ASKED, true).apply()
}

/**
 * [canAskAgain] for this app now. Not for use on every recomposition: read [LocationAsk.canAsk] there (its constructor
 * calls this once, inside `remember`). With approximate location granted, the rationale of the precise permission
 * decides whether Android will still show its "Change to precise location?" prompt.
 */
internal fun canAskLocation(context: Context): Boolean {
    val activity = context.findActivity() ?: return true
    return canAskAgain(
        locationAsked(context),
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION),
    )
}

/** The app's page in system settings, where a permission Android no longer asks for can be turned on. */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}

/** True when this app may post notifications at all: below API 33 always, from 33 with `POST_NOTIFICATIONS`. */
fun canPostNotifications(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** The activity this context belongs to, or null (a service's or the application's context). */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
