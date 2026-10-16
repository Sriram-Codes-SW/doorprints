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
package app.doorprints.server.ai.web;

import app.doorprints.server.ai.agent.PlanModels.PlanRequest;
import app.doorprints.server.ai.rag.AskModels.AskRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4b-BL-290 (T-I51): the {@code kind} of {@code POST /api/ai/ask} and {@code /plan-visits} on the wire. Absent reads as
 * today (null); a well-formed id passes the validator and then meets the allow-list in the services
 * ({@code RagServiceKindScopeTest}, {@code KindScopedPlanTest}); anything else stops at the validator or at the parser, so
 * a hostile value cannot even reach a service.
 */
class AskPlanKindRequestTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static Validator validator;

    @BeforeAll
    static void validator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static final List<String> HOSTILE = List.of("house OR 1=1", "house' OR '1'='1", "school; DROP TABLE houses",
            "__proto__", "constructor.prototype", "../school", "school\n", "school\r\n", " school", "school ", "SCHOOL", "School",
            "scho\u0000ol", "schоol", "a".repeat(33), "a".repeat(10_000), "", " ", "x", "1school", "*", "%", "$.kind",
            "{\"$ne\":null}", "school\" || \"1\" == \"1");

    private static String body(String kindJson) {
        return "{\"question\":\"Which one?\"" + (kindJson == null ? "" : ",\"kind\":" + kindJson) + "}";
    }

    private static String planBody(String kindJson) {
        return "{\"question\":\"Plan\",\"startLat\":12.97,\"startLon\":77.59" + (kindJson == null ? "" : ",\"kind\":" + kindJson) + "}";
    }

    @Test
    void absentOrNullKindIsNoKindAndWellFormedOnesPass() {
        assertThat(JSON.readValue(body(null), AskRequest.class).kind()).isNull();
        assertThat(JSON.readValue(body("null"), AskRequest.class).kind()).isNull();
        assertThat(JSON.readValue(planBody(null), PlanRequest.class).kind()).isNull();
        assertThat(JSON.readValue(planBody("null"), PlanRequest.class).kind()).isNull();
        for (var ok : List.of("house", "school", "clinic", "phone_2", "a1", "a".repeat(32))) {
            var ask = JSON.readValue(body(JSON.writeValueAsString(ok)), AskRequest.class);
            var plan = JSON.readValue(planBody(JSON.writeValueAsString(ok)), PlanRequest.class);
            assertThat(ask.kind()).isEqualTo(ok);
            assertThat(plan.kind()).isEqualTo(ok);
            assertThat(validator.validate(ask)).as(ok).isEmpty();
            assertThat(validator.validate(plan)).as(ok).isEmpty();
        }
    }

    @Test
    void theOlderCallsKeepTheirShape() {
        assertThat(new AskRequest("q", null).kind()).isNull();
        assertThat(new PlanRequest("q", 1.0, 2.0, null).kind()).isNull();
        assertThat(validator.validate(new AskRequest("q", null))).isEmpty();
        assertThat(validator.validate(new PlanRequest("q", 1.0, 2.0, null))).isEmpty();
    }

    @Test
    void aMalformedKindIsRefusedByTheValidatorOnBothCalls() {
        for (var kind : HOSTILE) {
            var ask = JSON.readValue(body(JSON.writeValueAsString(kind)), AskRequest.class);
            var plan = JSON.readValue(planBody(JSON.writeValueAsString(kind)), PlanRequest.class);
            assertThat(validator.validate(ask)).as("ask %s", kind).extracting(v -> v.getPropertyPath().toString()).containsExactly("kind");
            assertThat(validator.validate(plan)).as("plan %s", kind).extracting(v -> v.getPropertyPath().toString()).containsExactly("kind");
        }
    }

    @Test
    void aKindThatIsNotAStringIsRefusedByTheParserOrReadAsItsTextAndRefusedByTheList() {
        var kinds = app.doorprints.server.ai.kind.AiKinds.schools(true);
        for (var json : List.of("[\"school\"]", "{\"id\":\"school\"}", "true", "5", "5.5")) {
            for (var read : List.<java.util.function.Supplier<String>>of(
                    () -> JSON.readValue(body(json), AskRequest.class).kind(),
                    () -> JSON.readValue(planBody(json), PlanRequest.class).kind())) {
                String kind;
                try {
                    kind = read.get();
                } catch (RuntimeException refused) {
                    continue; // a structure is not a string
                }
                // A scalar is read as its text; it is no id this server lists.
                assertThat(kind).as(json).isEqualTo(json);
                assertThat(kinds.isAvailable(kind)).as(json).isFalse();
            }
        }
    }

    @Test
    void theKindOfAStatusIsLeftOutForAHouseOnlyServer() {
        var houseOnly = new AiStatusController.AiStatus(true, false, "chat", "embed", false);
        assertThat(JSON.writeValueAsString(houseOnly))
                .isEqualTo("{\"enabled\":true,\"mcpEnabled\":false,\"chatModel\":\"chat\",\"embeddingModel\":\"embed\",\"offForDevice\":false}");
        var withSchools = new AiStatusController.AiStatus(true, false, "chat", "embed", false, List.of("house", "school"));
        assertThat(JSON.writeValueAsString(withSchools)).endsWith(",\"offForDevice\":false,\"kinds\":[\"house\",\"school\"]}");
    }
}
