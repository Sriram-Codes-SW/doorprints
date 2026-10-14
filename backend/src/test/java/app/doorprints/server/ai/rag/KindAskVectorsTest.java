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

import app.doorprints.server.ai.HouseAiPins;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.house.TestHouses;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.visit.VisitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kind-aware Ask on the server (kinds PR 7, S4b-BL-211; docs/03 ADR-36 §18.11): the documents, the prompt and the
 * Ask filters of {@code docs/schemas/kinds/kind-ai-vectors.json}, which the website and the phone read too, and what the
 * vectors cannot show (the area-note and distance lines, the metadata keys, the indexer's flag).
 */
class KindAskVectorsTest {

    private static final JsonNode V = HouseAiPins.kindAiVectors();

    private static Document doc(JsonNode c) {
        return HouseDocuments.toDocument(HouseAiPins.candidate(c.get("candidate")), List.of(), List.of(),
                AreaLines.All.NONE, AiKinds.schools(c.get("devKinds").asBoolean()));
    }

    @Test
    void everyDocumentHasTheLinesOfTheVectors() {
        for (var c : V.get("documents")) {
            assertThat(doc(c).getText()).as(c.get("name").asString()).isEqualTo(c.get("expected").asString());
        }
    }

    @Test
    void theAskPromptNeutralisesATagInAValueOrTheQuestion() {
        for (var name : List.of("ask", "askTag")) {
            var ask = V.get(name);
            var docs = new ArrayList<Document>();
            for (var i : ask.get("documents")) docs.add(doc(V.get("documents").get(i.asInt())));
            var built = AskPrompts.build(ask.get("question").asString(), docs, ask.get("nonce").asString());
            assertThat(built.user()).as(name).isEqualTo(ask.get("user").asString());
        }
    }

    /** The pgvector filter of an Ask request, read over one document's metadata as the store compares it. */
    private static boolean keeps(Map<String, Object> m, JsonNode f) {
        if (f.has("status") && !f.get("status").asString().equals(m.get("status"))) return false;
        if (f.has("priceType") && !f.get("priceType").asString().equals(m.get("priceType"))) return false;
        if (f.has("maxPrice") && !(m.get("price") instanceof Long p && p <= f.get("maxPrice").asLong())) return false;
        if (f.has("minBedrooms") && !(m.get("bedrooms") instanceof Integer b && b >= f.get("minBedrooms").asInt())) return false;
        return !f.has("minRating") || m.get("rating") instanceof Integer r && r >= f.get("minRating").asInt();
    }

    @Test
    void anAskFilterOnAFieldTheKindHidesNeverKeepsIt() {
        for (var c : V.get("filters")) {
            var d = V.get("documents").get(c.get("candidate").asInt());
            var metadata = HouseDocuments.toDocument(HouseAiPins.candidate(d.get("candidate")), List.of(), List.of(),
                    AreaLines.All.NONE, AiKinds.schools(c.get("devKinds").asBoolean())).getMetadata();
            assertThat(keeps(metadata, c.get("filter"))).as(c.toString()).isEqualTo(c.get("matches").asBoolean());
        }
    }

    @Test
    void aSchoolsMetadataHasNoPriceNoBhkAndNoContact() {
        var hill = HouseAiPins.candidate(V.get("documents").get(2).get("candidate"));
        var m = HouseDocuments.toDocument(hill, List.of(), List.of(), AreaLines.All.NONE, AiKinds.schools(true)).getMetadata();
        assertThat(m).containsOnlyKeys("houseId", "label", "status", "rating", "locality", "visited")
                .containsEntry("label", "[contact]'s pick: Hill View").containsEntry("locality", "Beltola");
        var asHouse = HouseDocuments.toDocument(hill, List.of(), List.of(), AreaLines.All.NONE, AiKinds.OFF).getMetadata();
        assertThat(asHouse).containsEntry("price", 5000L).containsEntry("priceType", "RENT");
    }

    /** The school names the area notes and the distances: the house's lines, at the slot's place, redacted. */
    @Test
    void aSchoolsDocumentCarriesItsAreaNotesAndDistancesAtTheirPlace() {
        var hill = HouseAiPins.candidate(V.get("documents").get(2).get("candidate"));
        var areas = new AreaLines.All(List.of(new AreaLines.Area("a1", "Beltola", 26.12, 91.8, 500)),
                List.of(new AreaLines.Place("Ramesh's office", 26.13, 91.8)),
                List.of(new AreaLines.Note("n1", "a1", null, "Ramesh says\nthe road floods; 98450 12345", 5L)));
        var text = HouseDocuments.toDocument(hill, List.of(), List.of(), areas, AiKinds.schools(true)).getText();
        assertThat(text).contains("Fees per year: Rs 120000\nArea note: [contact] says the road floods; [phone]\n"
                + "Distance to [contact]'s office: 1.1 km\nStatus: NEW").doesNotContain("Ramesh").doesNotContain("98450");
    }

    /** The indexer reads each candidate as the server's flag says: off, a school is indexed as a house is today. */
    @Test
    void theIndexerReadsASchoolThroughItsKindOnlyWithTheFlag() {
        var houses = mock(HouseRepository.class);
        var school = TestHouses.entity(HouseAiPins.candidate(V.get("documents").get(0).get("candidate")));
        when(houses.findById(school.getId())).thenReturn(Optional.of(school));
        when(houses.findByDeletedFalseOrderByUpdatedAtDesc()).thenReturn(List.of(school));
        when(houses.findDeletedIds()).thenReturn(List.of());
        for (var on : List.of(true, false)) {
            var store = mock(VectorStore.class);
            var indexer = new HouseIndexer(houses, mock(VisitRepository.class), store, null, null, AiKinds.schools(on));
            indexer.index(school.getId());
            indexer.reindexAll();
            var captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(store, org.mockito.Mockito.times(2)).add(captor.capture());
            for (var docs : captor.getAllValues()) {
                var text = ((Document) docs.getFirst()).getText();
                assertThat(text).isEqualTo(V.get("documents").get(on ? 0 : 1).get("expected").asString());
            }
        }
    }

    /** {@code never} wins over {@code ask}: a kind that named a field in both would still never write it. */
    @Test
    void anIdThatNeverNamesIsNeverWrittenWhateverAskSays() {
        var k = app.doorprints.server.ai.kind.AiKinds.SCHOOL;
        var strict = new app.doorprints.server.ai.kind.AiKind(k.id(), k.noun(), k.extract(), k.ask(), k.plan(),
                List.of("contactName", "contactPhone", "notes", "board"), k.extras(), k.extractSystem());
        var pine = HouseAiPins.candidate(V.get("documents").get(0).get("candidate"));
        var text = KindDocuments.text(pine, List.of(), AreaLines.All.NONE, strict);
        assertThat(text).doesNotContain("Notes:").doesNotContain("Board:").contains("Medium of instruction: English");
        assertThat(KindDocuments.metadata(pine, List.of(), new app.doorprints.server.ai.kind.AiKind(k.id(), k.noun(),
                k.extract(), k.ask(), k.plan(), List.of("label", "status"), k.extras(), k.extractSystem())))
                .containsEntry("label", "").doesNotContainKey("status");
    }
}
