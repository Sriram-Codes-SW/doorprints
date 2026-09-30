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

package app.doorprints.server.ai.rag;

import app.doorprints.server.house.HouseCost;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseStatus;
import app.doorprints.server.visit.VisitDto;
import app.doorprints.server.visit.VisitSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HouseDocumentsTest {

    private final UUID id = UUID.randomUUID();
    private final HouseDto house = new HouseDto(id, "Blue gate", "12 MG Road", "MG Road", "Indiranagar", 12.97, 77.64,
            HouseStatus.SHORTLISTED, 28000L, "RENT", 2, 4, "Ramesh", "+91 98450 12345", null,
            "Great water pressure", null, null, null, null, Map.of("water", 5, "parking", 2), null, null, false, 7, null);

    @Test
    void oneLabelledDocumentPerHouseWithIdAsDocumentId() {
        var visit = new VisitDto(UUID.randomUUID(), id, 12.97, 77.64, "MG Road",
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T10:25:00Z"), VisitSource.AUTO,
                null, false, 1);
        var doc = HouseDocuments.toDocument(house, List.of(visit));
        assertThat(doc.getId()).isEqualTo(id.toString());
        assertThat(doc.getText())
                .contains("House: Blue gate")
                .contains("Price: Rs 28000 per month (rent)")
                .contains("Size: 2 BHK")
                .contains("Status: SHORTLISTED")
                .contains("Checklist: parking 2/5, water 5/5")
                .contains("Visits: 1 visit, last on 2026-09-01, 25 min in total")
                .contains("Notes: Great water pressure")
                .doesNotContain("98450") // phone numbers are not embedded
                .doesNotContain("Ramesh") // nor the contact name (F-30)
                .doesNotContain("Contact");
        assertThat(doc.getMetadata()).containsEntry("houseId", id.toString()).containsEntry("status", "SHORTLISTED")
                .containsEntry("price", 28000L).containsEntry("bedrooms", 2).containsEntry("rating", 4);
    }

    @Test
    void contactNameAndPhoneNeverReachTheEmbeddingTextEvenWhenTypedIntoOtherFields() {
        var leaky = new HouseDto(id, "Ramesh Kumar's flat", "Near Kumar stores, ph 98450 12345", "MG Road",
                "Indiranagar", 12.97, 77.64, HouseStatus.NEW, 28000L, "RENT", 2, 4, "Ramesh Kumar", "+91 98450 12345",
                "https://example.com/l/9845012345",
                "Ramesh said water is fine. Call ramesh on 98450-12345 or his wife on +91 99001 23456 after 6.",
                null, null, null, null, Map.of("Ramesh Kumar approved", 5), null, null, false, 7, null);

        var doc = HouseDocuments.toDocument(leaky, List.of());

        assertThat(doc.getText())
                .doesNotContainIgnoringCase("Ramesh")
                .doesNotContain("98450").doesNotContain("9845012345").doesNotContain("99001")
                .contains("House: [contact]'s flat")
                .contains("Notes: [contact] said water is fine. Call [contact] on [phone] or his wife on [phone] after 6.")
                .contains("Checklist: [contact] approved 5/5")
                // Address, street and locality keep single name parts (place names like "Kumar Park"); label,
                // checklist keys and notes lose them.
                .contains("Address: Near Kumar stores, ph [phone]");
        assertThat(doc.getMetadata().values()).allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain("Ramesh"));
    }

    @Test
    void labelNamedAfterTheOwnersFirstNameLosesItInTextAndMetadata() {
        var h = new HouseDto(id, "Ramesh's 2BHK, Indiranagar", "12 MG Road", "MG Road", "Indiranagar", 12.97, 77.64,
                HouseStatus.NEW, 28000L, "RENT", 2, 4, "Ramesh Kumar", "+91 98450 12345", null, "Kumar is fine",
                null, null, null, null, Map.of("Ramesh fixes leaks", 4), null, null, false, 7, null);

        var doc = HouseDocuments.toDocument(h, List.of());

        assertThat(doc.getText()).doesNotContainIgnoringCase("Ramesh").doesNotContain("Kumar")
                .contains("House: [contact]'s 2BHK, Indiranagar")
                .contains("Checklist: [contact] fixes leaks 4/5")
                .contains("Notes: [contact] is fine");
        assertThat(doc.getMetadata()).containsEntry("label", "[contact]'s 2BHK, Indiranagar");
        assertThat(doc.getMetadata().values()).allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain("Ramesh"));
    }

    @Test
    void careOfAddressWithTheOwnersFullNameLosesItInTextAndMetadata() {
        var h = new HouseDto(id, "Blue gate", "C/o Ramesh Kumar, 12 MG Road", "C/o Ramesh  Kumar",
                "Kumar Ramesh layout", 12.97, 77.64, HouseStatus.NEW, 28000L, "RENT", 2, 4, "Mr. Ramesh Kumar",
                "+91 98450 12345", null, "Fine", null, null, null, null, Map.of(), null, null, false, 7, null);

        var doc = HouseDocuments.toDocument(h, List.of());

        assertThat(doc.getText()).doesNotContainIgnoringCase("Ramesh Kumar").doesNotContainIgnoringCase("Ramesh")
                .contains("Address: C/o [contact], 12 MG Road")
                .contains("Street: C/o [contact]")
                .contains("Locality: [contact] layout");
        assertThat(doc.getMetadata()).containsEntry("locality", "[contact] layout");
        assertThat(doc.getMetadata().values())
                .allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContainIgnoringCase("Ramesh"));
    }

    /**
     * Slice 1a: the carpet area and the cost lines, in the words the on-device {@code AiHouse} uses too. Rupees win
     * over months when both are set; {@code myOffer} never goes to the provider (docs/11 section 5.30 item 5).
     */
    @Test
    void carpetAreaAndCostLinesAreIndexedButNeverMyOffer() {
        var cost = new HouseCost(64000L, 3, 2500L, false, null, 1, 11, 2, "2026-10-15", 30000L, 31000L);
        var h = new HouseDto(id, "Blue gate", null, null, null, 12.97, 77.64, HouseStatus.NEW, 32000L, "RENT", 2,
                null, null, null, null, null, 1150, "GPS", cost, null, Map.of(), null, null, false, 1, null);

        var text = HouseDocuments.text(h, List.of());

        assertThat(text)
                .contains("Carpet area: 1150 sq ft\n")
                .contains("Deposit: Rs 64000\n")
                .contains("Maintenance: Rs 2500 per month (not included)\n")
                .contains("Brokerage: 1 month\n")
                .contains("Lock-in: 11 months\n")
                .contains("Notice: 2 months\n")
                .contains("Available from: 2026-10-15\n")
                .contains("Agreed price: Rs 31000\n")
                .doesNotContain("30000").doesNotContain("offer").doesNotContain("Offer");
        // Months when there are no rupees, "included" when the rent covers the maintenance, and no line for a
        // field that is not set.
        var sale = new HouseCost(null, 2, 2500L, true, 25000L, null, null, null, null, null, null);
        var saleText = HouseDocuments.text(new HouseDto(id, "Plot", null, null, null, 0, 0, null, null, null,
                null, null, null, null, null, null, null, null, sale, null, Map.of(), null, null, false, 1, null), List.of());
        assertThat(saleText).contains("Deposit: 2 months\n").contains("Maintenance: Rs 2500 per month (included)\n")
                .contains("Brokerage: Rs 25000\n").doesNotContain("Lock-in").doesNotContain("Notice")
                .doesNotContain("Available").doesNotContain("Agreed").doesNotContain("Carpet");
        assertThat(HouseDocuments.text(house, List.of())).doesNotContain("Deposit").doesNotContain("Carpet");
    }

    @Test
    void metadataSkipsNullsAndNotesAreCapped() {
        var bare = new HouseDto(id, "Plot", null, null, null, 0, 0, null, null, null, null, null, null, null, null,
                "n".repeat(10_000), null, null, null, null, Map.of(), null, null, false, 1, null);
        var doc = HouseDocuments.toDocument(bare, List.of());
        assertThat(doc.getMetadata()).containsOnlyKeys("houseId", "label");
        assertThat(doc.getText()).contains("Visits: not visited yet");
        assertThat(doc.getText().length()).isLessThan(HouseDocuments.NOTES_MAX + 200);
    }
}
