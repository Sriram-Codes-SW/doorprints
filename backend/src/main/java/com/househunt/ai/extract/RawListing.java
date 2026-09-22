package com.househunt.ai.extract;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the model is asked to fill (structured output target). Loosely typed on purpose: price and bedrooms come
 * back as the model read them ("25k", "2 BHK") and {@link DraftSanitizer} does the strict parsing server side.
 */
public record RawListing(
        @JsonPropertyDescription("Short human label, e.g. '2BHK near Indiranagar metro'") String label,
        @JsonPropertyDescription("Full postal address as written in the listing") String address,
        @JsonPropertyDescription("Street / road name only") String street,
        @JsonPropertyDescription("Locality / neighbourhood / area") String locality,
        @JsonPropertyDescription("Monthly rent or sale price in rupees exactly as written, e.g. '25,000' or '1.2 Cr'") String price,
        @JsonPropertyDescription("RENT or SALE") String priceType,
        @JsonPropertyDescription("Number of bedrooms, e.g. '2' for 2BHK") String bedrooms,
        @JsonPropertyDescription("Contact person name") String contactName,
        @JsonPropertyDescription("Contact phone number exactly as written") String contactPhone,
        @JsonPropertyDescription("Listing URL if one is present in the text") String listingUrl,
        @JsonPropertyDescription("Other useful facts (deposit, floor, furnishing, availability) in one short paragraph") String notes,
        @JsonPropertyDescription("Amenities such as parking, lift, power backup, gym") List<String> amenities) {
}
