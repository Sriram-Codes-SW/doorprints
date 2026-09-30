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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSUUID

/**
 * [OfflineMapsServices] on iPhone (S4b-FR-6): MapLibre iOS's offline packs through the Swift shell's [IosOfflineMaps]
 * (`MLNOfflineStorage`, the same tile store the map's own cache uses, so the offline tiles serve the map's inline
 * style, whose tile addresses are the Liberty style's). The packs' style is [MAP_STYLE_URL], zoom 0 to
 * [OfflineTiles.MAX_ZOOM]. Not supported (no button, no section) until the Swift app registers its store. Main thread.
 */
internal object IosOfflineMapsServices : OfflineMapsServices, IosOfflineMapsListener {
    private val store: IosOfflineMaps? get() = IosMap.offline
    private var listening = false
    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())

    override val supported: Boolean get() = store != null

    override val areas: StateFlow<List<OfflineArea>>
        get() {
            listen()
            return _areas
        }

    private fun listen() {
        if (listening) return
        val s = store ?: return
        listening = true
        s.setListener(this)
    }

    override fun onPacks(packs: List<IosOfflinePack>) {
        _areas.value = packs.map { p ->
            OfflineArea(
                id = p.id, name = p.name, bounds = GeoBounds(p.south, p.west, p.north, p.east),
                state = when (p.state) {
                    1 -> OfflineAreaState.READY
                    2 -> OfflineAreaState.FAILED
                    else -> OfflineAreaState.SAVING
                },
                bytes = p.bytes,
            )
        }
    }

    override fun save(name: String, bounds: GeoBounds) {
        listen()
        store?.add(
            NSUUID().UUIDString.lowercase(), name, bounds.south, bounds.west, bounds.north, bounds.east,
            0.0, OfflineTiles.MAX_ZOOM.toDouble(),
        )
    }

    override fun delete(id: String) {
        store?.remove(id)
    }

    override fun networkMetered(): Boolean? = store?.networkMetered()
}
