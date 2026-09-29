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
