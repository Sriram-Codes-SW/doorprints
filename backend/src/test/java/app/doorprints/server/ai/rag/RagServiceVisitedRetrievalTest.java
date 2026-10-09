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
import app.doorprints.server.ai.rag.AskModels.AskFilters;
import app.doorprints.server.ai.web.AiUnavailableException;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.visit.VisitRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4b-BL-194 item 2: with 30 houses, 2 of them visited, vector similarity alone returned 20 houses without one of the
 * two visited (Vertex run 37988324265). A question about visits now also gets the visited houses, first, within the cap.
 */
class RagServiceVisitedRetrievalTest {

    private static final String VISIT_Q = "Which houses have I already visited and when?";
    private final UUID visitedA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID visitedB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** What the vector store returns for the plain similarity search: 19 unvisited houses and visited house A. */
    private List<Document> similar() {
        var docs = new ArrayList<Document>();
        docs.add(doc(visitedA));
        IntStream.range(0, 19).forEach(i -> docs.add(doc(UUID.randomUUID())));
        return docs;
    }

    @Test
    void aVisitQuestionAddsTheVisitedHousesFirstAndStaysWithinTheCap() {
        var store = mock(VectorStore.class);
        var plain = similar();
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            var req = (SearchRequest) inv.getArgument(0);
            // The visited-houses search carries a houseId filter; the plain one does not.
            return req.getFilterExpression() != null ? List.of(doc(visitedA), doc(visitedB)) : plain;
        });
        var visits = mock(VisitRepository.class);
        when(visits.visitedHouseIds()).thenReturn(List.of(visitedA, visitedB));

        var ids = retrievedIds(store, visits, VISIT_Q);

        assertThat(ids).hasSize(20).startsWith(visitedA.toString(), visitedB.toString()).doesNotHaveDuplicates();
        assertThat(ids).contains(plain.get(1).getId());
        assertThat(ids).doesNotContain(plain.get(19).getId()); // the last similar one made room
    }

    @Test
    void theVisitedSearchIsFilteredToTheVisitedHousesAndKeepsTheCallersFilters() {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc(visitedA)));
        var visits = mock(VisitRepository.class);
        when(visits.visitedHouseIds()).thenReturn(List.of(visitedA));

        retrievedIds(store, visits, VISIT_Q, new AskFilters(null, "RENT", null, null, null));

        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store, times(2)).similaritySearch(captor.capture());
        var visited = captor.getAllValues().get(1);
        assertThat(visited.getFilterExpression().toString()).contains("houseId").contains(visitedA.toString())
                .contains("priceType");
        assertThat(visited.getTopK()).isEqualTo(20);
        assertThat(captor.getAllValues().get(0).getFilterExpression().toString()).doesNotContain("houseId");
    }

    @Test
    void aQuestionNotAboutVisitsNeverAsksForVisitedHouses() {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(similar());
        var visits = mock(VisitRepository.class);

        var ids = retrievedIds(store, visits, "Which house has the best water pressure?");

        assertThat(ids).hasSize(20);
        verify(visits, never()).visitedHouseIds();
        verify(store, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void withNoVisitsTheOrdinaryResultIsUsed() {
        var store = mock(VectorStore.class);
        var plain = similar();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(plain);
        var visits = mock(VisitRepository.class);
        when(visits.visitedHouseIds()).thenReturn(List.of());

        assertThat(retrievedIds(store, visits, VISIT_Q)).isEqualTo(plain.stream().map(Document::getId).toList());
        verify(store, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void moreVisitedHousesThanTheCapAreCutAtTheCap() {
        var many = IntStream.range(0, 25).mapToObj(i -> UUID.randomUUID()).toList();
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(inv ->
                ((SearchRequest) inv.getArgument(0)).getFilterExpression() != null
                        ? many.stream().limit(20).map(RagServiceVisitedRetrievalTest::doc).toList() : similar());
        var visits = mock(VisitRepository.class);
        when(visits.visitedHouseIds()).thenReturn(many);

        assertThat(retrievedIds(store, visits, VISIT_Q)).hasSize(20)
                .containsExactlyElementsOf(many.stream().limit(20).map(UUID::toString).toList());
    }

    @Test
    void mergeKeepsTheOrderAndDropsRepeats() {
        var a = doc(visitedA);
        var b = doc(visitedB);
        var c = doc(UUID.randomUUID());
        assertThat(RagService.withVisited(List.of(c, a), List.of(a, b), 3)).extracting(Document::getId)
                .containsExactly(a.getId(), b.getId(), c.getId());
        assertThat(RagService.withVisited(List.of(c, a), List.of(a, b), 2)).extracting(Document::getId)
                .containsExactly(a.getId(), b.getId());
    }

    static Document doc(UUID id) {
        return Document.builder().id(id.toString()).text("House: x").metadata(Map.of("houseId", id.toString()))
                .score(0.5).build();
    }

    private static List<String> retrievedIds(VectorStore store, VisitRepository visits, String question) {
        return retrievedIds(store, visits, question, new AskFilters(null, null, null, null, null));
    }

    /** One ask whose model call fails; the DEBUG line lists the ids that were to reach the model, in order. */
    private static List<String> retrievedIds(VectorStore store, VisitRepository visits, String question,
                                             AskFilters filters) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RagService.class);
        var before = logger.getLevel();
        var lines = new ListAppender<ILoggingEvent>();
        lines.start();
        logger.addAppender(lines);
        logger.setLevel(Level.DEBUG);
        try {
            var chat = mock(ChatClient.class);
            when(chat.prompt()).thenThrow(new IllegalStateException("model down"));
            var props = new AiProperties(true, null, null, null, null, null, null, new AiProperties.Rag(20, null), null, null, null);
            var service = new RagService(chat, store, props, mock(HouseRepository.class), visits);
            assertThatThrownBy(() -> service.ask(question, filters)).isInstanceOf(AiUnavailableException.class);
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(before);
        }
        var text = lines.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("retrieved")).findFirst().orElseThrow();
        var inner = text.substring(text.indexOf('[') + 1, text.lastIndexOf(']'));
        return inner.isBlank() ? List.of() : List.of(inner.split(", "));
    }
}
