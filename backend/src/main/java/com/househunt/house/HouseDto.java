package com.househunt.house;

import jakarta.validation.constraints.*;

import java.time.Instant;
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
 *       {@code updatedAt} → server time ({@link com.househunt.sync.ClientClock}); {@code lat}/{@code lon} → 0,
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
        Map<@Size(max = 100) String, @Min(0) @Max(5) Integer> checklist,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted,
        long syncVersion,
        Double distanceMeters
) {
    public static HouseDto from(House h) {
        return from(h, null);
    }

    public static HouseDto from(House h, Double distanceMeters) {
        return new HouseDto(h.getId(), h.getLabel(), h.getAddress(), h.getStreet(), h.getLocality(),
                h.getLat(), h.getLon(), h.getStatus(), h.getPrice(), h.getPriceType(), h.getBedrooms(),
                h.getRating(), h.getContactName(), h.getContactPhone(), h.getListingUrl(), h.getNotes(),
                Map.copyOf(h.getChecklist()), h.getCreatedAt(), h.getUpdatedAt(), h.isDeleted(),
                h.getSyncVersion(), distanceMeters);
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
        h.setChecklist(checklist);
        h.setDeleted(deleted);
    }
}
