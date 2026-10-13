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

import app.doorprints.data.HouseEntity
import app.doorprints.shared.model.HouseSearch

/**
 * What the house list's search reads of [this] house ([HouseSearch], the website's `searchText`): the contact's name
 * since the readiness review, the linked broker ([brokerText]) since slice 1b, the rooms' names and notes since slice
 * 1c, the questions asked and their answers since slice 3a, the floor in the app's [language] too since S4b-BL-104, and
 * the area notes that reach it ([noteTexts]). Since kinds step 4 (S4b-BL-206) the house is stored as a candidate; its
 * values are read back out of the candidate's extras, so the search sees the same values, and never an extra this app
 * does not know.
 */
internal fun HouseEntity.searchFields(brokerText: String?, language: String, noteTexts: List<String>): List<String> =
    HouseSearch.fields(
        label, address, street, locality, notes, contactName, brokerText = brokerText, rooms = rooms, answers = answers,
        noteTexts = noteTexts, moveIn = moveIn, floor = floor, language = language,
    )
