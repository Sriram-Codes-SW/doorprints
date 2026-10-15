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

import app.doorprints.server.ai.PromptSafety;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.common.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The listing extract of a kind that is only a kind file (kinds PR 28, docs/03 §18.22, TC-U-274): {@code dojo.json} of the test
 * class path. The prompt, the schema the model is told, the reading of its answer and the draft are written by the code that
 * writes the house's and the school's, whose own are proved byte for byte by the house pins and the shared vectors.
 */
class ServerKindExtractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AiKinds DOJOS = new AiKinds(List.of("house", "dojo"));
    private static final String TEXT = "Lotus Dojo, Lake Road, Adyar. Karate for kids, Mon Wed 6 pm, first class free. "
            + "Fee 1,200 per month. Call 98400 12345. https://example.com/lotus";

    @Test
    void theExtractPromptIsWrittenFromTheKindsWords() {
        var built = ExtractionPrompts.build(DOJOS.of("dojo"), TEXT, "n0nce");
        assertThat(built.system()).startsWith("You extract dojo details from a single listing")
                .contains("<listing-n0nce>").contains(PromptSafety.NEUTRAL_RULE)
                .contains("- discipline: Discipline, one of KARATE, JUDO, YOGA, OTHER.")
                .contains("- trialClass: Free trial class, \"yes\" or \"no\" only when the listing says so.")
                .contains("- schedule: Class timings, as written, at most 60 characters.")
                .contains("- monthlyFee: Fee per month, copied as written")
                .doesNotContain("%").doesNotContain("classSize").doesNotContain("openSince").doesNotContain("house");
        assertThat(built.user()).isEqualTo("Extract the listing below.\n\n" + PromptSafety.wrap("listing", "n0nce", TEXT));
    }

    @Test
    void theSchemaHasTheSlotsFieldsInNameOrderAndOnlyThose() {
        var converter = new KindOutputConverter(DOJOS.of("dojo"));
        var schema = JSON.readTree(converter.getJsonSchema());
        assertThat(schema.get("properties").propertyNames()).containsExactly("address", "contactName",
                "contactPhone", "discipline", "label", "listingUrl", "locality", "monthlyFee", "notes", "schedule", "street",
                "trialClass");
        assertThat(schema.get("required")).hasSize(12);
        assertThat(schema.get("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.get("properties").get("label").get("description").asString()).isEqualTo("The dojo's name, at most 8 words");
        assertThat(converter.getFormat()).contains(converter.getJsonSchema()).startsWith("Your response should be in JSON format.");
    }

    @Test
    void theAnswerIsReadFieldByFieldAndNothingElse() {
        var converter = new KindOutputConverter(DOJOS.of("dojo"));
        var raw = converter.convert("```json\n{\"label\":\"Lotus Dojo\",\"discipline\":\"judo\",\"monthlyFee\":1200,"
                + "\"trialClass\":true,\"classSize\":99,\"contactPhone\":null,\"extra\":{\"x\":1}}\n```");
        assertThat(raw.fields()).containsEntry("label", "Lotus Dojo").containsEntry("monthlyFee", "1200")
                .containsEntry("trialClass", "true").doesNotContainKey("classSize").doesNotContainKey("contactPhone");
        assertThat(raw.amenities()).isNull();
        assertThat(converter.convert("null")).isNull();
        assertThatThrownBy(() -> converter.convert("{\"label\":{\"a\":1}}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("{\"label\":[\"a\"]}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("[1]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert("not json")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void theDraftIsTheSanitisedAnswer() {
        var raw = new LinkedHashMap<String, String>();
        raw.put("address", "Lake Road, Adyar");
        raw.put("locality", "Adyar");
        raw.put("contactPhone", "99999 00000");
        raw.put("listingUrl", "https://evil.example/phish");
        raw.put("discipline", "judo");
        raw.put("schedule", "Mon Wed 6 pm");
        raw.put("trialClass", "Yes");
        raw.put("monthlyFee", "1,200");
        raw.put("classSize", "40");
        var draft = KindDraftSanitizer.sanitize(DOJOS.of("dojo"), raw, TEXT);
        assertThat(draft.label()).isEqualTo("Dojo in Adyar");
        assertThat(draft.contactPhone()).as("a number the text does not hold").isNull();
        assertThat(draft.listingUrl()).isNull();
        assertThat(draft.price()).isNull();
        assertThat(draft.bedrooms()).isNull();
        assertThat(draft.amenities()).isEmpty();
        assertThat(draft.extras()).containsOnlyKeys("discipline", "schedule", "trialClass", "monthlyFee");
        assertThat(draft.extras()).containsEntry("discipline", "JUDO").containsEntry("trialClass", true)
                .containsEntry("monthlyFee", java.util.Map.of("amount", 1200L, "cadence", "month"));
        assertThat(draft.warnings()).contains("label: generated because the model returned none");
        assertThat(JSON.writeValueAsString(draft)).doesNotContain("classSize");
    }

    @Test
    void theServiceOffersTheExtractOnlyWhileTheKindIsListed() {
        var props = AiProperties.defaults();
        var on = new ListingExtractionService(null, props, DOJOS);
        assertThatThrownBy(() -> on.extract(" ", "dojo")).isInstanceOf(BadRequestException.class)
                .hasMessage("text must not be blank");
        assertThatThrownBy(() -> on.extract("x".repeat(props.maxInputChars() + 1), "dojo")).isInstanceOf(BadRequestException.class);
        var off = new ListingExtractionService(null, props, AiKinds.OFF);
        assertThatThrownBy(() -> off.extract(TEXT, "dojo")).hasMessage("kind dojo is not available");
    }

    @Test
    void theHouseGoesThroughTheSameCodeAndIsWhatItWas() throws Exception {
        // The converter's schema and format are those Spring AI wrote for the records the server no longer has.
        var house = new KindOutputConverter(AiKinds.HOUSE);
        var oracle = new BeanOutputConverter<>(RawListing.class);
        assertThat(house.getJsonSchema()).isEqualTo(oracle.getJsonSchema());
        assertThat(house.getFormat()).isEqualTo(oracle.getFormat());
        var school = new KindOutputConverter(AiKinds.SCHOOL);
        var schoolOracle = new BeanOutputConverter<>(RawSchoolListing.class);
        assertThat(school.getJsonSchema()).isEqualTo(schoolOracle.getJsonSchema());
        assertThat(school.getFormat()).isEqualTo(schoolOracle.getFormat());
        for (var answer : List.of(
                "{\"label\":\"2BHK\",\"price\":\"25,000\",\"priceType\":\"RENT\",\"bedrooms\":2,\"amenities\":[\"lift\",\"parking\"]}",
                "```json\n{\"label\":null,\"price\":25000,\"unknownKey\":1,\"amenities\":null}\n```",
                "<think>hmm</think>{\"notes\":\"x\"}", "{}", "null")) {
            var expected = oracle.convert(answer);
            var got = house.convert(answer);
            if (expected == null) {
                assertThat(got).isNull();
                continue;
            }
            assertThat(got.fields().get("label")).isEqualTo(expected.label());
            assertThat(got.fields().get("price")).isEqualTo(expected.price());
            assertThat(got.fields().get("bedrooms")).isEqualTo(expected.bedrooms());
            assertThat(got.fields().get("notes")).isEqualTo(expected.notes());
            assertThat(got.amenities()).isEqualTo(expected.amenities());
        }
        for (var bad : List.of("{\"label\":{\"a\":1}}", "{\"amenities\":\"lift\"}", "[\"x\"]")) {
            assertThatThrownBy(() -> oracle.convert(bad)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> house.convert(bad)).isInstanceOf(RuntimeException.class);
        }
    }
}
