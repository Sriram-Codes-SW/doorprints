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

package app.doorprints.server.ai.agent;

import app.doorprints.server.ai.agent.HouseSearchService.HouseSummary;
import app.doorprints.server.ai.agent.PlanModels.AgentPlan;
import app.doorprints.server.ai.agent.PlanModels.AgentStop;
import app.doorprints.server.house.HouseStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan's fallback route against the shared vectors ({@code docs/ai/evals/parity-vectors.json}, {@code planFallback},
 * S4b-BL-199), which the website's {@code assemblePlan} and the phones' {@code PlanChecks.assemble} pass too: the houses
 * in the running within 50 km of the start, nearest first, at most the cap, then nearest-neighbour order.
 */
class VisitPlannerFallbackVectorsTest {

    @Test
    void theFallbackRouteIsTheSharedVectorsAnswer() throws Exception {
        var cases = new ObjectMapper().readTree(Path.of("..", "docs", "ai", "evals", "parity-vectors.json").toFile())
                .get("planFallback");
        assertThat(cases).hasSize(6);
        for (JsonNode c : cases) {
            var note = c.get("note").asText();
            var seen = new LinkedHashMap<UUID, HouseSummary>();
            for (JsonNode h : c.get("houses")) {
                var id = UUID.fromString(h.get("id").asText());
                var status = h.get("status").isNull() ? null : HouseStatus.valueOf(h.get("status").asText());
                seen.put(id, new HouseSummary(id, "H", "L", null, status, null, null, null, null,
                        h.get("lat").asDouble(), h.get("lon").asDouble(), null));
            }
            AgentPlan plan = null;
            if (c.get("planStops").isArray()) {
                var stops = new ArrayList<AgentStop>();
                for (JsonNode s : c.get("planStops")) stops.add(new AgentStop(s.asText(), "x"));
                plan = new AgentPlan(null, stops);
            }
            var start = c.get("start");
            var res = VisitPlannerService.assemble(plan, seen, List.of(), start.get(0).asDouble(), start.get(1).asDouble(),
                    c.get("maxStops").asInt());
            var expected = c.get("expected");
            var ids = new ArrayList<String>();
            expected.get("ids").forEach(i -> ids.add(i.asText()));
            assertThat(res.stops()).as(note).extracting(s -> s.houseId().toString()).containsExactlyElementsOf(ids);
            assertThat(res.fallback()).as(note).isEqualTo(expected.get("fallback").asBoolean());
            assertThat(res.summary()).as(note).isEqualTo(expected.get("summary").asText());
        }
    }
}
