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

package app.doorprints

import android.content.Context
import app.doorprints.data.NetworkState
import app.doorprints.ui.GeoBounds
import app.doorprints.ui.MAP_STYLE_URL
import app.doorprints.ui.OfflineArea
import app.doorprints.ui.OfflineAreaState
import app.doorprints.ui.OfflineMapsServices
import app.doorprints.ui.OfflineTiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.UUID

/**
 * [OfflineMapsServices] on Android (S4b-FR-6, docs/11 5.20): MapLibre's `OfflineManager`, the store the map's own
 * cache already uses, so nothing new is added and the offline tiles serve the same style. Each area is one offline
 * region: the Liberty style ([MAP_STYLE_URL]) over the box from zoom 0 to [OfflineTiles.MAX_ZOOM], its name and id in
 * the region's metadata (JSON). A download still running when the process died is picked up at the next start
 * ([refresh]: an incomplete region is set active again). MapLibre calls back on the main thread; so does this class.
 */
class AndroidOfflineMaps(private val context: Context) : OfflineMapsServices {
    private val manager by lazy { OfflineManager.getInstance(context) }
    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    private val regions = LinkedHashMap<String, Tracked>()
    private var loaded = false

    /** One region and what is known of it; [status] as MapLibre last reported it. */
    private class Tracked(val region: OfflineRegion, val name: String, var status: OfflineRegionStatus?, var failed: Boolean = false)

    /** MapLibre's store needs `MapLibre.getInstance` first (DoorprintsApp); the screenshot app has none, so no store. */
    override val supported: Boolean get() = MapLibre.hasInstance()

    override val areas: StateFlow<List<OfflineArea>>
        get() {
            refresh()
            return _areas
        }

    /** Reads the regions once per process and resumes the incomplete ones. */
    private fun refresh() {
        if (loaded || !supported) return
        loaded = true
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().sortedByDescending { it.id }.forEach { region ->
                    val meta = Metadata.read(region.metadata) ?: return@forEach
                    val tracked = Tracked(region, meta.name, status = null)
                    regions[meta.id] = tracked
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            tracked.status = status
                            // The process died mid-download: carry on.
                            if (status != null && !status.isComplete) watch(meta.id, tracked)
                            publish()
                        }

                        override fun onError(error: String?) = publish()
                    })
                }
                publish()
            }

            override fun onError(error: String) = publish()
        })
    }

    override fun save(name: String, bounds: GeoBounds) {
        if (!supported) return
        refresh()
        val id = UUID.randomUUID().toString()
        val definition = OfflineTilePyramidRegionDefinition(
            MAP_STYLE_URL,
            LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
            0.0,
            OfflineTiles.MAX_ZOOM.toDouble(),
            context.resources.displayMetrics.density,
        )
        manager.createOfflineRegion(definition, Metadata(id, name).bytes(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                val tracked = Tracked(offlineRegion, name, status = null)
                regions[id] = tracked
                // Newest first: put it at the front by rebuilding the order.
                val rest = regions.filterKeys { it != id }
                regions.clear()
                regions[id] = tracked
                regions.putAll(rest)
                watch(id, tracked)
                publish()
            }

            override fun onError(error: String) = publish()
        })
    }

    /** Follows the download; a status arrives every few tiles. */
    private fun watch(id: String, tracked: Tracked) {
        tracked.region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                tracked.status = status
                if (status.isComplete) tracked.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                publish()
            }

            override fun onError(error: OfflineRegionError) {
                // A tile that failed once is retried by MapLibre; a region that keeps failing stays incomplete. The
                // state says "could not save" only after MapLibre gives up (no more progress); until then, saving.
                tracked.failed = true
                publish()
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                tracked.failed = true
                publish()
            }
        })
        tracked.region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        regions[id] = tracked
    }

    override fun delete(id: String) {
        val tracked = regions.remove(id) ?: return
        publish()
        tracked.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        tracked.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = Unit
            override fun onError(error: String) = Unit
        })
    }

    override fun networkMetered(): Boolean = NetworkState.current(context).metered

    private fun publish() {
        _areas.value = regions.map { (id, t) ->
            val definition = t.region.definition as? OfflineTilePyramidRegionDefinition
            val b = definition?.bounds
            val status = t.status
            OfflineArea(
                id = id,
                name = t.name,
                bounds = GeoBounds(b?.latitudeSouth ?: 0.0, b?.longitudeWest ?: 0.0, b?.latitudeNorth ?: 0.0, b?.longitudeEast ?: 0.0),
                state = when {
                    status?.isComplete == true -> OfflineAreaState.READY
                    t.failed && status?.downloadState != OfflineRegion.STATE_ACTIVE -> OfflineAreaState.FAILED
                    else -> OfflineAreaState.SAVING
                },
                bytes = status?.completedResourceSize ?: 0L,
            )
        }
    }

    /** The region's metadata: its id and name as a small JSON object. */
    private class Metadata(val id: String, val name: String) {
        fun bytes(): ByteArray = buildJsonObject { put("id", id); put("name", name) }.toString().encodeToByteArray()

        companion object {
            fun read(bytes: ByteArray?): Metadata? = runCatching {
                val json = Json.parseToJsonElement(bytes!!.decodeToString()).jsonObject
                Metadata(json.getValue("id").jsonPrimitive.content, json.getValue("name").jsonPrimitive.content)
            }.getOrNull()
        }
    }
}
