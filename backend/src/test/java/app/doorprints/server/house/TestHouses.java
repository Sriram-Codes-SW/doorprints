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

package app.doorprints.server.house;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A house candidate for tests, written in the house's own words: the price and its type, the BHK, the area, the cost
 * and the floor, as the tests wrote them before the format reset of S4b-BL-206. They become the candidate's
 * {@link Money} ({@link Money#of}) and its {@link CandidateExtras} ({@link CandidateExtras#of}), the two offers at the
 * money's cadence, exactly as the backup import maps the same values; the kind is {@code house}.
 */
public final class TestHouses {

    private TestHouses() {
    }

    /** The house's fields, in the order the tests have always listed them. */
    public static HouseDto dto(UUID id, String label, String address, String street, String locality, double lat,
                               double lon, HouseStatus status, Long price, String priceType, Integer bedrooms,
                               Integer rating, String contactName, String contactPhone, String listingUrl,
                               String notes, Integer areaSqft, String locationSource, HouseCost cost,
                               List<HouseRoom> rooms, List<HouseAnswer> answers, HouseMoveIn moveIn, Integer floor,
                               String brokerId, Map<String, Integer> checklist, Instant createdAt, Instant updatedAt,
                               boolean deleted, long syncVersion, Double distanceMeters) {
        var money = Money.of(price, priceType);
        var extras = CandidateExtras.orNull(CandidateExtras.of(bedrooms, areaSqft, floor, cost,
                money == null ? null : money.cadence()));
        return new HouseDto(id, House.HOUSE_KIND, label, address, street, locality, lat, lon, status, money, rating,
                contactName, contactPhone, listingUrl, notes, locationSource, extras, rooms, answers, moveIn, brokerId,
                checklist, createdAt, updatedAt, deleted, syncVersion, distanceMeters);
    }
}
