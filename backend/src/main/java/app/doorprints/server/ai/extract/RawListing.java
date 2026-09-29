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
