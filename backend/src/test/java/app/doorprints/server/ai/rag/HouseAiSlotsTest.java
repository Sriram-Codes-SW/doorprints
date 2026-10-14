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
import app.doorprints.server.ai.agent.HouseSearchService;
import app.doorprints.server.ai.extract.HouseDraft;
import app.doorprints.server.visit.VisitDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The slots of {@code docs/schemas/kinds/house.json} against the house's hand-written AI code (S4b-BL-271,
 * docs/ai/ai-design.md 16.8). A house is not read through its slots: its Ask document ({@link HouseDocuments}), Plan summary
 * ({@link HouseSearchService.HouseSummary}) and Extract draft ({@link HouseDraft}) are pinned byte for byte
 * (HouseAskPinsTest and its twins) and stay as they are. This holds the slots to what that code carries, with the shared
 * {@code house-ai-slot-vectors.json}: a line or field the code writes that no slot names, or a slot that nothing carries,
 * fails. The twins: the website's {@code house-ai-slots.spec.ts} and the phone's {@code HouseAiSlotsTest}.
 */
class HouseAiSlotsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode file(String name) throws IOException {
        return JSON.readTree(Files.readString(HouseAiPins.repoFile("docs/schemas/kinds/" + name)));
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static Set<String> idsOf(JsonNode map, Iterable<String> keys) {
        var out = new TreeSet<String>();
        for (var key : keys) out.addAll(strings(map.get(key)));
        return out;
    }

    private static List<String> keys(JsonNode map) {
        var out = new ArrayList<String>();
        map.fieldNames().forEachRemaining(out::add);
        return out.stream().sorted().toList();
    }

    private static List<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** The label of an Ask line: the text before the first colon, {@code Distance to <place>} read as {@code Distance to}. */
    private static String labelOf(String line) {
        var head = line.substring(0, line.indexOf(':'));
        return head.startsWith("Distance to ") ? "Distance to" : head;
    }

    @Test
    void askEveryLineOfAHouseDocumentIsInTheVectorsNamesOnlyAskSlotIdsAndTheSlotHasNoIdNoLineCarries() throws IOException {
        var ai = file("house.json").get("ai");
        var vectors = file("house-ai-slot-vectors.json");
        var viewings = List.of(new ViewingLine("v1", "h", Instant.parse("2026-10-02T05:00:00Z").toEpochMilli(),
                "SECOND", "PLANNED", "Water bill"));
        var areas = new AreaLines.All(List.of(new AreaLines.Area("a1", "Metro side", 12.9731, 77.6405, 500)),
                List.of(new AreaLines.Place("Office", 12.9784, 77.6408)),
                List.of(new AreaLines.Note("n1", "a1", null, "Quiet lanes", 5L)));
        var visits = HouseAiPins.fixtureVisits().values().stream().flatMap(List::stream).limit(1).toList();
        var text = HouseDocuments.text(HouseAiPins.richHouse(), visits, viewings, areas);

        var labels = new LinkedHashSet<String>();
        for (var line : text.split("\n")) if (line.matches("[A-Z][A-Za-z -]*[^:]*: .*")) labels.add(labelOf(line));
        assertThat(labels).containsExactlyInAnyOrderElementsOf(keys(vectors.get("ask")));
        var ids = idsOf(vectors.get("ask"), labels);
        assertThat(strings(ai.get("ask"))).containsAll(ids);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(strings(ai.get("ask")));
        assertThat(ids).doesNotContainAnyElementsOf(strings(ai.get("never")));
    }

    @Test
    void askNoLineCarriesTheContactOrThePersonsOwnOffer() {
        var text = HouseDocuments.text(HouseAiPins.richHouse(), List.<VisitDto>of());
        assertThat(text).doesNotContain("Ramesh Kumar").doesNotContain("98450").doesNotContain("example.com");
        assertThat(text).as("the pinned house carries an offer of 31000 only as the agreed price")
                .doesNotContain("myOffer").doesNotContain("Offer");
    }

    @Test
    void planEveryFieldOfASummaryIsInTheVectorsNamesOnlyPlanSlotIdsAndTheSlotHasNoIdNoFieldCarries() throws IOException {
        var ai = file("house.json").get("ai");
        var vectors = file("house-ai-slot-vectors.json");
        var structural = strings(vectors.get("planStructural"));
        var fields = names(HouseSearchService.HouseSummary.class).stream().filter(n -> !structural.contains(n)).toList();
        assertThat(fields).containsExactlyInAnyOrderElementsOf(keys(vectors.get("plan")));
        var ids = idsOf(vectors.get("plan"), fields);
        assertThat(strings(ai.get("plan"))).containsAll(ids);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(strings(ai.get("plan")));
        assertThat(ids).doesNotContainAnyElementsOf(strings(ai.get("never")));
    }

    @Test
    void extractEveryFieldOfADraftIsInTheVectorsNamesOnlyExtractSlotIdsAndWhatNoDraftCarriesIsTheNoAiParsersAlone() throws IOException {
        var ai = file("house.json").get("ai");
        var vectors = file("house-ai-slot-vectors.json");
        var structural = strings(vectors.get("extractStructural"));
        var fields = names(HouseDraft.class).stream().filter(n -> !structural.contains(n)).toList();
        // The server's draft has no area: the vectors list it as read by the no-AI parsers only.
        var served = new ArrayList<>(fields);
        assertThat(keys(vectors.get("extract"))).containsAll(fields);
        var ids = idsOf(vectors.get("extract"), served);
        assertThat(strings(ai.get("extract"))).containsAll(ids);
        var unfilled = strings(ai.get("extract")).stream().filter(id -> !ids.contains(id)).toList();
        assertThat(unfilled).containsExactlyInAnyOrderElementsOf(strings(vectors.get("noAiOnly")));
    }
}
