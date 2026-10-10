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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agreement across the trials of a case (S4b-BL-227): when a case is run more than once, how often do the runs say the
 * same thing? Needs no model and no key. The expected figures are counted by hand in each test, not computed with the
 * code under test.
 */
class AgreementTest {

    private static final String H1 = "11111111-1111-4111-8111-111111111111";
    private static final String H2 = "22222222-2222-4222-8222-222222222222";
    private static final String H3 = "33333333-3333-4333-8333-333333333333";

    /** Map.of rejects null values, and drafts and expectations use null for "absent". */
    private static Map<String, Object> map(Object... kv) {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> testCase(String id, String type, Map<String, Object> expected) {
        return map("id", id, "type", type, "category", null, "expected", expected, "input", map());
    }

    private static CaseResult extract(String id, Map<String, Object> draft) {
        return EvalScorer.scoreExtract(testCase(id, "extract", map()), draft, null);
    }

    private static CaseResult ask(String id, String answer, String... cited) {
        var citations = new ArrayList<Object>();
        for (var c : cited) citations.add(map("houseId", c));
        return EvalScorer.scoreAsk(testCase(id, "ask", map("mustContain", List.of("quiet"), "grounded", false)),
                map("answer", answer, "citations", citations, "grounded", false), null);
    }

    private static CaseResult plan(String id, boolean fallback, String... stops) {
        var list = new ArrayList<Object>();
        for (var s : stops) list.add(map("houseId", s));
        return EvalScorer.scorePlan(testCase(id, "plan", map()), map("stops", list, "fallback", fallback, "summary", "ok"), null,
                List.of(H1, H2, H3));
    }

    private static CaseResult infra(CaseResult r) {
        EvalScorer.markInfra(r, "provider down");
        return r;
    }

    // ---------------------------------------------------------------------------------------------------------
    // What counts as the same
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void twoDraftsAreTheSameWhenTheyDifferOnlyInWhatTheScorerIgnores() {
        var a = extract("x", map("price", 28000, "priceType", "RENT", "bedrooms", 2, "locality", "HSR Layout",
                "contactPhone", "+91 98450-12345", "listingUrl", "https://Example.com/l/1/", "notes", "Deposit: 1.5 lakh",
                "amenities", List.of("Car parking", "Lift"), "label", "  ", "contactName", null));
        var b = extract("x", map("price", 28000L, "priceType", "rent", "bedrooms", 2, "locality", "hsr  layout.",
                "contactPhone", "98450 12345", "listingUrl", "https://example.com/l/1", "notes", "deposit 1.5 lakh",
                "amenities", List.of("car parking", "LIFT!"), "contactName", ""));
        assertThat(a.agreeKey).isNotNull().isEqualTo(b.agreeKey);
    }

    @Test
    void twoDraftsDifferWhenAnyFieldOrItsOrderOrAnAddedFieldDiffers() {
        var base = map("price", 28000, "priceType", "RENT", "locality", "HSR Layout", "amenities", List.of("lift", "parking"));
        var key = extract("x", base).agreeKey;
        var changes = List.of(
                map("price", 28001), map("priceType", "SALE"), map("locality", "Koramangala"),
                map("amenities", List.of("parking", "lift")), map("amenities", List.of("lift")),
                map("warnings", List.of("the text has 2 links")), map("contactPhone", "98450 12345"),
                map("listingUrl", "https://example.com/other"), map("price", null));
        for (var change : changes) {
            var draft = new HashMap<>(base);
            draft.putAll(change);
            assertThat(extract("x", draft).agreeKey).as("%s", change).isNotEqualTo(key);
        }
        // Key order and a missing field against a null one make no difference.
        var reordered = new HashMap<String, Object>();
        reordered.put("amenities", List.of("lift", "parking"));
        reordered.put("locality", "HSR Layout");
        reordered.put("priceType", "RENT");
        reordered.put("price", 28000);
        reordered.put("notes", null);
        assertThat(extract("x", reordered).agreeKey).isEqualTo(key);
    }

    @Test
    void anAskAnswerIsTheSameWhenItCitesTheSameHousesInAnyOrderAndPassesOrFailsAlike() {
        var a = ask("ask-1", "It is quiet.", H1, H2);
        var sameSet = ask("ask-1", "Quiet, yes.", H2, H1);
        var extraCitation = ask("ask-1", "It is quiet.", H1, H2, H3);
        var failsInstead = ask("ask-1", "It is loud.", H1, H2);
        assertThat(a.passed()).isTrue();
        assertThat(failsInstead.passed()).isFalse();
        assertThat(a.agreeKey).isEqualTo(sameSet.agreeKey).isEqualTo(failsInstead.agreeKey).isNotEqualTo(extraCitation.agreeKey);
        assertThat(Agreement.key(a)).isEqualTo(Agreement.key(sameSet)).isNotEqualTo(Agreement.key(failsInstead))
                .isNotEqualTo(Agreement.key(extraCitation));
    }

    @Test
    void aPlanIsTheSameWhenItVisitsTheSameSetOfHousesWithTheSameFallbackAndItsOrderIsReportedApart() {
        var a = plan("plan-1", false, H1, H2);
        var reordered = plan("plan-1", false, H2, H1);
        var other = plan("plan-1", false, H1, H3);
        var fellBack = plan("plan-1", true, H1, H2);
        assertThat(a.agreeKey).isEqualTo(reordered.agreeKey).isNotEqualTo(other.agreeKey).isNotEqualTo(fellBack.agreeKey);
        assertThat(a.orderKey).isNotEqualTo(reordered.orderKey);
        assertThat(a.orderKey).isEqualTo(plan("plan-1", true, H1, H2).orderKey);
    }

    @Test
    void aCallThatFailedHasNoKeyAndNeverEqualsAnAnswer() {
        var failed = EvalScorer.scoreExtract(testCase("x", "extract", map()), null, "HTTP 400: nope");
        assertThat(failed.agreeKey).isNull();
        assertThat(Agreement.key(failed)).isEqualTo("no answer").isNotEqualTo(Agreement.key(extract("x", map("price", 1))));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Counting
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void agreementIsTheShareOfCasesWhoseEveryScoredTrialAgreesAndAnInfraTrialIsLeftOut() {
        // extract: a agrees in 3 trials (2 pairs), b has one odd trial (2 pairs, 1 differs), c has an unscored trial and 2
        // agreeing ones (1 pair), d has one scored trial only and is left out.
        var a = List.of(extract("a", map("price", 1)), extract("a", map("price", 1)), extract("a", map("price", 1)));
        var b = List.of(extract("b", map("price", 1)), extract("b", map("price", 2)), extract("b", map("price", 1)));
        var c = List.of(extract("c", map("price", 1)), infra(extract("c", map("price", 9))), extract("c", map("price", 1)));
        var d = List.of(extract("d", map("price", 1)), infra(extract("d", map("price", 9))));
        // ask: e agrees, f cites another house in trial 2.
        var e = List.of(ask("e", "quiet", H1), ask("e", "quiet", H1));
        var f = List.of(ask("f", "quiet", H1), ask("f", "quiet", H2));
        // plan: g keeps the set and changes the order, h changes the set.
        var g = List.of(plan("g", false, H1, H2), plan("g", false, H2, H1));
        var h = List.of(plan("h", false, H1), plan("h", false, H2));
        var rows = Agreement.rows(List.of(a, b, c, d, e, f, g, h));

        assertThat(rows).extracting(Agreement.Row::type).containsExactly("extract", "ask", "plan");
        var extractRow = rows.get(0);
        assertThat(extractRow.cases()).isEqualTo(3);
        assertThat(extractRow.agreeing()).isEqualTo(2);
        assertThat(extractRow.pairs()).isEqualTo(5);
        assertThat(extractRow.differing()).isEqualTo(1);
        assertThat(extractRow.mostTrials()).isEqualTo(3);
        assertThat(extractRow.value()).isCloseTo(2.0 / 3, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(rows.get(1)).isEqualTo(new Agreement.Row("ask", 2, 1, 2, 1, 2));
        assertThat(rows.get(2)).isEqualTo(new Agreement.Row("plan", 2, 2 - 1, 2, 1, 2));
        // g keeps its stops as a set (agrees), h does not: 1 of 2. The order: g changed it, h's single stop differs: 0 of 2.
        assertThat(Agreement.order(List.of(a, b, c, d, e, f, g, h))).isEqualTo(new Agreement.Order(2, 0));
        assertThat(Agreement.order(List.of(g, List.of(plan("i", false, H1, H2), plan("i", false, H1, H2))))).isEqualTo(new Agreement.Order(2, 1));
    }

    @Test
    void aTypeWithNoCaseRunTwiceHasNoRow() {
        var one = List.of(extract("a", map("price", 1)));
        assertThat(Agreement.rows(List.of(one))).isEmpty();
        assertThat(Agreement.rows(List.of())).isEmpty();
        var sb = new StringBuilder();
        Agreement.append(sb, List.of(one));
        assertThat(sb.toString()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The words
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void theRuleOfThreeIsStatedOnlyWhenNoPairDiffersAndNeverCallsAnythingDeterministic() {
        var all = new Agreement.Row("extract", 34, 34, 68, 0, 3);
        assertThat(Agreement.ruleOfThree(all)).isEqualTo("extract: 68 trial pairs, none differ, so the per-trial disagreement rate is at most "
                + "3/68 = 0.04 at 95% confidence (rule of three).");
        var some = new Agreement.Row("ask", 31, 26, 40, 5, 2);
        assertThat(Agreement.ruleOfThree(some)).isEqualTo("ask: 5 of 40 trial pairs differ, so the rule of three does not apply "
                + "(it needs none); the observed rate is 0.13.");
        assertThat(Agreement.ruleOfThree(new Agreement.Row("plan", 10, 10, 10, 0, 2))).contains("3/10 = 0.30");
    }

    @Test
    void theScorecardSectionHasTheTableTheOrderLineAndTheRuleOfThreeAndNoDeterminism() {
        var trials = new EvalScorer.Trials();
        for (var id : List.of("a", "b")) {
            trials.record(1, extract(id, map("price", 1)));
            trials.record(2, extract(id, map("price", id.equals("a") ? 1 : 2)));
        }
        trials.record(1, plan("p", false, H1, H2));
        trials.record(2, plan("p", false, H2, H1));
        var md = EvalScorer.markdown(EvalScorer.header(), EvalScorer.metrics(trials.gated(), Map.of()), trials.gated(),
                List.of(), List.of(), trials);

        assertThat(md).contains("## Agreement across trials (informational, not gated)")
                .contains("| extract | 2 | 2 | 1 | 0.50 | 2 | 1 |")
                .contains("| plan | 2 | 1 | 1 | 1.00 | 1 | 0 |")
                .contains("Plan stop order: 0 of 1 cases kept the same order in every trial (0.00).")
                .contains("extract: 1 of 2 trial pairs differ, so the rule of three does not apply (it needs none); the observed rate is 0.50.")
                .contains("plan: 1 trial pairs, none differ, so the per-trial disagreement rate is at most 3/1 = 3.00 at 95% confidence (rule of three).")
                .doesNotContainIgnoringCase("determinis");
        // The existing stability table is untouched, and says "plan" only while only plan cases were repeated.
        assertThat(md).contains("## Stability across repeats (informational, not gated)");
        assertThat(md).contains("how steady each case is");
    }

    @Test
    void thePlanOnlyStabilityTextIsTheOneItAlwaysWasAndAPlanOnlyRepeatAddsOnlyTheAgreementSection() {
        var trials = new EvalScorer.Trials();
        trials.record(1, plan("p", false, H1));
        trials.record(2, plan("p", false, H1));
        var md = EvalScorer.markdown(EvalScorer.header(), EvalScorer.metrics(trials.gated(), Map.of()), trials.gated(),
                List.of(), List.of(), trials);
        assertThat(md).contains("the other trials only show how steady each plan case is.")
                .contains("## Agreement across trials (informational, not gated)");
        var without = new EvalScorer.Trials();
        without.record(1, plan("p", false, H1));
        assertThat(EvalScorer.markdown(EvalScorer.header(), EvalScorer.metrics(without.gated(), Map.of()), without.gated(),
                List.of(), List.of(), without)).doesNotContain("Agreement across trials").doesNotContain("Stability across");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Field-level agreement of the extract cases (S4b-BL-236)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void theStructuredFieldsAreNormalisedOneByOneAndTheAmenitiesAreASet() {
        var fields = Agreement.extractFields(map("price", 28000, "priceType", "RENT", "bedrooms", 2, "locality", "Anna Nagar",
                "contactPhone", "+91 98400 12345", "listingUrl", "https://x.example/a/", "amenities", List.of("Lift", "parking"),
                "label", "2BHK Anna Nagar", "notes", "Deposit 2 months."));
        var again = Agreement.extractFields(map("price", 28000.0, "priceType", "rent", "bedrooms", 2L, "locality", "anna-nagar",
                "contactPhone", "9840012345", "listingUrl", "HTTPS://x.example/a", "amenities", List.of("parking", "lift"),
                "label", "Two BHK, Anna Nagar", "notes", "Deposit: two months", "street", ""));
        for (var f : Agreement.STRUCTURED_FIELDS) assertThat(fields.get(f)).as(f).isEqualTo(again.get(f));
        assertThat(fields.get("amenities")).isEqualTo("lift,parking").isEqualTo(again.get("amenities"));
        assertThat(fields.get("street")).isNull();
        assertThat(again.get("street")).isNull();
        assertThat(fields.get("areaSqft")).isNull();
        assertThat(fields.get("label")).isNotEqualTo(again.get("label"));
        assertThat(fields.get("notes")).isNotEqualTo(again.get("notes"));
        assertThat(fields).doesNotContainKey("warnings");
    }

    @Test
    void fieldAgreementCountsPerFieldAndTheWholeStructuredTupleWithFreeTextApart() {
        // Hand-counted: 3 extract cases run twice. a: all the same. b: price differs (28000 vs 30000), notes differ.
        // c: only the label differs. So price 2/3, the tuple 2/3, every other structured field 3/3, label 2/3, notes 2/3.
        var trials = new EvalScorer.Trials();
        trials.record(1, extract("a", map("price", 28000, "locality", "Adyar", "amenities", List.of("lift"), "label", "x", "notes", "n")));
        trials.record(2, extract("a", map("price", 28000, "locality", "Adyar", "amenities", List.of("Lift"), "label", "x", "notes", "n")));
        trials.record(1, extract("b", map("price", 28000, "locality", "Powai", "label", "y", "notes", "deposit 3 months")));
        trials.record(2, extract("b", map("price", 30000, "locality", "Powai", "label", "y", "notes", "3 months deposit")));
        trials.record(1, extract("c", map("price", 9500, "bedrooms", 0, "label", "1RK Velachery", "notes", "n")));
        trials.record(2, extract("c", map("price", 9500, "bedrooms", 0, "label", "Velachery 1RK", "notes", "n")));
        var byName = new HashMap<String, Agreement.FieldRow>();
        Agreement.fieldRows(trials.perCase()).forEach(r -> byName.put(r.field(), r));

        assertThat(byName.get("price")).isEqualTo(new Agreement.FieldRow("price", 3, 2, false));
        assertThat(byName.get("locality")).isEqualTo(new Agreement.FieldRow("locality", 3, 3, false));
        assertThat(byName.get("bedrooms")).isEqualTo(new Agreement.FieldRow("bedrooms", 3, 3, false));
        assertThat(byName.get("amenities")).isEqualTo(new Agreement.FieldRow("amenities", 3, 3, false));
        assertThat(byName.get(Agreement.STRUCTURED_TUPLE)).isEqualTo(new Agreement.FieldRow(Agreement.STRUCTURED_TUPLE, 3, 2, false));
        assertThat(byName.get("label")).isEqualTo(new Agreement.FieldRow("label", 3, 2, true));
        assertThat(byName.get("notes")).isEqualTo(new Agreement.FieldRow("notes", 3, 2, true));
        assertThat(byName.get("price").differing()).isEqualTo(1);

        var md = EvalScorer.markdown(EvalScorer.header(), EvalScorer.metrics(trials.gated(), Map.of()), trials.gated(),
                List.of(), List.of(), trials);
        assertThat(md).contains("### Field agreement of the extract cases (informational, not gated)")
                .contains("| price | 3 | 2 | 0.67 (95% CI 0.21-0.94) | 1 |")
                .contains("| all structured fields | 3 | 2 | 0.67 (95% CI 0.21-0.94) | 1 |")
                .contains("| label (free text) | 3 | 2 |")
                .doesNotContain("| price (free text)");
    }

    @Test
    void fieldAgreementLeavesOutInfraTrialsAndIsAbsentWithoutARepeatedExtractCase() {
        var trials = new EvalScorer.Trials();
        trials.record(1, extract("a", map("price", 1)));
        trials.record(2, infra(extract("a", map("price", 2))));
        trials.record(1, plan("p", false, H1));
        trials.record(2, plan("p", false, H1));
        assertThat(Agreement.fieldRows(trials.perCase())).isEmpty();
        var md = EvalScorer.markdown(EvalScorer.header(), EvalScorer.metrics(trials.gated(), Map.of()), trials.gated(),
                List.of(), List.of(), trials);
        assertThat(md).contains("## Agreement across trials").doesNotContain("Field agreement");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Which types are repeated
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void theRepeatedTypesAreThePlanCasesUnlessAskedOtherwiseAndTheDefaultIsUnchanged() {
        assertThat(EvalScorer.repeatTypesFrom(null)).containsExactly("plan");
        assertThat(EvalScorer.repeatTypesFrom("")).containsExactly("plan");
        assertThat(EvalScorer.repeatTypesFrom("  ")).containsExactly("plan");
        assertThat(EvalScorer.repeatTypesFrom("plan")).containsExactly("plan");
        assertThat(EvalScorer.repeatTypesFrom(" Plan , extract ")).containsExactly("extract", "plan");
        assertThat(EvalScorer.repeatTypesFrom("ask,extract,plan,ask")).containsExactly("extract", "ask", "plan");
        assertThat(EvalScorer.repeatTypesFrom("sing,ask")).containsExactly("ask");
        assertThat(EvalScorer.repeatTypesFrom("sing")).containsExactly("plan");
    }

    @Test
    void repeatsRunEveryCaseOfTheChosenTypesInTrialOrderAndStopAtTheBudget() {
        var cases = new ArrayList<Map<String, Object>>();
        cases.add(map("id", "extract-1", "type", "extract"));
        cases.add(map("id", "ask-1", "type", "ask"));
        cases.add(map("id", "plan-1", "type", "plan"));
        var seen = new ArrayList<String>();
        var trials = new EvalScorer.Trials();
        var stopped = EvalRun.runRepeats(cases, 3, trials, new Deadline(java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(5)),
                () -> { }, c -> {
                    seen.add(c.get("id") + "");
                    return new CaseResult(String.valueOf(c.get("id")), String.valueOf(c.get("type")), null);
                }, () -> { });
        assertThat(stopped).isFalse();
        assertThat(seen).containsExactly("extract-1", "ask-1", "plan-1", "extract-1", "ask-1", "plan-1");
        assertThat(trials.perCase()).isEmpty(); // trial 1 is the gated run, recorded by the case loop, not here
        trials.record(1, new CaseResult("ask-1", "ask", null));
        assertThat(trials.perCase()).singleElement().satisfies(list -> assertThat(list).hasSize(3));
    }
}
