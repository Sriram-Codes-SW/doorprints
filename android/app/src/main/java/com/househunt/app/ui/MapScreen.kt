package com.househunt.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.househunt.app.R
import com.househunt.app.data.HouseEntity
import com.househunt.app.location.HuntService
import com.househunt.app.location.HuntState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** Free vector map tiles from OpenFreeMap (OpenStreetMap data) — no API key or billing needed. */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val SOURCE = "houses"
private const val DOTS = "houses-dots"
private const val LABELS = "houses-labels"

fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

fun housesGeoJson(houses: List<HouseEntity>): String {
    val features = JSONArray()
    houses.forEach { h ->
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(h.lon).put(h.lat)))
                .put("properties", JSONObject().put("id", h.id).put("label", h.label).put("status", h.status.name))
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Pair<Double, Double>? {
    if (!hasLocationPermission(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
        ?: runCatching { client.lastLocation.await() }.getOrNull()
    return loc?.let { it.latitude to it.longitude }
}

@Composable
fun MapScreen(onOpenHouse: (String) -> Unit, onNewHouse: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val repo = repository()
    val scope = rememberCoroutineScope()
    val houses by repo.houses.collectAsStateWithLifecycle(emptyList())
    val hunt by HuntState.state.collectAsStateWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var permissionGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var startHuntAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (permissionGranted && startHuntAfterPermission) HuntService.start(context)
        if (!permissionGranted) Toast.makeText(context, context.getString(R.string.map_location_needed), Toast.LENGTH_LONG).show()
        startHuntAfterPermission = false
    }
    fun askPermissions(thenStartHunt: Boolean) {
        startHuntAfterPermission = thenStartHunt
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
    LaunchedEffect(Unit) { if (!permissionGranted) askPermissions(false) }

    val mapView = rememberMapViewWithLifecycle()
    // The map callbacks below are registered once, in the AndroidView factory; read the latest values through
    // these instead of the ones captured on the first composition.
    val currentHouses by rememberUpdatedState(houses)
    val currentOnOpenHouse by rememberUpdatedState(onOpenHouse)
    val currentOnNewHouse by rememberUpdatedState(onNewHouse)

    // Keep the markers in sync with the database.
    LaunchedEffect(style, houses) {
        (style?.getSource(SOURCE) as? GeoJsonSource)?.setGeoJson(housesGeoJson(houses))
    }

    // Show the blue "you are here" dot once we have permission.
    LaunchedEffect(style, permissionGranted) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!permissionGranted) return@LaunchedEffect
        enableLocationDot(context, m, s)
        currentLocation(context)?.let { (lat, lon) ->
            if (houses.isEmpty()) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.0))
        }
    }

    val mapDescription = stringResource(R.string.map_region_desc)
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { m ->
                        map = m
                        m.cameraPosition = CameraPosition.Builder().target(LatLng(20.59, 78.96)).zoom(4.0).build()
                        m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { s ->
                            addHouseLayers(s)
                            style = s
                            if (currentHouses.isNotEmpty()) {
                                val h = currentHouses.first()
                                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(h.lat, h.lon), 15.0))
                            }
                        }
                        m.addOnMapClickListener { point ->
                            val screen = m.projection.toScreenLocation(point)
                            val hit = m.queryRenderedFeatures(screen, DOTS, LABELS).firstOrNull()
                            hit?.getStringProperty("id")?.let { currentOnOpenHouse(it); true } ?: false
                        }
                        m.addOnMapLongClickListener { point ->
                            currentOnNewHouse(point.latitude, point.longitude); true
                        }
                    }
                }
            },
            // The canvas itself is not navigable with TalkBack; every house is also in the Houses tab (A11Y-B02).
            modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
        )

        HuntCard(
            hunt = hunt,
            onToggle = { on ->
                // start() returns false when the permission was revoked since this screen last checked.
                if (!on) HuntService.stop(context)
                else if (!permissionGranted || !HuntService.start(context)) askPermissions(true)
            },
            onOpenHouse = onOpenHouse,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
        )

        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(onClick = {
                scope.launch {
                    val here = currentLocation(context)
                    if (here == null) askPermissions(false)
                    else map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(here.first, here.second), 17.0))
                }
            }) { Icon(Icons.Default.LocationOn, contentDescription = stringResource(R.string.map_my_location)) }
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        val here = currentLocation(context)
                        if (here == null) {
                            askPermissions(false)
                            Toast.makeText(context, context.getString(R.string.map_long_press_tip), Toast.LENGTH_LONG).show()
                        } else onNewHouse(here.first, here.second)
                    }
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.map_save_here)) },
            )
        }
    }
}

@Composable
private fun HuntCard(
    hunt: HuntState.State,
    onToggle: (Boolean) -> Unit,
    onOpenHouse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // The whole row is one switch for TalkBack: "Hunt mode, <state hint>, switch, on/off".
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = hunt.active, role = Role.Switch, onValueChange = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.map_hunt_mode), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(if (hunt.active) R.string.map_hunt_on else R.string.map_hunt_off),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = hunt.active, onCheckedChange = null)
            }
            if (hunt.active) {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    hunt.street?.let { street ->
                        val text = if (hunt.streetHouses + hunt.streetVisits > 0) {
                            stringResource(R.string.map_street_seen, street, hunt.streetHouses, hunt.streetVisits)
                        } else {
                            stringResource(R.string.map_street_new, street)
                        }
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                    hunt.nearestHouse?.let { h ->
                        TextButton(onClick = { onOpenHouse(h.id) }, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.map_nearest, h.label, hunt.nearestDistanceM?.toInt() ?: 0))
                        }
                    }
                    val stale = hunt.lastFixAt == null || System.currentTimeMillis() - hunt.lastFixAt > 120_000L
                    when {
                        stale -> Text(stringResource(R.string.map_waiting_gps), style = MaterialTheme.typography.bodySmall)
                        hunt.accuracyM != null && hunt.accuracyM > HuntService.MAX_ACCURACY_M -> Text(
                            stringResource(R.string.map_weak_gps, hunt.accuracyM.toInt()),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun addHouseLayers(style: Style) {
    style.addSource(GeoJsonSource(SOURCE, housesGeoJson(emptyList())))
    val statusColor = Expression.match(
        Expression.get("status"),
        Expression.literal("SHORTLISTED"), Expression.color(MarkerColors.SHORTLISTED),
        Expression.literal("REJECTED"), Expression.color(MarkerColors.REJECTED),
        Expression.color(MarkerColors.NEW),
    )
    style.addLayer(
        CircleLayer(DOTS, SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(statusColor),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
        )
    )
    style.addLayer(
        SymbolLayer(LABELS, SOURCE).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textHaloColor(0xFFFFFFFF.toInt()),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textOptional(true),
        )
    )
}

@SuppressLint("MissingPermission")
private fun enableLocationDot(context: Context, map: MapLibreMap, style: Style) {
    runCatching {
        val lc = map.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, style).build())
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.COMPASS
    }
}

/**
 * A MapView driven by the screen's lifecycle. MapView crashes or leaks when its callbacks arrive out of order or
 * twice (onDestroy after onDestroy, onStop without onStart), so:
 *  - onStart/onResume/onPause/onStop are tracked and only ever called in pairs; leaving the screen (or the
 *    lifecycle owner changing) unwinds pause/stop before anything else,
 *  - onDestroy runs exactly once, when the MapView itself leaves the composition. Before this change it ran in
 *    the same effect as the observer, so a new lifecycle owner destroyed the map and then kept using it.
 * Compose disposes effects in reverse order, so the destroy effect is declared first and runs last.
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView) {
        onDispose { mapView.onDestroy() }
    }
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        fun start() {
            if (!started) {
                mapView.onStart()
                started = true
            }
        }
        fun resume() {
            start()
            if (!resumed) {
                mapView.onResume()
                resumed = true
            }
        }
        fun pause() {
            if (resumed) {
                mapView.onPause()
                resumed = false
            }
        }
        fun stop() {
            pause()
            if (started) {
                mapView.onStop()
                started = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    return mapView
}
