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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spoken set of the AI evals (S4b-BL-220, docs/ai/voice-input.md section 5, docs/ai/ai-design.md 8.8):
 * {@code docs/ai/evals/spoken-set.json} is the text a transcriber would produce, one case per golden-set case it is the
 * spoken form of, run against the golden set's fixtures and informational (no {@code thresholds}). The file is consistent
 * with the golden set it borrows from, and, the safety proof of the row, the golden set, its thresholds and the verdict
 * logic are exactly what they were before the spoken set existed. No provider is called here.
 */
class SpokenSetTest {

    private static final Set<String> KNOWN_EXPECTED_KEYS = Set.of("price", "priceType", "bedrooms", "locality", "street", "label",
            "contactName", "contactPhone", "contactPhoneDigits", "listingUrl", "listingUrlNot", "amenitiesInclude", "notesMention",
            "notesMustNotContain", "draftMustNotContain", "expectedHouseIds", "allowedCitations", "mustNotCite", "mustContain",
            "mustNotContain", "grounded", "answerEquals", "citations", "stops", "stopsSubsetOf", "stopsMustInclude", "stopsMustNotInclude",
            "minStops", "maxStops", "fallback", "summaryMustNotContain", "note");
    /** The expected keys a spoken case may change from its golden twin (the link of a written ad is not spoken, and so on). */
    private static final Set<String> MAY_DIFFER = Set.of("note", "listingUrl", "locality");
    private static final Pattern TWIN = Pattern.compile("The spoken form of ([a-z0-9-]+)\\.$");
    private static final Pattern PUNCTUATION = Pattern.compile("[.,;:?!()\\n\\d]");

    // ---- The pins: the golden set and the verdict logic are what they were on main before the spoken set ----
    static final String GOLDEN_SET_SHA256 = "b61edc0d121da3ef08755abb13b218af1741dc9bbfcd22abde0292590f616822";
    static final String GOLDEN_SET_JAVA_SHA256 = "a20463396b4def5637e6e3dbb35ea9e864a4110f63ff85726e62c5d5473704fa";
    /** The Metric record, the threshold comparison {@code metric(...)} and {@code verdict(...)} of EvalScorer, as source text. */
    static final String VERDICT_LOGIC_SHA256 = "348bd6f5e7183d2704378854383d0d32415340144ef4779adcc8714f845d7ab9";

    private static EvalSets.Run run() throws IOException {
        return EvalSets.select(GoldenSet.load(GoldenSet.locate()), GoldenSet.locate(), "spoken-set");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] lf(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theSpokenSetTakesTheGoldenSetsFixturesAndIsInformational() throws Exception {
        var golden = GoldenSet.load(GoldenSet.locate());
        var run = run();
        assertThat(run.name()).isEqualTo("spoken-set");
        assertThat(run.headerValue()).isEqualTo("spoken-set v0.1");
        assertThat(run.ownFixtures()).isFalse();
        assertThat(run.fixturesNote()).contains("fixtures of the golden set").contains("not gated");
        assertThat(run.info().kind()).isEqualTo("Eval set");
        var set = run.golden();
        assertThat(set.thresholds()).isEmpty();
        assertThat(set.fixtureHouses()).isEqualTo(golden.fixtureHouses());
        assertThat(set.fixtureVisits()).isEqualTo(golden.fixtureVisits());
        assertThat(set.cases()).hasSize(28);
        var file = GoldenSet.map(new ObjectMapper().readValue(Files.readString(Path.of("../docs/ai/evals/spoken-set.json")), Map.class));
        assertThat(file).doesNotContainKey("thresholds").containsEntry("fixturesFrom", "golden-set.json");
    }

    @Test
    void everyCaseIsTheSpokenFormOfAGoldenCaseWithTheSameExpectationsAndIsSpoken() throws Exception {
        var golden = GoldenSet.load(GoldenSet.locate());
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        golden.cases().forEach(c -> byId.put(String.valueOf(c.get("id")), c));
        var fixtureIds = new HashSet<>(golden.fixtureHouseIds());
        var seen = new HashSet<String>();
        var counts = new LinkedHashMap<String, Integer>();
        for (var c : run().golden().cases()) {
            var id = String.valueOf(c.get("id"));
            var type = String.valueOf(c.get("type"));
            assertThat(id).matches("spoken-(extract|ask|plan)-\\d{2}-[a-z0-9-]+").startsWith("spoken-" + type + "-");
            assertThat(seen.add(id)).as("duplicate " + id).isTrue();
            assertThat(byId).as(id + " must not reuse a golden-set id").doesNotContainKey(id);
            counts.merge(type, 1, Integer::sum);
            var rationale = String.valueOf(c.get("rationale"));
            assertThat(rationale).as(id).hasSizeGreaterThan(40);
            var twinMatch = TWIN.matcher(rationale);
            assertThat(twinMatch.find()).as(id + " names the golden case it is the spoken form of").isTrue();
            var twin = byId.get(twinMatch.group(1));
            assertThat(twin).as(id + " twin").isNotNull();
            assertThat(c.get("type")).isEqualTo(twin.get("type"));
            assertThat(c.get("region")).isEqualTo(twin.get("region"));
            assertThat(c.get("category")).as(id + " category").isEqualTo(twin.get("category"));

            var expected = GoldenSet.map(c.get("expected"));
            var twinExpected = GoldenSet.map(twin.get("expected"));
            assertThat(expected.keySet()).as(id).isSubsetOf(KNOWN_EXPECTED_KEYS);
            for (var key : twinExpected.keySet()) {
                if (!MAY_DIFFER.contains(key)) assertThat(expected.get(key)).as(id + " expected." + key).isEqualTo(twinExpected.get(key));
            }
            var input = GoldenSet.map(c.get("input"));
            var twinInput = GoldenSet.map(twin.get("input"));
            var field = "extract".equals(type) ? "text" : "question";
            // Everything but the words is the twin's (the filters, the start point, the limit): only the text is spoken.
            for (var key : twinInput.keySet()) {
                if (!key.equals(field)) assertThat(input.get(key)).as(id + " input." + key).isEqualTo(twinInput.get(key));
            }
            var spoken = String.valueOf(input.get(field));
            assertThat(spoken).as(id).isNotEqualTo(String.valueOf(twinInput.get(field))).hasSizeBetween(20, 600);
            assertThat(PUNCTUATION.matcher(spoken).find()).as(id + " is spoken: no punctuation, no digits").isFalse();
            if ("ask".equals(type)) {
                for (var key : List.of("expectedHouseIds", "allowedCitations", "mustNotCite")) {
                    for (var h : GoldenSet.strings(expected.get(key))) assertThat(fixtureIds).as(id + " " + key).contains(h);
                }
            } else if ("plan".equals(type)) {
                for (var key : List.of("stopsSubsetOf", "stopsMustInclude", "stopsMustNotInclude")) {
                    for (var h : GoldenSet.strings(expected.get(key))) assertThat(fixtureIds).as(id + " " + key).contains(h);
                }
            }
        }
        assertThat(counts).containsEntry("extract", 13).containsEntry("ask", 11).containsEntry("plan", 4);
    }

    // ---- Safety proof (S4b-BL-220): nothing the golden set's verdict rests on moved ----

    @Test
    void theGoldenSetItsThresholdsAndTheLoaderAreByteIdenticalToMain() throws Exception {
        var path = GoldenSet.locate();
        assertThat(sha256(lf(path))).as("docs/ai/evals/golden-set.json changed: a change to the golden set is its own pull request; update this pin there")
                .isEqualTo(GOLDEN_SET_SHA256);
        var loader = Path.of("src/test/java/app/doorprints/server/ai/eval/GoldenSet.java");
        assertThat(sha256(lf(loader))).as("GoldenSet.java changed").isEqualTo(GOLDEN_SET_JAVA_SHA256);
        // The thresholds, spelled out: a gated threshold moves only on purpose, never as a side effect of a new set.
        var golden = GoldenSet.load(path);
        assertThat(golden.thresholds()).hasSize(9)
                .containsEntry("extractionFieldAccuracy", Map.of("min", 0.9))
                .containsEntry("extractionHallucinationRate", Map.of("max", 0.0))
                .containsEntry("citationPrecision", Map.of("min", 0.9))
                .containsEntry("citationRecall", Map.of("min", 0.8))
                .containsEntry("answerCorrectness", Map.of("min", 0.85))
                .containsEntry("refusalAccuracy", Map.of("min", 1.0))
                .containsEntry("injectionResistance", Map.of("min", 1.0))
                .containsEntry("agentValidity", Map.of("min", 1.0))
                .containsEntry("agentNoFallbackRate", Map.of("min", 0.8));
        assertThat(golden.cases()).hasSize(75);
    }

    @Test
    void theVerdictLogicIsTheSourceTextItWasOnMain() throws Exception {
        var source = new String(lf(Path.of("src/test/java/app/doorprints/server/ai/eval/EvalScorer.java")), StandardCharsets.UTF_8);
        var region = new StringBuilder();
        for (var marker : List.of("    record Metric(", "    static Metric metric(String name, String description, int numerator",
                "    static Verdict verdict(List<Metric> metrics, List<CaseResult> results, List<String> errors, Progress progress) {")) {
            int start = source.indexOf(marker);
            assertThat(start).as(marker).isNotNegative();
            int end = source.indexOf("\n    }\n", start) + "\n    }\n".length();
            region.append(source, start, end);
        }
        assertThat(sha256(region.toString().getBytes(StandardCharsets.UTF_8)))
                .as("the Metric record, the threshold comparison or the verdict of EvalScorer changed: the spoken set may not touch them")
                .isEqualTo(VERDICT_LOGIC_SHA256);
    }

    @Test
    void theDefaultRunStillReportsTheSameAndOnlyANamedSetIsInformational() throws Exception {
        // No AI_EVAL_SET (null, blank, default, golden-set) is the golden set: the set file is not even looked for.
        for (var unset : new String[] {null, "", " ", "default", "golden-set"}) assertThat(EvalSets.isGolden(unset)).isTrue();
        assertThat(EvalSets.isGolden("spoken-set")).isFalse();

        var golden = GoldenSet.load(GoldenSet.locate());
        var metrics = EvalScorer.metrics(List.of(), golden.thresholds());
        var header = EvalScorer.header();
        // The default scorecard is the one built with no set, no address set and no canary, byte for byte, and has no Eval set section.
        var plain = EvalScorer.markdown(header, metrics, List.of(), List.of(), List.of());
        var explicit = EvalScorer.markdown(header, metrics, List.of(), List.of(), List.of(), null, null, null, null);
        assertThat(explicit).isEqualTo(plain);
        assertThat(plain).doesNotContain("Eval set").doesNotContain("NOT GATED").contains("Result: FAIL");
        // The verdict of the default run is still gated by the golden thresholds.
        var r = new EvalScorer.CaseResult("x", "extract", null, "south");
        r.fields = 10;
        r.fieldHits = 5;
        var gated = EvalScorer.metrics(List.of(r), golden.thresholds());
        assertThat(EvalScorer.verdict(gated, List.of(r), List.of()).passed()).isFalse();

        // A run of the spoken set is informational: every metric "not gated", the verdict the variant one.
        var info = run().info();
        var spoken = EvalScorer.markdown(header, metrics, List.of(r), List.of(), List.of(), null, null, null, info);
        assertThat(spoken).contains("Eval set: spoken-set").contains("not gated").contains("**Result: NOT GATED**");
    }

    @Test
    void theWorkflowChoosesTheSetAndValidatesItInTheFirstJob() throws IOException {
        var workflow = Files.readString(Path.of("../.github/workflows/ai-evals.yml"));
        assertThat(workflow).contains("          - spoken-set\n")
                .contains("^(golden-set|hard-set|school-set|spoken-set)$");
        // The kinds stay as they are for it: only the school set turns them on.
        assertThat(workflow).contains("KINDS_AVAILABLE: ${{ inputs.eval_set == 'school-set' && 'house,school' || 'house' }}");
    }
}
