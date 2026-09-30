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

import app.doorprints.server.record.RecordDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One house over the sync API ({@code GET /api/houses}, {@code PUT /api/houses/{id}}).
 *
 * <h2>Null semantics (NFR-025)</h2>
 * <ul>
 *   <li><b>On the wire the server writes every field, {@code null} included.</b> A {@code null} means "this house
 *       has no value for that field" — not "unknown" and not "unchanged". Clients can therefore tell an empty
 *       {@code notes} from a missing one without guessing, and a tombstone (see below) is recognisable by its
 *       blanked fields. This is the opposite choice from the backup format, which leaves empty fields out
 *       (docs/schemas/README.md section 4); both are explicit, so a round trip through either is lossless.</li>
 *   <li><b>On input, absent and {@code null} are the same thing, and a {@code PUT} is a full replacement.</b> The
 *       body describes the house as the client knows it; every field in {@link #applyTo} is written, so a field the
 *       client leaves out is cleared. A client that only wants to change one field must send the whole house
 *       (which is what the Android and web apps do — they hold the row locally). Partial updates ("null = leave
 *       as it is") arrive with HouseDto v2 in Sprint 4b, for collections only, and will be marked as such.</li>
 *   <li><b>Defaults for a missing value:</b> {@code status} → {@code NEW}; {@code checklist} → cleared, because
 *       absent is a value here too ({@link House#setChecklist} replaces the whole map); {@code createdAt} and
 *       {@code updatedAt} → server time ({@link app.doorprints.server.sync.ClientClock}); {@code lat}/{@code lon} → 0,
 *       which is also what "no location yet" looks like. {@code deleted} defaults to {@code false} and
 *       {@code syncVersion} to 0 because Jackson is configured not to fail on missing primitives.</li>
 *   <li><b>Server-managed fields.</b> {@code syncVersion} is assigned by the server and ignored on input;
 *       {@code distanceMeters} is only written, and only by {@code GET /api/houses/nearby} (it is {@code null}
 *       everywhere else, including in exports). {@code deleted} <em>is</em> read on input: {@code true} deletes
 *       the house, exactly like {@code DELETE /api/houses/{id}}.</li>
 *   <li><b>{@code label} is required but may be empty.</b> {@code ""} is a real value (an unnamed house, and
 *       what a tombstone keeps); only a missing {@code label} is rejected. The backup format allows the same, so a
 *       restored backup can be synced.</li>
 *   <li><b>A tombstone</b> (a row with {@code deleted: true}) carries {@code label: ""} and {@code null} in every
 *       other content field: the content is purged, not hidden (threat model F-16). Tombstones are never
 *       exported.</li>
 *   <li><b>The slice 1a values</b> ({@code areaSqft}, {@code locationSource}, {@code cost}; docs/11 section 5.30
 *       item 1) are optional like the rest. {@code locationSource} is {@code GPS}, {@code MAP} or {@code APPROX}
 *       (FR-068), absent for a house saved before it existed. {@code cost} is a {@link HouseCost} object whose own
 *       absent fields are left out; it is {@code null} when no field is set, and an empty object on input is the
 *       same as none. A value out of range is a 400, like the other fields.</li>
 *   <li><b>{@code rooms}</b> (slice 1c, docs/11 section 5.6) is a list of at most 30 {@link HouseRoom}s with
 *       distinct ids, {@code null} when the house has none (an empty list on input is the same as none). A room out
 *       of range, a 31st room or a repeated id is a 400. A tombstone carries {@code null}.</li>
 *   <li><b>{@code brokerId}</b> (slice 1b, docs/11 section 5.25) is the record id of the house's broker, absent for
 *       none. There is no foreign key: a broker deleted or not yet synced leaves the id dangling, which readers take
 *       as no broker. A tombstone carries {@code null}.</li>
 * </ul>
 */
public record HouseDto(
        UUID id,
        /* Required but may be empty: a house saved from a map tap before it is named, and every tombstone,
           has label "". @NotBlank would make such a row impossible to sync or to restore from a backup, which the
           shared backup format explicitly allows (docs/schemas/README.md section 3.1). */
        @NotNull @Size(max = 200) String label,
        @Size(max = 500) String address,
        @Size(max = 200) String street,
        @Size(max = 200) String locality,
        @DecimalMin("-90") @DecimalMax("90") double lat,
        @DecimalMin("-180") @DecimalMax("180") double lon,
        HouseStatus status,
        @PositiveOrZero Long price,
        @Pattern(regexp = "RENT|SALE") String priceType,
        @PositiveOrZero Integer bedrooms,
        @Min(1) @Max(5) Integer rating,
        @Size(max = 200) String contactName,
        @Size(max = 50) String contactPhone,
        @Size(max = 1000) String listingUrl,
        @Size(max = 20000) String notes,
        @Min(1) @Max(100_000) Integer areaSqft,
        @Pattern(regexp = LOCATION_SOURCES) String locationSource,
        @Valid HouseCost cost,
        /* Slice 1c: at most 30 rooms with distinct ids; an empty list is the same as none. */
        @Valid @Size(max = HouseRoom.MAX) List<@NotNull @Valid HouseRoom> rooms,
        /* Slice 3a: at most 60 viewing answers with distinct ids; an empty list is the same as none. */
        @Valid @Size(max = HouseAnswer.MAX) List<@NotNull @Valid HouseAnswer> answers,
        /* Slice 1b: a broker's record id. Not checked against the records: a dangling id reads as no broker. */
        @Pattern(regexp = RecordDto.ID_PATTERN) String brokerId,
        Map<@Size(max = 100) String, @Min(0) @Max(5) Integer> checklist,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted,
        long syncVersion,
        Double distanceMeters
) {
    /** GPS = <i>Use my location</i>; MAP = a tap or the crosshair; APPROX = the person says the spot is approximate. */
    public static final String LOCATION_SOURCES = "GPS|MAP|APPROX";

    public static HouseDto from(House h) {
        return from(h, null);
    }

    public static HouseDto from(House h, Double distanceMeters) {
        return new HouseDto(h.getId(), h.getLabel(), h.getAddress(), h.getStreet(), h.getLocality(),
                h.getLat(), h.getLon(), h.getStatus(), h.getPrice(), h.getPriceType(), h.getBedrooms(),
                h.getRating(), h.getContactName(), h.getContactPhone(), h.getListingUrl(), h.getNotes(),
                h.getAreaSqft(), h.getLocationSource(), HouseCost.parse(h.getCost()), HouseRoom.parse(h.getRooms()),
                HouseAnswer.parse(h.getAnswers()), h.getBrokerId(),
                Map.copyOf(h.getChecklist()), h.getCreatedAt(), h.getUpdatedAt(), h.isDeleted(),
                h.getSyncVersion(), distanceMeters);
    }

    /** Two rooms with one id cannot be told apart by the clients; a getter constraint so Bean Validation runs it. */
    @JsonIgnore
    @AssertTrue(message = "rooms must not repeat an id")
    public boolean isRoomIdsUnique() {
        return HouseRoom.idsAreUnique(rooms);
    }

    /** Two answers with one id cannot be told apart by the clients; a getter constraint so Bean Validation runs it. */
    @JsonIgnore
    @AssertTrue(message = "answers must not repeat an id")
    public boolean isAnswerIdsUnique() {
        return HouseAnswer.idsAreUnique(answers);
    }

    void applyTo(House h) {
        h.setLabel(label);
        h.setAddress(address);
        h.setStreet(street);
        h.setLocality(locality);
        h.setLat(lat);
        h.setLon(lon);
        h.setStatus(status == null ? HouseStatus.NEW : status);
        h.setPrice(price);
        h.setPriceType(priceType);
        h.setBedrooms(bedrooms);
        h.setRating(rating);
        h.setContactName(contactName);
        h.setContactPhone(contactPhone);
        h.setListingUrl(listingUrl);
        h.setNotes(notes);
        h.setAreaSqft(areaSqft);
        h.setLocationSource(locationSource);
        h.setCost(HouseCost.write(cost));
        h.setRooms(HouseRoom.write(rooms));
        h.setAnswers(HouseAnswer.write(answers));
        h.setBrokerId(brokerId);
        h.setChecklist(checklist);
        h.setDeleted(deleted);
    }
}
