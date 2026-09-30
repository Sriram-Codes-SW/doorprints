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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.doorprints.data.HouseEntity
import app.doorprints.data.TrackPointEntity
import kotlin.math.hypot
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/**
 * The Map's view on Android (ADR-23 CMP-7): MapLibre Native's [MapView] (the OpenGL ES build) in an [AndroidView],
 * with the code that was in `:app`'s `MapScreen` before the chrome moved to common code. The listeners are registered
 * once, in the factory, and read the latest [events] and [labelSizeSp]; the style-keyed effects (the houses' GeoJSON,
 * the label size, the location dot) run again after every style load, and the attribution margins whenever they
 * change.
 *
 * In inspection mode (Android Studio previews, Robolectric tests such as RootNavigationTest) MapLibre's native library
 * cannot load, so an empty box stands in and [events] hears nothing.
 */
@Composable
actual fun PlatformMap(
    houses: List<HouseEntity>,
    track: List<TrackPointEntity>,
    labelSizeSp: Float,
    showLocation: Boolean,
    attribution: MapAttribution,
    events: MapEvents,
    modifier: Modifier,
) {
    if (LocalInspectionMode.current) {
        Box(modifier)
        return
    }
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    // The map callbacks below are registered once, in the AndroidView factory; read the latest values through these
    // instead of the ones captured on the first composition.
    val currentEvents by rememberUpdatedState(events)
    val currentLabelSize by rememberUpdatedState(labelSizeSp)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Half of a 48 dp touch target: a tap anywhere within it hits the marker (whole-app audit, motor access).
    val hitRadiusPx = with(LocalDensity.current) { MARKER_HIT_RADIUS_DP.dp.toPx() }

    fun loadStyle(m: MapLibreMap) {
        m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { s ->
            // Before the house layers, on every load (the first one and each retry): India's own boundary, no LoC/LAC.
            applyIndiaView(MapLibreStyleOps(s, context.assets))
            addHouseLayers(s, currentLabelSize)
            style = s
            currentEvents.onStyleLoaded()
        }
    }

    LaunchedEffect(style, labelSizeSp) {
        val labels = style?.getLayer(HOUSE_LABELS_LAYER) as? SymbolLayer ?: return@LaunchedEffect
        labels.setProperties(PropertyFactory.textSize(labelSizeSp))
    }

    // Keep the markers in sync with the database.
    LaunchedEffect(style, houses) {
        (style?.getSource(HOUSES_SOURCE) as? GeoJsonSource)?.setGeoJson(housesGeoJson(houses))
    }

    // And the path trace (docs/11 5.27).
    LaunchedEffect(style, track) {
        (style?.getSource(TRACK_SOURCE) as? GeoJsonSource)?.setGeoJson(trackGeoJson(track))
    }

    // Show the blue "you are here" dot once we have permission.
    LaunchedEffect(style, showLocation) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (showLocation) enableLocationDot(context, m, s)
    }

    // The attribution ("i") on the gutter, lifted above what the chrome draws at the bottom start, and hidden while a
    // snackbar sits in its place (MapScreen); MapLibre's logo off.
    LaunchedEffect(map, attribution) {
        val ui = map?.uiSettings ?: return@LaunchedEffect
        ui.setLogoEnabled(false)
        ui.setAttributionMargins(attribution.startPx, 0, 0, attribution.bottomPx)
        ui.isAttributionEnabled = attribution.shown
    }

    AndroidView(
        factory = {
            mapView.apply {
                addOnDidFailLoadingMapListener { currentEvents.onFailed() }
                getMapAsync { m ->
                    map = m
                    // North-up (round 8; MAP_NORTH_UP, WCAG 2.5.1): no two-finger rotation or tilt, so no compass is
                    // needed to undo one, as on the web map.
                    m.uiSettings.isRotateGesturesEnabled = !MAP_NORTH_UP
                    m.uiSettings.isTiltGesturesEnabled = !MAP_NORTH_UP
                    m.uiSettings.isCompassEnabled = !MAP_NORTH_UP
                    // The chrome places the camera (a restored one, or the whole of India) before anything listens.
                    currentEvents.onReady(MapLibreControl(m) { loadStyle(m) })
                    // A pan, pinch or double-tap by the user (even before the style arrives) keeps their place: the
                    // first framing then never runs.
                    m.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            currentEvents.onUserGesture()
                        }
                    }
                    m.addOnCameraIdleListener { m.spot()?.let { currentEvents.onCameraIdle(it) } }
                    loadStyle(m)
                    m.addOnMapClickListener { point ->
                        val screen = m.projection.toScreenLocation(point)
                        val area = RectF(
                            screen.x - hitRadiusPx, screen.y - hitRadiusPx,
                            screen.x + hitRadiusPx, screen.y + hitRadiusPx,
                        )
                        // The marker nearest the finger, when several are inside the 48 dp square.
                        val hit = m.queryRenderedFeatures(area, HOUSE_DOTS_LAYER, HOUSE_LABELS_LAYER).minByOrNull { f ->
                            (f.geometry() as? Point)?.let { pt ->
                                val at = m.projection.toScreenLocation(LatLng(pt.latitude(), pt.longitude()))
                                hypot((at.x - screen.x).toDouble(), (at.y - screen.y).toDouble())
                            } ?: Double.MAX_VALUE
                        }
                        hit?.getStringProperty("id")?.let { currentEvents.onHouseTap(it); true } ?: false
                    }
                    m.addOnMapLongClickListener { point ->
                        currentEvents.onLongPress(point.latitude, point.longitude); true
                    }
                }
            }
        },
        modifier = modifier,
    )
}

private fun MapLibreMap.spot(): CameraSpot? {
    val p = cameraPosition
    return p.target?.let { CameraSpot(it.latitude, it.longitude, p.zoom, p.bearing) }
}

/** [MapControl] over MapLibre's [MapLibreMap], with the camera updates `MapScreen` made before CMP-7. */
private class MapLibreControl(private val m: MapLibreMap, private val reload: () -> Unit) : MapControl {
    override fun setCamera(lat: Double, lon: Double, zoom: Double, bearing: Double?) {
        val builder = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom)
        if (bearing != null) builder.bearing(bearing)
        m.cameraPosition = builder.build()
    }

    private fun move(update: CameraUpdate, animate: Boolean) {
        if (animate) m.animateCamera(update) else m.moveCamera(update)
    }

    override fun moveTo(lat: Double, lon: Double, zoom: Double, animate: Boolean) =
        move(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), animate)

    override fun zoomIn(animate: Boolean) = move(CameraUpdateFactory.zoomIn(), animate)

    override fun zoomOut(animate: Boolean) = move(CameraUpdateFactory.zoomOut(), animate)

    override fun frame(points: List<Pair<Double, Double>>, paddingPx: Int, maxZoom: Double) {
        runCatching {
            val bounds = LatLngBounds.Builder().apply { points.forEach { (lat, lon) -> include(LatLng(lat, lon)) } }
                .build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
            if (m.cameraPosition.zoom > maxZoom) m.moveCamera(CameraUpdateFactory.zoomTo(maxZoom))
        }
    }

    override fun camera(): CameraSpot? = m.spot()

    override fun visibleBounds(): GeoBounds? {
        if (m.width <= 0f || m.height <= 0f) return null
        val b = m.projection.visibleRegion.latLngBounds
        return GeoBounds(south = b.latitudeSouth, west = b.longitudeWest, north = b.latitudeNorth, east = b.longitudeEast)
    }

    override fun reloadStyle() = reload()
}

private fun addHouseLayers(style: Style, labelSizeSp: Float) {
    // The path trace first, so it is drawn under the houses (the same values as trackLayerJson, iOS's copy).
    style.addSource(GeoJsonSource(TRACK_SOURCE, trackGeoJson(emptyList())))
    style.addLayer(
        LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(TRACK_COLOR),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    *TRACK_WIDTHS.map { (zoom, width) -> Expression.stop(zoom, width.toFloat()) }.toTypedArray(),
                ),
            ),
        ),
    )
    style.addSource(GeoJsonSource(HOUSES_SOURCE, housesGeoJson(emptyList())))
    val statusColor = Expression.match(
        Expression.get("status"),
        Expression.literal("SHORTLISTED"), Expression.color(MarkerColors.SHORTLISTED),
        Expression.literal("REJECTED"), Expression.color(MarkerColors.REJECTED),
        Expression.color(MarkerColors.NEW),
    )
    // Status is told by size, ring and opacity as well as colour (round 5; docs/05 UX-002, A11Y-003, WCAG 1.4.1): the
    // shortlisted and rejected colours are about 1.3:1 apart in luminance, the same to deuteranopes and protanopes.
    // The web map's encoding (map-page.ts), from MapRules: shortlisted largest with a 3 dp ring, rejected smallest
    // at 75 % opacity, growing with the zoom. The 24 dp hit radius covers the largest dot (15 dp and its ring).
    fun byStatus(shortlisted: Float, rejected: Float, new: Float): Expression = Expression.match(
        Expression.get("status"),
        Expression.literal(new),
        Expression.stop("SHORTLISTED", shortlisted),
        Expression.stop("REJECTED", rejected),
    )
    val radius = Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        *MARKER_RADII.map { r -> Expression.stop(r.zoom, byStatus(r.shortlisted, r.rejected, r.new)) }.toTypedArray(),
    )
    style.addLayer(
        CircleLayer(HOUSE_DOTS_LAYER, HOUSES_SOURCE).withProperties(
            PropertyFactory.circleRadius(radius),
            PropertyFactory.circleColor(statusColor),
            PropertyFactory.circleStrokeWidth(
                Expression.match(
                    Expression.get("status"),
                    Expression.literal(MARKER_STROKE_DP),
                    Expression.stop("SHORTLISTED", MARKER_STROKE_SHORTLISTED_DP),
                ),
            ),
            PropertyFactory.circleOpacity(
                Expression.match(
                    Expression.get("status"),
                    Expression.literal(1f),
                    Expression.stop("REJECTED", MARKER_OPACITY_REJECTED),
                ),
            ),
            PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
        )
    )
    // House names as labels. MapLibre's symbol layer may not shape Devanagari, Tamil or Telugu conjuncts: README
    // section 8, device check 21 (d) (a release gate) checks a Tamil-named house; if it renders broken, set
    // MAP_LABELS_SHOW_INDIC to false and this filter leaves those names out rather than draw broken text. The size
    // follows the font scale up to 1.5× (markerLabelSizeSp, set again on resume by MapScreen; round 6, WCAG 1.4.4),
    // and a long name wraps after 8 ems.
    style.addLayer(
        SymbolLayer(HOUSE_LABELS_LAYER, HOUSES_SOURCE).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(labelSizeSp),
            PropertyFactory.textMaxWidth(MARKER_LABEL_MAX_WIDTH_EM),
            // Below the largest dot (15 dp and a 3 dp ring at zoom 18), so a name never sits on its marker.
            PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textHaloColor(0xFFFFFFFF.toInt()),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textOptional(true),
        ).withFilter(
            Expression.any(
                Expression.literal(MAP_LABELS_SHOW_INDIC),
                Expression.not(Expression.toBool(Expression.get("indic"))),
            ),
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
