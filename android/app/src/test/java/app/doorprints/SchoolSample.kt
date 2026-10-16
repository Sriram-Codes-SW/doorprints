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

import app.doorprints.data.HouseEntity
import app.doorprints.data.toEntity
import app.doorprints.shared.api.HouseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The saved school of `docs/schemas/kinds/school-vectors.json` (`candidate`, in Guwahati) as the phone stores it, for
 * the school form's tests and screenshot (kinds PR 5, S4b-BL-209). Read through the sync API's mapping, so the row is
 * the one a phone would hold after pulling it; the contact phone is left out, so saving it makes no broker (S4b-BL-260).
 */
object SchoolSample {
    const val ID = "55555555-5555-4555-8555-000000000209"

    fun entity(): HouseEntity {
        val wire = Json.parseToJsonElement(File("../../docs/schemas/kinds/school-vectors.json").readText()).jsonObject.getValue("candidate")
        val dto = Json { ignoreUnknownKeys = true; explicitNulls = false }.decodeFromJsonElement(HouseDto.serializer(), wire)
        return dto.toEntity().copy(contactPhone = null, dirty = false)
    }
}
