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

import android.content.Context
import android.content.DialogInterface
import org.maplibre.android.maps.AttributionDialogManager
import org.maplibre.android.maps.MapLibreMap

/**
 * The map's attribution dialog (its "i" button) with the Survey of India's credit as the last line (S4b-BL-114,
 * docs/03 ADR-22): MapLibre Android lists the attributions of the style's sources, and a GeoJsonSource added at run
 * time cannot carry one, so the line is added here. [credit] is `map_boundary_credit` in the app's language; [credit]
 * is read each time the dialog opens, so a language switch shows at once. The line opens nothing; the others open
 * their page as before (MapLibre Android 13.6.1: `showAttributionDialog` is protected, `onClick` picks the line by
 * its index among the sources' attributions).
 */
class SoiAttributionDialogManager(
    context: Context,
    map: MapLibreMap,
    private val credit: () -> String,
) : AttributionDialogManager(context, map) {
    private var sourceLines = 0

    override fun showAttributionDialog(attributionTitles: Array<String>) {
        sourceLines = attributionTitles.size
        super.showAttributionDialog(attributionTitles + credit())
    }

    override fun onClick(dialog: DialogInterface, which: Int) {
        if (which < sourceLines) super.onClick(dialog, which)
    }
}
