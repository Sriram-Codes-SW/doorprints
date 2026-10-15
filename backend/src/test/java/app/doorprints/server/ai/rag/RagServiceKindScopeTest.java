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
package app.doorprints.server.ai.rag;

import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.ai.rag.AskModels.AskFilters;
import app.doorprints.server.ai.web.AiUnavailableException;
import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S4b-BL-290 (T-I51): {@code POST /api/ai/ask} takes an optional {@code kind}. Absent is today's search, request for
 * request; a kind outside {@code app.kinds.available} is a 400 before the store, the model or the database is touched;
 * a kind of the list reads that kind's candidates and no others (the AI scoping rule, docs/03 §12 and §18.12), both by the
 * metadata a built-in kind's documents carry and by the stored row's own kind. The store, the model and the database are
 * mocks: the seam is {@code RagService.ask}.
 */
class RagServiceKindScopeTest {

    private static final AiProperties PROPS =
            new AiProperties(true, null, null, null, null, null, null, new AiProperties.Rag(20, null), null, null, null);
    private static final AskFilters NONE = new AskFilters(null, null, null, null, null);
    private static final String QUESTION = "Which one has the lowest fees?";

    /** A stored candidate of {@code kind} and the document written for it ({@code stamp}: the kind its metadata carries, or none). */
    private record Stored(House row, Document doc) {
    }

    private static Stored stored(String kind, String stamp, String text) {
        var id = UUID.randomUUID();
        var row = new House(id);
        row.setKind(kind);
        var meta = new HashMap<String, Object>();
        meta.put("houseId", id.toString());
        if (stamp != null) meta.put("kind", stamp);
        return new Stored(row, Document.builder().id(id.toString()).text(text).metadata(meta).score(0.9).build());
    }

    /** What one ask sent to the store, to the model, and what it answered. */
    private static final class Run {
        final VectorStore store = mock(VectorStore.class);
        final HouseRepository houses = mock(HouseRepository.class);
        final ChatClient chat = mock(ChatClient.class);
        final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_SELF);
        final ArgumentCaptor<SearchRequest> searches = ArgumentCaptor.forClass(SearchRequest.class);
        final ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        String answer;
        Throwable error;

        Run(AiKinds kinds, String question, AskFilters filters, String kind, List<Stored> found) {
            when(store.similaritySearch(any(SearchRequest.class))).thenReturn(found.stream().map(Stored::doc).toList());
            when(houses.findAllById(any())).thenReturn(found.stream().map(Stored::row).toList());
            when(spec.call()).thenThrow(new IllegalStateException("model down"));
            when(chat.prompt()).thenReturn(spec);
            var service = new RagService(chat, store, PROPS, houses, kinds);
            try {
                answer = service.ask(question, filters, kind).answer();
            } catch (RuntimeException e) {
                error = e;
            }
        }

        boolean modelCalled() {
            try {
                verify(chat).prompt();
                return true;
            } catch (AssertionError e) {
                return false;
            }
        }

        String sentUser() {
            verify(spec).user(user.capture());
            return user.getValue();
        }

        String sentSystem() {
            verify(spec).system(system.capture());
            return system.getValue();
        }

        List<SearchRequest> searched() {
            verify(store, org.mockito.Mockito.atLeastOnce()).similaritySearch(searches.capture());
            return searches.getAllValues();
        }
    }

    @Test
    void noKindIsTodaysSearchRequestForRequest() {
        var house = stored("house", null, "House: A");
        var filters = new AskFilters(null, "RENT", 30000L, null, null);
        var run = new Run(AiKinds.schools(true), QUESTION, filters, null, List.of(house));
        var sent = run.searched().getFirst();
        assertThat(sent.getTopK()).isEqualTo(20);
        assertThat(sent.getFilterExpression()).isEqualTo(AskPrompts.filter(filters));
        assertThat(run.error).isInstanceOf(AiUnavailableException.class);
        // The same filter however it is asked for, with or without the new argument.
        assertThat(AskPrompts.filter(filters, null, null)).isEqualTo(AskPrompts.filter(filters));
        assertThat(AskPrompts.filter(filters, true, null)).isEqualTo(AskPrompts.filter(filters, true));
        assertThat(AskPrompts.filter(null, null, null)).isNull();
        assertThat(new Run(AiKinds.schools(true), QUESTION, NONE, null, List.of(house)).searched().getFirst()
                .getFilterExpression()).isEqualTo(AskPrompts.filter(NONE));
    }

    @Test
    void noKindStillReadsEveryDocumentTheKindsListAllows() {
        var house = stored("house", null, "House: A");
        var school = stored("school", "school", "School: Pine Hill");
        var run = new Run(AiKinds.schools(true), QUESTION, NONE, null, List.of(house, school));
        assertThat(run.sentUser()).contains("House: A").contains("School: Pine Hill");
    }

    @Test
    void aSchoolKindIsSearchedByItsStampAndReadsOnlyRowsOfThatKind() {
        var pine = stored("school", "school", "School: Pine Hill, fees Rs 96000");
        // A document that says school but whose row is a house cannot be shown to be of the kind: dropped.
        var odd = stored("house", "school", "School: Mislabelled");
        var house = stored("house", null, "House: A, rent 20000");
        var run = new Run(AiKinds.schools(true), QUESTION, NONE, "school", List.of(pine, odd, house));
        var sent = run.searched().getFirst();
        assertThat(sent.getTopK()).isEqualTo(20);
        assertThat(sent.getFilterExpression()).isEqualTo(new FilterExpressionBuilder().eq("kind", "school").build());
        var user = run.sentUser();
        assertThat(user).startsWith("<schools-").contains("School: Pine Hill").doesNotContain("Mislabelled").doesNotContain("House: A");
        assertThat(run.sentSystem()).contains("ONE person's school search");
    }

    @Test
    void theStampIsAndedWithTheFiltersAndTheVisitedFlag() {
        var filters = new AskFilters(null, null, null, null, 3);
        var expected = new FilterExpressionBuilder().and(new FilterExpressionBuilder().eq("kind", "school"),
                new FilterExpressionBuilder().gte("rating", 3)).build();
        assertThat(AskPrompts.filter(filters, null, "school")).isEqualTo(expected);
        var visited = AskPrompts.filter(null, true, "school");
        assertThat(visited).isEqualTo(new FilterExpressionBuilder().and(new FilterExpressionBuilder().eq("kind", "school"),
                new FilterExpressionBuilder().eq("visited", true)).build());
        assertThat(AskPrompts.filter(null, null, "school")).isEqualTo(new FilterExpressionBuilder().eq("kind", "school").build());
    }

    @Test
    void theHouseKindIsNarrowedByTheRowsAndFetchesMoreToMakeUpForWhatIsDropped() {
        var school = stored("school", "school", "School: Pine Hill");
        var a = stored("house", null, "House: A");
        var b = stored("house", null, "House: B");
        var run = new Run(AiKinds.schools(true), QUESTION, NONE, "house", List.of(school, a, b));
        var sent = run.searched().getFirst();
        assertThat(sent.getTopK()).isEqualTo(80);
        assertThat(sent.getFilterExpression()).isNull();
        var user = run.sentUser();
        assertThat(user).startsWith("<houses-").contains("House: A").contains("House: B").doesNotContain("Pine Hill");
        assertThat(run.sentSystem()).contains("ONE person's house hunt");
    }

    @Test
    void aKindWithNoWordsOfItsOwnIsScopedAndReadsTheHousePrompt() {
        var clinics = new AiKinds(List.of("house", "clinic"));
        var clinic = stored("clinic", null, "Clinic: Dr Rao");
        var house = stored("house", null, "House: A");
        var run = new Run(clinics, QUESTION, NONE, "clinic", List.of(clinic, house));
        assertThat(run.searched().getFirst().getFilterExpression()).isNull();
        assertThat(run.sentUser()).contains("Clinic: Dr Rao").doesNotContain("House: A");
        assertThat(run.sentSystem()).contains("ONE person's house hunt");
    }

    @Test
    void nothingOfTheKindIsTheKindsOwnSentenceAndNoModelCall() {
        var house = stored("house", null, "House: A");
        var school = new Run(AiKinds.schools(true), QUESTION, NONE, "school", List.of(house));
        assertThat(school.answer).isEqualTo(AiKinds.SCHOOL.refusal());
        assertThat(school.modelCalled()).isFalse();
        var none = new Run(AiKinds.schools(true), QUESTION, NONE, "house", List.of(stored("school", "school", "School: P")));
        assertThat(none.answer).isEqualTo(AskPrompts.I_DONT_KNOW);
        assertThat(none.modelCalled()).isFalse();
        var absent = new Run(AiKinds.schools(true), QUESTION, NONE, null, List.of());
        assertThat(absent.answer).isEqualTo(AskPrompts.I_DONT_KNOW);
    }

    @Test
    void aVisitQuestionSearchesTheStampAgainAndDropsOtherKindsFromBothLists() {
        var seen = stored("school", "school", "School: Visited One");
        var other = stored("house", null, "House: Visited Too");
        var run = new Run(AiKinds.schools(true), "Which schools have I visited so far?", NONE, "school", List.of(seen, other));
        var searches = run.searched();
        assertThat(searches).hasSize(2);
        assertThat(searches.get(1).getTopK()).isEqualTo(RagService.VISITED_FETCH_MAX);
        assertThat(searches.get(1).getFilterExpression()).isEqualTo(new FilterExpressionBuilder()
                .and(new FilterExpressionBuilder().eq("kind", "school"), new FilterExpressionBuilder().eq("visited", true)).build());
        assertThat(run.sentUser()).contains("Visited One").doesNotContain("Visited Too");
    }

    @Test
    void aDocumentWithNoStoredRowCannotBeShownToBeOfTheKindAndIsDropped() {
        var kept = stored("school", "school", "School: Pine Hill");
        var gone = stored("school", "school", "School: Deleted Row");
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(kept.doc(), gone.doc()));
        var houses = mock(HouseRepository.class);
        when(houses.findAllById(any())).thenReturn(List.of(kept.row()));
        var spec = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_SELF);
        when(spec.call()).thenThrow(new IllegalStateException("model down"));
        var chat = mock(ChatClient.class);
        when(chat.prompt()).thenReturn(spec);
        assertThatThrownBy(() -> new RagService(chat, store, PROPS, houses, AiKinds.schools(true)).ask(QUESTION, NONE, "school"))
                .isInstanceOf(AiUnavailableException.class);
        var user = ArgumentCaptor.forClass(String.class);
        verify(spec).user(user.capture());
        assertThat(user.getValue()).contains("Pine Hill").doesNotContain("Deleted Row");
    }

    @Test
    void whatIsKeptOfAKindIsCappedAtTheTopKAfterTheNarrowing() {
        var found = new java.util.ArrayList<Stored>();
        for (int i = 0; i < 30; i++) found.add(stored("house", null, "House: number " + String.format("%02d", i)));
        var run = new Run(AiKinds.schools(true), QUESTION, NONE, "house", found);
        var user = run.sentUser();
        assertThat(user).contains("House: number 00").contains("House: number 19").doesNotContain("House: number 20");
    }

    @Test
    void aKindOutsideTheListIsA400BeforeAnythingIsTouched() {
        for (var kinds : List.of(AiKinds.OFF, new AiKinds(List.of("house")))) {
            var store = mock(VectorStore.class);
            var chat = mock(ChatClient.class);
            var houses = mock(HouseRepository.class);
            var service = new RagService(chat, store, PROPS, houses, kinds);
            assertThatThrownBy(() -> service.ask(QUESTION, NONE, "school")).isInstanceOf(BadRequestException.class)
                    .hasMessage("kind school is not available");
            verifyNoInteractions(store, chat, houses);
        }
    }

    /** A hostile kind never reaches a filter, a query or a prompt: the list is an allow-list of ids, compared as whole strings. */
    @Test
    void hostileKindValuesAreRefusedByTheAllowListAndNeverReachTheStoreTheDatabaseOrThePrompt() {
        var hostile = List.of("house OR 1=1", "house' OR '1'='1", "school; DROP TABLE houses", "__proto__", "constructor",
                "../school", "school\n", " school", "school ", "SCHOOL", "School", "scho\u0000ol", "schоol",
                "a".repeat(32), "a".repeat(10_000), "", " ", "*", "%", "$.kind", "school\" || \"1\" == \"1", "{\"$ne\":null}");
        for (var kind : hostile) {
            var store = mock(VectorStore.class);
            var chat = mock(ChatClient.class);
            var houses = mock(HouseRepository.class);
            var service = new RagService(chat, store, PROPS, houses, AiKinds.schools(true));
            assertThatThrownBy(() -> service.ask(QUESTION, NONE, kind)).as(kind).isInstanceOf(BadRequestException.class);
            verifyNoInteractions(store, chat, houses);
        }
    }

    @Test
    void aBadQuestionIsStillRefusedFirstAndAKindIsNeverReadWithoutOne() {
        var store = mock(VectorStore.class);
        var service = new RagService(mock(ChatClient.class), store, PROPS, mock(HouseRepository.class), AiKinds.schools(true));
        assertThatThrownBy(() -> service.ask(" ", NONE, "school")).isInstanceOf(BadRequestException.class)
                .hasMessage("question must not be blank");
        verify(store, never()).similaritySearch(any(SearchRequest.class));
    }
}
