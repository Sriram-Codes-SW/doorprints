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

import app.doorprints.server.ai.mcp.McpHouseTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The searchHouses text parameter tells the model that the filter is one literal substring (S4b-BL-204): the planner
 * gave "Pune flats" and got nothing. Both tool classes carry the same words so they cannot drift.
 */
class VisitPlannerToolsDescriptionTest {

    /** The description of the first parameter (text) of searchHouses, read from the annotation the model is shown. */
    private static String textDescription(Class<?> type) {
        var search = Arrays.stream(type.getMethods()).filter(m -> m.getName().equals("searchHouses")).findFirst().orElseThrow();
        return search.getParameters()[0].getAnnotation(ToolParam.class).description();
    }

    @Test
    void thePlannerToolSaysToGiveOneWordAndShowsTheExample() {
        assertThat(textDescription(VisitPlannerTools.class))
                .startsWith("Case-insensitive text to find in label, address, street, locality or notes")
                .contains("ONE word").contains("text=\"Pune\"").contains("\"Pune flats\" matches nothing");
    }

    @Test
    void theMcpToolSaysTheSame() {
        assertThat(textDescription(McpHouseTools.class))
                .startsWith("Case-insensitive text to find")
                .contains("ONE word").contains("text=\"Pune\"");
    }

    @Test
    void bothToolsCarryTheSameWords() {
        assertThat(textDescription(McpHouseTools.class)).isEqualTo(textDescription(VisitPlannerTools.class));
    }
}
