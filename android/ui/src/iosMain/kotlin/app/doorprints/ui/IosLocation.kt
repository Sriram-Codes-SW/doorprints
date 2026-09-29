package app.doorprints.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

// Core Location for the common screens (CMP-8b): what [IosPlatformServices] reports, the one-shot fix behind
// [AppServices.location] and the prompt of [rememberLocationPermissionRequest]. A CLLocationManager delivers its
// callbacks on the run loop of the thread that made it, so each one here is made on the main thread.

/** How long [IosLocationSource.current] waits for Core Location before it gives up (null: "no fix came"). */
private const val FIX_TIMEOUT_MS = 30_000L

/**
 * The manager the status reads below ask ([iosLocationAccess], [iosCanAskLocation]): one for the process, made on first
 * use on the main thread and never given a delegate. Main thread only.
 */
private val statusManager: CLLocationManager by lazy { CLLocationManager() }

/**
 * The manager to read the authorization from: [statusManager] on the main thread, where every screen reads it; a
 * short-lived one on any other thread (a status read has no callbacks, so its thread does not matter), so the shared
 * one is never made off the main thread.
 */
private fun managerForStatus(): CLLocationManager =
    if (NSThread.isMainThread) statusManager else CLLocationManager()

/**
 * [LocationAccess] from Core Location: while in use (or always) with full accuracy is [LocationAccess.PRECISE], with
 * reduced accuracy (the prompt's *Precise: Off*) [LocationAccess.APPROXIMATE]; not asked yet, denied or restricted
 * (parental controls) is [LocationAccess.NONE].
 */
internal fun iosLocationAccess(): LocationAccess {
    val manager = managerForStatus()
    val status = manager.authorizationStatus
    if (status != kCLAuthorizationStatusAuthorizedWhenInUse && status != kCLAuthorizationStatusAuthorizedAlways) {
        return LocationAccess.NONE
    }
    return if (manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy) {
        LocationAccess.PRECISE
    } else {
        LocationAccess.APPROXIMATE
    }
}

/** True while iOS will still show its location prompt: only before the first answer (it never asks twice). */
internal fun iosCanAskLocation(): Boolean = managerForStatus().authorizationStatus == kCLAuthorizationStatusNotDetermined

/**
 * [LocationSource] on Core Location: one `requestLocation()` fix, the best Core Location has within about ten
 * seconds, or null after [FIX_TIMEOUT_MS], on an error, or without precise location (as Android's
 * `currentLocation`). Talks to Core Location on the main thread.
 */
internal class IosLocationSource : LocationSource {
    override suspend fun current(): Pair<Double, Double>? = withContext(Dispatchers.Main) {
        if (!hasPrecisePermission()) return@withContext null
        withTimeoutOrNull(FIX_TIMEOUT_MS) { oneFix() }
    }

    override fun hasPrecisePermission(): Boolean = iosLocationAccess() == LocationAccess.PRECISE

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun oneFix(): Pair<Double, Double>? = suspendCancellableCoroutine { continuation ->
        val manager = CLLocationManager()
        // The manager holds its delegate weakly: this frame (and the cancellation handler) keeps both alive.
        val delegate = OneFixDelegate { location ->
            manager.delegate = null
            if (continuation.isActive) {
                val fix = location?.takeIf { it.horizontalAccuracy >= 0.0 }
                    ?.coordinate?.useContents { latitude to longitude }
                continuation.resume(fix)
            }
        }
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        continuation.invokeOnCancellation {
            // The timeout, or the caller went away: stop Core Location looking, on the manager's (main) thread. The
            // handler's reference to the delegate is also what keeps it alive until then.
            dispatch_async(dispatch_get_main_queue()) {
                manager.stopUpdatingLocation()
                if (manager.delegate === delegate) manager.delegate = null
            }
        }
        manager.requestLocation()
    }
}

/** `requestLocation()`'s answer, once: the newest fix, or null on an error. */
private class OneFixDelegate(private val onDone: (CLLocation?) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    private var done = false

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        finish(didUpdateLocations.lastOrNull() as? CLLocation)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        finish(null)
    }

    private fun finish(location: CLLocation?) {
        if (done) return
        done = true
        onDone(location)
    }
}

/**
 * Shows iOS's "Allow Doorprints to use your location?" prompt (`requestWhenInUseAuthorization`, with the prompt's own
 * *Precise* switch) and calls [onAnswered] once the user has answered, or at once when iOS shows nothing (already
 * answered: iOS asks only once). Made and kept on the main thread until then.
 */
internal class LocationPrompt(private val onAnswered: () -> Unit) {
    private var manager: CLLocationManager? = null
    private var delegate: AuthorizationDelegate? = null

    fun show() {
        if (manager != null) return
        if (!iosCanAskLocation()) {
            onAnswered()
            return
        }
        val newManager = CLLocationManager()
        val newDelegate = AuthorizationDelegate { status ->
            // Called once when the delegate is set (still not determined) and again after the answer.
            if (status != kCLAuthorizationStatusNotDetermined) {
                release()
                onAnswered()
            }
        }
        newManager.delegate = newDelegate
        manager = newManager
        delegate = newDelegate
        newManager.requestWhenInUseAuthorization()
    }

    /** Drops the manager, as when the screen leaves the composition before the answer. */
    fun release() {
        manager?.delegate = null
        manager = null
        delegate = null
    }
}

/** Hears Core Location's authorization changes (iOS 14+). */
private class AuthorizationDelegate(
    private val onChange: (CLAuthorizationStatus) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        onChange(manager.authorizationStatus)
    }
}
