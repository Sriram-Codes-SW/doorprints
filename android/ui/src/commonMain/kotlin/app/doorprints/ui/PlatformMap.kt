package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import app.doorprints.data.HouseEntity

/** The map's camera, kept in saved state so the map comes back where the user left it. */
data class CameraSpot(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double)

internal val CameraSpotSaver = Saver<CameraSpot?, DoubleArray>(
    save = { it?.let { c -> doubleArrayOf(c.lat, c.lon, c.zoom, c.bearing) } },
    restore = { a -> if (a.size == 4) CameraSpot(a[0], a[1], a[2], a[3]) else null },
)

/** Where the map starts before its first framing: the whole of India (never saved as the user's place). */
internal const val INDIA_START_LAT = 20.59
internal const val INDIA_START_LON = 78.96
internal const val INDIA_START_ZOOM = 4.0

/**
 * What the Map's chrome ([MapScreen]) asks of the map view once it is ready ([MapEvents.onReady]). Called on the main
 * thread. Android: MapLibre's `MapLibreMap` (camera updates through `CameraUpdateFactory`).
 */
interface MapControl {
    /** Puts the camera at once at [lat], [lon], [zoom], and at [bearing] when given (the view's own otherwise). */
    fun setCamera(lat: Double, lon: Double, zoom: Double, bearing: Double?)

    /** Moves to [lat], [lon] at [zoom]: animated, or at once when [animate] is false (*Remove animations*). */
    fun moveTo(lat: Double, lon: Double, zoom: Double, animate: Boolean)

    fun zoomIn(animate: Boolean)

    fun zoomOut(animate: Boolean)

    /**
     * Frames [points] (at least two) with [paddingPx] around them, at most at [maxZoom] (houses on one spot give an
     * empty box, which zooms in as far as the map goes); a box the view cannot fit is left as it is.
     */
    fun frame(points: List<Pair<Double, Double>>, paddingPx: Int, maxZoom: Double)

    /** The camera now, or null when the view has no target yet. */
    fun camera(): CameraSpot?

    /** Loads the base style again (*Try again* after a failure); [MapEvents.onStyleLoaded] follows on success. */
    fun reloadStyle()
}

/**
 * What the map view tells the Map's chrome, on the main thread. The view reads the latest object on every call, so
 * the chrome may hand a new one on each composition.
 */
interface MapEvents {
    /** The view is ready: the chrome places the camera here, before the view listens for moves or loads the style. */
    fun onReady(control: MapControl)

    /** The base style finished loading (the first time and after each [MapControl.reloadStyle]). */
    fun onStyleLoaded()

    /** The map could not load (offline, or the tile service failed). */
    fun onFailed()

    /** The user moved the map (a pan, pinch or double-tap), even before the style arrived. */
    fun onUserGesture()

    /** The camera came to rest at [spot]. */
    fun onCameraIdle(spot: CameraSpot)

    /** A tap within 48 dp of the house [id]'s marker (the nearest one). */
    fun onHouseTap(id: String)

    /** A long press at [lat], [lon]. */
    fun onLongPress(lat: Double, lon: Double)
}

/**
 * MapLibre's attribution button ("i": the OpenStreetMap, OpenMapTiles and OpenFreeMap credits): [startPx] from the
 * start edge, [bottomPx] from the bottom, hidden while not [shown]; the MapLibre logo is always off.
 */
data class MapAttribution(val startPx: Int, val bottomPx: Int, val shown: Boolean)

/**
 * The map view under the Map's chrome (ADR-23 CMP-7): the OpenFreeMap Liberty base style with India's boundary as the
 * Government of India shows it ([applyIndiaView] on every style load, ADR-22), the houses as markers and names
 * ([houses], labels at [labelSizeSp]), the "you are here" dot while [showLocation], north-up, and the attribution at
 * [attribution]. Taps, long presses, gestures and camera rests go to [events].
 *
 * Android: the MapLibre `MapView` in an `AndroidView` (MapLibre Native, OpenGL ES build), driven by the screen's
 * lifecycle; in inspection mode (previews, JVM tests), where MapLibre's native library cannot load, an empty box.
 * iOS: an empty box until the iOS shell brings MapLibre iOS (CMP-8).
 */
@Composable
expect fun PlatformMap(
    houses: List<HouseEntity>,
    labelSizeSp: Float,
    showLocation: Boolean,
    attribution: MapAttribution,
    events: MapEvents,
    modifier: Modifier = Modifier,
)
