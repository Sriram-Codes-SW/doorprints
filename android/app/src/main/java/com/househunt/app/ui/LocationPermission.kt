package com.househunt.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.househunt.app.ui.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The location permission, shared by every screen that asks for it: the Map (Hunt switch, *Save house here*, *My
 * location*, the Hunt card's *Allow location*), the house form (*Use my current location*) and the Assistant's
 * *Allow location* (UX review, whole-app audit, round 2). Before this, only the Map remembered that it had asked, so
 * after refusals on the form or the Assistant the Map still offered a prompt Android no longer shows. Round 3 tells
 * approximate-only location apart from no location ([LocationAccess]) and gives the three screens one note
 * ([LocationPermissionNote]).
 */

/** What each screen asks for: precise and approximate together, as Android recommends. */
internal val LOCATION_PERMISSIONS = arrayOf(
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

/**
 * How much location the app has. On Android 12+ the prompt offers *Precise* and *Approximate* as two equal choices, so
 * [APPROXIMATE] (coarse granted, fine not) is a common answer, and it is not "no permission": the system's page then
 * says location is *Allowed* (UX review, whole-app audit, round 3).
 */
internal enum class LocationAccess { PRECISE, APPROXIMATE, NONE }

/** [LocationAccess] from the two grants. */
internal fun locationAccess(fine: Boolean, coarse: Boolean): LocationAccess = when {
    fine -> LocationAccess.PRECISE
    coarse -> LocationAccess.APPROXIMATE
    else -> LocationAccess.NONE
}

/** [LocationAccess] for this app now. */
internal fun currentLocationAccess(context: Context): LocationAccess =
    locationAccess(hasLocationPermission(context), hasCoarseLocationPermission(context))

/** What the note offers for [access] and whether Android will still ask ([canAsk]); null with precise location. */
internal fun locationFix(access: LocationAccess, canAsk: Boolean): LocationFix? = when (access) {
    LocationAccess.PRECISE -> null
    LocationAccess.APPROXIMATE -> if (canAsk) LocationFix.TURN_ON_PRECISE else LocationFix.OPEN_SETTINGS_PRECISE
    LocationAccess.NONE -> if (canAsk) LocationFix.ALLOW else LocationFix.OPEN_SETTINGS
}

/**
 * What a tap on a feature that needs precise location does (UX review, whole-app audit, round 5), the same on the Map
 * (Hunt switch, *Save house here*, *My location*) and the Assistant (*Plan visits*):
 *  - [RUN]: precise location is allowed: the feature runs.
 *  - [ASK]: Android will still show its prompt: ask now (with approximate location, "Change to precise location?").
 *  - [SHOW_NOTE]: Android will not ask again: nothing starts (no "Planning…" or "Finding your location…" that can
 *    only end in the same note); the screen shows, or brings into view, its [LocationPermissionNote] with *Open
 *    settings*.
 */
internal enum class LocationStart { RUN, ASK, SHOW_NOTE }

/** [LocationStart] for precise location allowed or not ([precise]) and whether Android will still ask ([canAsk]). */
internal fun locationStart(precise: Boolean, canAsk: Boolean): LocationStart = when {
    precise -> LocationStart.RUN
    canAsk -> LocationStart.ASK
    else -> LocationStart.SHOW_NOTE
}

/** True when the note's button starts Android's prompt; false when it opens the app's settings. */
internal fun LocationFix.launchesRequest(): Boolean = this == LocationFix.ALLOW || this == LocationFix.TURN_ON_PRECISE

private fun prefs(context: Context) = context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)

/** True once any screen has shown Android's location prompt. */
internal fun locationAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCATION_ASKED, false)

/** Call right before launching the location request, from whichever screen. */
internal fun markLocationAsked(context: Context) {
    prefs(context).edit().putBoolean(KEY_LOCATION_ASKED, true).apply()
}

/**
 * Whether Android will still show its prompt: never asked, or asked and refused once (it then says a rationale may be
 * shown). Asked, refused twice (or "Don't ask again") and no rationale: only the app's settings can turn it on.
 */
internal fun canAskAgain(asked: Boolean, rationale: Boolean): Boolean = !asked || rationale

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
internal fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}

/**
 * The location permission's state for one screen, as Compose state: [asked], [canAsk] and [access] are read once when
 * the state is created (in `remember`, not on every recomposition; round 3: `canAsk` used to start as `!asked`, so the
 * Hunt card's button showed *Open settings* for a frame and then *Allow location*), again on every resume (a grant or
 * reset in system settings counts at once) and after each answer ([refresh]), so a label such as *Allow location* /
 * *Open settings* follows a second refusal at once.
 */
@Stable
internal class LocationAsk(private val context: Context) {
    var asked by mutableStateOf(locationAsked(context))
        private set
    var canAsk by mutableStateOf(canAskLocation(context))
        private set
    var access by mutableStateOf(currentLocationAccess(context))
        private set

    /** Precise location is allowed (what every location feature needs). */
    val granted: Boolean get() = access == LocationAccess.PRECISE

    /** Only approximate location is allowed: the screens say so, and offer *Turn on precise location*. */
    val approximateOnly: Boolean get() = access == LocationAccess.APPROXIMATE

    /** What a [LocationPermissionNote] offers now; null with precise location. */
    val fix: LocationFix? get() = locationFix(access, canAsk)

    /** Records the ask (shared with the other screens) just before the launcher is started. */
    fun markAsked() {
        markLocationAsked(context)
        asked = true
    }

    /** After a permission result, and on resume. */
    fun refresh() {
        asked = locationAsked(context)
        access = currentLocationAccess(context)
        canAsk = canAskLocation(context)
    }
}

/** A [LocationAsk] for this screen, refreshed on every resume. */
@Composable
internal fun rememberLocationAsk(): LocationAsk {
    val context = LocalContext.current
    val state = remember(context) { LocationAsk(context) }
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}

/**
 * The note's text for [fix] (UX review, whole-app audit, round 4): no location says [deniedText]; approximate only
 * says [approximateText], which is the shared lead ("Doorprints has only your approximate location.") and the screen's
 * own reason, and adds [preciseInSettings] once only the settings page can turn precise location on.
 */
internal fun locationNoteText(
    fix: LocationFix,
    deniedText: String,
    approximateText: String,
    preciseInSettings: String,
): String = when (fix) {
    LocationFix.ALLOW, LocationFix.OPEN_SETTINGS -> deniedText
    LocationFix.TURN_ON_PRECISE -> approximateText
    LocationFix.OPEN_SETTINGS_PRECISE -> "$approximateText $preciseInSettings"
}

/**
 * The approximate-only text for one screen: the shared lead `location_approximate_only` and the screen's reason
 * ([why]: `map_needs_precise`, `house_needs_precise` or `ai_plan_needs_precise`), joined by a space.
 */
@Composable
internal fun approximateLocationText(why: StringResource): String =
    stringResource(Res.string.location_approximate_only) + " " + stringResource(why)

/**
 * What the note's button does for [LocationAsk.fix], the one rule every screen uses (round 4): while Android will
 * still ask ([LocationFix.launchesRequest]) it records the ask and calls [launchRequest], which launches
 * [LOCATION_PERMISSIONS] on the screen's own launcher; otherwise it opens the app's settings. Read at tap time, so
 * a refusal or a grant since the last frame counts.
 */
internal fun LocationAsk.requestOrOpenSettings(context: Context, launchRequest: () -> Unit) {
    // Precise location already: nothing to turn on.
    val next = this.fix ?: return
    if (next.launchesRequest()) {
        markAsked()
        launchRequest()
    } else {
        openAppSettings(context)
    }
}

/**
 * Why a location feature cannot run, and the next step, the same on the Map's Hunt card, the house form and the
 * Assistant (UX review, whole-app audit, round 3): a calm [WarnNote] with a 48 dp text button. Refusing a permission
 * is the user's choice, not a failure, so it is never in error red (a real failure such as `house_location_failed`
 * still is). Nothing is drawn with precise location.
 *
 * Both texts are the screen's own (round 4), so the note names what the user just tried and never a feature that is
 * not on the screen: no location says [deniedText] (the Map's "Location is off for Doorprints…", the form's "…Allow
 * it, or type the latitude and longitude below.", the Assistant's "Location is off for Doorprints. ‘Plan visits’ needs it to start from where you are.",
 * `ai_plan_location_off`, round 5) with
 * *Allow location*, or *Open settings* once Android will not ask. Approximate only says [approximateText]
 * ([approximateLocationText]: "Doorprints has only your approximate location." and the screen's reason) with *Turn on
 * precise location* (Android then shows "Change to precise location?"), or *Open settings* and "In settings, open
 * Permissions, then Location, and turn on ‘Use precise location’." ([locationNoteText]).
 *
 * The button follows [LocationFix.launchesRequest] here, not in each screen ([requestOrOpenSettings]): it calls
 * [launchRequest] (after [LocationAsk.markAsked]) or opens the app's settings, so it can never drift from the label.
 *
 * The text is bodyMedium (14/20 sp Latin, 14/24 sp for Indic scripts; round 5), not the privacy hint's bodySmall:
 * this is the main message on three screens, up to three sentences, and must not be smaller than its button's label.
 * Each screen wraps it in a polite [LiveMessage], so TalkBack reads it once when it appears.
 */
@Composable
internal fun LocationPermissionNote(
    ask: LocationAsk,
    deniedText: String,
    approximateText: String,
    launchRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fix = ask.fix ?: return
    val text = locationNoteText(
        fix,
        deniedText = deniedText,
        approximateText = approximateText,
        preciseInSettings = stringResource(Res.string.location_precise_in_settings),
    )
    val action = stringResource(
        when (fix) {
            LocationFix.ALLOW -> Res.string.map_allow_location
            LocationFix.TURN_ON_PRECISE -> Res.string.location_turn_on_precise
            LocationFix.OPEN_SETTINGS, LocationFix.OPEN_SETTINGS_PRECISE -> Res.string.perm_open_settings
        },
    )
    WarnNote(
        text,
        modifier,
        action = action,
        onAction = { ask.requestOrOpenSettings(context, launchRequest) },
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}
