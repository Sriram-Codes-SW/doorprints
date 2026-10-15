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

import app.doorprints.server.ai.HouseAiPins;
import app.doorprints.server.ai.PromptSafety;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.house.HouseService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner of a kind that is only a kind file (kinds PR 28, docs/03 §18.22, TC-U-275): the planner's system text is written
 * from the kind's words, and a candidate is seen through the {@code ai.plan} slot (its extras by their words).
 */
class ServerKindPlanTest {

    private static final AiKinds DOJOS = new AiKinds(List.of("house", "dojo"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void thePlannerTextIsWrittenFromTheKindsWords() {
        var text = VisitPlannerService.systemPrompt(13.0, 80.25, 4, DOJOS.of("dojo"));
        assertThat(text).startsWith("You plan dojo visits for one person. Start point: lat 13.000000, lon 80.250000.")
                .contains("Plan at most 4 stops.").contains(PromptSafety.NEUTRAL_RULE).contains("saved dojos")
                .doesNotContain("%");
        // No kind, the house and a kind with no text are the house's text.
        var house = VisitPlannerService.systemPrompt(13.0, 80.25, 4);
        assertThat(house).startsWith("You plan house visits for one person who is house hunting.");
        assertThat(VisitPlannerService.systemPrompt(13.0, 80.25, 4, null)).isEqualTo(house);
        assertThat(VisitPlannerService.systemPrompt(13.0, 80.25, 4, AiKinds.HOUSE)).isEqualTo(house);
        var noText = new app.doorprints.server.ai.kind.AiKind("pg", "PG", List.of(), List.of(), List.of(), List.of(), List.of(),
                "x", "pgs", "r", "a");
        assertThat(noText.planSystem()).isNull();
        assertThat(VisitPlannerService.systemPrompt(13.0, 80.25, 4, noText)).isEqualTo(house);
    }

    @Test
    void aCandidateIsSeenThroughThePlanSlotWithItsExtrasByTheirWords() {
        var wire = """
                {"id":"55555555-5555-4555-8555-0000000002d0","kind":"dojo","label":"Lotus Dojo","address":"4 Lake Road",
                 "street":"Lake Road","locality":"Adyar","lat":13.0,"lon":80.25,"status":"NEW","rating":4,
                 "contactName":"Sensei Rao","contactPhone":"+91 98400 12345","notes":"private note","locationSource":"MAP",
                 "extras":{"discipline":"YOGA","schedule":"Mon 6 pm","classSize":18,"monthlyFee":{"amount":900,"cadence":"month"}},
                 "checklist":{},"createdAt":"2026-05-28T20:26:40Z","updatedAt":"2026-05-28T20:28:20Z","deleted":false,"syncVersion":3}
                """;
        var h = HouseAiPins.candidate(JSON.readTree(wire));
        var search = new HouseSearchService(Mockito.mock(HouseService.class), DOJOS);
        var seen = search.forPlan(h);
        assertThat(seen.address()).isNull();
        assertThat(seen.notes()).isNull();
        // The plan slot names the discipline and the fee, not the timings or the class size.
        assertThat(search.summary(seen).extras()).containsExactly(Map.entry("Discipline", "Yoga"),
                Map.entry("Fee per month", "Rs 900"));
        assertThat(search.detailsExtras(search.forDetails(h))).containsKeys("Class size", "Class timings");
    }
}
