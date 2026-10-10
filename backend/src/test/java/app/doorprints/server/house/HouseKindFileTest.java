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

    /** Where each field's value is on today's wire: {@code was} split on ", ", or the field id. */
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

    @Test
    void everyHouseFieldHasAPlaceInTheKindFileAndNothingElseDoes() throws IOException {
        var k = kind();
        var placed = new ArrayList<String>();
        for (var f : list(k.get("core"))) placed.addAll(wire(f));
        for (var f : list(k.get("extras"))) placed.addAll(wire(f));
        var dto = names(HouseDto.class).stream().filter(n -> !NOT_FIELDS.contains(n)).toList();
        assertThat(placed.stream().filter(w -> !w.startsWith("cost.")).toList()).containsExactlyInAnyOrderElementsOf(dto);
        assertThat(placed.stream().filter(w -> w.startsWith("cost.")).toList())
                .containsExactlyElementsOf(names(HouseCost.class).stream().map(n -> "cost." + n).toList());
    }

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

    /** The AI slot {@code extract} names what the listing extract returns today, in the schema's order. */
    @Test
    void theExtractSlotIsTheListingExtractsFields() throws IOException {
        var k = kind();
        var byId = new HashMap<String, JsonNode>();
        for (var f : list(k.get("core"))) byId.put(f.get("id").asText(), f);
        for (var f : list(k.get("extras"))) byId.put(f.get("id").asText(), f);
        var extract = list(k.get("ai").get("extract")).stream().flatMap(id -> wire(byId.get(id.asText())).stream()).toList();
        assertThat(extract).containsExactlyElementsOf(names(RawListing.class));
    }
}
