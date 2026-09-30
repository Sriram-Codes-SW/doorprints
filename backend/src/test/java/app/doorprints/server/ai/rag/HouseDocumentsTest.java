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

import app.doorprints.server.house.HouseAnswer;
import app.doorprints.server.house.HouseCost;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseRoom;
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
            "Great water pressure", null, null, null, null, null, null, Map.of("water", 5, "parking", 2), null, null, false, 7, null);

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
                null, null, null, null, null, null, Map.of("Ramesh Kumar approved", 5), null, null, false, 7, null);

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
                null, null, null, null, null, null, Map.of("Ramesh fixes leaks", 4), null, null, false, 7, null);

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
                "+91 98450 12345", null, "Fine", null, null, null, null, null, null, Map.of(), null, null, false, 7, null);

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
                null, null, null, null, null, 1150, "GPS", cost, null, null, null, Map.of(), null, null, false, 1, null);

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
                null, null, null, null, null, null, null, null, sale, null, null, null, Map.of(), null, null, false, 1, null), List.of());
        assertThat(saleText).contains("Deposit: 2 months\n").contains("Maintenance: Rs 2500 per month (included)\n")
                .contains("Brokerage: Rs 25000\n").doesNotContain("Lock-in").doesNotContain("Notice")
                .doesNotContain("Available").doesNotContain("Agreed").doesNotContain("Carpet");
        assertThat(HouseDocuments.text(house, List.of())).doesNotContain("Deposit").doesNotContain("Carpet");
    }

    /**
     * Slice 1c: one Rooms line after the cost lines, in the words of the on-device {@code AiHouse}: names, sizes in
     * feet and inches and condition only, in the order shown. A room's notes never go to the provider.
     */
    @Test
    void roomsAreOneLineWithSizesAndConditionButNeverTheirNotes() {
        var rooms = List.of(
                new HouseRoom("k", "KITCHEN", "Kitchen", 300, 244, null, "Call Ramesh on 98450 12345", 1),
                new HouseRoom("m", "BEDROOM", "Master bedroom", 396, 366, 4, "Damp patch near the window", 0),
                new HouseRoom("s", "STORE", "", null, 200, 2, null, 2),
                new HouseRoom("p", "POOJA", null, null, null, null, null, 2));
        var h = new HouseDto(id, "Blue gate", null, null, null, 12.97, 77.64, HouseStatus.NEW, null, null, 2,
                null, null, null, null, null, 1150, "GPS", null, rooms, null, null, Map.of(), null, null, false, 1, null);

        var text = HouseDocuments.text(h, List.of());

        assertThat(text).contains("\nRooms: Master bedroom 13 ft 0 in x 12 ft 0 in (condition 4/5); "
                + "Kitchen 9 ft 10 in x 8 ft 0 in; Pooja; Store (condition 2/5)\n");
        assertThat(text).doesNotContain("Damp patch").doesNotContain("Ramesh").doesNotContain("98450");
        assertThat(text.indexOf("Carpet area")).isLessThan(text.indexOf("Rooms:"));
        assertThat(text.indexOf("Rooms:")).isLessThan(text.indexOf("Status:"));
        assertThat(HouseDocuments.text(house, List.of())).doesNotContain("Rooms");
        assertThat(HouseDocuments.feetInches(396)).isEqualTo("13 ft 0 in");
        assertThat(HouseDocuments.feetInches(0)).isEqualTo("0 ft 0 in");
    }

    /** Slice 3a: answered questions, then open ones, each kind at most 20; skipped ones are not sent. */
    @Test
    void answersAreAskedAndAnsweredLinesThenStillToAskLines() {
        var answers = List.of(
                new HouseAnswer("a3", null, "Is there a lift?", null, "OPEN", 2),
                new HouseAnswer("a2", "qd_water", "How is the water supply?", "Borewell, 24 hours", "ANSWERED", 1),
                new HouseAnswer("a1", null, "Any pets rule?", "No dogs", "ANSWERED", 0),
                new HouseAnswer("a4", null, "Is the terrace open?", null, "SKIPPED", 3),
                new HouseAnswer("a5", null, "Who pays for power?", null, "OPEN", 1));
        var text = HouseDocuments.text(withAnswers(answers), List.of());

        assertThat(text).contains("\nAsked: Any pets rule? | Answer: No dogs\n"
                + "Asked: How is the water supply? | Answer: Borewell, 24 hours\n"
                + "Still to ask: Who pays for power?\nStill to ask: Is there a lift?\n");
        assertThat(text).doesNotContain("terrace");
        assertThat(text.indexOf("Asked:")).isLessThan(text.indexOf("Status:"));
        assertThat(HouseDocuments.text(house, List.of())).doesNotContain("Asked").doesNotContain("Still to ask");
    }

    @Test
    void atMostTwentyAskedAndTwentyStillToAskLinesAreWritten() {
        var answers = new java.util.ArrayList<HouseAnswer>();
        for (int i = 0; i < 25; i++) answers.add(new HouseAnswer("o" + i, null, "Open " + i, null, "OPEN", i));
        for (int i = 0; i < 25; i++) answers.add(new HouseAnswer("d" + i, null, "Done " + i, "yes " + i, "ANSWERED", i));
        var text = HouseDocuments.text(withAnswers(answers), List.of());

        assertThat(text.split("Asked: ", -1).length - 1).isEqualTo(20);
        assertThat(text.split("Still to ask: ", -1).length - 1).isEqualTo(20);
        assertThat(text).contains("Asked: Done 19 | Answer: yes 19").doesNotContain("Done 20")
                .contains("Still to ask: Open 19").doesNotContain("Open 20");
    }

    /** F-30: an owner's number said aloud at the viewing is in the answer; it never reaches the provider. */
    @Test
    void aPhoneNumberInAnAnswerOrAQuestionIsRedacted() {
        var answers = List.of(
                new HouseAnswer("a1", null, "Who do we call? 98450 12345", "Call the caretaker on 99001-23456 or "
                        + "+91 98765 43210", "ANSWERED", 0),
                new HouseAnswer("a2", null, "Ask Ramesh about the deposit", null, "OPEN", 1));
        var text = HouseDocuments.text(withAnswers(answers), List.of());

        assertThat(text).contains("Asked: ").contains("| Answer: Call the caretaker on ")
                .doesNotContain("99001").doesNotContain("98450").doesNotContain("98765")
                .doesNotContain("Ramesh");
    }

    private HouseDto withAnswers(List<HouseAnswer> answers) {
        return new HouseDto(id, "Blue gate", null, null, null, 12.97, 77.64, HouseStatus.NEW, null, null, 2,
                null, "Ramesh Kumar", "+91 98450 12345", null, null, null, null, null, null, answers, null, Map.of(),
                null, null, false, 1, null);
    }

    @Test
    void metadataSkipsNullsAndNotesAreCapped() {
        var bare = new HouseDto(id, "Plot", null, null, null, 0, 0, null, null, null, null, null, null, null, null,
                "n".repeat(10_000), null, null, null, null, null, null, Map.of(), null, null, false, 1, null);
        var doc = HouseDocuments.toDocument(bare, List.of());
        assertThat(doc.getMetadata()).containsOnlyKeys("houseId", "label");
        assertThat(doc.getText()).contains("Visits: not visited yet");
        assertThat(doc.getText().length()).isLessThan(HouseDocuments.NOTES_MAX + 200);
    }
}
