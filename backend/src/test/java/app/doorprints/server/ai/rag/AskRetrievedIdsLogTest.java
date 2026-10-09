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
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S4b-BL-194: each ask logs, at DEBUG only, the ids of the houses sent to the model, in the order sent, and nothing
 * else of the question or the documents.
 */
class AskRetrievedIdsLogTest {

    private static final String QUESTION = "question-marker-" + UUID.randomUUID();
    private static final String BODY = "document-marker Ramesh 98450";

    @Test
    void theRetrievedIdsAreLoggedInOrderAtDebugAndNothingElse() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        var lines = askAt(Level.DEBUG, List.of(doc(second), doc(first)));

        var ask = lines.stream().filter(e -> e.getFormattedMessage().contains("retrieved")).toList();
        assertThat(ask).hasSize(1);
        assertThat(ask.getFirst().getLevel()).isEqualTo(Level.DEBUG);
        var text = ask.getFirst().getFormattedMessage();
        assertThat(text).contains("2 houses");
        assertThat(text.indexOf(second.toString())).isGreaterThan(-1).isLessThan(text.indexOf(first.toString()));
        for (var e : lines) {
            assertThat(e.getFormattedMessage()).doesNotContain(QUESTION).doesNotContain("document-marker")
                    .doesNotContain("Ramesh").doesNotContain("98450");
        }
        // The model call fails (thrown, not logged): this addition logs nothing at INFO or above.
        assertThat(lines).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO));
    }

    @Test
    void atInfoTheLineIsNotEmitted() {
        assertThat(askAt(Level.INFO, List.of(doc(UUID.randomUUID())))).isEmpty();
    }

    private static Document doc(UUID id) {
        return Document.builder().id(id.toString()).text(BODY)
                .metadata(Map.of("houseId", id.toString())).score(0.8).build();
    }

    /** Runs one ask whose model call fails, with RagService's logger at {@code level}; returns what it logged. */
    private static List<ILoggingEvent> askAt(Level level, List<Document> docs) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RagService.class);
        var before = logger.getLevel();
        var lines = new ListAppender<ILoggingEvent>();
        lines.start();
        logger.addAppender(lines);
        logger.setLevel(level);
        try {
            var store = mock(VectorStore.class);
            when(store.similaritySearch(any(SearchRequest.class))).thenReturn(docs);
            var chat = mock(ChatClient.class);
            when(chat.prompt()).thenThrow(new IllegalStateException("model down"));
            var props = new AiProperties(true, null, null, null, null, null, null, null, null, null, null);
            var service = new RagService(chat, store, props, mock(HouseRepository.class));
            assertThatThrownBy(() -> service.ask(QUESTION, new AskFilters(null, null, null, null, null)))
                    .isInstanceOf(AiUnavailableException.class);
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(before);
        }
        return List.copyOf(lines.list);
    }
}
