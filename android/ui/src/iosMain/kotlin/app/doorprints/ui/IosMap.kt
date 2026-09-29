package app.doorprints.ui

import platform.UIKit.UIView

/*
 * The seam between the common map (PlatformMap.ios.kt) and MapLibre iOS (ADR-23 CMP-8c). MapLibre iOS is a Swift
 * package the Xcode project links (ios/project.yml); Kotlin/Native cannot import it, so the Swift app implements
 * these interfaces over `MLNMapView` (ios/Doorprints/MapLibreMapView.swift) and registers its factory in [IosMap]
 * before the first view controller is made. Kotlin hands over only text (the style and the houses as JSON) and
 * numbers; the style arrives with India's boundary rules already applied ([prepareMapStyle]), so Swift never touches
 * a filter. Everything here runs on the main thread.
 */

/** Makes one map view that reports to [listener]; the Swift app's MapLibreMapViewFactory. */
interface IosMapViewFactory {
    fun create(listener: IosMapListener): IosMapView
}

/** One MapLibre map view, driven by [PlatformMap]. Camera values are MapLibre's: degrees and zoom levels. */
interface IosMapView {
    /** The view to host (an `MLNMapView`). */
    val view: UIView

    /** Loads the style [json] (a whole MapLibre style); [IosMapListener.onStyleLoaded] or `onFailed` follows. */
    fun loadStyle(json: String)

    /** Replaces the data of the houses' source ([HOUSES_SOURCE]) with [geoJson]; nothing before a style has loaded. */
    fun setHouses(geoJson: String)

    /** The house names' text size ([HOUSE_LABELS_LAYER]), in points. */
    fun setLabelSize(size: Double)

    fun setShowsUserLocation(show: Boolean)

    /** MapLibre's attribution button ("i") [startPt] from the leading edge and [bottomPt] from the bottom, or hidden. */
    fun setAttribution(startPt: Double, bottomPt: Double, shown: Boolean)

    /** Puts the camera at once; [bearing] only when [setBearing]. */
    fun setCamera(lat: Double, lon: Double, zoom: Double, bearing: Double, setBearing: Boolean)

    fun moveTo(lat: Double, lon: Double, zoom: Double, animated: Boolean)

    /** Zooms by [delta] levels (+1 in, -1 out) about the centre. */
    fun zoomBy(delta: Double, animated: Boolean)

    /** Frames the box at once with [paddingPt] around it, at most at [maxZoom]. */
    fun frame(south: Double, west: Double, north: Double, east: Double, paddingPt: Double, maxZoom: Double)

    /** The view leaves the screen for good: stop the location updates and the callbacks. */
    fun release()

    fun centerLatitude(): Double

    fun centerLongitude(): Double

    fun zoomLevel(): Double

    fun bearing(): Double
}

/** What a map view reports, on the main thread. */
interface IosMapListener {
    /** A style loaded: its layers bottom to top ([layerIds]) and those MapLibre reports hidden ([hiddenIds]). */
    fun onStyleLoaded(layerIds: List<String>, hiddenIds: List<String>)

    /** The style or its tiles could not load; [message] is MapLibre's, for the log. */
    fun onFailed(message: String)

    /** The user moved the map (pan, pinch, double-tap). */
    fun onUserGesture()

    fun onCameraIdle(lat: Double, lon: Double, zoom: Double, bearing: Double)

    /** A tap on or near the house [id]'s marker or name (the nearest, within 24 pt). */
    fun onHouseTap(id: String)

    fun onLongPress(lat: Double, lon: Double)
}

/** Where the Swift app registers its map factory (`IosMap.shared.factory = …` before the first view controller). */
object IosMap {
    /** Null in tests and previews: the map is then an empty box, as on Android in inspection mode. */
    var factory: IosMapViewFactory? = null
}
