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

package app.doorprints.server.ai.rag;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import app.doorprints.server.house.HouseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Request/response types of {@code POST /api/ai/ask}. */
public final class AskModels {

    private AskModels() {
    }

    /** Optional structured pre-filters, applied in SQL (pgvector metadata filter) before similarity ranking. */
    public record AskFilters(
            HouseStatus status,
            @Pattern(regexp = "RENT|SALE") String priceType,
            @PositiveOrZero Long maxPrice,
            @Min(0) @Max(20) Integer minBedrooms,
            @Min(1) @Max(5) Integer minRating) {
    }

    public record AskRequest(@NotBlank @Size(max = 4000) String question, @Valid AskFilters filters) {
    }

    public record Citation(UUID houseId, String label, String snippet) {
    }

    /** {@code grounded} is false when the model answered without citing any retrieved house. */
    public record AskResponse(String answer, List<Citation> citations, boolean grounded, int retrieved) {
    }

    /** Structured output target for the model. */
    public record ModelAnswer(
            @JsonPropertyDescription("The answer, in 1-6 sentences, citing houses inline as [house:<id>]") String answer,
            @JsonPropertyDescription("Ids of the houses cited inline as [house:<id>] in the answer, copied exactly from the context") List<String> citedHouseIds) {
    }
}
