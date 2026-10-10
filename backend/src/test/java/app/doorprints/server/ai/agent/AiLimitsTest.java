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

import app.doorprints.server.ai.agent.HouseSearchService.Criteria;
import app.doorprints.server.ai.agent.HouseSearchService.HouseSummary;
import app.doorprints.server.ai.agent.PlanModels.AgentPlan;
import app.doorprints.server.ai.agent.PlanModels.AgentStop;
import app.doorprints.server.ai.agent.PlanModels.PlanRequest;
import app.doorprints.server.ai.PromptSafety;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.extract.ListingExtractionService;
import app.doorprints.server.ai.rag.HouseDocuments;
import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseService;
import app.doorprints.server.house.HouseStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AI limits on the server (S4b-BL-181, docs/ai/ai-design.md 7, 9 and the API section): 3,000 characters of notes in
 * a house document and 2,000 in a tool result, each then " …"; 8,000 characters of pasted listing; a plan of at most 8
 * stops (the server's cap, 25 at most); a search that returns 20 houses by default and 50 at most; a start point on
 * Earth. The numbers are written from the rules, not read from the constants.
 */
class AiLimitsTest {

    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static HouseDto house(String notes) {
        return new HouseDto(UUID.randomUUID(), "Plot", null, null, null, 12.97, 77.64, HouseStatus.NEW, null, null, null, null,
                null, null, null, notes, null, null, null, null, null, null, null, null, Map.of(), null, null, false, 1, null);
    }

    private static String documentNotes(String notes) {
        return HouseDocuments.text(house(notes), List.of()).lines().filter(l -> l.startsWith("Notes: ")).findFirst().orElseThrow();
    }

    private static String toolNotes(String notes) {
        return HouseQueries.HouseDetails.of(house(notes)).notes();
    }

    /** A lone half of a surrogate pair is a broken character. */
    private static boolean hasBrokenPair(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && !(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))) return true;
            if (Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)))) return true;
        }
        return false;
    }

    // ---- notes

    @Test
    void aHouseDocumentCutsNotesAtThreeThousandCharactersWithTheMarkerAndNotBefore() {
        assertThat(documentNotes("x".repeat(3001))).isEqualTo("Notes: " + "x".repeat(3000) + " …");
        assertThat(documentNotes("x".repeat(3000))).isEqualTo("Notes: " + "x".repeat(3000));
        assertThat(documentNotes("x".repeat(2999))).isEqualTo("Notes: " + "x".repeat(2999));
        assertThat(documentNotes("  " + "x".repeat(3000) + "  \n")).isEqualTo("Notes: " + "x".repeat(3000));
    }

    @Test
    void aHouseDocumentCutNeverLeavesHalfOfAnEmoji() {
        // The emoji is two UTF-16 units at 2,999 and 3,000: the cut falls between them, so the whole emoji goes.
        assertThat(documentNotes("a".repeat(2999) + "😀tail")).isEqualTo("Notes: " + "a".repeat(2999) + " …");
        assertThat(documentNotes("a".repeat(2998) + "😀tail")).isEqualTo("Notes: " + "a".repeat(2998) + "😀 …");
        assertThat(hasBrokenPair(documentNotes("a".repeat(2999) + "😀tail"))).isFalse();
    }

    @Test
    void aHouseDocumentCutsHindiTamilAndTeluguNotesAtThreeThousandCharactersWithoutBreakingOne() {
        for (var unit : List.of("घर बहुत अच्छा है। ", "வீடு மிகவும் நல்லது. ", "ఇల్లు చాలా బాగుంది. ")) {
            var notes = unit.repeat(3100 / unit.length() + 1);
            var line = documentNotes(notes);
            assertThat(line).endsWith(" …");
            assertThat(line.substring("Notes: ".length(), line.length() - 2)).isEqualTo(notes.substring(0, 3000));
            assertThat(hasBrokenPair(line)).isFalse();
        }
    }

    @Test
    void theCutHelperKeepsATextOfExactlyTheLimitWholeAndDropsAWholeCharacterAtTheEdge() {
        assertThat(PromptSafety.clipUnits("ab", 2)).isEqualTo("ab");
        assertThat(PromptSafety.clipUnits("a", 2)).isEqualTo("a");
        // A text that ends exactly at the limit is returned as it is, even when its last unit is half a pair.
        assertThat(PromptSafety.clipUnits("a\uD83D", 2)).isEqualTo("a\uD83D");
        assertThat(PromptSafety.clipUnits("abc", 2)).isEqualTo("ab");
        assertThat(PromptSafety.clipUnits("a😀", 2)).isEqualTo("a");
        assertThat(PromptSafety.clipUnits("a😀", 3)).isEqualTo("a😀");
        assertThat(PromptSafety.clipUnits("😀😀", 3)).isEqualTo("😀");
    }

    @Test
    void aToolResultCutsNotesAtTwoThousandCharactersWithTheMarkerAndNotBefore() {
        assertThat(toolNotes("x".repeat(2001))).isEqualTo("x".repeat(2000) + " …");
        assertThat(toolNotes("x".repeat(2000))).isEqualTo("x".repeat(2000));
        assertThat(toolNotes("x".repeat(1999))).isEqualTo("x".repeat(1999));
    }

    @Test
    void aToolResultCutNeverLeavesHalfOfAnEmoji() {
        assertThat(toolNotes("a".repeat(1999) + "😀tail")).isEqualTo("a".repeat(1999) + " …");
        assertThat(toolNotes("a".repeat(1998) + "😀tail")).isEqualTo("a".repeat(1998) + "😀 …");
        for (var unit : List.of("घर बहुत अच्छा है। ", "வீடு மிகவும் நல்லது. ", "ఇల్లు చాలా బాగుంది. ")) {
            var notes = unit.repeat(2100 / unit.length() + 1);
            assertThat(toolNotes(notes)).isEqualTo(notes.substring(0, 2000) + " …");
        }
    }

    // ---- a pasted listing

    @Test
    void aListingOfEightThousandCharactersPassesTheLengthCheckAndEightThousandAndOneIsRefused() {
        var props = AiProperties.defaults();
        assertThat(props.maxInputChars()).isEqualTo(8000);
        var extraction = new ListingExtractionService(null, props);
        assertThatThrownBy(() -> extraction.extract("   ")).isInstanceOf(BadRequestException.class)
                .hasMessage("text must not be blank");
        assertThatThrownBy(() -> extraction.extract(null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> extraction.extract("a".repeat(8001))).isInstanceOf(BadRequestException.class)
                .hasMessage("text is longer than 8000 characters");
        // 8,000 gets past the check and reaches the model call, which has no chat client here and so fails as unavailable.
        assertThatThrownBy(() -> extraction.extract("a".repeat(8000))).isNotInstanceOf(BadRequestException.class)
                .hasMessage("Listing extraction failed");
    }

    @Test
    void theExtractEndpointRefusesMoreThanTwentyThousandCharactersAndBlank() {
        assertThat(validator.validate(new app.doorprints.server.ai.web.AiController.ExtractRequest("a".repeat(20000)))).isEmpty();
        assertThat(validator.validate(new app.doorprints.server.ai.web.AiController.ExtractRequest("a".repeat(20001)))).hasSize(1);
        assertThat(validator.validate(new app.doorprints.server.ai.web.AiController.ExtractRequest("   "))).hasSize(1);
    }

    // ---- houses a search returns

    private static HouseService housesOf(int n) {
        var service = mock(HouseService.class);
        var list = new ArrayList<HouseDto>();
        for (int i = 0; i < n; i++) list.add(house(null));
        when(service.list(any())).thenReturn(list);
        return service;
    }

    @Test
    void aSearchReturnsFiftyHousesByDefaultAndAtMostFifty() {
        var search = new HouseSearchService(housesOf(60));
        assertThat(HouseSearchService.MAX_RESULTS).isEqualTo(50);
        assertThat(search.search(null)).hasSize(50);
        assertThat(search.search(new Criteria(null, null, null, null, null, null, null, null, null))).hasSize(50);
        assertThat(search.search(new Criteria(null, null, null, null, null, null, null, null, 7))).hasSize(7);
        assertThat(search.search(new Criteria(null, null, null, null, null, null, null, null, 50))).hasSize(50);
        assertThat(search.search(new Criteria(null, null, null, null, null, null, null, null, 51))).hasSize(50);
        assertThat(search.search(new Criteria(null, null, null, null, null, null, null, null, 1000))).hasSize(50);
        assertThat(search.search(new Criteria(null, null, null, null, null, null, null, null, 0))).hasSize(1);
        assertThat(search.search(new Criteria(null, null, null, null, null, null, null, null, -3))).hasSize(1);
    }

    // ---- the stops of a plan

    private static HouseSummary summary(int i) {
        return new HouseSummary(UUID.nameUUIDFromBytes(("h" + i).getBytes()), "H" + i, "Indiranagar", null, HouseStatus.SHORTLISTED,
                30000L, "RENT", 2, 4, 12.9716 + i * 0.001, 77.5946, null);
    }

    private final List<HouseSummary> twelve = IntStream.range(0, 12).mapToObj(AiLimitsTest::summary).toList();
    private final Map<UUID, HouseSummary> seen = new LinkedHashMap<>();

    {
        twelve.forEach(h -> seen.put(h.id(), h));
    }

    private List<UUID> ids(int from, int to) {
        return twelve.subList(from, to).stream().map(HouseSummary::id).toList();
    }

    @Test
    void aPlanStopsAtTheCapWithTheFirstHousesTheModelNamedInItsOrder() {
        var plan = new AgentPlan("s", twelve.stream().map(h -> new AgentStop(h.id().toString(), "near")).toList());
        assertThat(VisitPlannerService.assemble(plan, seen, List.of(), 12.9716, 77.5946, 8).stops())
                .extracting(PlanModels.PlannedStop::houseId).containsExactlyElementsOf(ids(0, 8));
        assertThat(VisitPlannerService.assemble(plan, seen, List.of(), 12.9716, 77.5946, 3).stops())
                .extracting(PlanModels.PlannedStop::houseId).containsExactlyElementsOf(ids(0, 3));
        var eight = new AgentPlan("s", plan.stops().subList(0, 8));
        assertThat(VisitPlannerService.assemble(eight, seen, List.of(), 12.9716, 77.5946, 8).stops()).hasSize(8);
    }

    @Test
    void theFallbackRouteKeepsTheCapTheEightNearestHousesInTheRunning() {
        var madeUp = new AgentPlan("s", List.of(new AgentStop("made-up", "x")));
        var fallback = VisitPlannerService.assemble(madeUp, seen, List.of(), 12.9716, 77.5946, 8);
        assertThat(fallback.fallback()).isTrue();
        assertThat(fallback.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactlyElementsOf(ids(0, 8));
        assertThat(VisitPlannerService.assemble(null, seen, List.of(), 12.9716, 77.5946, 2).stops())
                .extracting(PlanModels.PlannedStop::houseId).containsExactlyElementsOf(ids(0, 2));
    }

    @Test
    void aRequestMayAskForFewerStopsThanTheServerCapNeverForMore() {
        assertThat(VisitPlannerService.maxStops(null, 8)).isEqualTo(8);
        assertThat(VisitPlannerService.maxStops(3, 8)).isEqualTo(3);
        assertThat(VisitPlannerService.maxStops(8, 8)).isEqualTo(8);
        assertThat(VisitPlannerService.maxStops(9, 8)).isEqualTo(8);
        assertThat(VisitPlannerService.maxStops(25, 8)).isEqualTo(8);
        assertThat(VisitPlannerService.maxStops(25, 25)).isEqualTo(25);
    }

    @Test
    void theServerCapForStopsIsEightByDefaultAndTwentyFiveAtMost() {
        assertThat(AiProperties.defaults().agent().maxStops()).isEqualTo(8);
        assertThat(new AiProperties.Agent(null, null, 12).maxStops()).isEqualTo(12);
        assertThat(new AiProperties.Agent(null, null, 25).maxStops()).isEqualTo(25);
        assertThat(new AiProperties.Agent(null, null, 26).maxStops()).isEqualTo(25);
        assertThat(new AiProperties.Agent(null, null, 1000).maxStops()).isEqualTo(25);
        assertThat(new AiProperties.Agent(null, null, 0).maxStops()).isEqualTo(8);
        assertThat(new AiProperties.Agent(null, null, -2).maxStops()).isEqualTo(8);
    }

    // ---- the request

    private static PlanRequest request(Double lat, Double lon, Integer maxStops) {
        return new PlanRequest("A walk", lat, lon, maxStops);
    }

    private List<String> invalid(PlanRequest request) {
        return validator.validate(request).stream().map(v -> v.getPropertyPath().toString()).sorted().toList();
    }

    @Test
    void aStartPointOnEarthIsAcceptedPolesAndDateLineIncluded() {
        assertThat(invalid(request(90.0, 180.0, null))).isEmpty();
        assertThat(invalid(request(-90.0, -180.0, 1))).isEmpty();
        assertThat(invalid(request(12.9716, 77.5946, 25))).isEmpty();
    }

    @Test
    void aLatitudeOutsideMinusNinetyToNinetyIsRefused() {
        for (double lat : new double[] {90.0001, -90.0001, 91, -91, 1000, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
            assertThat(invalid(request(lat, 77.0, null))).as("latitude %s", lat).containsOnly("startLat");
        }
    }

    @Test
    void aLongitudeOutsideMinusOneEightyToOneEightyIsRefused() {
        for (double lon : new double[] {180.0001, -180.0001, 181, -181, 1000, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
            assertThat(invalid(request(12.0, lon, null))).as("longitude %s", lon).containsOnly("startLon");
        }
    }

    @Test
    void aMissingStartPointAndOutOfRangeStopsAreRefused() {
        assertThat(invalid(request(null, 77.0, null))).containsExactly("startLat");
        assertThat(invalid(request(12.0, null, null))).containsExactly("startLon");
        assertThat(invalid(request(12.0, 77.0, 0))).containsExactly("maxStops");
        assertThat(invalid(request(12.0, 77.0, 26))).containsExactly("maxStops");
        assertThat(invalid(request(12.0, 77.0, -1))).containsExactly("maxStops");
    }

    @Test
    void theQuestionOfARequestIsBlankOrAtMostFourThousandCharacters() {
        assertThat(invalid(new PlanRequest("q".repeat(4000), 12.0, 77.0, null))).isEmpty();
        assertThat(invalid(new PlanRequest("q".repeat(4001), 12.0, 77.0, null))).containsExactly("question");
        assertThat(invalid(new PlanRequest("  ", 12.0, 77.0, null))).containsExactly("question");
    }

    @Test
    void nearbyRefusesACoordinateThatIsNotANumber() {
        var search = new HouseSearchService(null);
        assertThatThrownBy(() -> search.nearby(Double.NaN, 77, 100)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> search.nearby(12, Double.NaN, 100)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> search.nearby(Double.POSITIVE_INFINITY, 77, 100)).isInstanceOf(BadRequestException.class);
    }
}
