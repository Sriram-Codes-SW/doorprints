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

package app.doorprints.server.house;

import app.doorprints.server.ai.extract.RawListing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kind file for houses, {@code docs/schemas/kinds/house.json} (docs/03 ADR-36 section 18, S4b-BL-205), against
 * the server's own classes: the five status names, every field of {@link HouseDto} and {@link HouseCost} and their
 * caps, and the listing extract's fields ({@link RawListing}) as the file's AI slot {@code extract}. The website
 * (house-kind-file.spec.ts) validates the file against {@code kind.schema.json}; Android's HouseKindFileTest checks
 * the phones' side. Read from the working directory like {@code RawListingSchemaTest}.
 */
class HouseKindFileTest {

    private static final Path FILE = Path.of("..", "docs", "schemas", "kinds", "house.json");
    /** Server stamps, nested lists and the search distance: not fields a kind places. */
    private static final Set<String> NOT_FIELDS = Set.of("id", "cost", "rooms", "answers", "moveIn", "checklist",
            "createdAt", "updatedAt", "deleted", "syncVersion", "distanceMeters");

    private static JsonNode kind() throws IOException {
        return new ObjectMapper().readTree(FILE.toFile());
    }

    private static List<JsonNode> list(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).toList();
    }

    /**
     * Where each field's value is in the backup format and the listing extract of today: {@code was} split on ", ", or
     * the field id. The sync API has the new shape already (S4b-BL-206); the backup follows with the apps.
     */
    private static List<String> wire(JsonNode field) {
        var was = field.get("was");
        return Arrays.asList((was == null ? field.get("id").asText() : was.asText()).split(", "));
    }

    private static List<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void theFiveStatusNamesAreTheServers() throws IOException {
        assertThat(list(kind().get("statuses")).stream().map(s -> s.get("id").asText()).toList())
                .containsExactly(Arrays.stream(HouseStatus.values()).map(Enum::name).toArray(String[]::new));
    }

    /**
     * The sync API's shape since the format reset (S4b-BL-206): a core field sits under its own id ({@code location}
     * as {@code lat} and {@code lon}), every extra under {@code extras.<id>} in the kind file's order, and the cost
     * fields the AI documents read ({@link HouseCost}) are the extras after the BHK, the area and the floor.
     */
    @Test
    void everyHouseFieldHasAPlaceInTheKindFileAndNothingElseDoes() throws IOException {
        var k = kind();
        var core = new ArrayList<String>();
        for (var f : list(k.get("core"))) {
            var id = f.get("id").asText();
            if ("location".equals(id)) core.addAll(List.of("lat", "lon"));
            else core.add(id);
        }
        var dto = names(HouseDto.class).stream().filter(n -> !NOT_FIELDS.contains(n) && !WIRE_ONLY.contains(n)).toList();
        assertThat(core).containsExactlyInAnyOrderElementsOf(dto);
        var extras = list(k.get("extras")).stream().map(f -> f.get("id").asText()).toList();
        assertThat(extras).containsExactlyElementsOf(CandidateExtras.KNOWN);
        assertThat(extras.subList(3, extras.size())).containsExactlyElementsOf(names(HouseCost.class));
    }

    /** The candidate's own kind id and its extras object: on the wire, but not fields the kind file places. */
    private static final Set<String> WIRE_ONLY = Set.of("kind", "extras");

    @Test
    void theCapsAreTheServers() throws IOException {
        for (var f : list(kind().get("extras"))) {
            var id = f.get("id").asText();
            if ("money".equals(f.get("type").asText())) assertThat(f.get("max").asLong()).as(id).isEqualTo(HouseCost.MAX_RUPEES);
            if (id.endsWith("Months")) assertThat(f.get("max").asInt()).as(id).isEqualTo(HouseCost.MAX_MONTHS);
            if ("floor".equals(id)) {
                assertThat(f.get("min").asInt()).isEqualTo(House.MIN_FLOOR);
                assertThat(f.get("max").asInt()).isEqualTo(House.MAX_FLOOR);
            }
        }
    }

    /**
     * The extract's outputs that fill no field of the candidate: {@code amenities} is a list on the draft only (shown as
     * a hint, never stored as a house value). Since S4b-BL-271 the kind file's {@code extract} slot names it as well
     * (a slot says what the extract carries), and this test skips it when it maps the slot to the model's fields.
     */
    private static final Set<String> DRAFT_ONLY = Set.of("amenities");

    /** Slot ids the server's model does not fill: only the no-AI parsers of the website and the phones read the area. */
    private static final Set<String> NO_AI_ONLY = Set.of("areaSqft");

    /** The AI slot {@code extract} names the candidate fields the listing extract fills today, in the schema's order. */
    @Test
    void theExtractSlotIsTheListingExtractsFields() throws IOException {
        var k = kind();
        var byId = new HashMap<String, JsonNode>();
        for (var f : list(k.get("core"))) byId.put(f.get("id").asText(), f);
        for (var f : list(k.get("extras"))) byId.put(f.get("id").asText(), f);
        var extract = list(k.get("ai").get("extract")).stream().map(JsonNode::asText)
                .filter(id -> !DRAFT_ONLY.contains(id) && !NO_AI_ONLY.contains(id))
                .flatMap(id -> wire(byId.get(id)).stream()).toList();
        assertThat(list(k.get("ai").get("extract")).stream().map(JsonNode::asText).toList())
                .containsAll(DRAFT_ONLY).containsAll(NO_AI_ONLY);
        assertThat(names(RawListing.class)).containsAll(DRAFT_ONLY);
        assertThat(extract).containsExactlyElementsOf(
                names(RawListing.class).stream().filter(n -> !DRAFT_ONLY.contains(n)).toList());
    }
}
