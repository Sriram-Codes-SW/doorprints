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

package app.doorprints.server.ai.extract;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's extraction schema says the same as the phones' and the website's: every field description of
 * {@link RawListing} equals the one in the shared vectors (docs/ai/evals/parity-vectors.json, "schemaDialect", the
 * "listing" schema), which the Kotlin and TypeScript ports are checked against. S4b-BL-187: the street and locality
 * descriptions tell the model that the locality is never the city alone.
 */
class RawListingSchemaTest {

    private static final Path FILE = Path.of("..", "docs", "ai", "evals", "parity-vectors.json");

    @Test
    void everyFieldDescriptionIsTheOneInTheSharedVectors() throws Exception {
        JsonNode listing = null;
        for (var c : new ObjectMapper().readTree(FILE.toFile()).get("schemaDialect")) {
            if ("listing".equals(c.get("name").asText())) {
                listing = c.get("gemini").get("properties");
            }
        }
        assertThat(listing).isNotNull();
        var components = RawListing.class.getRecordComponents();
        assertThat(components).hasSize(listing.size());
        for (var component : components) {
            var described = described(component.getName());
            assertThat(described).as(component.getName()).isNotNull();
            assertThat(listing.get(component.getName()).get("description").asText())
                    .as(component.getName()).isEqualTo(described.value());
        }
    }

    @Test
    void theLocalityIsTheAreaAndNotTheCity() {
        assertThat(described("locality").value())
                .contains("never the city or district alone").contains("repeat the road here");
    }

    /** Where the compiler puts the annotation of a record component depends on its targets: look at the field. */
    private static JsonPropertyDescription described(String name) {
        try {
            return RawListing.class.getDeclaredField(name).getAnnotation(JsonPropertyDescription.class);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
