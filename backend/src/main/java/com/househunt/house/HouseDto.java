package com.househunt.house;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record HouseDto(
        UUID id,
        @NotBlank @Size(max = 200) String label,
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
