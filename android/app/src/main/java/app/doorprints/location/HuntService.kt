package app.doorprints.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import app.doorprints.DoorprintsApp
import app.doorprints.Notifications
import app.doorprints.R
import app.doorprints.data.HouseEntity
import app.doorprints.data.VisitEntity
import app.doorprints.data.labelRes
import app.doorprints.shared.location.Geo
import app.doorprints.shared.location.StayDetector
import app.doorprints.shared.location.StreetAlerts
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.VisitSource
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.Formats
import kotlinx.coroutines.launch
import java.util.*

/**
 * "Hunt mode": a foreground service that follows your location while you're out house hunting and
 *  - alerts you when you pass a house you've already visited,
 *  - tells you when you enter a street you've been on before,
 *  - notices when you stop somewhere for a few minutes and offers to save it as a house.
 *
 * Battery (docs/09 L1): GPS fixes every 15 s while walking, every 60 s while standing still (inside a house you are
 * viewing), and the service stops itself when the battery falls to [LOW_BATTERY_PERCENT] and is not charging.
 * Fixes worse than [MAX_ACCURACY_M] (indoors, urban canyons) are shown but never trigger alerts or visits.
 */
class HuntService : LifecycleService() {

    private val repo by lazy { (application as DoorprintsApp).container.repository }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val geocoder by lazy { ReverseGeocoder(this) }
    private val stays = StayDetector()

    private var houses: List<HouseEntity> = emptyList()
    private var alertRadiusM = 30
    private val houseAlertedAt = mutableMapOf<String, Long>()
    private val streetAlertedAt = mutableMapOf<String, Long>()
    private var lastGeocodeAt = 0L
    private var lastGeocodeLat = 0.0
    private var lastGeocodeLon = 0.0
    private var currentStreet: String? = null
    private var stayPromptVisitId: String? = null
    private var stationaryMode: Boolean? = null
    private var lastBatteryCheckAt = 0L

    /** Set just before a stopSelf() the user did not ask for; the Map says why (see [HuntState.State.stopReason]). */
    private var stopReason: HuntState.StopReason? = null

    private fun stopFor(reason: HuntState.StopReason) {
        stopReason = reason
        stopSelf()
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch { repo.houses.collect { houses = it } }
        lifecycleScope.launch {
            repo.settings.settings.collect {
                alertRadiusM = it.alertRadiusM
                stays.minStayMs = it.minStayMinutes * 60_000L
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // The user's own Stop (the notification action): no reason to show.
            stopReason = null
            stopSelf()
            return START_NOT_STICKY
        }
        val stopIntent = android.app.PendingIntent.getService(
            this, 0, Intent(this, HuntService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_HUNT)
            .setSmallIcon(R.drawable.ic_stat_doorprints)
            .setContentTitle(getString(R.string.notif_hunt_title))
            .setContentText(getString(R.string.notif_hunt_text))
            .setOngoing(true)
            .setContentIntent(Notifications.openAppIntent(this, 0))
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        // startForeground comes first: a service started with startForegroundService must call it within a few
        // seconds. It throws SecurityException on API 34+ when the location permission is missing (type "location"),
        // and ForegroundServiceStartNotAllowedException on API 31+ when a START_STICKY restart happens while the
        // app is in the background. Either way Hunt mode cannot run, so stop quietly instead of crashing.
        val inForeground = try {
            ServiceCompat.startForeground(this, Notifications.ONGOING_ID, notification, type)
            true
        } catch (e: RuntimeException) {
            false
        }
        if (!inForeground) {
            stopFor(HuntState.StopReason.NOT_ALLOWED)
            return START_NOT_STICKY
        }
        if (!hasLocationPermission(this)) {
            stopFor(HuntState.StopReason.NO_PERMISSION)
            return START_NOT_STICKY
        }

        stopReason = null
        stationaryMode = null
        requestUpdates(stationary = false)
        HuntState.update { it.copy(active = true, startedAt = System.currentTimeMillis(), stopReason = null) }
        return START_STICKY
    }

    /** Same callback, so a new request replaces the previous one. */
    @SuppressLint("MissingPermission")
    private fun requestUpdates(stationary: Boolean) {
        if (stationaryMode == stationary) return
        stationaryMode = stationary
        val request = if (stationary) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L)
                .setMinUpdateIntervalMillis(30_000L)
                .setMinUpdateDistanceMeters(10f)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .setMinUpdateDistanceMeters(5f)
                .build()
        }
        runCatching { fused.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
            .onFailure { stopFor(HuntState.StopReason.NO_PERMISSION) } // permission revoked while running
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        // Everything resets except why it stopped, which the Map shows until it is closed.
        HuntState.update { HuntState.State(stopReason = stopReason) }
        super.onDestroy()
    }

    private fun onLocation(loc: Location) {
        HuntState.update {
            it.copy(lat = loc.latitude, lon = loc.longitude, accuracyM = loc.accuracy, lastFixAt = System.currentTimeMillis())
        }
        if (stopIfBatteryLow()) return
        // Readings this rough can't tell one house from the next.
        if (loc.accuracy > MAX_ACCURACY_M) return
        checkNearbyHouses(loc)
        checkStreet(loc)
        checkStay(loc)
        requestUpdates(stationary = stays.isStaying)
    }

    private fun stopIfBatteryLow(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBatteryCheckAt < 120_000L) return false
        lastBatteryCheckAt = now
        val battery = getSystemService(BatteryManager::class.java) ?: return false
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent in 1..LOW_BATTERY_PERCENT && !battery.isCharging) {
            Notifications.alert(this, Notifications.LOW_BATTERY_ID, getString(R.string.notif_battery_title),
                getString(R.string.notif_battery_text, percent), Notifications.openAppIntent(this, 0))
            stopFor(HuntState.StopReason.LOW_BATTERY)
            return true
        }
        return false
    }

    private fun checkNearbyHouses(loc: Location) {
        val now = System.currentTimeMillis()
        val nearest = houses
            .map { it to Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lon) }
            .minByOrNull { it.second }
        val close = nearest?.takeIf { it.second <= 150 }
        HuntState.update { it.copy(nearestHouse = close?.first, nearestDistanceM = close?.second) }
        val (house, distance) = nearest ?: return
        if (distance > alertRadiusM) return
        val last = houseAlertedAt[house.id] ?: 0
        if (now - last < 30 * 60_000L) return
        houseAlertedAt[house.id] = now
        Notifications.alert(
            this, house.id.hashCode(),
            getString(R.string.notif_seen_house, house.label),
            getString(R.string.notif_distance, describe(house), distance.toInt()),
            Notifications.openAppIntent(this, house.id.hashCode()) {
                putExtra(Notifications.EXTRA_OPEN_HOUSE, house.id)
            },
        )
    }

    private fun checkStreet(loc: Location) {
        val now = System.currentTimeMillis()
        val moved = Geo.distanceM(lastGeocodeLat, lastGeocodeLon, loc.latitude, loc.longitude)
        if (now - lastGeocodeAt < 45_000 && moved < 80) return
        lastGeocodeAt = now
        lastGeocodeLat = loc.latitude
        lastGeocodeLon = loc.longitude
        lifecycleScope.launch {
            // Offline or DNS failure: the geocoder returns null and street alerts simply pause (docs/09 L3).
            val street = geocoder.lookup(loc.latitude, loc.longitude)?.street ?: return@launch
            if (StreetAlerts.sameStreet(street, currentStreet)) return@launch
            currentStreet = street
            val info = repo.streetInfo(street)
            HuntState.update { it.copy(street = street, streetHouses = info.houses, streetVisits = info.visits) }
            val key = StreetAlerts.key(street)
            if (!StreetAlerts.shouldAlert(info.houses, info.visits, streetAlertedAt[key], now)) return@launch
            streetAlertedAt[key] = now
            val text = info.firstVisit?.let {
                getString(R.string.notif_street_text_since, info.houses, info.visits, Formats.date(it))
            } ?: getString(R.string.notif_street_text, info.houses, info.visits)
            Notifications.alert(
                this@HuntService, key.hashCode(),
                getString(R.string.notif_street_title, street),
                text,
                Notifications.openAppIntent(this@HuntService, key.hashCode()),
            )
        }
    }

    private fun checkStay(loc: Location) {
        when (val event = stays.onLocation(loc.latitude, loc.longitude, loc.time)) {
            is StayDetector.Event.Started -> onStayStarted(event)
            is StayDetector.Event.Ended -> onStayEnded(event)
            null -> Unit
        }
        HuntState.update { it.copy(staying = stays.isStaying) }
    }

    private fun houseAt(lat: Double, lon: Double) = houses
        .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
        .filter { it.second <= 40 }
        .minByOrNull { it.second }?.first

    private fun onStayStarted(e: StayDetector.Event.Started) {
        val visitId = UUID.randomUUID().toString()
        stayPromptVisitId = visitId
        val house = houseAt(e.lat, e.lon)
        lifecycleScope.launch {
            val street = geocoder.lookup(e.lat, e.lon)?.street
            repo.saveVisit(
                VisitEntity(
                    id = visitId, houseId = house?.id, lat = e.lat, lon = e.lon, street = street,
                    arrivedAt = e.since, source = VisitSource.AUTO, updatedAt = System.currentTimeMillis(),
                )
            )
        }
        if (house == null) {
            Notifications.alert(
                this, visitId.hashCode(),
                getString(R.string.notif_stay_title),
                getString(R.string.notif_stay_text),
                Notifications.openAppIntent(this, visitId.hashCode()) {
                    putExtra(Notifications.EXTRA_NEW_LAT, e.lat)
                    putExtra(Notifications.EXTRA_NEW_LON, e.lon)
                    putExtra(Notifications.EXTRA_VISIT_ID, visitId)
                },
            )
        }
    }

    private fun onStayEnded(e: StayDetector.Event.Ended) {
        val visitId = stayPromptVisitId ?: return
        stayPromptVisitId = null
        lifecycleScope.launch {
            val visit = repo.getVisit(visitId) ?: return@launch
            repo.saveVisit(visit.copy(leftAt = e.leftAt, lat = e.lat, lon = e.lon))
        }
    }

    private fun describe(h: HouseEntity): String {
        val parts = mutableListOf<String>()
        if (h.status != HouseStatus.NEW) parts += getString(h.status.labelRes)
        Formats.price(h.price, h.priceType) { getString(R.string.price_per_month, it) }?.let { parts += it }
        h.rating?.let { parts += "★".repeat(it) }
        h.score?.let { parts += getString(R.string.common_score_value, Formats.score(it)) }
        return parts.joinToString(" · ").ifEmpty { getString(R.string.notif_visited_before) }
    }

    companion object {
        private const val ACTION_STOP = "stop"
        const val MAX_ACCURACY_M = HuntState.MAX_ACCURACY_M
        const val LOW_BATTERY_PERCENT = 15

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Starts Hunt mode. Returns false without starting when location permission is missing (a location
         * foreground service cannot start without it on API 34+) or when the system refuses a foreground-service
         * start because the app is not in the foreground (API 31+).
         */
        fun start(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, HuntService::class.java))
                true
            } catch (e: IllegalStateException) {
                false
            }
        }

        /** The user turned Hunt mode off: onDestroy runs with no stop reason. */
        fun stop(context: Context) = context.stopService(Intent(context, HuntService::class.java))

        /** Closes the "Hunt mode stopped because…" card on the Map. */
        fun clearStopReason() = HuntState.update { it.copy(stopReason = null) }
    }
}
