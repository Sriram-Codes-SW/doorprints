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
                .doesNotContain("98450"); // phone numbers are not embedded
        assertThat(doc.getMetadata()).containsEntry("houseId", id.toString()).containsEntry("status", "SHORTLISTED")
                .containsEntry("price", 28000L).containsEntry("bedrooms", 2).containsEntry("rating", 4);
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
