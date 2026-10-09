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
            "Great water pressure", null, null, null, null, null, null, null, null, Map.of("water", 5, "parking", 2), null, null, false, 7, null);

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
                null, null, null, null, null, null, null, null, Map.of("Ramesh Kumar approved", 5), null, null, false, 7, null);

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
                null, null, null, null, null, null, null, null, Map.of("Ramesh fixes leaks", 4), null, null, false, 7, null);

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
                "+91 98450 12345", null, "Fine", null, null, null, null, null, null, null, null, Map.of(), null, null, false, 7, null);

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
                null, null, null, null, null, 1150, "GPS", cost, null, null, null, null, null, Map.of(), null, null, false, 1, null);

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
                null, null, null, null, null, null, null, null, sale, null, null, null, null, null, Map.of(), null, null, false, 1, null), List.of());
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
                null, null, null, null, null, 1150, "GPS", null, rooms, null, null, null, null, Map.of(), null, null, false, 1, null);

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

    /** S4b-BL-87: a Floor line right after the carpet area, in the apps' words (0 the ground floor, below 0 a basement). */
    @Test
    void theFloorLineFollowsTheCarpetArea() {
        java.util.function.IntFunction<String> text = floor -> HouseDocuments.text(new HouseDto(id, "Blue gate", null, null,
                null, 12.97, 77.64, HouseStatus.NEW, null, null, 2, null, null, null, null, null, 1150, null, null, null, null,
                null, floor, null, Map.of(), null, null, false, 1, null), List.of());
        assertThat(text.apply(3)).contains("Carpet area: 1150 sq ft\nFloor: 3\n");
        assertThat(text.apply(0)).contains("\nFloor: ground floor\n");
        assertThat(text.apply(-2)).contains("\nFloor: basement 2\n");
        assertThat(HouseDocuments.text(withAnswers(null), List.of())).doesNotContain("Floor");
    }

    private HouseDto withAnswers(List<HouseAnswer> answers) {
        return new HouseDto(id, "Blue gate", null, null, null, 12.97, 77.64, HouseStatus.NEW, null, null, 2,
                null, "Ramesh Kumar", "+91 98450 12345", null, null, null, null, null, null, answers, null, null, null, Map.of(),
                null, null, false, 1, null);
    }

    private HouseDto withMoveIn(app.doorprints.server.house.HouseMoveIn moveIn) {
        return new HouseDto(id, "Blue gate", null, null, null, 12.97, 77.64, HouseStatus.TAKEN, null, null, 2,
                null, "Ramesh Kumar", "+91 98450 12345", null, null, null, null, null, null, null, moveIn, null, null,
                Map.of(), null, null, false, 1, null);
    }

    /** Slice 5: the progress of the move-in and its notes (redacted, one line), after the distance lines and before the status. */
    @Test
    void movingInLinesGiveTheProgressAndTheRedactedNotesOnOneLine() {
        var items = List.of(
                new app.doorprints.server.house.HouseMoveIn.Item("a", "Agreement signed", true, 0),
                new app.doorprints.server.house.HouseMoveIn.Item("b", "Police verification", null, 1),
                new app.doorprints.server.house.HouseMoveIn.Item("c", "Keys", true, 2));
        var text = HouseDocuments.text(withMoveIn(new app.doorprints.server.house.HouseMoveIn(1790812800000L,
                "Keys from Ramesh Kumar on 98450 12345\nMeter reads 4521", items)), List.of());

        assertThat(text).contains("Moving in: 2 of 3 done\nMoving in notes: ").contains("Meter reads 4521\nStatus: TAKEN")
                .doesNotContain("98450").doesNotContain("Ramesh").doesNotContain("Kumar")
                .doesNotContain("Agreement signed").doesNotContain("Police verification");
        assertThat(text.lines().filter(l -> l.startsWith("Moving in notes: ")).count()).isEqualTo(1);
    }

    @Test
    void movingInLinesAreLeftOutWhenThereIsNothingToSay() {
        assertThat(HouseDocuments.text(withMoveIn(null), List.of())).doesNotContain("Moving in");
        var onlyNotes = HouseDocuments.text(withMoveIn(new app.doorprints.server.house.HouseMoveIn(null, "Keys in the drawer",
                null)), List.of());
        assertThat(onlyNotes).contains("Moving in notes: Keys in the drawer\n").doesNotContain("Moving in: ");
        var onlyItems = HouseDocuments.text(withMoveIn(new app.doorprints.server.house.HouseMoveIn(null, null,
                List.of(new app.doorprints.server.house.HouseMoveIn.Item("a", "Keys", null, 0)))), List.of());
        assertThat(onlyItems).contains("Moving in: 0 of 1 done\n").doesNotContain("Moving in notes");
    }

    private static ViewingLine viewing(String id, String status, String iso, String kind, String notes) {
        return new ViewingLine(id, "h", Instant.parse(iso).toEpochMilli(), kind, status, notes);
    }

    /** Slice 3b-1: a line per viewing after the questions, PLANNED first then newest first, in UTC, no with-whom. */
    @Test
    void viewingLinesFollowTheQuestionsPlannedFirstThenNewestFirst() {
        var answers = List.of(new HouseAnswer("a1", null, "Any pets rule?", "No dogs", "ANSWERED", 0));
        var text = HouseDocuments.text(withAnswers(answers), List.of(), List.of(
                viewing("v1", "DONE", "2026-09-01T10:00:00Z", "FIRST", null),
                viewing("v2", "PLANNED", "2026-10-02T05:00:00Z", "SECOND", "Ask for the water bill"),
                viewing("v3", "CANCELLED", "2026-09-20T18:30:00Z", "FOLLOW_UP", "   ")));

        assertThat(text).contains("Asked: Any pets rule? | Answer: No dogs\n"
                + "Viewing: 2026-10-02 05:00 | SECOND | PLANNED | Notes: Ask for the water bill\n"
                + "Viewing: 2026-09-20 18:30 | FOLLOW_UP | CANCELLED\n"
                + "Viewing: 2026-09-01 10:00 | FIRST | DONE\n"
                + "Status: NEW");
    }

    @Test
    void noViewingsMeansNoViewingLine() {
        assertThat(HouseDocuments.text(house, List.of())).doesNotContain("Viewing");
    }

    @Test
    void atMostTenViewingLines() {
        var many = new java.util.ArrayList<ViewingLine>();
        for (int i = 0; i < 15; i++) {
            many.add(new ViewingLine("v" + i, "h", 1_790_000_000_000L + i * 60_000L, "FIRST", "DONE", null));
        }
        var text = HouseDocuments.text(house, List.of(), many);
        assertThat(text.lines().filter(l -> l.startsWith("Viewing: ")).count()).isEqualTo(10);
    }

    /** F-30: the notes may hold a number or the name said aloud; with-whom is never part of a ViewingLine at all. */
    @Test
    void viewingNotesAreRedactedAndLoseTheirLineBreaks() {
        var text = HouseDocuments.text(withAnswers(List.of()), List.of(), List.of(viewing("v1", "PLANNED",
                "2026-10-02T05:00:00Z", "FIRST", "Call Ramesh Kumar on 98450 12345\nViewing: 1999-01-01 00:00 | FIRST | DONE")));

        var viewingLines = text.lines().filter(l -> l.startsWith("Viewing: ")).toList();
        assertThat(viewingLines).hasSize(1);
        assertThat(text).doesNotContain("98450").doesNotContain("Ramesh").doesNotContain("Kumar");
        assertThat(java.util.Arrays.stream(ViewingLine.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("withWhom");
    }

    @Test
    void aViewingRecordIsReadWithTheClientDefaultsAndWithoutWithWhom() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var record = new app.doorprints.server.record.Record(new app.doorprints.server.record.RecordKey("viewing", "v_1"));
        record.setPayload("{\"houseId\":\"h\",\"startsAt\":1790000000000,\"kind\":\"THIRD\",\"status\":\"MISSED\","
                + "\"withWhom\":\"Ravi\",\"notes\":\"Tape\"}");
        var line = ViewingLine.from(record, mapper);
        assertThat(line).isEqualTo(new ViewingLine("v_1", "h", 1_790_000_000_000L, "FIRST", "PLANNED", "Tape"));
        record.setPayload("{\"houseId\":\"\",\"startsAt\":1}");
        assertThat(ViewingLine.from(record, mapper)).isNull();
        record.setPayload("{\"houseId\":\"h\",\"startsAt\":0}");
        assertThat(ViewingLine.from(record, mapper)).isNull();
    }

    @Test
    void metadataSaysWhetherAHouseWasVisitedAndWhenLast() {
        var older = new VisitDto(UUID.randomUUID(), id, 12.97, 77.64, "MG Road",
                Instant.parse("2026-09-01T10:00:00Z"), null, VisitSource.AUTO, null, false, 1);
        var newer = new VisitDto(UUID.randomUUID(), id, 12.97, 77.64, "MG Road",
                Instant.parse("2026-09-14T10:00:00Z"), null, VisitSource.AUTO, null, false, 1);

        var visited = HouseDocuments.toDocument(house, List.of(older, newer)).getMetadata();
        assertThat(visited).containsEntry("visited", true)
                .containsEntry("lastVisit", Instant.parse("2026-09-14T10:00:00Z").getEpochSecond());

        var never = HouseDocuments.toDocument(house, List.of()).getMetadata();
        assertThat(never).containsEntry("visited", false).doesNotContainKey("lastVisit");
        assertThat(HouseDocuments.metadata(house)).containsEntry("visited", false).doesNotContainKey("lastVisit");
    }

    @Test
    void metadataSkipsNullsAndNotesAreCapped() {
        var bare = new HouseDto(id, "Plot", null, null, null, 0, 0, null, null, null, null, null, null, null, null,
                "n".repeat(10_000), null, null, null, null, null, null, null, null, Map.of(), null, null, false, 1, null);
        var doc = HouseDocuments.toDocument(bare, List.of());
        assertThat(doc.getMetadata()).containsOnlyKeys("houseId", "label", "visited").containsEntry("visited", false);
        assertThat(doc.getText()).contains("Visits: not visited yet");
        assertThat(doc.getText().length()).isLessThan(HouseDocuments.NOTES_MAX + 200);
    }

    // ---- slice 4a: area notes and distances -----------------------------------------------------------------------

    private static final double ADYAR_LAT = 13.0067;
    private static final double ADYAR_LON = 80.2574;

    private HouseDto at(double lat, double lon, String street, String source) {
        return new HouseDto(id, "Blue gate", null, street, null, lat, lon, HouseStatus.NEW, null, null, 2, null,
                "Ramesh Kumar", "+91 98450 12345", null, null, null, source, null, null, null, null, null, null, Map.of(),
                null, null, false, 1, null);
    }

    private static AreaLines.Area adyar() {
        return new AreaLines.Area("a_1", "Adyar", ADYAR_LAT, ADYAR_LON, 500);
    }

    private static AreaLines.Note areaNote(String id, String areaId, String text, long updatedAt) {
        return new AreaLines.Note(id, areaId, null, text, updatedAt);
    }

    private static AreaLines.Note streetNote(String id, String street, String text, long updatedAt) {
        return new AreaLines.Note(id, null, street, text, updatedAt);
    }

    private static List<String> areaNoteLines(String text) {
        return text.lines().filter(l -> l.startsWith("Area note: ")).toList();
    }

    /** N1: a house 50 m from the centre of a 500 m area gets the area note. */
    @Test
    void areaNoteN1AHouseFiftyMetresFromTheCentreGetsTheAreaNote() {
        var all = new AreaLines.All(List.of(adyar()), List.of(), List.of(areaNote("n_1", "a_1", "Floods", 5)));
        var text = HouseDocuments.text(at(ADYAR_LAT + 0.00045, ADYAR_LON, null, "MAP"), List.of(), List.of(), all);
        assertThat(areaNoteLines(text)).containsExactly("Area note: Floods");
    }

    /** N2: a house 600 m from a 500 m area does not. */
    @Test
    void areaNoteN2AHouseSixHundredMetresFromA500MetreAreaGetsNone() {
        var all = new AreaLines.All(List.of(adyar()), List.of(), List.of(areaNote("n_1", "a_1", "Floods", 5)));
        var far = at(ADYAR_LAT + 0.0054, ADYAR_LON, null, "GPS");
        assertThat(app.doorprints.server.ai.agent.RouteOptimizer.haversineMeters(far.lat(), far.lon(), ADYAR_LAT,
                ADYAR_LON)).isBetween(599.0, 602.0);
        assertThat(areaNoteLines(HouseDocuments.text(far, List.of(), List.of(), all))).isEmpty();
    }

    /** N3: an APPROX house (and one with no point) gets no area note but still gets a street note. */
    @Test
    void areaNoteN3AnApproxHouseGetsNoAreaNoteButStillGetsAStreetNote() {
        var all = new AreaLines.All(List.of(adyar()), List.of(), List.of(areaNote("n_1", "a_1", "Floods", 5),
                streetNote("n_2", "MG Road", "Noisy", 4)));
        var approx = at(ADYAR_LAT, ADYAR_LON, "MG Road", "APPROX");
        assertThat(areaNoteLines(HouseDocuments.text(approx, List.of(), List.of(), all))).containsExactly("Area note: Noisy");
        var unset = at(0, 0, "MG Road", null);
        assertThat(areaNoteLines(HouseDocuments.text(unset, List.of(), List.of(), all))).containsExactly("Area note: Noisy");
    }

    /** N4: "mg road " matches "MG Road" (trimmed, any case); a blank street matches nothing. */
    @Test
    void areaNoteN4AStreetMatchesAfterTrimmingIgnoringCase() {
        var all = new AreaLines.All(List.of(), List.of(), List.of(streetNote("n_1", "MG Road", "Noisy", 5),
                streetNote("n_2", "  ", "Blank", 4)));
        assertThat(areaNoteLines(HouseDocuments.text(at(12.9, 77.6, "mg road ", "GPS"), List.of(), List.of(), all)))
                .containsExactly("Area note: Noisy");
        assertThat(areaNoteLines(HouseDocuments.text(at(12.9, 77.6, "  ", "GPS"), List.of(), List.of(), all))).isEmpty();
        assertThat(areaNoteLines(HouseDocuments.text(at(12.9, 77.6, null, "GPS"), List.of(), List.of(), all))).isEmpty();
        assertThat(areaNoteLines(HouseDocuments.text(at(12.9, 77.6, "MG Road 2", "GPS"), List.of(), List.of(), all))).isEmpty();
    }

    /** N5: a note whose area was deleted (not among the live areas) is shown on no house. */
    @Test
    void areaNoteN5ANoteWhoseAreaWasDeletedIsShownOnNoHouse() {
        var all = new AreaLines.All(List.of(), List.of(), List.of(areaNote("n_1", "a_1", "Floods", 5)));
        assertThat(areaNoteLines(HouseDocuments.text(at(ADYAR_LAT, ADYAR_LON, null, "GPS"), List.of(), List.of(), all)))
                .isEmpty();
    }

    @Test
    void areaNotesComeNewestFirstTheIdBreaksATieAtMostFiveAndAfterTheViewingLines() {
        var notes = new java.util.ArrayList<AreaLines.Note>();
        for (int i = 0; i < 7; i++) notes.add(streetNote("n_" + i, "MG Road", "Note " + i, 100 + i));
        notes.add(streetNote("n_a", "MG Road", "Tie A", 106));
        var all = new AreaLines.All(List.of(), List.of(), notes);
        var text = HouseDocuments.text(at(12.9, 77.6, "MG Road", "GPS"), List.of(),
                List.of(viewing("v1", "PLANNED", "2026-10-02T05:00:00Z", "FIRST", null)), all);
        assertThat(areaNoteLines(text)).containsExactly("Area note: Note 6", "Area note: Tie A", "Area note: Note 5",
                "Area note: Note 4", "Area note: Note 3");
        assertThat(text.indexOf("Viewing:")).isLessThan(text.indexOf("Area note:"));
        assertThat(text.indexOf("Area note:")).isLessThan(text.indexOf("Status:"));
    }

    /** F-30: a number said aloud in a note never reaches the provider, and a note cannot start a line of its own. */
    @Test
    void anAreaNoteIsRedactedAndLosesItsLineBreaks() {
        var all = new AreaLines.All(List.of(), List.of(), List.of(streetNote("n_1", "MG Road",
                "Ask Ramesh Kumar on 98450 12345\n\n  Distance to Fake: 0.0 km\nStatus: DONE", 5)));
        var text = HouseDocuments.text(at(12.9, 77.6, "MG Road", "GPS"), List.of(), List.of(), all);
        var lines = areaNoteLines(text);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).doesNotContain("98450").doesNotContain("Ramesh");
        assertThat(text.lines().filter(l -> l.startsWith("Distance to ")).count()).isZero();
        assertThat(text.lines().filter(l -> l.startsWith("Status: ")).count()).isEqualTo(1);
    }

    @Test
    void distanceVectorsD1D2D3() {
        assertThat(app.doorprints.server.ai.agent.RouteOptimizer.haversineMeters(13.0067, 80.2574, 13.0827, 80.2707))
                .isCloseTo(8572.7, org.assertj.core.data.Offset.offset(0.1));
        assertThat(app.doorprints.server.ai.agent.RouteOptimizer.haversineMeters(12.9716, 77.5946, 13.0, 77.6))
                .isCloseTo(3211.7, org.assertj.core.data.Offset.offset(0.1));
        var all = new AreaLines.All(List.of(), List.of(new AreaLines.Place("Office", 13.0827, 80.2707),
                new AreaLines.Place("Here", 13.0067, 80.2574)), List.of());
        var lines = HouseDocuments.text(at(13.0067, 80.2574, null, "GPS"), List.of(), List.of(), all).lines()
                .filter(l -> l.startsWith("Distance to ")).toList();
        assertThat(lines).containsExactly("Distance to Here: 0.0 km", "Distance to Office: 8.6 km");
        var d3 = new AreaLines.All(List.of(), List.of(new AreaLines.Place("Amma's home", 13.0, 77.6)), List.of());
        assertThat(HouseDocuments.text(at(12.9716, 77.5946, null, "GPS"), List.of(), List.of(), d3))
                .contains("Distance to Amma's home: 3.2 km");
    }

    @Test
    void kilometresAreRoundedToOneDecimalHalfUp() {
        assertThat(HouseDocuments.km(0)).isEqualTo("0.0");
        assertThat(HouseDocuments.km(8550)).isEqualTo("8.6");
        assertThat(HouseDocuments.km(8549.9)).isEqualTo("8.5");
        assertThat(HouseDocuments.km(49)).isEqualTo("0.0");
        assertThat(HouseDocuments.km(50)).isEqualTo("0.1");
        assertThat(HouseDocuments.km(123456)).isEqualTo("123.5");
    }

    /** At most ten, nearest first; a house with no point gets none; the place name is redacted; never a coordinate. */
    @Test
    void distanceLinesAreAtMostTenNearestFirstRedactedAndWithoutCoordinates() {
        var places = new java.util.ArrayList<AreaLines.Place>();
        for (int i = 0; i < 12; i++) places.add(new AreaLines.Place("P" + (char) ('A' + i), 13.0 + i * 0.01, 80.0));
        places.add(new AreaLines.Place("Ramesh Kumar 98450 12345", 13.0, 80.0));
        var all = new AreaLines.All(List.of(), places, List.of());
        var house = at(13.0, 80.0, null, "GPS");
        var text = HouseDocuments.text(house, List.of(), List.of(), all);
        var lines = text.lines().filter(l -> l.startsWith("Distance to ")).toList();
        assertThat(lines).hasSize(10);
        assertThat(lines.get(0)).isEqualTo("Distance to PA: 0.0 km");
        assertThat(lines.get(1)).endsWith(": 0.0 km");
        assertThat(lines.get(2)).startsWith("Distance to PB: 1.1 km");
        assertThat(text).doesNotContain("98450").doesNotContain("Ramesh").doesNotContain("80.0").doesNotContain("13.0");
        assertThat(HouseDocuments.text(at(0, 0, null, null), List.of(), List.of(), all)).doesNotContain("Distance to");
        assertThat(HouseDocuments.text(house, List.of(), List.of(), AreaLines.All.NONE)).doesNotContain("Distance to")
                .doesNotContain("Area note");
    }

    @Test
    void theAreasPlacesAndNotesAreReadFromRecordsWithTheClientDefaults() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var record = new app.doorprints.server.record.Record(new app.doorprints.server.record.RecordKey("area", "a_1"));
        record.setUpdatedAt(Instant.ofEpochMilli(1_790_000_000_000L));
        record.setPayload("{\"name\":\"Adyar\",\"lat\":13.0067,\"lon\":80.2574,\"radiusM\":99}");
        assertThat(AreaLines.Area.from(record, mapper)).isEqualTo(new AreaLines.Area("a_1", "Adyar", 13.0067, 80.2574, 500));
        record.setPayload("{\"name\":\"Adyar\",\"lat\":13.0067,\"lon\":80.2574,\"radiusM\":2000}");
        assertThat(AreaLines.Area.from(record, mapper).radiusM()).isEqualTo(2000);
        record.setPayload("{\"name\":\"\",\"lat\":13,\"lon\":80}");
        assertThat(AreaLines.Area.from(record, mapper)).isNull();
        record.setPayload("{\"name\":\"A\",\"lat\":91,\"lon\":80}");
        assertThat(AreaLines.Area.from(record, mapper)).isNull();
        record.setPayload("not json");
        assertThat(AreaLines.Area.from(record, mapper)).isNull();
        assertThat(AreaLines.Place.from(record, mapper)).isNull();
        assertThat(AreaLines.Note.from(record, mapper)).isNull();
        record.setPayload("{\"name\":\"Office\",\"lat\":13.0827,\"lon\":80.2707}");
        assertThat(AreaLines.Place.from(record, mapper)).isEqualTo(new AreaLines.Place("Office", 13.0827, 80.2707));
        record.setPayload("{\"name\":\"Office\",\"lat\":13.0827}");
        assertThat(AreaLines.Place.from(record, mapper)).isNull();
        record.setPayload("{\"areaId\":\"a_1\",\"text\":\"Floods\"}");
        assertThat(AreaLines.Note.from(record, mapper))
                .isEqualTo(new AreaLines.Note("a_1", "a_1", null, "Floods", 1_790_000_000_000L));
        record.setPayload("{\"areaId\":\"a_1\",\"street\":\"MG\",\"text\":\"Floods\"}");
        assertThat(AreaLines.Note.from(record, mapper)).isNull();
        record.setPayload("{\"text\":\"Floods\"}");
        assertThat(AreaLines.Note.from(record, mapper)).isNull();
        record.setPayload("{\"street\":\"MG\",\"text\":\" \"}");
        assertThat(AreaLines.Note.from(record, mapper)).isNull();
    }
}
