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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4b-BL-194 item 2: with 30 houses, 2 of them visited, vector similarity alone returned 20 houses without one of the
 * two visited (Vertex run 37988324265). A question about visits now also runs ONE search filtered on the document
 * metadata {@code visited}, sorts it by {@code lastVisit} (newest first) and puts it before the similar houses.
 */
class RagServiceVisitedRetrievalTest {

    private static final String VISIT_Q = "Which houses have I already visited and when?";
    private static final String NOT_VISIT_Q = "Which houses have I not visited yet?";
    private final UUID visitedA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID visitedB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** 19 unvisited houses and visited house A, as the similarity search returns them. */
    private List<Document> similar() {
        var docs = new ArrayList<Document>();
        docs.add(doc(visitedA, 100L));
        IntStream.range(0, 19).forEach(i -> docs.add(doc(UUID.randomUUID(), null)));
        return docs;
    }

    @Test
    void aVisitQuestionRunsOneFilteredSearchAndPutsTheVisitedHousesFirstWithinTheCap() {
        var plain = similar();
        var store = store(plain, List.of(doc(visitedB, 300L), doc(visitedA, 100L)));

        var ids = retrievedIds(store, VISIT_Q);

        assertThat(ids).hasSize(20).startsWith(visitedB.toString(), visitedA.toString()).doesNotHaveDuplicates();
        assertThat(ids).contains(plain.get(1).getId());
        assertThat(ids).doesNotContain(plain.get(19).getId()); // the last similar one made room
    }

    @Test
    void theExtraSearchFiltersOnVisitedTrueKeepsTheCallersFiltersAndTheTopK() {
        var store = store(similar(), List.of(doc(visitedA, 100L)));

        retrievedIds(store, VISIT_Q, new AskFilters(null, "RENT", null, null, null));

        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store, times(2)).similaritySearch(captor.capture());
        var extra = captor.getAllValues().get(1);
        assertThat(extra.getFilterExpression().toString()).contains("key=visited]").contains("true").contains("priceType");
        assertThat(extra.getTopK()).isEqualTo(RagService.VISITED_FETCH_MAX); // not only the 20 most similar
        assertThat(extra.getSimilarityThreshold()).isEqualTo(0.0);
        assertThat(captor.getAllValues().get(0).getFilterExpression().toString()).doesNotContain("key=visited]");
    }

    @Test
    void aNegatedVisitQuestionFiltersOnVisitedFalseAndKeepsTheStoresOrder() {
        var unvisited = IntStream.range(0, 3).mapToObj(i -> doc(UUID.randomUUID(), null)).toList();
        var store = store(similar(), unvisited);

        var ids = retrievedIds(store, NOT_VISIT_Q);

        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store, times(2)).similaritySearch(captor.capture());
        assertThat(captor.getAllValues().get(1).getFilterExpression().toString()).contains("visited").contains("false");
        assertThat(ids).startsWith(unvisited.stream().map(Document::getId).toArray(String[]::new));
        assertThat(ids).hasSize(20);
    }

    @Test
    void aQuestionNotAboutVisitsMakesNoExtraSearch() {
        var store = store(similar(), List.of());

        var ids = retrievedIds(store, "Which house has the best water pressure?");

        assertThat(ids).hasSize(20);
        verify(store, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void visitedHousesReachTheModelNewestVisitFirstWhateverTheSimilarityOrder() {
        var newestFirst = IntStream.range(0, 6).mapToObj(i -> UUID.randomUUID()).toList();
        var store = store(similar(), scrambled(newestFirst));

        var ids = retrievedIds(store, VISIT_Q);

        assertThat(ids).startsWith(newestFirst.stream().map(UUID::toString).toArray(String[]::new));
    }

    @Test
    void moreVisitedHousesThanTheCapKeepTheMostRecentlyVisited() {
        var newestFirst = IntStream.range(0, 25).mapToObj(i -> UUID.randomUUID()).toList();
        // The stub honours topK like pgvector: asked for only 20 of the 25 it would drop 5 by similarity, so the
        // service must ask for more than the cap and let the recency sort decide.
        var store = store(similar(), scrambled(newestFirst));

        var ids = retrievedIds(store, VISIT_Q);

        assertThat(ids).hasSize(20).containsExactlyElementsOf(
                newestFirst.stream().limit(20).map(UUID::toString).toList());
        assertThat(ids).doesNotContainAnyElementsOf(newestFirst.stream().skip(20).map(UUID::toString).toList());
    }

    @Test
    void byLastVisitSortsNewestFirstTiesByIdAndMissingLast() {
        var low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var none = UUID.randomUUID();
        var sorted = RagService.byLastVisit(List.of(doc(none, null), doc(high, 50L), doc(UUID.randomUUID(), 90L),
                doc(low, 50L)));
        assertThat(sorted).extracting(d -> d.getMetadata().get("lastVisit"))
                .containsExactly(90L, 50L, 50L, null);
        assertThat(sorted.get(1).getId()).isEqualTo(low.toString());
        assertThat(sorted.get(2).getId()).isEqualTo(high.toString());
    }

    @Test
    void byLastVisitReadsAnyNumberType() {
        var a = new HashMap<String, Object>(java.util.Map.of("lastVisit", 5));
        var b = new HashMap<String, Object>(java.util.Map.of("lastVisit", 7.0));
        var c = new HashMap<String, Object>(java.util.Map.of("lastVisit", "x"));
        var da = Document.builder().id("a").text("t").metadata(a).build();
        var db = Document.builder().id("b").text("t").metadata(b).build();
        var dc = Document.builder().id("c").text("t").metadata(c).build();
        assertThat(RagService.byLastVisit(List.of(dc, da, db))).extracting(Document::getId)
                .containsExactly("b", "a", "c");
    }

    @Test
    void mergeKeepsTheOrderAndDropsRepeats() {
        var a = doc(visitedA, 1L);
        var b = doc(visitedB, 2L);
        var c = doc(UUID.randomUUID(), null);
        assertThat(RagService.withVisited(List.of(c, a), List.of(a, b), 3)).extracting(Document::getId)
                .containsExactly(a.getId(), b.getId(), c.getId());
        assertThat(RagService.withVisited(List.of(c, a), List.of(a, b), 2)).extracting(Document::getId)
                .containsExactly(a.getId(), b.getId());
    }

    /** The visited documents in a fixed scrambled order, each with a lastVisit that falls with its position. */
    private static List<Document> scrambled(List<UUID> newestFirst) {
        var docs = new ArrayList<Document>();
        for (int i = 0; i < newestFirst.size(); i++) docs.add(doc(newestFirst.get(i), 1_000_000L - i));
        Collections.shuffle(docs, new Random(7));
        return docs;
    }

    /** A store answering the unfiltered search with {@code plain} and the {@code visited} filtered one with {@code extra}. */
    private static VectorStore store(List<Document> plain, List<Document> extra) {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            var req = (SearchRequest) inv.getArgument(0);
            var filter = req.getFilterExpression();
            return filter != null && filter.toString().contains("key=visited]")
                    ? extra.stream().limit(req.getTopK()).toList() : plain;
        });
        return store;
    }

    static Document doc(UUID id, Long lastVisit) {
        var meta = new HashMap<String, Object>();
        meta.put("houseId", id.toString());
        if (lastVisit != null) meta.put("lastVisit", lastVisit);
        return Document.builder().id(id.toString()).text("House: x").metadata(meta).score(0.5).build();
    }

    private static List<String> retrievedIds(VectorStore store, String question) {
        return retrievedIds(store, question, new AskFilters(null, null, null, null, null));
    }

    /** One ask whose model call fails; the DEBUG line lists the ids that were to reach the model, in order. */
    private static List<String> retrievedIds(VectorStore store, String question, AskFilters filters) {
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
            var service = new RagService(chat, store, props, mock(HouseRepository.class));
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
