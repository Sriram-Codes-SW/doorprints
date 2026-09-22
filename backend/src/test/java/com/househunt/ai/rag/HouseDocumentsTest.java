package com.househunt.ai.rag;

import com.househunt.house.HouseDto;
import com.househunt.house.HouseStatus;
import com.househunt.visit.VisitDto;
import com.househunt.visit.VisitSource;
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
            "Great water pressure", Map.of("water", 5, "parking", 2), null, null, false, 7, null);

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
                Map.of("Ramesh Kumar approved", 5), null, null, false, 7, null);

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
                Map.of("Ramesh fixes leaks", 4), null, null, false, 7, null);

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
                "+91 98450 12345", null, "Fine", Map.of(), null, null, false, 7, null);

        var doc = HouseDocuments.toDocument(h, List.of());

        assertThat(doc.getText()).doesNotContainIgnoringCase("Ramesh Kumar").doesNotContainIgnoringCase("Ramesh")
                .contains("Address: C/o [contact], 12 MG Road")
                .contains("Street: C/o [contact]")
                .contains("Locality: [contact] layout");
        assertThat(doc.getMetadata()).containsEntry("locality", "[contact] layout");
        assertThat(doc.getMetadata().values())
                .allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContainIgnoringCase("Ramesh"));
    }

    @Test
    void metadataSkipsNullsAndNotesAreCapped() {
        var bare = new HouseDto(id, "Plot", null, null, null, 0, 0, null, null, null, null, null, null, null, null,
                "n".repeat(10_000), Map.of(), null, null, false, 1, null);
        var doc = HouseDocuments.toDocument(bare, List.of());
        assertThat(doc.getMetadata()).containsOnlyKeys("houseId", "label");
        assertThat(doc.getText()).contains("Visits: not visited yet");
        assertThat(doc.getText().length()).isLessThan(HouseDocuments.NOTES_MAX + 200);
    }
}
