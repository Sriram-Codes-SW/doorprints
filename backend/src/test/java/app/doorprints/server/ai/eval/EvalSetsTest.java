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

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The additive eval sets (S4b-BL-236): the hard set file is consistent with the golden set it borrows its fixtures from,
 * and a run of it is informational by construction.
 */
class EvalSetsTest {

    private static final Set<String> KNOWN_EXPECTED_KEYS = Set.of("price", "priceType", "bedrooms", "locality", "street", "label",
            "contactName", "contactPhone", "contactPhoneDigits", "listingUrl", "listingUrlNot", "amenitiesInclude", "notesMention",
            "notesMustNotContain", "draftMustNotContain", "expectedHouseIds", "allowedCitations", "mustNotCite", "mustContain",
            "mustNotContain", "grounded", "answerEquals", "citations", "stops", "stopsSubsetOf", "stopsMustInclude", "stopsMustNotInclude",
            "minStops", "maxStops", "fallback", "summaryMustNotContain", "note");

    @Test
    void theHardSetHasEighteenCasesEachWithARationaleAndIdsAndFixturesTheGoldenSetKnows() throws Exception {
        var golden = GoldenSet.load(GoldenSet.locate());
        var run = EvalSets.select(golden, GoldenSet.locate(), "hard-set");
        assertThat(run.name()).isEqualTo("hard-set");
        assertThat(run.headerValue()).isEqualTo("hard-set v0.1");
        assertThat(run.info().kind()).isEqualTo("Eval set");
        assertThat(run.info().resultLine()).isNull();
        var set = run.golden();
        assertThat(set.cases()).hasSize(18);
        assertThat(set.thresholds()).isEmpty();
        assertThat(set.fixtureHouses()).isEqualTo(golden.fixtureHouses());
        assertThat(set.fixtureVisits()).isEqualTo(golden.fixtureVisits());

        var goldenIds = new HashSet<>(golden.cases().stream().map(c -> String.valueOf(c.get("id"))).toList());
        var fixtureIds = new HashSet<>(golden.fixtureHouseIds());
        var seen = new HashSet<String>();
        int extract = 0, ask = 0, plan = 0;
        for (var c : set.cases()) {
            var id = String.valueOf(c.get("id"));
            assertThat(id).startsWith("hard-").matches("hard-(extract|ask|plan)-\\d{2}-[a-z0-9-]+");
            assertThat(seen.add(id)).as("duplicate id " + id).isTrue();
            assertThat(goldenIds).as(id + " must not reuse a golden-set id").doesNotContain(id);
            var type = String.valueOf(c.get("type"));
            assertThat(id).startsWith("hard-" + type + "-");
            assertThat(String.valueOf(c.get("rationale"))).as(id + " rationale").hasSizeGreaterThan(40);
            assertThat(String.valueOf(c.get("region"))).isIn("north", "south", "east", "west", "north-east", "hills", "coast", "cross-region");
            var expected = GoldenSet.map(c.get("expected"));
            assertThat(expected.keySet()).as(id).isSubsetOf(KNOWN_EXPECTED_KEYS);
            switch (type) {
                case "extract" -> {
                    extract++;
                    assertThat(String.valueOf(GoldenSet.map(c.get("input")).get("text"))).hasSizeBetween(20, 8000);
                }
                case "ask" -> {
                    ask++;
                    for (var key : List.of("expectedHouseIds", "allowedCitations", "mustNotCite")) {
                        for (var h : GoldenSet.strings(expected.get(key))) assertThat(fixtureIds).as(id + " " + key).contains(h);
                    }
                }
                default -> {
                    plan++;
                    for (var key : List.of("stopsSubsetOf", "stopsMustInclude", "stopsMustNotInclude")) {
                        for (var h : GoldenSet.strings(expected.get(key))) assertThat(fixtureIds).as(id + " " + key).contains(h);
                    }
                }
            }
        }
        assertThat(extract).isEqualTo(9);
        assertThat(ask).isEqualTo(6);
        assertThat(plan).isEqualTo(3);
    }

    @Test
    void theGoldenSetIsTheDefaultAndABadNameOrAMissingFileIsRefused() throws Exception {
        assertThat(EvalSets.isGolden(null)).isTrue();
        assertThat(EvalSets.isGolden(" ")).isTrue();
        assertThat(EvalSets.isGolden("default")).isTrue();
        assertThat(EvalSets.isGolden("golden-set")).isTrue();
        assertThat(EvalSets.isGolden("hard-set")).isFalse();
        var golden = GoldenSet.load(GoldenSet.locate());
        assertThatThrownBy(() -> EvalSets.select(golden, GoldenSet.locate(), "../golden-set")).hasMessageContaining("set name");
        assertThatThrownBy(() -> EvalSets.select(golden, GoldenSet.locate(), "no-such-set")).hasMessageContaining("no file");
    }

    @Test
    void aSetWithThresholdsOrOtherFixturesIsRefusedBecauseAnAdditiveSetIsInformational() {
        var golden = GoldenSet.parse("{\"version\":\"0.9\",\"fixtureHouses\":[{\"id\":\"a\"}],\"fixtureVisits\":[],\"cases\":[]}");
        assertThatThrownBy(() -> EvalSets.build(golden, "golden-set.json", "x",
                "{\"version\":\"0.1\",\"fixturesFrom\":\"golden-set.json\",\"thresholds\":{},\"cases\":[]}"))
                .hasMessageContaining("thresholds");
        assertThatThrownBy(() -> EvalSets.build(golden, "golden-set.json", "x",
                "{\"version\":\"0.1\",\"fixturesFrom\":\"other.json\",\"cases\":[]}"))
                .hasMessageContaining("takes its fixtures from 'other.json'");
        var run = EvalSets.build(golden, "golden-set.json", "x",
                "{\"version\":\"0.1\",\"date\":\"2026-10-10\",\"description\":\"d\",\"fixturesFrom\":\"golden-set.json\",\"cases\":[{\"id\":\"hard-extract-01-x\",\"type\":\"extract\"}]}");
        assertThat(run.golden().fixtureHouses()).containsExactly(Map.of("id", "a"));
        assertThat(run.golden().cases()).hasSize(1);
        assertThat(run.golden().thresholds()).isEmpty();
        // The scorecard of such a run is not gated and carries the set's section.
        var md = EvalScorer.markdown(EvalScorer.header(), EvalScorer.metrics(List.of(), Map.of()), List.of(), List.of(), List.of(),
                null, null, null, run.info());
        assertThat(md).contains("**Result: FAIL**").contains("no golden-set case ran").contains("## Eval set: x (not gated)")
                .contains("| extractionFieldAccuracy | n/a | 0/0 | - | not gated |");
    }
}
