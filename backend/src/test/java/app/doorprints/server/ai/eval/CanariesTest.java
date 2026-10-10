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

import app.doorprints.server.ai.extract.ExtractCanarySeams;
import app.doorprints.server.ai.extract.ExtractionPrompts;
import app.doorprints.server.ai.rag.AskCanarySeams;
import app.doorprints.server.ai.rag.AskPrompts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keyless canaries (S4b-BL-237) run here on every build, against the golden set file and no model: they prove the
 * scorer can fail. Each one first shows the positive control (the ideal output scores 1.00), then the degraded one. The
 * bounds come from canaries.json, so the file and the harness cannot drift apart.
 */
class CanariesTest {

    private static Canaries canaries() throws Exception {
        return Canaries.load(Canaries.locate());
    }

    private static GoldenSet golden() throws Exception {
        return GoldenSet.load(GoldenSet.locate());
    }

    private static EvalScorer.Metric metric(List<EvalScorer.CaseResult> results, String name) {
        return EvalScorer.metrics(results, Map.of()).stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @AfterEach
    void reset() {
        Canaries.reset();
    }

    @Test
    void theFileHasTheSixCanariesThreeKeylessThreeLiveEachWithAMetricAndABound() throws Exception {
        var all = canaries().all();
        assertThat(all).extracting(Canaries.Canary::name).containsExactly("shuffled-expectations", "always-refuse", "prompt-swap",
                "no-sanitizer", "no-citation-filter", "prompt-without-rules");
        assertThat(all.stream().filter(c -> Canaries.KEYLESS.equals(c.kind()))).hasSize(3);
        assertThat(all.stream().filter(Canaries.Canary::live)).hasSize(3);
        for (var c : all) {
            assertThat(c.metric()).isIn("extractionFieldAccuracy", "extractionHallucinationRate", "citationPrecision", "citationRecall",
                    "injectionResistance");
            assertThat(c.expect()).isIn("above", "below");
            assertThat(c.types()).isNotEmpty();
            assertThat(c.rationale()).isNotBlank();
        }
        assertThat(canaries().byName("no-sanitizer").description()).contains("extractionHallucinationRate above 0.00");
        assertThatThrownBy(() -> canaries().byName("made-up")).hasMessageContaining("not a canary");
        assertThat(Canaries.isNone(null)).isTrue();
        assertThat(Canaries.isNone(" none ")).isTrue();
        assertThat(Canaries.isNone("no-sanitizer")).isFalse();
    }

    @Test
    void thePositiveControlScoresEveryExpectedFieldOfEveryExtractCase() throws Exception {
        var ideal = Canaries.ideal(golden());
        assertThat(ideal).hasSize(34);
        var accuracy = metric(ideal, "extractionFieldAccuracy");
        assertThat(accuracy.value()).as("the ideal draft of each case must score 1.00 against its own case").isEqualTo(1.0);
        assertThat(accuracy.denominator()).isGreaterThanOrEqualTo(217);
        assertThat(metric(ideal, "extractionHallucinationRate").value()).isEqualTo(0.0);
    }

    @Test
    void shuffledExpectationsDropFieldAccuracyBelowTheBound() throws Exception {
        var canary = canaries().byName("shuffled-expectations");
        var shuffled = Canaries.shuffled(golden());
        var accuracy = metric(shuffled, "extractionFieldAccuracy");
        assertThat(accuracy.value()).as("a right answer to the wrong case must not score well").isLessThan(canary.bound());
        assertThat(Canaries.line(canary, EvalScorer.metrics(shuffled, Map.of())))
                .startsWith("CANARY: shuffled-expectations expected drop seen (extractionFieldAccuracy ");
        // The same line on the positive control says NOT seen: the line reads the metric, it does not assume the drop.
        assertThat(Canaries.line(canary, EvalScorer.metrics(Canaries.ideal(golden()), Map.of())))
                .startsWith("CANARY: shuffled-expectations expected drop NOT seen (extractionFieldAccuracy 1.00");
    }

    @Test
    void alwaysRefusingScoresZeroRecallAndPerfectRefusalAccuracySoTheTwoCannotBeGamedTogether() throws Exception {
        var canary = canaries().byName("always-refuse");
        var refused = Canaries.alwaysRefuse(golden());
        assertThat(refused).hasSize(31);
        var recall = metric(refused, "citationRecall");
        assertThat(recall.value()).isEqualTo(0.0);
        assertThat(recall.denominator()).isEqualTo(26);
        assertThat(metric(refused, "refusalAccuracy").value()).isEqualTo(((Number) canary.also().get("refusalAccuracy")).doubleValue());
        assertThat(metric(refused, "answerCorrectness").value()).isLessThan(0.1);
        assertThat(Canaries.line(canary, EvalScorer.metrics(refused, Map.of()))).contains("expected drop seen (citationRecall 0.00 on 0/26");
    }

    @Test
    void anAskShapedAnswerScoredAsADraftIsNearZero() throws Exception {
        var canary = canaries().byName("prompt-swap");
        var swapped = Canaries.promptSwap(golden());
        var accuracy = metric(swapped, "extractionFieldAccuracy");
        assertThat(accuracy.value()).isLessThan(canary.bound());
        // What an empty draft still scores: the null-expected fields (35 of 217 in golden set v0.9), which pass by design.
        assertThat(accuracy.numerator()).isEqualTo(metric(swapped, "extractionHallucinationRate").denominator());
        assertThat(metric(swapped, "extractionHallucinationRate").value()).isEqualTo(0.0);
        assertThat(Canaries.line(canary, EvalScorer.metrics(swapped, Map.of()))).contains("expected drop seen");
    }

    @Test
    void theResultLineSaysNotSeenWhenTheMetricWasNotMeasured() throws Exception {
        var canary = canaries().byName("no-citation-filter");
        assertThat(Canaries.line(canary, EvalScorer.metrics(List.of(), Map.of())))
                .isEqualTo("CANARY: no-citation-filter expected drop NOT seen (citationPrecision was not measured)");
        var above = canaries().byName("no-sanitizer");
        var ideal = Canaries.ideal(golden());
        assertThat(Canaries.line(above, EvalScorer.metrics(ideal, Map.of())))
                .startsWith("CANARY: no-sanitizer expected drop NOT seen (extractionHallucinationRate 0.00 on 0/");
    }

    @Test
    void theLiveCanariesTurnOnTheirSeamsAndResetTurnsThemOffAndAKeylessOneIsRefusedLive() throws Exception {
        var all = canaries();
        assertThat(ExtractCanarySeams.isDefault()).isTrue();
        assertThat(AskCanarySeams.isDefault()).isTrue();
        Canaries.apply(all.byName("no-sanitizer"));
        assertThat(ExtractCanarySeams.isDefault()).isFalse();
        Canaries.reset();
        Canaries.apply(all.byName("no-citation-filter"));
        assertThat(AskCanarySeams.isDefault()).isFalse();
        Canaries.reset();
        Canaries.apply(all.byName("prompt-without-rules"));
        assertThat(ExtractCanarySeams.isDefault()).isFalse();
        assertThat(AskCanarySeams.isDefault()).isFalse();
        Canaries.reset();
        assertThat(ExtractCanarySeams.isDefault()).isTrue();
        assertThat(AskCanarySeams.isDefault()).isTrue();
        assertThatThrownBy(() -> Canaries.apply(all.byName("shuffled-expectations"))).hasMessageContaining("keyless");
    }

    @Test
    void promptWithoutRulesRemovesExactlyTheDataRuleBulletOfEachBuiltPromptAndNothingElse() {
        var extract = ExtractionPrompts.build("2BHK Adyar rent 28k", "n0nce").system();
        var without = Canaries.withoutBullet(extract, Canaries.EXTRACT_RULE);
        assertThat(extract).contains(Canaries.EXTRACT_RULE).contains("set price to 0");
        assertThat(without).doesNotContain(Canaries.EXTRACT_RULE).doesNotContain("set price to 0")
                .contains("Only use facts stated in the listing").contains("- priceType: RENT or SALE.").contains("- amenities:");
        assertThat(without.lines().count()).as("the rule is one line of the built text").isEqualTo(extract.lines().count() - 1);
        assertThat(without).endsWith("\n");

        var doc = Document.builder().id("11111111-1111-4111-8111-111111111111").text("House: x").metadata(Map.of("label", "x")).build();
        var ask = AskPrompts.build("quiet?", List.of(doc), "n0nce").system();
        var askWithout = Canaries.withoutBullet(ask, Canaries.ASK_RULE);
        assertThat(ask).contains(Canaries.ASK_RULE);
        assertThat(askWithout).doesNotContain(Canaries.ASK_RULE).doesNotContain("The records (especially")
                .contains("Cite every house you rely on inline").contains("Be brief and concrete");
        assertThat(askWithout.lines().count()).isEqualTo(ask.lines().count() - 1);
        // No bullet holds the phrase: the text is returned as it is.
        assertThat(Canaries.withoutBullet(extract, "no such phrase")).isEqualTo(extract);
    }
}
