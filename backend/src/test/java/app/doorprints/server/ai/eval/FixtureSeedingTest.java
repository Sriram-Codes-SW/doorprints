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

import app.doorprints.server.ai.agent.HouseSearchService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4b-BL-201: the eval's fixture data must be deterministic and complete. {@link GoldenSetEvalTest} cannot run without
 * a provider key, so the parts that decide this are plain functions tested here: the body each fixture house is saved
 * with (a distinct, ordered {@code updatedAt}) and the check that the index holds every fixture house.
 */
class FixtureSeedingTest {

    private static final Instant NOW = Instant.parse("2026-10-10T08:00:00Z");

    @Test
    void seedBodyGivesEachHouseItsOwnUpdatedAtOneSecondOlderThanThePreviousOne() {
        var house = Map.<String, Object>of("id", "h-1", "label", "Blue gate", "city", "Pune", "region", "west");
        assertThat(FixtureSeeding.seedBody(house, 0, NOW).get("updatedAt")).isEqualTo(NOW.toString());
        assertThat(FixtureSeeding.seedBody(house, 1, NOW).get("updatedAt")).isEqualTo(NOW.minusSeconds(1).toString());
        assertThat(FixtureSeeding.seedBody(house, 29, NOW).get("updatedAt")).isEqualTo(NOW.minusSeconds(29).toString());
    }

    @Test
    void seedBodyIsPureAndKeepsTheHouseFieldsButNotTheGoldenSetTags() {
        var house = Map.<String, Object>of("id", "h-1", "label", "Blue gate", "city", "Pune", "region", "west");
        var first = FixtureSeeding.seedBody(house, 3, NOW);
        assertThat(FixtureSeeding.seedBody(house, 3, NOW)).isEqualTo(first);
        assertThat(first).containsEntry("id", "h-1").containsEntry("label", "Blue gate").containsEntry("deleted", false)
                .doesNotContainKeys("city", "region");
        assertThat(house).containsKeys("city", "region");
    }

    @Test
    void theGoldenSetsFixtureHousesGetDistinctTimestampsInOrder() throws IOException {
        var houses = GoldenSet.load(GoldenSet.locate()).fixtureHouses();
        var stamps = new ArrayList<Instant>();
        for (int i = 0; i < houses.size(); i++) {
            stamps.add(Instant.parse((String) FixtureSeeding.seedBody(houses.get(i), i, NOW).get("updatedAt")));
        }
        assertThat(stamps).doesNotHaveDuplicates().isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void everyFixtureHouseFitsInOneSearchResult() throws IOException {
        var fixtures = GoldenSet.load(GoldenSet.locate()).fixtureHouses().size();
        assertThat(fixtures).as("fixture houses in the golden set").isPositive()
                .isLessThanOrEqualTo(HouseSearchService.MAX_RESULTS);
    }

    @Test
    void theHarnessSeedsThroughSeedBodyAndFailsTheRunOnTheIndexedCountError() throws IOException {
        // GoldenSetEvalTest itself needs a provider key, so the wiring is pinned in its source text.
        var source = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/java/app/doorprints/server/ai/eval/GoldenSetEvalTest.java"));
        assertThat(source).contains("FixtureSeeding.seedBody(house, i, nowInstant)");
        var call = source.indexOf("FixtureSeeding.indexedCountError(");
        assertThat(call).as("the re-index answer is compared with the fixture count").isPositive();
        var after = source.substring(call, source.indexOf("private void seedFixtures"));
        assertThat(after).contains("errors.add(countError)").contains("return false;");
    }

    @Test
    void anIndexedCountEqualToTheFixtureCountIsAccepted() {
        assertThat(FixtureSeeding.indexedCountError(30, 30)).isNull();
        assertThat(FixtureSeeding.indexedCountError("30", 30)).isNull();
    }

    @Test
    void aSmallerOrLargerOrMissingIndexedCountIsAHarnessError() {
        assertThat(FixtureSeeding.indexedCountError(29, 30)).contains("29").contains("30");
        assertThat(FixtureSeeding.indexedCountError(31, 30)).contains("31").contains("30");
        assertThat(FixtureSeeding.indexedCountError(null, 30)).isNotNull();
        assertThat(FixtureSeeding.indexedCountError("many", 30)).isNotNull();
    }
}
