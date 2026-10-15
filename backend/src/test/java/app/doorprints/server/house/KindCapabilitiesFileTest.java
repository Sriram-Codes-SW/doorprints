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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kind files' {@code capabilities} (docs/03 ADR-36 section 18.19), the server's half. The server keeps no copy of
 * them and reads nothing new at run time; this reads {@code docs/schemas/kinds/capabilities-vectors.json} and the schema so
 * that the owner's constraint holds on this stack too: India's boundaries apply to every kind (ADR-22), so a kind file has no
 * map configuration. Read from the working directory like {@link HouseKindFileTest}.
 */
class KindCapabilitiesFileTest {

    private static final Path KINDS = Path.of("..", "docs", "schemas", "kinds");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode read(String file) throws IOException {
        return JSON.readTree(KINDS.resolve(file).toFile());
    }

    private static List<String> strings(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void theSchemaHasNoMapConfigurationAndMapIsABooleanOnly() throws IOException {
        var schema = read("kind.schema.json");
        var vectors = read("capabilities-vectors.json");
        var forbidden = strings(vectors.get("forbiddenMapKeys"));
        assertThat(forbidden).hasSize(14).contains("tileUrl", "boundary", "geojson", "mapStyleUrl", "geo");
        var capabilities = schema.get("properties").get("capabilities");
        var shapes = List.of(schema, schema.get("$defs").get("section"), capabilities,
                capabilities.get("properties").get("identity"));
        for (var shape : shapes) {
            assertThat(shape.get("additionalProperties").asBoolean(true)).isFalse();
            for (var key : forbidden) assertThat(shape.get("properties").has(key)).as(key).isFalse();
        }
        assertThat(capabilities.get("properties").get("map").get("type").asText()).isEqualTo("boolean");
    }

    @Test
    void theBuiltInKindFilesHoldNoMapKeyAndTheirCapabilitiesAreTheVectors() throws IOException {
        var vectors = read("capabilities-vectors.json");
        var forbidden = Set.copyOf(strings(vectors.get("forbiddenMapKeys")));
        for (var file : List.of("house.json", "school.json")) {
            var kind = read(file);
            assertThat(kind.fieldNames()).toIterable().as(file).doesNotContainAnyElementsOf(forbidden);
            var caps = kind.get("capabilities");
            assertThat(caps.fieldNames()).toIterable().as(file).doesNotContainAnyElementsOf(forbidden);
            assertThat(caps.get("map").isBoolean()).as(file).isTrue();
        }
        var house = read("house.json").get("capabilities");
        assertThat(house.get("hunt").get("alertRadiusM").asInt()).isEqualTo(vectors.get("today").get("huntAlertRadiusM").asInt());
        assertThat(house.get("compare").get("criteria").size()).isEqualTo(10);
    }

    /** Every invalid case in the vectors is parseable JSON whose capabilities the strict readers of the other stacks refuse. */
    @Test
    void theInvalidCasesAreWellFormedJson() throws IOException {
        var invalid = read("capabilities-vectors.json").get("invalid");
        assertThat(invalid.size()).isGreaterThan(55);
        for (var c : invalid) assertThat(JSON.readTree(c.get("json").asText()).isObject()).as(c.get("name").asText()).isTrue();
    }

    /** Tripwire: the map-rules files know nothing of a candidate's kind. */
    @Test
    void theMapRulesFilesDoNotReadAKind() throws IOException {
        var symbols = Pattern.compile("capabilitiesOf|KindCapabilities|kind-capabilities|DevKinds|BuiltInKind|KindLabels|KindSetting|LocalSpace|\\bSpace\\b|kindId|candidate\\.kind|house\\.kind");
        var root = Path.of("..");
        for (var f : List.of("web/src/app/shared/map-style-url.ts", "web/src/app/shared/india-boundaries.ts",
                "android/ui/src/commonMain/kotlin/app/doorprints/ui/IndiaViewRules.kt",
                "android/ui/src/commonMain/kotlin/app/doorprints/ui/MapStyleJson.kt",
                "android/ui/src/commonMain/kotlin/app/doorprints/ui/IndiaViewOps.kt")) {
            assertThat(symbols.matcher(java.nio.file.Files.readString(root.resolve(f))).find()).as(f).isFalse();
        }
    }
}
