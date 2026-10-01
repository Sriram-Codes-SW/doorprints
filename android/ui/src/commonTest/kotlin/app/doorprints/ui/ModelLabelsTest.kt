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

import app.doorprints.shared.model.Checklist
import app.doorprints.shared.model.HouseStatus
import app.doorprints.ui.res.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Compose resource labels Compare reads (CMP-4 P4c): one for every shared checklist key, in the shared order and
 * all different, and one for every status, with its glyph. `:app`'s `ModelMappingTest` checks the Android copies.
 */
class ModelLabelsTest {
    @Test
    fun everyChecklistKeyHasItsOwnLabelInTheSharedOrder() {
        assertEquals(Checklist.keys, ChecklistResources.items.keys.toList())
        assertEquals(Checklist.keys.size, ChecklistResources.items.values.toSet().size)
        assertEquals(Res.string.check_water, ChecklistResources.items["water"])
    }

    @Test
    fun everyStatusHasItsLabelAndGlyph() {
        assertEquals(
            listOf(
                Res.string.status_NEW, Res.string.status_SHORTLISTED, Res.string.status_REJECTED, Res.string.status_TAKEN,
                Res.string.status_NOT_CHOSEN,
            ),
            HouseStatus.entries.map { it.labelResource },
        )
        assertEquals(listOf("●", "★", "✕", "✓", "○"), HouseStatus.entries.map { it.glyph })
    }

    /** Slice 5: every fixed photo tag has its translated name, in [app.doorprints.shared.model.PhotoTags.FIXED]'s order. */
    @Test
    fun everyFixedPhotoTagHasItsLabel() {
        assertEquals(app.doorprints.shared.model.PhotoTags.FIXED, PHOTO_TAG_LABELS.keys.toList())
        assertEquals(Res.string.tag_MOVE_IN, PHOTO_TAG_LABELS["MOVE_IN"])
    }
}
