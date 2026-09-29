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

package app.doorprints.data

import androidx.annotation.StringRes
import app.doorprints.R
import app.doorprints.shared.model.HouseStatus

/**
 * Translated label of a status (values/strings.xml status_*), as an Android resource for the Hunt notification, which
 * has no composition. The enum itself lives in :shared; the screens read the Compose resource version, and the status
 * glyph is in :ui (`ui/ModelLabels.kt`, CMP-4 P4c). The checklist labels are only Compose resources since CMP-6 P6a
 * (`ChecklistResources`).
 */
@get:StringRes
val HouseStatus.labelRes: Int
    get() = when (this) {
        HouseStatus.NEW -> R.string.status_NEW
        HouseStatus.SHORTLISTED -> R.string.status_SHORTLISTED
        HouseStatus.REJECTED -> R.string.status_REJECTED
    }
