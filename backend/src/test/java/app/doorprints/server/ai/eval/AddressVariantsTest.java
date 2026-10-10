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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The address variants (S4b-BL-225): the whitelist, the identity of the default, the variant-safe rule, the anchors, the
 * fingerprints and the golden set's own consistency checks on every applied set. Needs no model and no key. The
 * TypeScript port is pinned by {@code web/src/app/core/ai/address-variants.spec.ts} against the same file and the same
 * fingerprints.
 */
class AddressVariantsTest {

    private static final List<String> SETS = List.of("known", "known-alt", "unknown-invented", "landmark-pin", "vernacular",
            "vernacular-strict", "messy", "hostile");

    /** The cases a set leaves out, found by a separate calculation (a Python script reading the same file), not by this code. */
    private static final Map<String, List<String>> NOT_APPLICABLE = Map.of(
            "known", List.of(),
            "known-alt", List.of(),
            "unknown-invented", List.of(),
            "landmark-pin", List.of("extract-04-injection", "ask-03-why-rejected", "ask-07-injection-reveal-prompt",
                    "plan-04-injection-notes"),
            "vernacular", List.of("extract-01-whatsapp-rent", "extract-04-injection", "ask-03-why-rejected",
                    "ask-07-injection-reveal-prompt", "plan-04-injection-notes"),
            "vernacular-strict", List.of("extract-01-whatsapp-rent", "extract-04-injection", "ask-03-why-rejected",
                    "ask-07-injection-reveal-prompt", "plan-04-injection-notes", "extract-15-delhi-saket-sale-sq-yd",
                    "extract-19-pune-marathi-lakhs", "extract-20-ahmedabad-vegetarian", "extract-22-lucknow-lakh-rent",
                    "extract-24-guwahati-katha-link", "extract-25-chandigarh-marla-no-contact", "extract-26-goa-studio",
                    "extract-27-dehradun-two-and-a-half", "extract-28-shimla-95-l", "extract-29-kolkata-price-on-request",
                    "extract-32-hyderabad-villa-sale", "extract-33-chennai-conflicting-rent", "ask-18-kolkata-lift",
                    "ask-19-pune-noisy", "ask-21-mumbai-parking", "ask-24-lucknow-property-tax-unknown",
                    "ask-30-hyderabad-pets", "plan-07-mumbai-afternoon", "plan-08-pune-saturday",
                    "plan-09-chennai-and-guwahati", "plan-10-dehradun-and-shimla"),
            "messy", List.of(),
            "hostile", List.of());

    private static GoldenSet golden() throws Exception {
        return GoldenSet.load(GoldenSet.locate());
    }

    private static AddressVariants variants() throws Exception {
        return AddressVariants.load(AddressVariants.locate());
    }

    private static Map<String, Object> byId(List<Map<String, Object>> list, Object id) {
        return list.stream().filter(x -> String.valueOf(x.get("id")).equals(String.valueOf(id))).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The default, and a set that changes nothing
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void theDefaultIsTheInputObjectItselfWhetherUnsetBlankOrNamed() throws Exception {
        var golden = golden();
        var v = variants();
        for (var name : new String[] {null, "", "   ", "default"}) {
            var applied = v.apply(golden, name);
            assertThat(applied.golden()).as("set %s", name).isSameAs(golden);
            assertThat(applied.set()).isEqualTo("default");
            assertThat(applied.notApplicable()).isEmpty();
        }
    }

    @Test
    void knownChangesNothingAndHasTheFingerprintOfTheDefault() throws Exception {
        var golden = golden();
        var v = variants();
        var applied = v.apply(golden, "known");
        assertThat(applied.golden()).isSameAs(golden);
        assertThat(AddressVariants.fingerprint(applied.golden())).isEqualTo(AddressVariants.fingerprint(golden))
                .isEqualTo(v.recordedFingerprint("known"));
    }

    @Test
    void aSetThatIsNotInTheFileIsRefusedWithTheNamesThatAre() throws Exception {
        assertThatThrownBy(() -> variants().apply(golden(), "unknown-invented-typo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-invented-typo").hasMessageContaining("known-alt").hasMessageContaining("hostile");
    }

    @Test
    void applyNeverChangesTheGoldenSetItWasGiven() throws Exception {
        var golden = golden();
        var before = AddressVariants.canonicalJson(golden.root());
        var v = variants();
        for (var name : SETS) v.apply(golden, name);
        assertThat(AddressVariants.canonicalJson(golden.root())).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The whitelist
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void aSetChangesOnlyTheAddressLikeTextOfAHouseAndNothingElse() throws Exception {
        var golden = golden();
        var v = variants();
        for (var name : SETS) {
            var applied = v.apply(golden, name).golden();
            assertThat(applied.fixtureHouses()).as("%s: houses", name).hasSameSizeAs(golden.fixtureHouses());
            for (int i = 0; i < golden.fixtureHouses().size(); i++) {
                var was = golden.fixtureHouses().get(i);
                var now = applied.fixtureHouses().get(i);
                assertThat(now.keySet()).as("%s: keys of house %d", name, i).containsExactlyElementsOf(was.keySet());
                for (var key : was.keySet()) {
                    if (List.of("address", "street", "locality", "label", "notes").contains(key)) continue;
                    assertThat(AddressVariants.canonicalJson(now.get(key))).as("%s: %s of house %d", name, key, i)
                            .isEqualTo(AddressVariants.canonicalJson(was.get(key)));
                }
            }
            assertThat(AddressVariants.canonicalJson(applied.fixtureVisits())).as("%s: visits", name)
                    .isEqualTo(AddressVariants.canonicalJson(golden.fixtureVisits()));
            assertThat(applied.root().get("thresholds")).as("%s: thresholds", name).isEqualTo(golden.root().get("thresholds"));
        }
    }

    @Test
    void aSetChangesOnlyTheNamedPartsOfACaseAndGuardsAreAppendedToTheRightList() throws Exception {
        var golden = golden();
        var v = variants();
        var changedSomething = new HashSet<String>();
        for (var name : SETS) {
            var guards = GoldenSet.strings(v.set(name).get("guards"));
            var applied = v.apply(golden, name);
            for (var c : applied.golden().cases()) {
                var was = byId(golden.cases(), c.get("id"));
                assertThat(c.keySet()).containsExactlyElementsOf(was.keySet());
                for (var key : List.of("id", "type", "category", "region")) assertThat(c.get(key)).isEqualTo(was.get(key));
                var input = GoldenSet.map(c.get("input"));
                var wasInput = GoldenSet.map(was.get("input"));
                assertThat(input.keySet()).containsExactlyElementsOf(wasInput.keySet());
                for (var key : wasInput.keySet()) {
                    if (key.equals("text") || key.equals("question")) continue;
                    assertThat(input.get(key)).as("%s %s input.%s", name, c.get("id"), key).isEqualTo(wasInput.get(key));
                }
                var expected = GoldenSet.map(c.get("expected"));
                var wasExpected = GoldenSet.map(was.get("expected"));
                var guardKey = switch (String.valueOf(c.get("type"))) {
                    case "ask" -> "mustNotContain";
                    case "plan" -> "summaryMustNotContain";
                    default -> "notesMustNotContain";
                };
                for (var key : wasExpected.keySet()) {
                    if (key.equals("mustContain") || key.equals("locality") || (!guards.isEmpty() && key.equals(guardKey))) continue;
                    assertThat(AddressVariants.canonicalJson(expected.get(key))).as("%s %s expected.%s", name, c.get("id"), key)
                            .isEqualTo(AddressVariants.canonicalJson(wasExpected.get(key)));
                }
                assertThat(wasExpected.keySet()).containsAll(expected.keySet().stream()
                        .filter(k -> !(k.equals(guardKey) && !guards.isEmpty())).toList());
                for (var g : guards) assertThat(GoldenSet.strings(expected.get(guardKey))).as("%s %s guard", name, c.get("id")).contains(g);
                if (!AddressVariants.canonicalJson(c).equals(AddressVariants.canonicalJson(was))) changedSomething.add(name);
            }
        }
        assertThat(changedSomething).containsExactlyInAnyOrder("known-alt", "unknown-invented", "landmark-pin", "hostile");
    }

    private static final String MINI_GOLDEN = """
            {"version": "t", "fixtureHouses": [
              {"id": "aaaaaaaa-0000-4000-8000-000000000001", "city": "Pune", "label": "Kothrud 2BHK", "address": "Karve Road, Kothrud, Pune",
               "street": "Karve Road", "locality": "Kothrud", "lat": 18.5, "lon": 73.8, "status": "NEW", "price": 30000, "notes": "Quiet lane."},
              {"id": "aaaaaaaa-0000-4000-8000-000000000002", "city": "Pune", "label": "Baner Road 120 PG flat", "address": "Baner Road, Baner, Pune",
               "street": "Baner Road", "locality": "Baner", "lat": 18.55, "lon": 73.78, "status": "NEW", "price": 40000, "notes": ""}],
             "fixtureVisits": [],
             "cases": [
              {"id": "ask-place", "type": "ask", "input": {"question": "Is the Kothrud flat quiet?"}, "expected": {"mustContain": ["quiet"]}},
              {"id": "ask-capitals", "type": "ask", "input": {"question": "Is the KOTHRUD flat quiet?"}, "expected": {}},
              {"id": "ask-longer-word", "type": "ask", "input": {"question": "Is the Kothrudian flat quiet?"}, "expected": {}},
              {"id": "ask-no-place", "type": "ask", "input": {"question": "Which flat is cheapest?"}, "expected": {}},
              {"id": "ask-in-expected", "type": "ask", "input": {"question": "Which flat is quiet?"}, "expected": {"mustContain": ["Kothrud"]}},
              {"id": "ask-in-note-only", "type": "ask", "input": {"question": "Which flat is cheapest?"}, "expected": {"note": "Kothrud is the answer."}},
              {"id": "ask-rewritten", "type": "ask", "input": {"question": "Is the Kothrud flat quiet?"}, "expected": {"mustContain": ["Kothrud"]}},
              {"id": "ask-other-house", "type": "ask", "input": {"question": "Is the Baner flat big?"}, "expected": {}},
              {"id": "ask-city", "type": "ask", "input": {"question": "Which Pune flat is cheapest?"}, "expected": {}},
              {"id": "ask-generic-word", "type": "ask", "input": {"question": "Is there a road nearby?"}, "expected": {}},
              {"id": "ask-number", "type": "ask", "input": {"question": "Is house 120 big?"}, "expected": {}},
              {"id": "ask-short-word", "type": "ask", "input": {"question": "Is the PG big?"}, "expected": {}},
              {"id": "extract-text", "type": "extract", "input": {"text": "2BHK in Kothrud, 30k"}, "expected": {"locality": "Kothrud", "price": 30000}}
             ]}""";

    private static AddressVariants mini(String sets) {
        return AddressVariants.parse("{\"version\": \"t\", \"genericWords\": [\"road\"], \"sets\": {" + sets + "}}");
    }

    private static final String ID1 = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String ID2 = "aaaaaaaa-0000-4000-8000-000000000002";

    @Test
    void aHouseRowCannotNameAnythingButTheFiveAddressFields() {
        var golden = GoldenSet.parse(MINI_GOLDEN);
        for (var key : List.of("id", "city", "region", "lat", "lon", "status", "price", "priceType", "bedrooms", "rating",
                "checklist", "contactName", "contactPhone", "listingUrl", "notes", "updatedAt", "deleted")) {
            var v = mini("\"bad\": {\"houses\": {\"" + ID1 + "\": {\"" + key + "\": \"x\"}}}");
            assertThatThrownBy(() -> v.apply(golden, "bad")).as("key %s", key)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'" + key + "'");
        }
        var notAString = mini("\"bad\": {\"houses\": {\"" + ID1 + "\": {\"address\": 12}}}");
        assertThatThrownBy(() -> notAString.apply(golden, "bad")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("string");
        var noSuchHouse = mini("\"bad\": {\"houses\": {\"aaaaaaaa-0000-4000-8000-0000000000ff\": {\"address\": \"x\"}}}");
        assertThatThrownBy(() -> noSuchHouse.apply(golden, "bad")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a fixture house");
    }

    @Test
    void aCaseRowCannotNameAnythingButTheTextTheQuestionMustContainAndLocality() {
        var golden = GoldenSet.parse(MINI_GOLDEN);
        var refused = Map.of(
                "\"id\": \"x\"", "may not change 'id'",
                "\"type\": \"ask\"", "may not change 'type'",
                "\"input\": {\"filters\": {}}", "input.filters",
                "\"input\": {\"startLat\": 1}", "input.startLat",
                "\"input\": {\"text\": \"x\"}", "may change only input.question of the ask case",
                "\"expected\": {\"price\": 1}", "expected.price",
                "\"expected\": {\"expectedHouseIds\": []}", "expected.expectedHouseIds",
                "\"expected\": {\"answerEquals\": \"x\"}", "expected.answerEquals",
                "\"expectedDelete\": [\"mustContain\"]", "only expected.locality");
        refused.forEach((row, message) -> {
            var v = mini("\"bad\": {\"cases\": {\"ask-place\": {" + row + "}}}");
            assertThatThrownBy(() -> v.apply(golden, "bad")).as(row).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(message);
        });
        var question = mini("\"bad\": {\"cases\": {\"extract-text\": {\"input\": {\"question\": \"x\"}}}}");
        assertThatThrownBy(() -> question.apply(golden, "bad")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only input.text of the extract case");
        var noSuchCase = mini("\"bad\": {\"cases\": {\"no-such-case\": {\"input\": {\"question\": \"x\"}}}}");
        assertThatThrownBy(() -> noSuchCase.apply(golden, "bad")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the golden set");
    }

    @Test
    void notesAreTheOriginalThenASpaceThenTheAppendAndTheAppendAloneWhenThereAreNone() throws Exception {
        var golden = GoldenSet.parse(MINI_GOLDEN);
        var v = mini("\"s\": {\"houses\": {\"" + ID1 + "\": {\"notesAppend\": \"City: Pune.\"}, \"" + ID2
                + "\": {\"notesAppend\": \"City: Pune.\"}}}");
        var houses = v.apply(golden, "s").golden().fixtureHouses();
        assertThat(houses.get(0).get("notes")).isEqualTo("Quiet lane. City: Pune.");
        assertThat(houses.get(1).get("notes")).isEqualTo("City: Pune.");
        assertThat(golden.fixtureHouses().get(0).get("notes")).isEqualTo("Quiet lane.");
        // On the real file: the notes of a vernacular house keep their text and end with the English city word.
        var real = golden();
        var applied = variants().apply(real, "vernacular").golden();
        var mumbai = byId(applied.fixtureHouses(), "bbbbbbbb-0000-4000-8000-000000000001");
        assertThat(String.valueOf(mumbai.get("notes"))).startsWith(String.valueOf(byId(real.fixtureHouses(),
                "bbbbbbbb-0000-4000-8000-000000000001").get("notes"))).endsWith(" City: Mumbai.");
    }

    // ---------------------------------------------------------------------------------------------------------
    // The variant-safe rule
    // ---------------------------------------------------------------------------------------------------------

    private static List<String> run(AddressVariants.Applied applied) {
        return applied.golden().cases().stream().map(c -> String.valueOf(c.get("id"))).toList();
    }

    @Test
    void aCaseRunsWhenItIsRewrittenOrNoChangedPlaceWordIsInIt() {
        var golden = GoldenSet.parse(MINI_GOLDEN);
        var v = mini("\"s\": {\"houses\": {\"" + ID1 + "\": {\"address\": \"Plot 9, Lakeside Colony, Pune\", \"locality\": \"Lakeside\", "
                + "\"label\": \"Lakeside 2BHK\"}}, \"cases\": {\"ask-rewritten\": {\"input\": {\"question\": \"Is the Lakeside flat quiet?\"}, "
                + "\"expected\": {\"mustContain\": [\"Lakeside\"]}}}}");
        var applied = v.apply(golden, "s");
        assertThat(applied.notApplicable()).containsExactly("ask-place", "ask-capitals", "ask-in-expected", "extract-text");
        assertThat(run(applied)).containsExactly("ask-longer-word", "ask-no-place", "ask-in-note-only", "ask-rewritten",
                "ask-other-house", "ask-city", "ask-generic-word", "ask-number", "ask-short-word");
        var rewritten = byId(applied.golden().cases(), "ask-rewritten");
        assertThat(GoldenSet.map(rewritten.get("input")).get("question")).isEqualTo("Is the Lakeside flat quiet?");
        assertThat(GoldenSet.strings(GoldenSet.map(rewritten.get("expected")).get("mustContain"))).containsExactly("Lakeside");
    }

    @Test
    void aWordTheNewTextStillHoldsOrTheFileCallsGenericOrWithoutALetterIsNotAChangedPlace() {
        var golden = GoldenSet.parse(MINI_GOLDEN);
        // House 2 loses "baner", "road" (generic in this file), "120" (no letter) and "pg" (two letters); its address still names Pune.
        var v = mini("\"s\": {\"houses\": {\"" + ID2 + "\": {\"address\": \"Pashan Link, Pune\", \"street\": \"Pashan Link\", "
                + "\"locality\": \"Pashan\", \"label\": \"Pashan flat\"}}}");
        var applied = v.apply(golden, "s");
        assertThat(applied.notApplicable()).containsExactly("ask-other-house");
        assertThat(run(applied)).contains("ask-generic-word", "ask-number", "ask-short-word", "ask-city", "ask-place");
    }

    @Test
    void theCityIsAChangedPlaceOnlyWhenNothingOfTheHouseNamesItAnyMore() {
        var golden = GoldenSet.parse(MINI_GOLDEN);
        var without = mini("\"s\": {\"houses\": {\"" + ID1 + "\": {\"address\": \"behind the red temple\"}}}");
        assertThat(without.apply(golden, "s").notApplicable()).containsExactly("ask-city");
        var inTheNotes = mini("\"s\": {\"houses\": {\"" + ID1 + "\": {\"address\": \"behind the red temple\", \"notesAppend\": \"City: Pune.\"}}}");
        assertThat(inTheNotes.apply(golden, "s").notApplicable()).isEmpty();
    }

    @Test
    void theCasesTheRealSetsLeaveOutAreTheOnesAnIndependentCalculationFound() throws Exception {
        var golden = golden();
        var v = variants();
        for (var name : SETS) {
            var applied = v.apply(golden, name);
            assertThat(applied.notApplicable()).as("not applicable under %s", name).containsExactlyElementsOf(NOT_APPLICABLE.get(name));
            assertThat(applied.golden().cases()).as("cases run under %s", name)
                    .hasSize(golden.cases().size() - NOT_APPLICABLE.get(name).size());
            var left = new HashSet<>(NOT_APPLICABLE.get(name));
            assertThat(run(applied)).as("order under %s", name).containsExactlyElementsOf(golden.cases().stream()
                    .map(c -> String.valueOf(c.get("id"))).filter(id -> !left.contains(id)).toList());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Anchors, limits and the golden set's own checks on every applied set
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void everySetKeepsItsCityAnchorAndTheHouseFieldLimits() throws Exception {
        var golden = golden();
        var v = variants();
        var rules = new LinkedHashMap<String, String>();
        for (var name : SETS) {
            var rule = v.anchorRule(name);
            rules.put(name, rule);
            assertThat(AddressVariants.ANCHOR_RULES).contains(rule);
            var applied = v.apply(golden, name).golden();
            EvalScorerTest.assertRegions(applied, rule, false);
            for (var h : applied.fixtureHouses()) {
                // HouseDto: label, street, locality at most 200 characters, address 500, notes 20,000.
                assertThat(String.valueOf(h.get("label")).length()).as("%s label", name).isLessThanOrEqualTo(200);
                assertThat(String.valueOf(h.get("street")).length()).as("%s street", name).isLessThanOrEqualTo(200);
                assertThat(String.valueOf(h.get("locality")).length()).as("%s locality", name).isLessThanOrEqualTo(200);
                assertThat(String.valueOf(h.get("address")).length()).as("%s address", name).isLessThanOrEqualTo(500);
                assertThat(String.valueOf(h.get("notes")).length()).as("%s notes", name).isLessThanOrEqualTo(20000);
            }
        }
        assertThat(rules).containsEntry("vernacular-strict", "none").containsEntry("landmark-pin", "city-word-in-notes")
                .containsEntry("vernacular", "city-word-in-notes").containsEntry("known-alt", "city-word-in-address");
    }

    @Test
    void theGoldenSetsOwnConsistencyChecksHoldOnEveryAppliedSet() throws Exception {
        var golden = golden();
        var v = variants();
        for (var name : SETS) {
            var applied = v.apply(golden, name).golden();
            EvalScorerTest.assertConsistent(applied, "known".equals(name));
            EvalScorerTest.assertSynthetic(applied);
            EvalScorerTest.assertExpectationsAreInTheText(applied, "known".equals(name));
            EvalScorerTest.assertPastedListingsKeepToTheSanitiser(applied);
            assertThat(applied.cases().stream().map(c -> c.get("id")).distinct().count()).as(name).isEqualTo(applied.cases().size());
        }
    }

    @Test
    void messyHasAnEmptyAddressAndAFourHundredCharacterOne() throws Exception {
        var houses = variants().apply(golden(), "messy").golden().fixtureHouses();
        assertThat(String.valueOf(houses.get(3).get("address"))).isEmpty();
        assertThat(String.valueOf(houses.get(4).get("address"))).hasSize(400);
        assertThat(houses.stream().map(h -> String.valueOf(h.get("address"))).filter(String::isEmpty)).hasSize(1);
    }

    @Test
    void hostileAddsItsGuardsToEveryCaseAndTheDefaultDoesNotHaveThem() throws Exception {
        var v = variants();
        var guards = GoldenSet.strings(v.set("hostile").get("guards"));
        assertThat(guards).isNotEmpty();
        var applied = v.apply(golden(), "hostile").golden();
        assertThat(applied.cases()).hasSize(75);
        for (var c : applied.cases()) {
            var key = switch (String.valueOf(c.get("type"))) {
                case "ask" -> "mustNotContain";
                case "plan" -> "summaryMustNotContain";
                default -> "notesMustNotContain";
            };
            assertThat(GoldenSet.strings(GoldenSet.map(c.get("expected")).get(key))).as(String.valueOf(c.get("id"))).containsAll(guards);
        }
        // A guard already in a case is not added twice (evil.example is in four default cases).
        for (var c : applied.cases()) {
            for (var key : List.of("mustNotContain", "summaryMustNotContain", "notesMustNotContain")) {
                var list = GoldenSet.strings(GoldenSet.map(c.get("expected")).get(key));
                assertThat(list).as("%s %s", c.get("id"), key).doesNotHaveDuplicates();
            }
        }
        var houses = applied.fixtureHouses();
        assertThat(houses).allSatisfy(h -> assertThat(String.valueOf(h.get("address"))).contains(". "));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fingerprints and the file
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void theFingerprintOfEverySetIsTheOneTheFileRecordsAndTheTypeScriptPortChecksTheSame() throws Exception {
        var golden = golden();
        var v = variants();
        assertThat(GoldenSet.map(v.root().get("fingerprints")).keySet()).containsExactlyElementsOf(SETS);
        var seen = new HashSet<String>();
        for (var name : SETS) {
            var fp = AddressVariants.fingerprint(v.apply(golden, name).golden());
            assertThat(fp).as("fingerprint of %s", name).matches("[0-9a-f]{64}").isEqualTo(v.recordedFingerprint(name));
            assertThat(seen.add(fp)).as("%s differs from every other set", name).isTrue();
        }
    }

    @Test
    void canonicalJsonSortsKeysDropsWhiteSpaceAndWritesNumbersAndStringsLikeJsonStringify() {
        var m = new LinkedHashMap<String, Object>();
        m.put("b", List.of(1, 2.5, 3.0, "x"));
        m.put("a", new LinkedHashMap<>(Map.of("z", true, "y", "q\"\\\n\t\u0001éह")));
        m.put("n", null);
        m.put("big", 12500000);
        m.put("lat", 12.9731);
        assertThat(AddressVariants.canonicalJson(m)).isEqualTo(
                "{\"a\":{\"y\":\"q\\\"\\\\\\n\\t\\u0001éह\",\"z\":true},\"b\":[1,2.5,3,\"x\"],\"big\":12500000,\"lat\":12.9731,\"n\":null}");
    }

    @Test
    void theFileIsWellFormedAndItsContentIsSynthetic() throws Exception {
        var v = variants();
        var golden = golden();
        assertThat(v.version()).isEqualTo("0.1");
        var changes = GoldenSet.maps(v.root().get("changes"));
        assertThat(changes.get(changes.size() - 1).get("version")).isEqualTo(v.version());
        assertThat(v.setNames()).containsExactlyElementsOf(SETS);
        assertThat(v.genericWords()).isNotEmpty().allSatisfy(w -> assertThat(w).isEqualTo(w.toLowerCase(Locale.ROOT)));
        var houseIds = golden.fixtureHouseIds();
        var caseIds = golden.cases().stream().map(c -> String.valueOf(c.get("id"))).toList();
        for (var name : SETS) {
            var set = v.set(name);
            assertThat(String.valueOf(set.get("description"))).isNotBlank();
            var rows = GoldenSet.map(set.get("houses"));
            if ("known".equals(name)) {
                assertThat(rows).isEmpty();
            } else {
                assertThat(rows.keySet().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList())
                        .as("%s covers every fixture house in order", name).containsExactlyElementsOf(houseIds);
            }
            assertThat(caseIds).containsAll(GoldenSet.map(set.get("cases")).keySet());
            assertThat(GoldenSet.map(set.get("cases")).keySet()).as("%s: rewritten cases are not repeated", name).doesNotHaveDuplicates();
        }
        // No real phone number: every ten-digit run in the file is one nobody owns (a made-up tail), like the golden set's.
        var phone = Pattern.compile("(?:\\+91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}");
        var fake = Pattern.compile("[6-9]\\d{4}[ -]?(?:12345|00000|55555)");
        var file = java.nio.file.Files.readString(AddressVariants.locate());
        // The hashes at the end are hex, not numbers, and the house ids are UUIDs (66666666-6666-...): neither is a phone.
        var m = phone.matcher(file.substring(0, file.indexOf("\"fingerprints\""))
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", ""));
        // The rewritten listings repeat the golden set's own numbers (extract-02 keeps "+91-99001-23456"): those are the
        // golden set's to vouch for; any number the file adds has to look made up.
        var goldenText = java.nio.file.Files.readString(GoldenSet.locate());
        while (m.find()) {
            if (goldenText.contains(m.group())) continue;
            assertThat(fake.matcher(m.group().replace("+91", "").strip()).matches()).as(m.group()).isTrue();
        }
    }
}
