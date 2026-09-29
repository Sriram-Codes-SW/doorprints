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

// The iOS map view (CMP-8c): MapLibre iOS's MLNMapView behind the Kotlin interfaces of IosMap.kt (android/ui's
// iosMain), which the common map (PlatformMap.ios.kt) drives. Kotlin hands over the whole style as JSON, with India's
// boundary as the Government of India shows it already applied (prepareMapStyle, ADR-22), and the houses as GeoJSON;
// this file never builds or changes a filter. Main thread only, as MapLibre and Compose both require.
import CoreLocation
import DoorprintsKit
import MapLibre
import UIKit

/// Made once at start-up and registered with Kotlin (DoorprintsApp.init).
final class MapLibreMapViewFactory: NSObject, IosMapViewFactory {
    func create(listener: IosMapListener) -> IosMapView {
        MapLibreMapView(listener: listener)
    }
}

/// One map: the view, its camera, the houses' source and the taps, long presses and camera moves it reports.
final class MapLibreMapView: NSObject, IosMapView, MLNMapViewDelegate, UIGestureRecognizerDelegate {
    /// Half of a 48 pt touch target: a tap anywhere within it hits the nearest marker (as MARKER_HIT_RADIUS_DP).
    private static let hitRadius: CGFloat = 24
    private static let housesSource = "houses"
    private static let houseLayers: Set<String> = ["houses-dots", "houses-labels"]
    /// What the view shows until Kotlin hands over the prepared style: nothing. `MLNMapView(frame:)` alone would load
    /// MapLibre's demo style, whose borders ignore India's boundary rules (ADR-22) until Liberty replaces it.
    private static let emptyStyle = #"{"version":8,"sources":{},"layers":[]}"#

    private let listener: IosMapListener
    private let mapView: MLNMapView

    var view: UIView { mapView }

    init(listener: IosMapListener) {
        self.listener = listener
        mapView = MLNMapView(frame: .zero, styleJSON: Self.emptyStyle)
        super.init()
        mapView.delegate = self
        mapView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // North-up (MAP_NORTH_UP, WCAG 2.5.1): no rotation or tilt, so no compass is needed to undo one; no logo, as
        // on Android. The attribution button stays (setAttribution places it).
        mapView.isRotateEnabled = false
        mapView.isPitchEnabled = false
        mapView.compassView.compassVisibility = .hidden
        mapView.logoView.isHidden = true
        mapView.attributionButtonPosition = .bottomLeft

        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.delegate = self
        // A double tap zooms (MapLibre's own recognizer); a single tap waits until it is not the first half of one.
        for recognizer in mapView.gestureRecognizers ?? [] {
            if let other = recognizer as? UITapGestureRecognizer, other.numberOfTapsRequired == 2 {
                tap.require(toFail: other)
            }
        }
        mapView.addGestureRecognizer(tap)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.delegate = self
        mapView.addGestureRecognizer(longPress)
    }

    // MARK: IosMapView

    func loadStyle(json: String) {
        mapView.styleJSON = json
    }

    func setHouses(geoJson: String) {
        guard let source = mapView.style?.source(withIdentifier: Self.housesSource) as? MLNShapeSource else { return }
        source.shape = try? MLNShape(data: Data(geoJson.utf8), encoding: String.Encoding.utf8.rawValue)
    }

    func setLabelSize(size: Double) {
        guard let labels = mapView.style?.layer(withIdentifier: "houses-labels") as? MLNSymbolStyleLayer else { return }
        labels.textFontSize = NSExpression(forConstantValue: size)
    }

    func setShowsUserLocation(show: Bool) {
        mapView.showsUserLocation = show
    }

    func setAttribution(startPt: Double, bottomPt: Double, shown: Bool) {
        mapView.attributionButtonMargins = CGPoint(x: startPt, y: bottomPt)
        mapView.attributionButton.isHidden = !shown
    }

    func setCamera(lat: Double, lon: Double, zoom: Double, bearing: Double, setBearing: Bool) {
        mapView.setCenter(
            CLLocationCoordinate2D(latitude: lat, longitude: lon),
            zoomLevel: zoom,
            // A negative direction keeps the map's own (MLNMapView.h).
            direction: setBearing ? bearing : -1,
            animated: false
        )
    }

    func moveTo(lat: Double, lon: Double, zoom: Double, animated: Bool) {
        mapView.setCenter(CLLocationCoordinate2D(latitude: lat, longitude: lon), zoomLevel: zoom, animated: animated)
    }

    func zoomBy(delta: Double, animated: Bool) {
        mapView.setZoomLevel(mapView.zoomLevel + delta, animated: animated)
    }

    func frame(south: Double, west: Double, north: Double, east: Double, paddingPt: Double, maxZoom: Double) {
        // A view with no size yet cannot fit a box; the chrome frames again once the map has settled.
        guard mapView.bounds.width > 2 * paddingPt, mapView.bounds.height > 2 * paddingPt else { return }
        let bounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: south, longitude: west),
            ne: CLLocationCoordinate2D(latitude: north, longitude: east)
        )
        let padding = CGFloat(paddingPt)
        let camera = mapView.cameraThatFitsCoordinateBounds(
            bounds, edgePadding: UIEdgeInsets(top: padding, left: padding, bottom: padding, right: padding)
        )
        mapView.setCamera(camera, animated: false)
        if mapView.zoomLevel > maxZoom {
            mapView.setZoomLevel(maxZoom, animated: false)
        }
    }

    func shutDown() {
        mapView.showsUserLocation = false
        mapView.delegate = nil
    }

    func centerLatitude() -> Double { mapView.centerCoordinate.latitude }

    func centerLongitude() -> Double { mapView.centerCoordinate.longitude }

    func zoomLevel() -> Double { mapView.zoomLevel }

    func bearing() -> Double { mapView.direction }

    // MARK: MLNMapViewDelegate

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        let layers = style.layers
        listener.onStyleLoaded(
            layerIds: layers.map { $0.identifier },
            hiddenIds: layers.filter { !$0.isVisible }.map { $0.identifier }
        )
    }

    func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
        listener.onFailed(message: error.localizedDescription)
    }

    func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        let gestures: MLNCameraChangeReason = [
            .gesturePan, .gesturePinch, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom,
        ]
        if !reason.intersection(gestures).isEmpty {
            listener.onUserGesture()
        }
    }

    func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
        let center = mapView.centerCoordinate
        listener.onCameraIdle(lat: center.latitude, lon: center.longitude, zoom: mapView.zoomLevel, bearing: mapView.direction)
    }

    // MARK: Taps and long presses

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
        guard recognizer.state == .ended else { return }
        let point = recognizer.location(in: mapView)
        let r = Self.hitRadius
        let area = CGRect(x: point.x - r, y: point.y - r, width: 2 * r, height: 2 * r)
        let features = mapView.visibleFeatures(in: area, styleLayerIdentifiers: Self.houseLayers)
        // The marker nearest the finger, when several are inside the 48 pt square.
        let nearest = features.min { a, b in
            distance(from: point, to: a.coordinate) < distance(from: point, to: b.coordinate)
        }
        if let id = nearest?.attribute(forKey: "id") as? String {
            listener.onHouseTap(id: id)
        }
    }

    @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began else { return }
        let coordinate = mapView.convert(recognizer.location(in: mapView), toCoordinateFrom: mapView)
        listener.onLongPress(lat: coordinate.latitude, lon: coordinate.longitude)
    }

    private func distance(from point: CGPoint, to coordinate: CLLocationCoordinate2D) -> CGFloat {
        let at = mapView.convert(coordinate, toPointTo: mapView)
        return hypot(at.x - point.x, at.y - point.y)
    }

    // Our tap and long press run beside MapLibre's own recognizers, never instead of them.
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }
}
