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

import app.doorprints.server.ai.HouseAiPins;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.ai.web.AiController;
import app.doorprints.server.common.BadRequestException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kind-aware Extract on the server (kinds PR 7, S4b-BL-211; docs/03 ADR-36 §18.11): the school's prompt, schema and
 * drafts of {@code docs/schemas/kinds/kind-ai-vectors.json}, and the request: a house's body is as before, a school's
 * needs the server's developer flag, any other kind is refused rather than read as a house.
 */
class KindExtractVectorsTest {

    private static final JsonNode V = HouseAiPins.kindAiVectors().get("extract");
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    void theSchoolsPromptIsTheVectorsWordForWord() {
        var built = ExtractionPrompts.build(AiKinds.SCHOOL, V.get("listing").asString(), V.get("nonce").asString());
        assertThat(built.system()).isEqualTo(V.get("system").asString());
        assertThat(built.user()).isEqualTo(V.get("user").asString());
    }

    @Test
    void theSchoolsSchemaHasTheSlotsFieldsWithTheVectorsDescriptions() {
        var schema = JSON.readTree(new BeanOutputConverter<>(RawSchoolListing.class).getJsonSchema()).get("properties");
        var names = new ArrayList<String>();
        schema.propertyNames().forEach(names::add);
        var expected = new ArrayList<String>();
        V.get("schema").propertyNames().forEach(expected::add);
        // Spring AI's schema generator writes the properties in name order; the vectors list them in the slot's.
        assertThat(expected).isEqualTo(AiKinds.SCHOOL.extract());
        assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
        for (var name : expected) {
            assertThat(schema.get(name).get("description").asString()).as(name)
                    .isEqualTo(V.get("schema").get(name).asString());
        }
    }

    @Test
    void everyRawAnswerGivesTheVectorsDraft() {
        for (var c : V.get("sanitize")) {
            var raw = c.get("raw").isNull() ? null : JSON.treeToValue(c.get("raw"), RawSchoolListing.class);
            var draft = KindDraftSanitizer.sanitize(AiKinds.SCHOOL, KindDraftSanitizer.fields(raw), c.get("source").asString());
            assertThat(JSON.readTree(JSON.writeValueAsString(draft))).as(c.get("name").asString()).isEqualTo(c.get("expected"));
        }
    }

    @Test
    void aHouseDraftHasNoExtrasKeyAndAHousesRequestIsTheTextAlone() throws Exception {
        var house = DraftSanitizer.sanitize(null, "");
        assertThat(JSON.valueToTree(house).has("extras")).isFalse();
        var request = JSON.readValue("{\"text\":\"2BHK\"}", AiController.ExtractRequest.class);
        assertThat(request.kind()).isNull();
        assertThat(request).isEqualTo(new AiController.ExtractRequest("2BHK"));
        try (var f = Validation.buildDefaultValidatorFactory()) {
            assertThat(f.getValidator().validate(new AiController.ExtractRequest("x", "School!"))).hasSize(1);
        }
    }

    /** A kind other than the house is never read as one: with the flag off, or a kind with no extract, a 400. */
    @Test
    void aSchoolNeedsTheFlagAndAnyOtherKindIsRefused() {
        var off = new ListingExtractionService(null, AiProperties.defaults());
        assertThatThrownBy(() -> off.extract("Pine Hill School", "school")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("school");
        var on = new ListingExtractionService(null, AiProperties.defaults(), new AiKinds(true));
        for (var kind : List.of("clinic", "pg")) {
            assertThatThrownBy(() -> on.extract("Lake Clinic", kind)).isInstanceOf(BadRequestException.class);
        }
        assertThatThrownBy(() -> on.extract(" ", "school")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> on.extract("x".repeat(8001), "school")).isInstanceOf(BadRequestException.class);
    }
}
