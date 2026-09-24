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
