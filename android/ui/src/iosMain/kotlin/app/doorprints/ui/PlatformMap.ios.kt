package app.doorprints.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import app.doorprints.data.HouseEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Map's view on iOS (ADR-23 CMP-8c): MapLibre iOS's `MLNMapView`, made by the Swift app through [IosMap.factory]
 * (ios/Doorprints/MapLibreMapView.swift), in a [UIKitView]. The same contract as Android's (PlatformMap.android.kt):
 * [MapEvents.onReady] first, then the style, which is Liberty with India's boundary rules applied in Kotlin
 * ([IosMapStyle], [prepareMapStyle]) and handed to MapLibre whole as JSON; the houses, the label size, the location dot
 * and the attribution follow the style and their own changes.
 *
 * Touches go to the map at once ([UIKitInteropInteractionMode.NonCooperative]): the map is the whole screen under
 * Compose's chrome, so there is no Compose scroll to share a pan with, and a cooperative delay would make every pan
 * start late. Without a registered factory (tests, previews) an empty box stands in and [events] hears nothing, as on
 * Android in inspection mode.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun PlatformMap(
    houses: List<HouseEntity>,
    labelSizeSp: Float,
    showLocation: Boolean,
    attribution: MapAttribution,
    events: MapEvents,
    modifier: Modifier,
) {
    val factory = IosMap.factory
    if (factory == null) {
        Box(modifier)
        return
    }
    val currentEvents by rememberUpdatedState(events)
    val currentLabelSize by rememberUpdatedState(labelSizeSp)
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    // The style the map was last given, and whether it has loaded (the houses and label size wait for it).
    var style by remember { mutableStateOf<PreparedMapStyle?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf<Job?>(null) }

    val map = remember {
        factory.create(
            object : IosMapListener {
                override fun onStyleLoaded(layerIds: List<String>, hiddenIds: List<String>) {
                    style?.let { IosMapStyle.loaded(it, layerIds, hiddenIds) }
                    styleLoaded = true
                    currentEvents.onStyleLoaded()
                }

                override fun onFailed(message: String) {
                    logLine("DOORPRINTS-MAP failed: $message")
                    currentEvents.onFailed()
                }

                override fun onUserGesture() = currentEvents.onUserGesture()

                override fun onCameraIdle(lat: Double, lon: Double, zoom: Double, bearing: Double) =
                    currentEvents.onCameraIdle(CameraSpot(lat, lon, zoom, bearing))

                override fun onHouseTap(id: String) = currentEvents.onHouseTap(id)

                override fun onLongPress(lat: Double, lon: Double) = currentEvents.onLongPress(lat, lon)
            },
        )
    }

    // Prepares the style off the main thread and hands it to the map; a failure (no network and no cached copy) is
    // the map's "could not load" state, with *Try again* ([MapControl.reloadStyle]).
    fun loadStyle() {
        loading?.cancel()
        styleLoaded = false
        loading = scope.launch {
            try {
                val prepared = IosMapStyle.prepare(currentLabelSize)
                style = prepared
                map.loadStyle(prepared.json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logLine("DOORPRINTS-MAP style failed: ${e::class.simpleName}: ${e.message}")
                currentEvents.onFailed()
            }
        }
    }

    LaunchedEffect(map) {
        // The chrome places the camera (a restored one, or the whole of India) before the style loads, as on Android.
        currentEvents.onReady(IosMapControl(map, density) { loadStyle() })
        loadStyle()
    }

    LaunchedEffect(styleLoaded, houses) {
        if (styleLoaded) map.setHouses(housesGeoJson(houses))
    }

    LaunchedEffect(styleLoaded, labelSizeSp) {
        if (styleLoaded) map.setLabelSize(labelSizeSp.toDouble())
    }

    // The blue "you are here" dot once the chrome has permission; MLNMapView reads the location itself.
    LaunchedEffect(showLocation) {
        map.setShowsUserLocation(showLocation)
    }

    // The attribution ("i") where the chrome leaves room for it (MapScreen), in points; hidden under a snackbar.
    LaunchedEffect(attribution, density) {
        map.setAttribution(
            attribution.startPx / density.toDouble(), attribution.bottomPx / density.toDouble(), attribution.shown,
        )
    }

    UIKitView(
        factory = { map.view },
        modifier = modifier,
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            // VoiceOver reaches MapLibre's own elements (the attribution button, the markers it announces).
            isNativeAccessibilityEnabled = true,
        ),
    )
}

/** [MapControl] over an [IosMapView]; pixels from the chrome become points at [density]. */
private class IosMapControl(
    private val map: IosMapView,
    private val density: Float,
    private val reload: () -> Unit,
) : MapControl {
    override fun setCamera(lat: Double, lon: Double, zoom: Double, bearing: Double?) =
        map.setCamera(lat, lon, zoom, bearing ?: 0.0, setBearing = bearing != null)

    override fun moveTo(lat: Double, lon: Double, zoom: Double, animate: Boolean) = map.moveTo(lat, lon, zoom, animate)

    override fun zoomIn(animate: Boolean) = map.zoomBy(1.0, animate)

    override fun zoomOut(animate: Boolean) = map.zoomBy(-1.0, animate)

    override fun frame(points: List<Pair<Double, Double>>, paddingPx: Int, maxZoom: Double) {
        if (points.isEmpty()) return
        map.frame(
            south = points.minOf { it.first }, west = points.minOf { it.second },
            north = points.maxOf { it.first }, east = points.maxOf { it.second },
            paddingPt = paddingPx / density.toDouble(), maxZoom = maxZoom,
        )
    }

    override fun camera(): CameraSpot? {
        val lat = map.centerLatitude()
        val lon = map.centerLongitude()
        if (lat.isNaN() || lon.isNaN()) return null
        return CameraSpot(lat, lon, map.zoomLevel(), map.bearing())
    }

    override fun reloadStyle() = reload()
}
