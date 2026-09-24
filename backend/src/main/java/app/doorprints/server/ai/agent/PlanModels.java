package app.doorprints.server.ai.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Request/response types of {@code POST /api/ai/plan-visits}. */
public final class PlanModels {

    private PlanModels() {
    }

    public record PlanRequest(
            @NotBlank @Size(max = 4000) String question,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double startLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double startLon,
            @Min(1) @Max(25) Integer maxStops) {
    }

    public record PlannedStop(int order, UUID houseId, String label, double lat, double lon, String reason,
                              long legMeters, int walkMinutes) {
    }

    /**
     * {@code fallback} is true when the agent ran out of budget or returned an unusable plan and the server built a
     * deterministic nearest-neighbour route from the houses the agent had found.
     */
    public record PlanResponse(String summary, List<PlannedStop> stops, long totalMeters, int totalWalkMinutes,
                               List<String> toolCalls, boolean fallback) {
    }

    /** Structured output target for the model. */
    public record AgentPlan(
            @JsonPropertyDescription("2-4 sentences explaining the plan") String summary,
            @JsonPropertyDescription("Houses to visit, in visiting order") List<AgentStop> stops) {
    }

    public record AgentStop(
            @JsonPropertyDescription("House id exactly as returned by a tool") String houseId,
            @JsonPropertyDescription("Why this house is in the plan, one sentence") String reason) {
    }
}
