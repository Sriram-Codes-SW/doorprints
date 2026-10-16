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

    private static HouseSummary at(String id, String label, double lat, double lon, HouseStatus status) {
        return new HouseSummary(UUID.fromString(id), label, "L", null, status, 30000L, "RENT", 2, 4, lat, lon, null);
    }

    private static final String FAR_SUMMARY = "No saved houses within reach of your start point were found.";
    private static final double PUNE_LAT = 18.5074, PUNE_LON = 73.8077;

    /** Plan-08: the Bengaluru houses were seen first, the Pune flats after; the start is in Pune. */
    private static Map<UUID, HouseSummary> bengaluruThenPune() {
        var m = new LinkedHashMap<UUID, HouseSummary>();
        for (var h : List.of(
                at("aaaaaaaa-0000-4000-8000-000000000002", "B2", 12.9352, 77.6245, HouseStatus.SHORTLISTED),
                at("aaaaaaaa-0000-4000-8000-000000000001", "B1", 12.9716, 77.6400, HouseStatus.NEW),
                at("aaaaaaaa-0000-4000-8000-000000000005", "B5", 12.9141, 77.6101, HouseStatus.NEW),
                at("aaaaaaaa-0000-4000-8000-000000000003", "B3", 12.9279, 77.6271, HouseStatus.SHORTLISTED),
                at("bbbbbbbb-0000-4000-8000-000000000012", "P12", 18.5204, 73.8567, HouseStatus.NEW),
                at("bbbbbbbb-0000-4000-8000-000000000011", "P11", 18.5089, 73.8070, HouseStatus.SHORTLISTED))) {
            m.put(h.id(), h);
        }
        return m;
    }

    @Test
    void theFallbackOffersTheHousesNearTheStartNotTheFirstSeen() {
        var res = VisitPlannerService.assemble(null, bengaluruThenPune(), List.of("searchHouses"), PUNE_LAT, PUNE_LON, 4);
        assertThat(res.fallback()).isTrue();
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::label).containsExactly("P11", "P12");
        assertThat(res.summary()).startsWith("The assistant could not finish a plan");
    }

    @Test
    void theFallbackWithOnlyFarHousesIsEmptyAndSaysSo() {
        var res = VisitPlannerService.assemble(null, bengaluruThenPune(), List.of("searchHouses"), 28.6139, 77.2090, 4);
        assertThat(res.fallback()).isTrue();
        assertThat(res.stops()).isEmpty();
        assertThat(res.summary()).isEqualTo(FAR_SUMMARY);
        assertThat(res.totalMeters()).isZero();
    }

    @Test
    void theFallbackRangeIsFiftyKilometres() {
        // 0.4 degrees of latitude is about 44.5 km, 0.5 about 55.6 km.
        var inside = at("cccccccc-0000-4000-8000-000000000001", "in", PUNE_LAT + 0.4, PUNE_LON, HouseStatus.NEW);
        var outside = at("cccccccc-0000-4000-8000-000000000002", "out", PUNE_LAT + 0.5, PUNE_LON, HouseStatus.NEW);
        var m = new LinkedHashMap<UUID, HouseSummary>();
        m.put(outside.id(), outside);
        m.put(inside.id(), inside);
        var res = VisitPlannerService.assemble(null, m, List.of(), PUNE_LAT, PUNE_LON, 8);
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::label).containsExactly("in");
    }

    @Test
    void theFallbackSkipsAHouseWithoutUsableCoordinates() {
        var m = bengaluruThenPune();
        var nan = at("dddddddd-0000-4000-8000-000000000001", "nan", Double.NaN, PUNE_LON, HouseStatus.NEW);
        var inf = at("dddddddd-0000-4000-8000-000000000002", "inf", PUNE_LAT, Double.POSITIVE_INFINITY, HouseStatus.NEW);
        var wild = at("dddddddd-0000-4000-8000-000000000003", "wild", 95.0, PUNE_LON, HouseStatus.NEW);
        m.put(nan.id(), nan);
        m.put(inf.id(), inf);
        m.put(wild.id(), wild);
        var res = VisitPlannerService.assemble(null, m, List.of(), PUNE_LAT, PUNE_LON, 8);
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::label).containsExactly("P11", "P12");
    }

    @Test
    void theFallbackStillCapsTheStopsAtTheNearestOnes() {
        var res = VisitPlannerService.assemble(null, bengaluruThenPune(), List.of(), PUNE_LAT, PUNE_LON, 1);
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::label).containsExactly("P11");
    }

    @Test
    void theFallbackKeepsRejectedAndNotChosenHousesOutEvenWhenTheyAreNearest() {
        var m = new LinkedHashMap<UUID, HouseSummary>();
        var near = at("eeeeeeee-0000-4000-8000-000000000001", "rejected", PUNE_LAT, PUNE_LON, HouseStatus.REJECTED);
        var near2 = at("eeeeeeee-0000-4000-8000-000000000002", "notchosen", PUNE_LAT, PUNE_LON, HouseStatus.NOT_CHOSEN);
        var ok = at("eeeeeeee-0000-4000-8000-000000000003", "ok", PUNE_LAT + 0.05, PUNE_LON, HouseStatus.NEW);
        m.put(near.id(), near);
        m.put(near2.id(), near2);
        m.put(ok.id(), ok);
        var res = VisitPlannerService.assemble(null, m, List.of(), PUNE_LAT, PUNE_LON, 1);
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::label).containsExactly("ok");
    }

    @Test
    void theFallbackBreaksEqualDistancesByHouseId() {
        var hi = at("ffffffff-0000-4000-8000-000000000009", "hi", PUNE_LAT + 0.01, PUNE_LON, HouseStatus.NEW);
        var lo = at("ffffffff-0000-4000-8000-000000000001", "lo", PUNE_LAT + 0.01, PUNE_LON, HouseStatus.NEW);
        var m = new LinkedHashMap<UUID, HouseSummary>();
        m.put(hi.id(), hi);
        m.put(lo.id(), lo);
        var res = VisitPlannerService.assemble(null, m, List.of(), PUNE_LAT, PUNE_LON, 1);
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::label).containsExactly("lo");
        var all = VisitPlannerService.assemble(null, m, List.of(), PUNE_LAT, PUNE_LON, 2);
        assertThat(all.stops()).extracting(PlanModels.PlannedStop::label).containsExactly("lo", "hi");
    }

    @Test
    void anEmptyPlanFromTheModelIsNotTheFarFallback() {
        var res = VisitPlannerService.assemble(new AgentPlan("Nothing matches", List.of()), bengaluruThenPune(), List.of(),
                PUNE_LAT, PUNE_LON, 4);
        assertThat(res.fallback()).isFalse();
        assertThat(res.summary()).isEqualTo("Nothing matches");
    }

    // ---- S4b-BL-200: fallbackCause

    @Test
    void fallbackCauseIsNullWithoutAFallbackAndTheGivenCauseWhenThereIsNoPlan() {
        var fine = VisitPlannerService.assemble(new AgentPlan("Two", List.of(new AgentStop(a.id().toString(), "x"))), seen,
                List.of(), 12.9716, 77.6400, 8, "provider");
        assertThat(fine.fallback()).isFalse();
        assertThat(fine.fallbackCause()).as("a cause is only reported for a fallback").isNull();

        for (var cause : List.of("provider", "parse", "limit")) {
            var res = VisitPlannerService.assemble(null, seen, List.of(), 12.9716, 77.6400, 8, cause);
            assertThat(res.fallback()).isTrue();
            assertThat(res.fallbackCause()).isEqualTo(cause);
        }
        assertThat(VisitPlannerService.assemble(null, seen, List.of(), 12.9716, 77.6400, 8).fallbackCause())
                .as("no cause given: the output was unusable").isEqualTo("parse");
    }

    @Test
    void aPlanWithOnlyInventedHousesFallsBackWithCauseParseWhateverCauseWasGiven() {
        var plan = new AgentPlan("Invented", List.of(new AgentStop(UUID.randomUUID().toString(), "invented")));
        var res = VisitPlannerService.assemble(plan, seen, List.of(), 12.9716, 77.6400, 8, "provider");
        assertThat(res.fallback()).isTrue();
        assertThat(res.fallbackCause()).isEqualTo("parse");
    }
}
