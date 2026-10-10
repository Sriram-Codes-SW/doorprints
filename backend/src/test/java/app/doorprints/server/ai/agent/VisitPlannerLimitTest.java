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

import app.doorprints.server.ai.agent.HouseSearchService.HouseSummary;
import app.doorprints.server.ai.agent.PlanModels.PlanRequest;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.web.AiUnavailableException;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseService;
import app.doorprints.server.house.HouseStatus;
import app.doorprints.server.visit.VisitRepository;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S4b-BL-194 item 3, the planner at its tool limit. The real ChatClient, tool-calling advisor and tool manager run over a
 * scripted model: golden cases plan-08 and plan-02 each ended with the fifth {@code searchHouses} attempt (the per-tool
 * budget is 4), which threw and left the nearest-neighbour fallback. The planner must now make ONE wrap-up call with no
 * tools; the fallback is for a failed wrap-up only. A unit test cannot show what a live model does with the wrap-up text.
 */
class VisitPlannerLimitTest {

    private static final double LAT = 18.5204;
    private static final double LON = 73.8567;
    private static final List<String> FIVE_SEARCHES = List.of("searchHouses", "searchHouses", "searchHouses",
            "searchHouses", "searchHouses");

    private final HouseService houseService = mock(HouseService.class);
    private final HouseQueries queries = new HouseQueries(new HouseSearchService(houseService), mock(VisitRepository.class));
    private final List<HouseDto> saved = new ArrayList<>();

    private HouseDto house(String label, String locality, double lat, String notes) {
        var h = new HouseDto(UUID.randomUUID(), label, "addr", "Main Road", locality, lat, LON, HouseStatus.SHORTLISTED,
                32000L, "RENT", 2, 5, "Lakshmi Narayanan", "+91 99001 23456", null, notes, null, null, null, null, null,
                null, null, null, Map.of(), null, null, false, 1, null);
        saved.add(h);
        return h;
    }

    /** A scripted model: the tool turns it asks for, then what it answers with tools on, and with tools off. */
    private static final class Script implements ChatModel {
        final List<String> toolTurns;
        final String finalAnswer;
        final Function<Prompt, ChatResponse> wrapUp;
        final List<Prompt> withTools = new ArrayList<>();
        final List<Prompt> withoutTools = new ArrayList<>();
        /** When set, the model call after the scripted tool turns fails with this instead of answering. */
        RuntimeException failAfterTools;

        Script(List<String> toolTurns, String finalAnswer, Function<Prompt, ChatResponse> wrapUp) {
            this.toolTurns = toolTurns;
            this.finalAnswer = finalAnswer;
            this.wrapUp = wrapUp;
        }

        /** Like a real provider model, it defaults to tool-calling options, so the client can attach the tools. */
        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean tools = prompt.getOptions() instanceof ToolCallingChatOptions o && o.getToolCallbacks() != null
                    && !o.getToolCallbacks().isEmpty();
            if (!tools) {
                withoutTools.add(prompt);
                return wrapUp.apply(prompt);
            }
            int turn = withTools.size();
            withTools.add(prompt);
            if (turn < toolTurns.size()) return toolCall(turn, toolTurns.get(turn));
            if (failAfterTools != null) throw failAfterTools;
            return text(finalAnswer);
        }
    }

    private static ChatResponse toolCall(int turn, String tool) {
        var args = switch (tool) {
            case "nearbyHouses" -> "{\"lat\":18.52,\"lon\":73.85}";
            case "estimateWalkMinutes" -> "{\"fromLat\":1,\"fromLon\":1,\"toLat\":1,\"toLon\":2}";
            case "orderByNearestNeighbour" -> "{\"houseIds\":[]}";
            default -> "{}";
        };
        var message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + turn, "function", tool, args))).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse text(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    private static String plan(String summary, UUID... ids) {
        var stops = new StringBuilder();
        for (var id : ids) {
            if (!stops.isEmpty()) stops.append(',');
            stops.append("{\"houseId\":\"").append(id).append("\",\"reason\":\"fits\"}");
        }
        return "{\"summary\":\"" + summary + "\",\"stops\":[" + stops + "]}";
    }

    private PlanModels.PlanResponse run(Script model) {
        when(houseService.list(any())).thenReturn(List.copyOf(saved));
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> none = mock(ObjectProvider.class);
        when(none.getIfUnique(any())).thenReturn(ObservationRegistry.NOOP);
        var service = new VisitPlannerService(ChatClient.builder(model).build(), queries,
                new AiProperties(true, null, null, null, null, null, null, null, null, null, null), none);
        return service.plan(new PlanRequest("Plan visits for the Pune round", LAT, LON, null));
    }

    private static String userText(Prompt prompt) {
        return prompt.getInstructions().stream().filter(m -> m instanceof UserMessage).map(m -> m.getText())
                .reduce("", (a, b) -> a + b);
    }

    // ---- the two evidence cases

    @Test
    void theFifthSearchHousesAttemptLeadsToAFinalPlanNotTheFallback() {
        var a = house("Baner flat", "Baner", 18.5590, "has a lift");
        var b = house("Kothrud flat", "Kothrud", 18.5074, "near the park");
        var wrap = plan("Two Pune houses", b.id(), a.id());
        var model = new Script(FIVE_SEARCHES, "unused", p -> text(wrap));

        var res = run(model);

        assertThat(res.fallback()).isFalse();
        assertThat(res.summary()).isEqualTo("Two Pune houses");
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(b.id(), a.id());
        // The fifth attempt was refused, not run: only four searches are in the tool log, and one model call had no tools.
        assertThat(res.toolCalls()).containsExactly("searchHouses", "searchHouses", "searchHouses", "searchHouses");
        assertThat(model.withTools).hasSize(5);
        assertThat(model.withoutTools).hasSize(1);
    }

    @Test
    void aRequestThatMatchesNothingGetsAnEmptyPlanWithTheModelsHonestSummaryNotAFallback() {
        house("Baner flat", "Baner", 18.5590, null);
        house("Kothrud flat", "Kothrud", 18.5074, null);
        var model = new Script(FIVE_SEARCHES, "unused",
                p -> text(plan("None of your saved houses has five bedrooms under 10,000 rupees.")));

        var res = run(model);

        assertThat(res.fallback()).isFalse();
        assertThat(res.stops()).isEmpty();
        assertThat(res.totalMeters()).isZero();
        assertThat(res.summary()).isEqualTo("None of your saved houses has five bedrooms under 10,000 rupees.");
    }

    @Test
    void theFifthSearchWithNoHouseFoundAtAllStillGetsTheWrapUpAndAnEmptyPlan() {
        var model = new Script(FIVE_SEARCHES, "unused", p -> text(plan("You have no saved houses that match.")));

        var res = run(model);

        assertThat(res.fallback()).isFalse();
        assertThat(res.stops()).isEmpty();
        assertThat(res.summary()).isEqualTo("You have no saved houses that match.");
        assertThat(userText(model.withoutTools.get(0))).contains("returned no houses");
    }

    // ---- other limits

    @Test
    void theTotalBudgetOfTwelveIsHandledTheSameWay() {
        var a = house("Baner flat", "Baner", 18.5590, null);
        // 4 + 4 + 4 calls are inside the per-tool and total budgets; the 13th call is the one refused.
        var all = new ArrayList<>(List.of("searchHouses", "searchHouses", "searchHouses", "searchHouses",
                "nearbyHouses", "nearbyHouses", "nearbyHouses", "nearbyHouses",
                "estimateWalkMinutes", "estimateWalkMinutes", "estimateWalkMinutes", "estimateWalkMinutes"));
        all.add("orderByNearestNeighbour");
        var model = new Script(all, "unused", p -> text(plan("One house", a.id())));

        var res = run(model);

        assertThat(res.fallback()).isFalse();
        assertThat(res.toolCalls()).hasSize(12);
        assertThat(model.withoutTools).hasSize(1);
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(a.id());
    }

    @Test
    void aRunInsideTheBudgetMakesNoWrapUpCall() {
        var a = house("Baner flat", "Baner", 18.5590, null);
        var model = new Script(List.of("searchHouses", "searchHouses", "searchHouses", "searchHouses"), plan("One", a.id()),
                p -> {
                    throw new AssertionError("no wrap-up call expected");
                });

        var res = run(model);

        assertThat(res.fallback()).isFalse();
        assertThat(res.stops()).hasSize(1);
        assertThat(model.withoutTools).isEmpty();
    }

    // ---- the wrap-up call itself

    @Test
    void theWrapUpCallHasNoToolsTheSameSystemTextAndTheLimitNoticeInTheUserText() {
        var a = house("Baner flat", "Baner", 18.5590, "has a lift");
        var model = new Script(FIVE_SEARCHES, "unused", p -> text(plan("One", a.id())));

        run(model);

        var wrapUp = model.withoutTools.get(0);
        assertThat(wrapUp.getSystemMessage().getText()).isEqualTo(VisitPlannerService.systemPrompt(LAT, LON, 8));
        var user = userText(wrapUp);
        assertThat(user).contains("Plan visits for the Pune round")
                .contains("Tool call limit reached: answer now with what you have found; if nothing matches, "
                        + "return an empty stops list and say so.")
                .contains("searchHouses x4")
                // The house text is data: it sits in a block whose tag carries a nonce.
                .containsPattern("<houses-[0-9a-f]{6}>")
                .contains(a.id().toString()).contains("Baner flat").contains("Baner");
        assertThat(wrapUp.getOptions().getTemperature()).isEqualTo(0.2);
        assertThat(wrapUp.getOptions().getMaxTokens()).isEqualTo(8192);
    }

    @Test
    void theWrapUpPromptCarriesNoContactNoteOrFreeTextTheToolsDidNotReturn() {
        var a = house("Lakshmi Narayanan house", "Baner", 18.5590, "Lakshmi sir wants 6 months deposit, call 9900123456");
        var model = new Script(FIVE_SEARCHES, "unused", p -> text(plan("One", a.id())));

        run(model);

        var user = userText(model.withoutTools.get(0));
        assertThat(user).contains("[contact] house")
                .doesNotContainIgnoringCase("Lakshmi").doesNotContainIgnoringCase("Narayanan")
                .doesNotContain("99001").doesNotContain("9900123456").doesNotContain("deposit");
    }

    @Test
    void theWrapUpHouseListIsCappedAtFiftyAndSaysHowManyWereLeftOut() {
        var many = new LinkedHashMap<UUID, HouseSummary>();
        IntStream.range(0, 60).forEach(i -> {
            var id = UUID.randomUUID();
            many.put(id, new HouseSummary(id, "House " + i, "Baner", "Main Road", HouseStatus.NEW, 30000L, "RENT", 2, 4,
                    18.5, 73.8, null));
        });

        var text = VisitPlannerService.limitNotice(List.of("searchHouses"), many);

        assertThat(text).contains("House 0").contains("House 49").doesNotContain("House 50").contains("10 more houses");
        assertThat(VisitPlannerService.limitNotice(List.of("searchHouses"), Map.of())).contains("returned no houses");
    }

    @Test
    void aWrapUpPlanIsValidatedExactlyLikeAnyOther() {
        var a = house("Baner flat", "Baner", 18.5590, null);
        var wrap = plan("Three", UUID.randomUUID(), a.id(), a.id());
        var model = new Script(FIVE_SEARCHES, "unused", p -> text(wrap));

        var res = run(model);

        assertThat(res.fallback()).isFalse();
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(a.id());
        assertThat(res.stops().get(0).order()).isEqualTo(1);
    }

    // ---- when the wrap-up fails

    @Test
    void theFallbackRunsOnlyWhenTheWrapUpCallItselfFails() {
        var a = house("Baner flat", "Baner", 18.5590, null);
        var model = new Script(FIVE_SEARCHES, "unused", p -> {
            throw new IllegalStateException("provider down");
        });

        var res = run(model);

        assertThat(res.fallback()).isTrue();
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(a.id());
        assertThat(model.withoutTools).as("one wrap-up attempt, no retry").hasSize(1);
    }

    @Test
    void aWrapUpAnswerThatIsNotAPlanFallsBackToo() {
        var a = house("Baner flat", "Baner", 18.5590, null);
        var model = new Script(FIVE_SEARCHES, "unused", p -> text("I could not decide."));

        var res = run(model);

        assertThat(res.fallback()).isTrue();
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(a.id());
    }

    @Test
    void aFailedWrapUpWithNoHouseFoundIsStillUnavailable() {
        var model = new Script(FIVE_SEARCHES, "unused", p -> {
            throw new IllegalStateException("provider down");
        });

        assertThatThrownBy(() -> run(model)).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void aFailureThatIsNotTheLimitFallsBackAsBeforeWithoutAWrapUpCall() {
        var a = house("Baner flat", "Baner", 18.5590, null);
        var model = new Script(List.of("searchHouses"), "this is not json at all", p -> {
            throw new AssertionError("no wrap-up call expected");
        });

        var res = run(model);

        assertThat(res.fallback()).isTrue();
        assertThat(res.stops()).extracting(PlanModels.PlannedStop::houseId).containsExactly(a.id());
        assertThat(model.withoutTools).isEmpty();
    }

    // ---- S4b-BL-200: why a plan fell back (fallbackCause)

    @Test
    void aPlanThatNeededNoFallbackHasNoFallbackCause() {
        var a = house("Baner flat", "Baner", 18.5590, null);
        var res = run(new Script(List.of("searchHouses"), plan("One", a.id()), p -> {
            throw new AssertionError("no wrap-up call expected");
        }));

        assertThat(res.fallback()).isFalse();
        assertThat(res.fallbackCause()).isNull();
    }

    @Test
    void aProviderErrorAfterUsefulToolCallsFallsBackWithCauseProvider() {
        house("Baner flat", "Baner", 18.5590, null);
        var model = new Script(List.of("searchHouses"), "unused", p -> {
            throw new AssertionError("no wrap-up call expected");
        });
        model.failAfterTools = new RuntimeException("Failed to generate content",
                new com.google.genai.errors.ServerException(503, "UNAVAILABLE", "overloaded"));

        var res = run(model);

        assertThat(res.fallback()).isTrue();
        assertThat(res.fallbackCause()).isEqualTo("provider");
    }

    @Test
    void unreadableModelOutputFallsBackWithCauseParse() {
        house("Baner flat", "Baner", 18.5590, null);
        var res = run(new Script(List.of("searchHouses"), "this is not json at all", p -> {
            throw new AssertionError("no wrap-up call expected");
        }));

        assertThat(res.fallback()).isTrue();
        assertThat(res.fallbackCause()).isEqualTo("parse");
    }

    @Test
    void aWrapUpThatDoesNotAnswerWithAPlanFallsBackWithCauseLimit() {
        house("Baner flat", "Baner", 18.5590, null);

        var res = run(new Script(FIVE_SEARCHES, "unused", p -> text("I could not decide.")));

        assertThat(res.fallback()).isTrue();
        assertThat(res.fallbackCause()).isEqualTo("limit");
    }

    @Test
    void aWrapUpThatAnswersWithNothingFallsBackWithCauseLimit() {
        house("Baner flat", "Baner", 18.5590, null);

        var res = run(new Script(FIVE_SEARCHES, "unused", p -> text("null")));

        assertThat(res.fallback()).isTrue();
        assertThat(res.fallbackCause()).isEqualTo("limit");
    }

    @Test
    void aWrapUpCallThatFailsOnTheProviderFallsBackWithCauseProvider() {
        house("Baner flat", "Baner", 18.5590, null);

        var res = run(new Script(FIVE_SEARCHES, "unused", p -> {
            throw new RuntimeException("Failed to generate content",
                    new com.google.genai.errors.ServerException(503, "UNAVAILABLE", "overloaded"));
        }));

        assertThat(res.fallback()).isTrue();
        assertThat(res.fallbackCause()).isEqualTo("provider");
    }

    @Test
    void aWrapUpCallThatFailsForAnyOtherReasonFallsBackWithCauseLimit() {
        house("Baner flat", "Baner", 18.5590, null);

        var res = run(new Script(FIVE_SEARCHES, "unused", p -> {
            throw new IllegalStateException("provider down");
        }));

        assertThat(res.fallback()).isTrue();
        assertThat(res.fallbackCause()).isEqualTo("limit");
    }
}
