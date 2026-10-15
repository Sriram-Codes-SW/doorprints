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
import app.doorprints.server.ai.agent.VisitPlannerService;
import app.doorprints.server.ai.extract.ListingExtractionService;
import app.doorprints.server.ai.rag.AskModels.AskFilters;
import app.doorprints.server.ai.rag.AskModels.AskRequest;
import app.doorprints.server.ai.rag.HouseIndexer;
import app.doorprints.server.ai.rag.RagService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** S4b-BL-290: the controller hands the request's {@code kind} to the services, and nothing else decides it. */
class AiControllerKindTest {

    private final RagService rag = mock(RagService.class);
    private final VisitPlannerService planner = mock(VisitPlannerService.class);
    private final AiController controller =
            new AiController(mock(ListingExtractionService.class), rag, mock(HouseIndexer.class), planner);

    @Test
    void askPassesItsKindAndAnAbsentKindIsNull() {
        var filters = new AskFilters(null, null, null, null, 3);
        controller.ask(new AskRequest("Which?", filters, "school"));
        verify(rag).ask("Which?", filters, "school");
        controller.ask(new AskRequest("Which?", null));
        verify(rag).ask("Which?", null, null);
    }

    @Test
    void planVisitsPassesTheWholeRequestWithItsKind() {
        var withKind = new PlanRequest("Plan", 12.97, 77.59, 4, "school");
        controller.planVisits(withKind);
        verify(planner).plan(withKind);
        var without = new PlanRequest("Plan", 12.97, 77.59, null);
        controller.planVisits(without);
        verify(planner).plan(without);
    }
}
