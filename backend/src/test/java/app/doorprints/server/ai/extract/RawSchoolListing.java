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

/**
 * TEST ORACLE (kinds PR 28): the record that was the school's structured-output target before the server read the kind files. The server
 * no longer has it; the tests keep it to prove that {@link KindOutputConverter} writes byte for byte its JSON schema and that
 * the sanitizer reads the same fields. What the model is asked to fill for a school listing (kinds PR 7, S4b-BL-211): the fields of the schools kind's
 * {@code ai.extract} slot, all text, as {@link RawListing} is for a house; {@link KindDraftSanitizer} does the typed
 * parsing. The descriptions are word for word those of {@code docs/schemas/kinds/kind-ai-vectors.json}
 * ({@code extract.schema}), which the website's and the phone's schemas hold too.
 */
public record RawSchoolListing(
        @JsonPropertyDescription("The school's name, e.g. 'Pine Hill School'") String label,
        @JsonPropertyDescription("Full postal address as written in the listing") String address,
        @JsonPropertyDescription("Street / road name only, e.g. 'MG Road'; null when no road is named") String street,
        @JsonPropertyDescription("Locality / neighbourhood / area inside the city, e.g. 'Indiranagar', 'Sector 56'; never the city or district alone. When the listing names only a road, repeat the road here") String locality,
        @JsonPropertyDescription("Contact person or office name") String contactName,
        @JsonPropertyDescription("Contact phone number exactly as written") String contactPhone,
        @JsonPropertyDescription("School website or listing URL if one is present in the text") String listingUrl,
        @JsonPropertyDescription("Other useful facts (timings, facilities, admission process) in one short paragraph") String notes,
        @JsonPropertyDescription("CBSE, ICSE, STATE, IB, IGCSE or OTHER") String board,
        @JsonPropertyDescription("ENGLISH, HINDI, REGIONAL or OTHER") String medium,
        @JsonPropertyDescription("Classes offered as written, e.g. 'Nursery to 12'") String grades,
        @JsonPropertyDescription("yes or no: does the school run a bus") String busService,
        @JsonPropertyDescription("Fee per year in rupees exactly as written, e.g. '96,000' or '1.2 lakh'") String fees,
        @JsonPropertyDescription("One-time admission fee in rupees exactly as written") String admissionFee) {
}
