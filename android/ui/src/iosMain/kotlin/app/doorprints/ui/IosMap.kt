/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

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

    /** Replaces the data of the path trace's source ([TRACK_SOURCE]) with [geoJson]; nothing before a style has loaded. */
    fun setTrack(geoJson: String)

    /**
     * The repeat overlay's width stops (`[zoom, width]` pairs, [TRACK_REPEAT_LAYER]) and whether it shows (docs/11
     * 5.27.4): the person's look, applied live; nothing before a style has loaded.
     */
    fun setRepeatLook(widthStops: List<List<Double>>, visible: Boolean)

    /** Replaces the data of the place check's source ([CHECK_SOURCE]) with [geoJson]; nothing before a style has loaded. */
    fun setCheck(geoJson: String)

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

    /** The view leaves the screen for good: stop the location updates and the callbacks. (Not `release`: that is NSObject's.) */
    fun shutDown()

    fun centerLatitude(): Double

    fun centerLongitude(): Double

    /** The box on screen (offline maps, docs/11 5.20): south, west, north, east; NaN before the view has a size. */
    fun visibleSouth(): Double

    fun visibleWest(): Double

    fun visibleNorth(): Double

    fun visibleEast(): Double

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

    /** The offline store (`MLNOfflineStorage`, MapLibreOfflineMaps.swift), registered with the factory; null: none. */
    var offline: IosOfflineMaps? = null
}

/**
 * MapLibre iOS's offline packs behind a Kotlin interface (S4b-FR-6, docs/11 5.20), as [IosMapView] is for the map:
 * the Swift shell implements it over `MLNOfflineStorage` and reports every change as a whole snapshot ([setListener]).
 * Main thread.
 */
interface IosOfflineMaps {
    /** Set once by `IosOfflineMapsServices`; the packs as they are now follow at once, then on every change. */
    fun setListener(listener: IosOfflineMapsListener)

    /** Adds a pack for the box, zoom [minZoom] to [maxZoom], with [id] and [name] in its context. */
    fun add(id: String, name: String, south: Double, west: Double, north: Double, east: Double, minZoom: Double, maxZoom: Double)

    fun remove(id: String)

    /** True on mobile data or a metered hotspot (`NWPath.isExpensive`). */
    fun networkMetered(): Boolean
}

/** Hears the offline store: every change comes as the whole list of packs. */
interface IosOfflineMapsListener {
    fun onPacks(packs: List<IosOfflinePack>)
}

/** One pack as Swift reports it; [state] is 0 saving, 1 ready, 2 failed. */
class IosOfflinePack(
    val id: String,
    val name: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val state: Int,
    val bytes: Long,
)
