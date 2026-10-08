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

package app.doorprints.server.ai.eval;

import app.doorprints.server.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the configuration of {@link GoldenSetEvalTest}, which cannot run without a provider key (S4b-BL-176): retrieval
 * must return as much as the server allows and every city's fixture houses must fit in it, or a question about the dropped
 * one is scored on an answer the server could not have given (docs/ai/ai-design.md 8.4, "retrieval topK"). The production default of
 * {@code app.ai.rag.top-k} (6) is not touched. Reads the test's own annotation and the golden set file; no model, no
 * database, no Spring context.
 */
class GoldenSetEvalConfigTest {

    private static final String PROPERTY = "app.ai.rag.top-k=";

    private static Integer configuredTopK() {
        return Arrays.stream(GoldenSetEvalTest.class.getAnnotation(SpringBootTest.class).properties())
                .filter(p -> p.startsWith(PROPERTY))
                .map(p -> Integer.valueOf(p.substring(PROPERTY.length())))
                .findFirst().orElse(null);
    }

    @Test
    void theEvalSetsRetrievalTopKToTheLargestValueTheServerAllows() {
        // The golden set has more fixture houses than the server can ever retrieve (30 against the cap of 20, since v0.7),
        // so "cover every fixture house" is out of reach. The eval takes the most the server offers instead.
        var cap = new AiProperties.Rag(Integer.MAX_VALUE, null).topK();
        assertThat(configuredTopK()).as("%s in GoldenSetEvalTest's properties", PROPERTY).isNotNull().isEqualTo(cap);
    }

    @Test
    void noCityHasMoreFixtureHousesThanRetrievalCanReturn() throws IOException {
        // A question is about one city (or one region of a few); its houses must all fit in what retrieval returns, or a
        // question about the dropped one is scored on an answer the server could not have given.
        var byCity = GoldenSet.load(GoldenSet.locate()).fixtureHouses().stream()
                .collect(java.util.stream.Collectors.groupingBy(h -> String.valueOf(h.get("city")), java.util.stream.Collectors.counting()));
        assertThat(byCity).as("fixture houses per city in the golden set").isNotEmpty();
        assertThat(byCity.values()).as("houses in the biggest city").allSatisfy(n -> assertThat(n).isLessThanOrEqualTo((long) configuredTopK()));
    }

    @Test
    void theConfiguredTopKIsNotSilentlyCappedByTheServer() {
        // AiProperties.Rag clamps topK to its maximum; a larger value would look configured and not be.
        var cap = new AiProperties.Rag(Integer.MAX_VALUE, null).topK();
        assertThat(configuredTopK()).isNotNull().isLessThanOrEqualTo(cap);
    }

    @Test
    void theProductionDefaultIsLeftAlone() {
        assertThat(new AiProperties.Rag(null, null).topK()).isEqualTo(6);
    }
}
