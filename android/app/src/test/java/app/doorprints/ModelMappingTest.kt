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

import app.doorprints.data.labelRes
import app.doorprints.shared.model.HouseStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Android glue around :shared: translated labels for the shared enums. The entity <-> DTO mappers and the rules
 * are tested in :shared's commonTest (`MappersTest`, since the readiness review of 2026-09-29).
 */
class ModelMappingTest {

    @Test
    fun everyStatusHasATranslatedLabel() {
        // The checklist labels are Compose resources only since CMP-6 P6a (ModelLabelsTest in :ui).
        assertEquals(HouseStatus.entries.size, HouseStatus.entries.map { it.labelRes }.toSet().size)
        assertEquals(R.string.status_NEW, HouseStatus.NEW.labelRes)
    }
}
