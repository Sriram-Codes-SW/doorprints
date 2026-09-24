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
    void emptyPlanStaysEmpty() {
        var res = VisitPlannerService.assemble(new AgentPlan("Nothing matches", List.of()), seen, List.of(), 0, 0, 8);
        assertThat(res.fallback()).isFalse();
        assertThat(res.stops()).isEmpty();
        assertThat(res.summary()).isEqualTo("Nothing matches");
    }
}
