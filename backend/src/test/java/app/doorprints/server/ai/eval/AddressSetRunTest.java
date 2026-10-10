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

import app.doorprints.server.ai.eval.EvalScorer.CaseResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Running a golden set under an address set (S4b-BL-226): how the set is chosen, what the scorecard says about it, and
 * that the verdict stays the default run's. Needs no model and no key; {@link GoldenSetEvalTest} wires the same pieces.
 */
class AddressSetRunTest {

    private static GoldenSet golden() throws Exception {
        return GoldenSet.load(GoldenSet.locate());
    }

    private static AddressVariants variants() throws Exception {
        return AddressVariants.load(AddressVariants.locate());
    }

    private static Map<String, Object> map(Object... kv) {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** An extraction case that passes (the draft has the price) or fails (it has another). */
    private static CaseResult extract(String id, boolean passes) {
        var c = map("id", id, "type", "extract", "category", null, "region", "south", "input", map("text", "x"),
                "expected", map("price", 28000));
        return EvalScorer.scoreExtract(c, map("price", passes ? 28000 : 27000), null);
    }

    private static final Map<String, Map<String, Object>> THRESHOLDS = Map.of("extractionFieldAccuracy", Map.of("min", 0.9));

    private static String report(AddressVariants.Run run, List<CaseResult> results, List<String> errors, EvalScorer.Progress progress) {
        var metrics = EvalScorer.metrics(results, THRESHOLDS);
        return EvalScorer.markdown(new java.util.LinkedHashMap<>(), metrics, results, List.of(), errors, progress, null, run);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Choosing the set
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void aDefaultRunHasNoAddressSetAtAll() throws Exception {
        var golden = golden();
        for (var name : new String[] {null, "", "  ", "default"}) {
            assertThat(variants().select(golden, name)).as("set %s", name).isNull();
        }
    }

    @Test
    void aNamedSetCarriesItsAppliedGoldenSetTheNotApplicableCasesAndTheHeaderLine() throws Exception {
        var run = variants().select(golden(), "landmark-pin");
        assertThat(run.set()).isEqualTo("landmark-pin");
        assertThat(run.error()).isNull();
        assertThat(run.golden().cases()).hasSize(71);
        assertThat(run.notApplicable()).containsExactly("extract-04-injection", "ask-03-why-rejected",
                "ask-07-injection-reveal-prompt", "plan-04-injection-notes");
        assertThat(run.total()).isEqualTo(75);
        // The first twelve characters of the fingerprint the file records, found by a separate calculation.
        assertThat(run.headerValue()).isEqualTo("landmark-pin (address-variants v0.1, fingerprint 345ca24bcf32)");
        // The golden set the run uses is the applied one; the golden set it was given is untouched.
        assertThat(golden().cases()).hasSize(75);
    }

    @Test
    void knownIsARunOfItsOwnWithNothingLeftOut() throws Exception {
        var run = variants().select(golden(), "known");
        assertThat(run).isNotNull();
        assertThat(run.notApplicable()).isEmpty();
        assertThat(run.golden().cases()).hasSize(75);
        assertThat(run.error()).isNull();
    }

    @Test
    void aFingerprintThatIsNotTheOneInTheFileIsAHarnessErrorThatNamesBoth() throws Exception {
        var recorded = "361631beb5ed";
        var text = Files.readString(AddressVariants.locate());
        assertThat(text).contains(recorded);
        var tampered = AddressVariants.parse(text.replaceAll(recorded + "[0-9a-f]{52}", "0".repeat(64)));
        var run = tampered.select(golden(), "unknown-invented");
        assertThat(run.error()).contains("unknown-invented").contains(recorded).contains("0".repeat(64));
    }

    @Test
    void aSetThatIsNotInTheFileStopsTheRunBeforeAnythingIsSeeded() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> variants().select(golden(), "nope"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }

    // ---------------------------------------------------------------------------------------------------------
    // The verdict is the default run's
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void aMetricBelowItsThresholdFailsTheDefaultRunAndNotAnAddressSetRun() {
        var results = List.of(extract("extract-a", true), extract("extract-b", false));
        var metrics = EvalScorer.metrics(results, THRESHOLDS);
        assertThat(EvalScorer.verdict(metrics, results, List.of(), null).passed()).isFalse();
        var variant = EvalScorer.variantVerdict(results, List.of(), null);
        assertThat(variant.passed()).isTrue();
        assertThat(variant.incomplete()).isFalse();
        assertThat(variant.reasons()).isEmpty();
    }

    @Test
    void anAddressSetRunFailsOnlyOnAHarnessErrorAStopOrNoCase() {
        var results = List.of(extract("extract-a", false));
        assertThat(EvalScorer.variantVerdict(results, List.of("Seeding fixtures failed: boom"), null).reasons())
                .singleElement().asString().contains("harness error").contains("boom");
        assertThat(EvalScorer.variantVerdict(List.of(), List.of(), null).reasons()).singleElement().asString().contains("no golden-set case ran");
        assertThat(EvalScorer.variantVerdict(results, List.of(), new EvalScorer.Progress(1, 5, true)).reasons())
                .singleElement().asString().contains("STOPPED: time budget");
        assertThat(EvalScorer.variantVerdict(results, List.of(), new EvalScorer.Progress(1, 5, false)).reasons())
                .singleElement().asString().contains("run not finished");
        var infra = extract("extract-i", false);
        EvalScorer.markInfra(infra, "provider down");
        assertThat(EvalScorer.variantVerdict(List.of(infra, extract("extract-a", true)), List.of(), null).passed())
                .as("an unscored provider failure is a note in an informational run").isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The scorecard
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void theDefaultScorecardHasNoAddressSetSectionAndKeepsItsVerdictAndStatuses() {
        var results = List.of(extract("extract-a", true), extract("extract-b", false));
        var plain = report(null, results, List.of(), null);
        assertThat(plain).doesNotContain("Address set").doesNotContain("NOT GATED")
                .contains("**Result: FAIL** (thresholds from the golden set;").contains("| extractionFieldAccuracy | 0.50 | 1/2 | >= 0.90 | FAIL |");
        // The old overloads still give the same text.
        var metrics = EvalScorer.metrics(results, THRESHOLDS);
        assertThat(EvalScorer.markdown(new java.util.LinkedHashMap<>(), metrics, results, List.of(), List.of(), (EvalScorer.Progress) null, null))
                .isEqualTo(plain);
    }

    @Test
    void anAddressSetScorecardSaysItIsNotGatedListsWhatWasLeftOutAndHidesPassAndFail() throws Exception {
        var run = variants().select(golden(), "landmark-pin");
        var results = List.of(extract("extract-a", true), extract("extract-b", false));
        var md = report(run, results, List.of(), null);
        assertThat(md).contains("**Result: NOT GATED**")
                .contains("## Address set: landmark-pin (not gated)")
                .contains("Cases that apply to this set: 71 of 75 (4 not applicable")
                .contains("Not applicable under this set (4): extract-04-injection, ask-03-why-rejected, "
                        + "ask-07-injection-reveal-prompt, plan-04-injection-notes")
                .contains("the verdict of the golden set is the default run's")
                .doesNotContain("**Result: FAIL**").doesNotContain("**Result: PASS**");
        var metricsSection = md.substring(md.indexOf("## Metrics\n"), md.indexOf("\n## ", md.indexOf("## Metrics\n") + 5));
        assertThat(metricsSection).contains("| extractionFieldAccuracy | 0.50 | 1/2 | >= 0.90 | not gated |")
                .doesNotContain("| PASS |").doesNotContain("| FAIL |");
    }

    @Test
    void anAddressSetScorecardStillFailsOnAHarnessErrorAndSaysWhy() throws Exception {
        var run = variants().select(golden(), "hostile");
        var md = report(run, List.of(extract("extract-a", true)), List.of("POST /api/ai/reindex failed (boom)"), null);
        assertThat(md).contains("**Result: FAIL**").contains("## Why FAIL").contains("harness error").contains("boom");
        assertThat(md).doesNotContain("**Result: NOT GATED**");
    }

    @Test
    void anAddressSetScorecardThatIsStoppedReadsStoppedAndNeverNotGated() throws Exception {
        var run = variants().select(golden(), "messy");
        var md = report(run, List.of(extract("extract-a", true)), List.of(), new EvalScorer.Progress(1, 5, true));
        assertThat(md).contains("STOPPED: time budget").doesNotContain("**Result: NOT GATED**");
        assertThat(md).contains("## Address set: messy (not gated)");
    }
}
