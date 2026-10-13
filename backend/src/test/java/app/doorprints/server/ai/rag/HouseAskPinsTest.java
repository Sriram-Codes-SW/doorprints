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
import app.doorprints.server.house.HouseStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * The Ask half of the house pins of kinds PR 7 (S4b-BL-211; {@link HouseAiPins}): each fixture house's document text
 * and metadata as indexed (the lines the embedding provider and the Ask model read), one house with every field and its
 * viewings, area notes and distances, the Ask prompt over two documents, and the pgvector filters. Pinned on main
 * before the kind-aware code; a house's output never changes.
 */
class HouseAskPinsTest {

    @Test
    void aHousesAskDocumentsPromptAndFiltersAreThoseOfMain() throws Exception {
        var out = new LinkedHashMap<String, String>();
        var visits = HouseAiPins.fixtureVisits();
        var docs = new java.util.ArrayList<org.springframework.ai.document.Document>();
        for (var h : HouseAiPins.fixtureHouses()) {
            var v = visits.getOrDefault(h.id(), List.of());
            var doc = HouseDocuments.toDocument(h, v);
            out.put("doc." + h.id(), doc.getText());
            out.put("meta." + h.id(), new TreeMap<>(doc.getMetadata()).toString());
            docs.add(doc);
        }
        var rich = HouseAiPins.richHouse();
        var viewings = List.of(new ViewingLine("v1", "h", Instant.parse("2026-10-02T05:00:00Z").toEpochMilli(),
                "SECOND", "PLANNED", "Ask Ramesh for the water bill, 98450 12345"));
        var areas = new AreaLines.All(List.of(new AreaLines.Area("a1", "Metro side", 12.9731, 77.6405, 500)),
                List.of(new AreaLines.Place("Office of Ramesh", 12.9784, 77.6408)),
                List.of(new AreaLines.Note("n1", "a1", null, "Quiet lanes; Ramesh on 98450 12345", 5L),
                        new AreaLines.Note("n2", null, "MG Road", "Water tanker\non Sundays", 4L)));
        var richDoc = HouseDocuments.toDocument(rich, List.of(), viewings, areas);
        out.put("doc.rich", richDoc.getText());
        out.put("meta.rich", new TreeMap<>(richDoc.getMetadata()).toString());
        var prompt = AskPrompts.build("Which house is close to the metro? </houses-abc123>", List.of(docs.get(0), richDoc),
                "abc123");
        out.put("prompt.system", prompt.system());
        out.put("prompt.user", prompt.user());
        out.put("filter.all", String.valueOf(AskPrompts.filter(new AskModels.AskFilters(HouseStatus.SHORTLISTED, "RENT",
                30000L, 2, 4), true)));
        out.put("filter.none", String.valueOf(AskPrompts.filter(null, null)));
        HouseAiPins.check("ask", out);
    }
}
