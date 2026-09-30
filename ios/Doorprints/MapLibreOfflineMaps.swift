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

// Offline maps (S4b-FR-6, docs/11 5.20): MapLibre iOS's offline packs (MLNOfflineStorage) behind the Kotlin interface
// IosOfflineMaps of IosMap.kt, as MapLibreMapView.swift is for the map. Each pack's context holds the area's id and
// name as JSON; every change (the packs loading, progress, an error, a removal) goes to Kotlin as one whole snapshot.
// The store is the same one the map's own cache uses, so the offline tiles serve the map's style. Main thread only.
import DoorprintsKit
import Foundation
import MapLibre
import Network

final class MapLibreOfflineMaps: NSObject, IosOfflineMaps {
    private var listener: IosOfflineMapsListener?
    private var packsObservation: NSKeyValueObservation?
    /// Packs that reported an error (MapLibre retries a tile; the pack stays incomplete when it keeps failing).
    private var failed = Set<String>()
    private let monitor = NWPathMonitor()
    private var expensive = false

    override init() {
        super.init()
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async { self?.expensive = path.isExpensive }
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(packChanged(_:)), name: .MLNOfflinePackProgressChanged, object: nil)
        center.addObserver(self, selector: #selector(packFailed(_:)), name: .MLNOfflinePackError, object: nil)
        center.addObserver(self, selector: #selector(packFailed(_:)), name: .MLNOfflinePackMaximumMapboxTilesReached, object: nil)
    }

    // MARK: IosOfflineMaps

    func setListener(listener: IosOfflineMapsListener) {
        self.listener = listener
        // `packs` is nil until the store has read them (KVO says when); each pack's progress is asked for once.
        packsObservation = MLNOfflineStorage.shared.observe(\.packs, options: [.initial, .new]) { [weak self] _, _ in
            DispatchQueue.main.async {
                MLNOfflineStorage.shared.packs?.forEach { $0.requestProgress() }
                self?.publish()
            }
        }
    }

    func add(id: String, name: String, south: Double, west: Double, north: Double, east: Double, minZoom: Double, maxZoom: Double) {
        let bounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: south, longitude: west),
            ne: CLLocationCoordinate2D(latitude: north, longitude: east)
        )
        let region = MLNTilePyramidOfflineRegion(
            styleURL: URL(string: MapStyleJsonKt.MAP_STYLE_URL), bounds: bounds, fromZoomLevel: minZoom, toZoomLevel: maxZoom
        )
        let context = (try? JSONSerialization.data(withJSONObject: ["id": id, "name": name])) ?? Data()
        MLNOfflineStorage.shared.addPack(for: region, withContext: context) { [weak self] pack, _ in
            pack?.resume()
            self?.publish()
        }
    }

    func remove(id: String) {
        guard let pack = MLNOfflineStorage.shared.packs?.first(where: { Self.meta($0)?.id == id }) else { return }
        failed.remove(id)
        MLNOfflineStorage.shared.removePack(pack) { [weak self] _ in self?.publish() }
        publish()
    }

    func networkMetered() -> Bool { expensive }

    // MARK: Notifications

    @objc private func packChanged(_ note: Notification) {
        if let pack = note.object as? MLNOfflinePack, pack.state == .complete { pack.suspend() }
        publish()
    }

    @objc private func packFailed(_ note: Notification) {
        if let pack = note.object as? MLNOfflinePack, let meta = Self.meta(pack) { failed.insert(meta.id) }
        publish()
    }

    /// Every pack as Kotlin's IosOfflinePack, newest first (the store lists them oldest first).
    private func publish() {
        let packs = (MLNOfflineStorage.shared.packs ?? []).reversed().compactMap { pack -> IosOfflinePack? in
            guard let meta = Self.meta(pack), let region = pack.region as? MLNTilePyramidOfflineRegion else { return nil }
            let state: Int32
            switch pack.state {
            case .complete: state = 1
            case .invalid: state = 2
            default: state = failed.contains(meta.id) && pack.state != .active ? 2 : 0
            }
            return IosOfflinePack(
                id: meta.id, name: meta.name,
                south: region.bounds.sw.latitude, west: region.bounds.sw.longitude,
                north: region.bounds.ne.latitude, east: region.bounds.ne.longitude,
                state: state, bytes: Int64(pack.progress.countOfBytesCompleted)
            )
        }
        listener?.onPacks(packs: packs)
    }

    private static func meta(_ pack: MLNOfflinePack) -> (id: String, name: String)? {
        guard let json = try? JSONSerialization.jsonObject(with: pack.context) as? [String: String],
              let id = json["id"], let name = json["name"] else { return nil }
        return (id, name)
    }
}
