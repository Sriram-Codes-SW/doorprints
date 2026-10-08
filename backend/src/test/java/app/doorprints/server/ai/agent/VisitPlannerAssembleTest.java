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
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VisitPlannerAssembleTest {

    private static HouseSummary house(String label, double lat, HouseStatus status) {
        return new HouseSummary(UUID.randomUUID(), label, "Indiranagar", null, status, 30000L, "RENT", 2, 4,
                lat, 77.6400, null);
    }

    private final HouseSummary a = house("A", 12.9720, HouseStatus.SHORTLISTED);
    private final HouseSummary b = house("B", 12.9740, HouseStatus.NEW);
    private final HouseSummary rejected = house("R", 12.9730, HouseStatus.REJECTED);
    private final Map<UUID, HouseSummary> seen = new LinkedHashMap<>(Map.of(a.id(), a, b.id(), b, rejected.id(), rejected));

    @Test
    void keepsModelOrderDropsHallucinatedAndDuplicateIds() {
        var plan = new AgentPlan("Two stops", List.of(
                new AgentStop(b.id().toString(), "new listing"),
                new AgentStop(UUID.randomUUID().toString(), "invented"),
                new AgentStop("not-a-uuid", "garbage"),
                new AgentStop(a.id().toString(), "shortlisted"),
                new AgentStop(b.id().toString(), "duplicate")));
        var res = VisitPlannerService.assemble(plan, seen, List.of("searchHouses"), 12.9716, 77.6400, 8);
        assertThat(res.fallback()).isFalse();
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(b.id(), a.id());
        assertThat(res.stops().get(0).order()).isEqualTo(1);
        assertThat(res.stops().get(0).reason()).isEqualTo("new listing");
        assertThat(res.totalMeters()).isEqualTo(res.stops().stream().mapToLong(PlanModels.PlannedStop::legMeters).sum());
        assertThat(res.toolCalls()).containsExactly("searchHouses");
    }

    @Test
    void summaryAndReasonsLoseLinksAndForeignAddressesButKeepOnesFromTheHouses() {
        var gate = house("Gate https://example.com/g", 12.9720, HouseStatus.NEW);
        var plan = new AgentPlan("Go ![x](https://evil.example/p.png) see https://evil.example/s", List.of(new AgentStop(
                gate.id().toString(), "Close, [photos](https://evil.example/r) and https://example.com/g, https://evil.example/q")));
        var res = VisitPlannerService.assemble(plan, new LinkedHashMap<>(Map.of(gate.id(), gate)), List.of(), 12.9716, 77.6400, 8);
        assertThat(res.summary()).isEqualTo("Go x see [link removed]");
        assertThat(res.stops().get(0).reason()).isEqualTo("Close, photos and https://example.com/g, [link removed]");
    }

    @Test
    void capsNumberOfStops() {
        var plan = new AgentPlan("x", List.of(new AgentStop(a.id().toString(), ""), new AgentStop(b.id().toString(), "")));
        var res = VisitPlannerService.assemble(plan, seen, List.of(), 12.9716, 77.6400, 1);
        assertThat(res.stops()).hasSize(1);
    }

    @Test
    void fallsBackToNearestNeighbourWithoutRejectedWhenNoPlan() {
        var res = VisitPlannerService.assemble(null, seen, List.of("searchHouses"), 12.9716, 77.6400, 8);
        assertThat(res.fallback()).isTrue();
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(a.id(), b.id());
    }

    @Test
    void fallsBackWithoutNotChosenHousesEitherAndThePromptSkipsThem() {
        var notChosen = house("N", 12.9718, HouseStatus.NOT_CHOSEN);
        var taken = house("T", 12.9750, HouseStatus.TAKEN);
        var mixed = new LinkedHashMap<UUID, HouseSummary>();
        mixed.put(notChosen.id(), notChosen);
        mixed.put(rejected.id(), rejected);
        mixed.put(taken.id(), taken);
        var res = VisitPlannerService.assemble(null, mixed, List.of("searchHouses"), 12.9716, 77.6400, 8);
        assertThat(res.fallback()).isTrue();
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(taken.id());
        assertThat(VisitPlannerService.systemPrompt(12.9716, 77.64, 5)).contains("skip REJECTED and NOT_CHOSEN unless asked.");
    }

    @Test
    void emptyPlanStaysEmpty() {
        var res = VisitPlannerService.assemble(new AgentPlan("Nothing matches", List.of()), seen, List.of(), 0, 0, 8);
        assertThat(res.fallback()).isFalse();
        assertThat(res.stops()).isEmpty();
        assertThat(res.summary()).isEqualTo("Nothing matches");
    }
}
