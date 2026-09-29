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

package app.doorprints.server.ai.extract;

import java.util.List;

/**
 * A validated, clamped suggestion for a new house. Field names and limits match {@code HouseDto}, so clients can
 * copy them straight into their "new house" form (the user still picks the map location and saves).
 * {@code warnings} lists every field that was dropped or changed during validation.
 */
public record HouseDraft(
        String label,
        String address,
        String street,
        String locality,
        Long price,
        String priceType,
        Integer bedrooms,
        String contactName,
        String contactPhone,
        String listingUrl,
        String notes,
        List<String> amenities,
        List<String> warnings) {
}
