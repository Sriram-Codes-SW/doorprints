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

package app.doorprints.server.ai.web;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.genai.errors.ClientException;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Vertex AI setup hints: chat points to GCP_LOCATION, embeddings to AI_VERTEX_EMBEDDING_LOCATION; quota never hints. */
class AiExceptionHandlerTest {

    private static AiProperties vertex(String location, String embeddingLocation) {
        return new AiProperties(true, AiProperties.VERTEX, null, null, null, null, null, null, null, null,
                new AiProperties.Vertex("doorprints-ai", location, embeddingLocation, null, null));
    }

    private static AiUnavailableException failure(Throwable providerError) {
        return new AiUnavailableException("Answering failed", new RuntimeException("wrapped", providerError));
    }

    private static Object hint(AiExceptionHandler handler, Throwable providerError) {
        var body = handler.aiUnavailable(failure(providerError)).getBody();
        assertThat(body).isNotNull();
        return body.getProperties() == null ? null : body.getProperties().get(AiExceptionHandler.SETUP_HINT_PROPERTY);
    }

    @Test
    void chat404PointsToGcpLocation() {
        var h = hint(AiExceptionHandlers.of(vertex("asia-south1", null)), new ClientException(404, "Not Found", "x"));
        assertThat((String) h).contains("chat model").contains("asia-south1").contains("GCP_LOCATION=global");
    }

    @Test
    void chat404OnGlobalSuggestsUsCentral1() {
        var h = hint(AiExceptionHandlers.of(vertex("global", null)), new ClientException(404, "Not Found", "x"));
        assertThat((String) h).contains("GCP_LOCATION=us-central1");
    }

    @Test
    void embedding404PointsToEmbeddingLocation() {
        var h = hint(AiExceptionHandlers.of(vertex("asia-south1", "us-central1")),
                new GeminiEmbeddingException("HTTP 404 (NOT_FOUND)", 404, "NOT_FOUND"));
        assertThat((String) h).contains("embedding model").contains("location us-central1")
                .contains("AI_VERTEX_EMBEDDING_LOCATION=global").doesNotContain("GCP_LOCATION=");
    }

    @Test
    void chat401PointsToCredentials() {
        var h = hint(AiExceptionHandlers.of(vertex("asia-south1", null)), new ClientException(401, "Unauthorized", "x"));
        assertThat((String) h).contains("application-default login");
    }

    @Test
    void noHintForQuotaOtherStatusesAiStudioOrNoSettings() {
        var vertex = AiExceptionHandlers.of(vertex("asia-south1", null));
        assertThat(hint(vertex, new ClientException(429, "Too Many Requests", "x"))).isNull();
        assertThat(hint(vertex, new ClientException(400, "Bad Request", "x"))).isNull();
        assertThat(hint(AiExceptionHandlers.of(AiProperties.defaults()), new ClientException(404, "Not Found", "x")))
                .isNull();
        assertThat(hint(AiExceptionHandlers.of(null), new ClientException(404, "Not Found", "x"))).isNull();
    }

    /**
     * SEC-016, PRV-011 (S4b-BL-159): a provider error can echo parts of the prompt, which holds the person's notes, so
     * the log names only the kind of failure (the cause's class and the provider's HTTP status) and never its text.
     * The seam is the log output of the handler.
     */
    @Test
    void theLogNamesTheFailureButNeverTheProviderMessage() {
        var marker = "prompt-echo-" + java.util.UUID.randomUUID();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AiExceptionHandler.class);
        var lines = new ListAppender<ILoggingEvent>();
        lines.start();
        logger.addAppender(lines);
        try {
            var handler = AiExceptionHandlers.of(null);
            handler.aiUnavailable(new AiUnavailableException("Answering failed",
                    new ClientException(503, "UNAVAILABLE", marker)));
            handler.aiUnavailable(new AiUnavailableException("Search failed", new IllegalStateException(marker)));
            handler.aiUnavailable(new AiUnavailableException("Answering failed", null));
        } finally {
            logger.detachAppender(lines);
        }
        assertThat(lines.list).hasSize(3);
        for (var line : lines.list) {
            assertThat(line.getFormattedMessage()).doesNotContain(marker);
            assertThat(line.getThrowableProxy()).as("no stack trace, whose message would carry the text").isNull();
        }
        assertThat(lines.list.get(0).getFormattedMessage()).contains("ClientException").contains("503");
        assertThat(lines.list.get(1).getFormattedMessage()).contains("IllegalStateException");
    }
}
