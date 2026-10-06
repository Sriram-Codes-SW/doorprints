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

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.doorprints.shared.trace.RepeatLook
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
    track: TraceDrawing,
    repeatLook: RepeatLook,
    check: CheckOverlay?,
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
    // True from handing MapLibre a prepared style until it loads or fails. The view starts on an empty style of its
    // own (MapLibreMapView.swift), whose load and any failure before ours are not the map's state.
    var awaitingStyle by remember { mutableStateOf(false) }

    val map = remember {
        factory.create(
            object : IosMapListener {
                override fun onStyleLoaded(layerIds: List<String>, hiddenIds: List<String>) {
                    // Only a style we prepared (it has the house layers) counts; the view's empty start style does not.
                    val prepared = style
                    if (!awaitingStyle || prepared == null || HOUSE_DOTS_LAYER !in layerIds) return
                    awaitingStyle = false
                    IosMapStyle.loaded(prepared, layerIds, hiddenIds)
                    styleLoaded = true
                    currentEvents.onStyleLoaded()
                }

                override fun onFailed(message: String) {
                    logLine("DOORPRINTS-MAP failed: $message")
                    if (!awaitingStyle) return
                    awaitingStyle = false
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
                styleLoaded = false
                awaitingStyle = true
                map.loadStyle(prepared.json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logLine("DOORPRINTS-MAP style failed: ${e::class.simpleName}: ${e.message}")
                currentEvents.onFailed()
            }
        }
    }

    val currentDensity by rememberUpdatedState(density)
    LaunchedEffect(map) {
        // The chrome places the camera (a restored one, or the whole of India) before the style loads, as on Android.
        currentEvents.onReady(IosMapControl(map, { currentDensity }) { loadStyle() })
        loadStyle()
    }
    DisposableEffect(map) {
        onDispose { map.shutDown() }
    }

    LaunchedEffect(styleLoaded, houses) {
        if (styleLoaded) map.setHouses(housesGeoJson(houses))
    }

    LaunchedEffect(styleLoaded, track) {
        if (styleLoaded) map.setTrack(track.geoJson)
    }

    // How repeated paths look (docs/11 5.27.4): the overlay layer's widths and visibility, live (MapLibreMapView.swift).
    LaunchedEffect(styleLoaded, repeatLook) {
        if (styleLoaded) {
            map.setRepeatLook(repeatWidthStops(repeatLook).map { (zoom, width) -> listOf(zoom, width) }, repeatLook != RepeatLook.OFF)
        }
    }

    // The place check's stretches and ring (docs/11 5.27.13).
    LaunchedEffect(styleLoaded, check) {
        if (styleLoaded) map.setCheck(checkGeoJson(check))
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
    private val density: () -> Float,
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
            paddingPt = paddingPx / density().toDouble(), maxZoom = maxZoom,
        )
    }

    override fun camera(): CameraSpot? {
        val lat = map.centerLatitude()
        val lon = map.centerLongitude()
        if (lat.isNaN() || lon.isNaN()) return null
        return CameraSpot(lat, lon, map.zoomLevel(), map.bearing())
    }

    override fun visibleBounds(): GeoBounds? {
        val south = map.visibleSouth()
        if (south.isNaN()) return null
        return GeoBounds(south = south, west = map.visibleWest(), north = map.visibleNorth(), east = map.visibleEast())
    }

    override fun reloadStyle() = reload()
}
